package com.dvarahq.oss.evals4j.langchain4j;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.request.json.JsonRawSchema;
import dev.langchain4j.model.chat.response.ChatResponse;
import com.dvarahq.oss.evals4j.EvalRequest;
import com.dvarahq.oss.evals4j.judge.JudgeSchemas;
import com.dvarahq.oss.evals4j.judge.LlmAsJudge;
import com.dvarahq.oss.evals4j.message.ChatMessage;
import com.dvarahq.oss.evals4j.prompt.Prompts;
import com.dvarahq.oss.evals4j.result.EvaluatorResult;
import com.dvarahq.oss.evals4j.spi.JudgeModel;
import com.dvarahq.oss.evals4j.spi.StructuredOutputMode;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LangChain4jJudgeModelTest {

    /** A ChatModel that replies with whatever the supplied function returns. */
    private static final class RecordingChatModel implements ChatModel {
        private final Function<ChatRequest, String> reply;
        private final List<ChatRequest> requests = new ArrayList<>();

        RecordingChatModel(Function<ChatRequest, String> reply) {
            this.reply = reply;
        }

        @Override
        public ChatResponse chat(ChatRequest request) {
            requests.add(request);
            return ChatResponse.builder().aiMessage(AiMessage.from(reply.apply(request))).build();
        }

        ChatRequest lastRequest() {
            return requests.get(requests.size() - 1);
        }
    }

    private static String textOf(ChatRequest request) {
        StringBuilder text = new StringBuilder();
        for (dev.langchain4j.data.message.ChatMessage message : request.messages()) {
            if (message instanceof UserMessage user) {
                text.append(user.singleText()).append('\n');
            } else if (message instanceof SystemMessage system) {
                text.append(system.text()).append('\n');
            }
        }
        return text.toString();
    }

    @Test
    void scoresThroughALangChain4jChatModel() {
        RecordingChatModel model = new RecordingChatModel(
                request -> "{\"reasoning\": \"Too wordy.\", \"score\": false}");

        EvaluatorResult result = LlmAsJudge.builder()
                .prompt(Prompts.CONCISENESS_PROMPT)
                .feedbackKey("conciseness")
                .model(LangChain4jJudgeModel.of(model))
                .build()
                .evaluate("How is the weather?", "Thanks so much for asking! It is sunny.");

        assertThat(result.key()).isEqualTo("conciseness");
        assertThat(result.passed()).isFalse();
        assertThat(result.comment()).isEqualTo("Too wordy.");
    }

    @Test
    void passesTheSchemaThroughUntouchedAsARawSchema() {
        RecordingChatModel model = new RecordingChatModel(request -> "{\"score\": true}");

        var schema = JudgeSchemas.defaultSchema(false, List.of(0.0, 0.5, 1.0), true);
        LangChain4jJudgeModel.of(model, StructuredOutputMode.NATIVE)
                .invokeStructured(List.of(ChatMessage.user("grade this")), schema);

        var responseFormat = model.lastRequest().responseFormat();
        assertThat(responseFormat).isNotNull();
        assertThat(responseFormat.jsonSchema().rootElement()).isInstanceOf(JsonRawSchema.class);

        // Raw pass-through means dynamically built schemas survive intact — including the enum,
        // which a typed-schema conversion would have to model explicitly.
        String raw = ((JsonRawSchema) responseFormat.jsonSchema().rootElement()).schema();
        assertThat(raw).contains("\"enum\"").contains("0.5").contains("reasoning");
    }

    @Test
    void promptedModePutsTheSchemaInTheMessage() {
        RecordingChatModel model = new RecordingChatModel(request -> "{\"score\": true}");

        LangChain4jJudgeModel.of(model, StructuredOutputMode.PROMPTED)
                .invokeStructured(
                        List.of(ChatMessage.user("grade this")),
                        JudgeSchemas.defaultSchema(false, null, false));

        assertThat(textOf(model.lastRequest())).contains("JSON Schema").contains("\"score\"");
        assertThat(model.lastRequest().responseFormat()).isNull();
    }

    @Test
    void autoFallsBackToPromptingWhenTheProviderRejectsTheSchema() {
        RecordingChatModel model = new RecordingChatModel(request -> {
            if (request.responseFormat() != null) {
                throw new UnsupportedOperationException("this provider has no schema support");
            }
            return "{\"score\": true}";
        });

        var response = LangChain4jJudgeModel.of(model)
                .invokeStructured(
                        List.of(ChatMessage.user("grade this")),
                        JudgeSchemas.defaultSchema(false, null, false));

        assertThat(response.path("score").asBoolean()).isTrue();
    }

    @Test
    void nativeModeSurfacesTheProviderFailure() {
        RecordingChatModel model = new RecordingChatModel(request -> {
            throw new UnsupportedOperationException("no schema support");
        });

        assertThatThrownBy(() -> LangChain4jJudgeModel.of(model, StructuredOutputMode.NATIVE)
                        .invokeStructured(
                                List.of(ChatMessage.user("x")),
                                JudgeSchemas.defaultSchema(false, null, false)))
                .isInstanceOf(JudgeModel.JudgeInvocationException.class)
                .hasMessageContaining("PROMPTED");
    }

    @Test
    void recoversJsonWrappedInProseAndFences() {
        RecordingChatModel model = new RecordingChatModel(request -> """
                Certainly! My assessment:

                ```json
                {"reasoning": "It is fine. Thus, the score should be: true.", "score": true}
                ```
                """);

        EvaluatorResult result = LlmAsJudge.builder()
                .prompt("{outputs}")
                .model(LangChain4jJudgeModel.of(model, StructuredOutputMode.PROMPTED))
                .build()
                .evaluate(EvalRequest.ofOutputs("an answer"));

        assertThat(result.passed()).isTrue();
        assertThat(result.comment()).contains("It is fine");
    }

    @Test
    void mapsMessageRolesOntoLangChain4jTypes() {
        List<dev.langchain4j.data.message.ChatMessage> converted =
                LangChain4jMessages.toLangChain4j(List.of(
                        ChatMessage.system("sys"), ChatMessage.user("usr"), ChatMessage.assistant("asst")));

        assertThat(converted.get(0)).isInstanceOf(SystemMessage.class);
        assertThat(converted.get(1)).isInstanceOf(UserMessage.class);
        assertThat(converted.get(2)).isInstanceOf(AiMessage.class);
    }

    @Test
    void embedsThroughALangChain4jEmbeddingModel() {
        var provider = LangChain4jEmbeddingProvider.of(
                new dev.langchain4j.model.embedding.EmbeddingModel() {
                    @Override
                    public dev.langchain4j.model.output.Response<
                                    List<dev.langchain4j.data.embedding.Embedding>>
                            embedAll(List<dev.langchain4j.data.segment.TextSegment> segments) {
                        return dev.langchain4j.model.output.Response.from(
                                List.of(dev.langchain4j.data.embedding.Embedding.from(
                                        new float[] {1f, 2f})));
                    }
                });

        assertThat(provider.embed("anything")).containsExactly(1f, 2f);
    }
}
