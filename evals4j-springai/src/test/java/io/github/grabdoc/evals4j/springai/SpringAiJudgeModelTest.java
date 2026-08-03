package io.github.grabdoc.evals4j.springai;

import io.github.grabdoc.evals4j.EvalRequest;
import io.github.grabdoc.evals4j.judge.LlmAsJudge;
import io.github.grabdoc.evals4j.message.ChatMessage;
import io.github.grabdoc.evals4j.prompt.Prompts;
import io.github.grabdoc.evals4j.result.EvaluatorResult;
import io.github.grabdoc.evals4j.spi.JudgeModel;
import io.github.grabdoc.evals4j.spi.StructuredOutputMode;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.StructuredOutputChatOptions;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SpringAiJudgeModelTest {

    /** A ChatModel that replies with whatever the supplied function returns. */
    private static final class RecordingChatModel implements ChatModel {
        private final Function<Prompt, String> reply;
        private final List<Prompt> prompts = new ArrayList<>();

        RecordingChatModel(Function<Prompt, String> reply) {
            this.reply = reply;
        }

        @Override
        public ChatResponse call(Prompt prompt) {
            prompts.add(prompt);
            return new ChatResponse(List.of(new Generation(new AssistantMessage(reply.apply(prompt)))));
        }

        Prompt lastPrompt() {
            return prompts.get(prompts.size() - 1);
        }
    }

    private static String textOf(Prompt prompt) {
        StringBuilder text = new StringBuilder();
        for (Message message : prompt.getInstructions()) {
            text.append(message.getText()).append('\n');
        }
        return text.toString();
    }

    @Test
    void scoresThroughASpringAiChatModel() {
        RecordingChatModel model = new RecordingChatModel(
                prompt -> "{\"reasoning\": \"Too wordy.\", \"score\": false}");

        EvaluatorResult result = LlmAsJudge.builder()
                .prompt(Prompts.CONCISENESS_PROMPT)
                .feedbackKey("conciseness")
                .model(SpringAiJudgeModel.of(model))
                .build()
                .evaluate("How is the weather?", "Thanks so much for asking! It is sunny.");

        assertThat(result.key()).isEqualTo("conciseness");
        assertThat(result.passed()).isFalse();
        assertThat(result.comment()).isEqualTo("Too wordy.");
    }

    @Test
    void passesTheSchemaThroughStructuredOutputOptions() {
        RecordingChatModel model = new RecordingChatModel(prompt -> "{\"score\": true}");

        SpringAiJudgeModel.of(model, StructuredOutputMode.NATIVE)
                .invokeStructured(List.of(ChatMessage.user("grade this")),
                        io.github.grabdoc.evals4j.judge.JudgeSchemas.defaultSchema(false, null, false));

        assertThat(model.lastPrompt().getOptions()).isInstanceOf(StructuredOutputChatOptions.class);
        String schema = ((StructuredOutputChatOptions) model.lastPrompt().getOptions()).getOutputSchema();
        assertThat(schema).contains("\"score\"").contains("boolean");
    }

    @Test
    void promptedModePutsTheSchemaInTheMessage() {
        RecordingChatModel model = new RecordingChatModel(prompt -> "{\"score\": true}");

        SpringAiJudgeModel.of(model, StructuredOutputMode.PROMPTED)
                .invokeStructured(List.of(ChatMessage.user("grade this")),
                        io.github.grabdoc.evals4j.judge.JudgeSchemas.defaultSchema(false, null, false));

        assertThat(textOf(model.lastPrompt())).contains("JSON Schema").contains("\"score\"");
        // Prompted mode sends no options at all, so the model's own defaults still apply.
        assertThat(model.lastPrompt().getOptions()).isNull();
    }

    @Test
    void autoFallsBackToPromptingWhenTheProviderRejectsTheSchema() {
        // Providers that do not understand the options object throw; AUTO should recover.
        RecordingChatModel model = new RecordingChatModel(prompt -> {
            if (prompt.getOptions() instanceof StructuredOutputChatOptions) {
                throw new UnsupportedOperationException("this provider has no schema support");
            }
            return "{\"score\": true}";
        });

        var response = SpringAiJudgeModel.of(model)
                .invokeStructured(List.of(ChatMessage.user("grade this")),
                        io.github.grabdoc.evals4j.judge.JudgeSchemas.defaultSchema(false, null, false));

        assertThat(response.path("score").asBoolean()).isTrue();
    }

    @Test
    void nativeModeSurfacesTheProviderFailure() {
        RecordingChatModel model = new RecordingChatModel(prompt -> {
            throw new UnsupportedOperationException("no schema support");
        });

        assertThatThrownBy(() -> SpringAiJudgeModel.of(model, StructuredOutputMode.NATIVE)
                        .invokeStructured(List.of(ChatMessage.user("x")),
                                io.github.grabdoc.evals4j.judge.JudgeSchemas.defaultSchema(false, null, false)))
                .isInstanceOf(JudgeModel.JudgeInvocationException.class)
                .hasMessageContaining("PROMPTED");
    }

    @Test
    void recoversJsonWrappedInProseAndFences() {
        RecordingChatModel model = new RecordingChatModel(prompt -> """
                Sure, here is my assessment:

                ```json
                {"reasoning": "It is fine. Thus, the score should be: true.", "score": true}
                ```

                Let me know if you need anything else!
                """);

        EvaluatorResult result = LlmAsJudge.builder()
                .prompt("{outputs}")
                .model(SpringAiJudgeModel.of(model, StructuredOutputMode.PROMPTED))
                .build()
                .evaluate(EvalRequest.ofOutputs("an answer"));

        assertThat(result.passed()).isTrue();
        assertThat(result.comment()).contains("It is fine");
    }

    @Test
    void mapsMessageRolesOntoSpringAiTypes() {
        List<Message> converted = SpringAiMessages.toSpringAi(List.of(
                ChatMessage.system("sys"), ChatMessage.user("usr"), ChatMessage.assistant("asst")));

        assertThat(converted.get(0)).isInstanceOf(SystemMessage.class);
        assertThat(converted.get(1)).isInstanceOf(UserMessage.class);
        assertThat(converted.get(2)).isInstanceOf(AssistantMessage.class);
    }

    @Test
    void embedsThroughASpringAiEmbeddingModel() {
        var provider = SpringAiEmbeddingProvider.of(new org.springframework.ai.embedding.EmbeddingModel() {
            @Override
            public org.springframework.ai.embedding.EmbeddingResponse call(
                    org.springframework.ai.embedding.EmbeddingRequest request) {
                return new org.springframework.ai.embedding.EmbeddingResponse(
                        List.of(new org.springframework.ai.embedding.Embedding(new float[] {1f, 2f}, 0)));
            }

            @Override
            public float[] embed(org.springframework.ai.document.Document document) {
                return new float[] {1f, 2f};
            }
        });

        assertThat(provider.embed("anything")).containsExactly(1f, 2f);
    }
}
