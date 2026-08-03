package io.github.grabdoc.evals4j.langchain4j;

import com.fasterxml.jackson.databind.JsonNode;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.request.ResponseFormat;
import dev.langchain4j.model.chat.request.ResponseFormatType;
import dev.langchain4j.model.chat.request.json.JsonRawSchema;
import dev.langchain4j.model.chat.request.json.JsonSchema;
import dev.langchain4j.model.chat.response.ChatResponse;
import io.github.grabdoc.evals4j.internal.Json;
import io.github.grabdoc.evals4j.internal.JsonExtraction;
import io.github.grabdoc.evals4j.spi.JudgeModel;
import io.github.grabdoc.evals4j.spi.StructuredOutputMode;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Drives evals4j evaluators with a LangChain4j {@link ChatModel}.
 *
 * <pre>{@code
 * JudgeModel judge = LangChain4jJudgeModel.of(
 *         OpenAiChatModel.builder().apiKey(key).modelName("gpt-5.4").strictJsonSchema(true).build());
 *
 * LlmAsJudge conciseness = LlmAsJudge.builder()
 *         .prompt(Prompts.CONCISENESS_PROMPT)
 *         .model(judge)
 *         .build();
 * }</pre>
 */
public final class LangChain4jJudgeModel implements JudgeModel {

    private final ChatModel model;
    private final StructuredOutputMode mode;

    private LangChain4jJudgeModel(ChatModel model, StructuredOutputMode mode) {
        this.model = Objects.requireNonNull(model, "model");
        this.mode = Objects.requireNonNull(mode, "mode");
    }

    /** Uses the provider's JSON-schema support when it has any, falling back to prompting. */
    public static LangChain4jJudgeModel of(ChatModel model) {
        return new LangChain4jJudgeModel(model, StructuredOutputMode.AUTO);
    }

    public static LangChain4jJudgeModel of(ChatModel model, StructuredOutputMode mode) {
        return new LangChain4jJudgeModel(model, mode);
    }

    @Override
    public JsonNode invokeStructured(
            List<io.github.grabdoc.evals4j.message.ChatMessage> messages, JsonNode jsonSchema) {

        if (mode == StructuredOutputMode.PROMPTED) {
            return invokePrompted(messages, jsonSchema);
        }
        try {
            return invokeNative(messages, jsonSchema);
        } catch (RuntimeException e) {
            if (mode == StructuredOutputMode.NATIVE) {
                throw new JudgeInvocationException(
                        "The model rejected the JSON-schema response format. Use "
                                + "StructuredOutputMode.PROMPTED with this provider.", e);
            }
            // AUTO: the provider does not support schemas, so ask for the JSON in the prompt instead.
            return invokePrompted(messages, jsonSchema);
        }
    }

    /**
     * Passes the schema through as a raw JSON Schema.
     *
     * <p>LangChain4j's {@code JsonRawSchema} takes the schema verbatim, so nothing is lost converting
     * into its typed schema tree — which matters because evaluators build schemas dynamically.
     */
    private JsonNode invokeNative(
            List<io.github.grabdoc.evals4j.message.ChatMessage> messages, JsonNode jsonSchema) {

        String name = jsonSchema.path("title").asText("response");
        ChatRequest request = ChatRequest.builder()
                .messages(LangChain4jMessages.toLangChain4j(messages))
                .responseFormat(ResponseFormat.builder()
                        .type(ResponseFormatType.JSON)
                        .jsonSchema(JsonSchema.builder()
                                .name(name)
                                .rootElement(JsonRawSchema.from(jsonSchema.toString()))
                                .build())
                        .build())
                .build();

        ChatResponse response = model.chat(request);
        String text = response.aiMessage().text();
        return JsonExtraction.firstObject(text);
    }

    /** Puts the schema in the prompt and pulls the JSON back out of the reply. */
    private JsonNode invokePrompted(
            List<io.github.grabdoc.evals4j.message.ChatMessage> messages, JsonNode jsonSchema) {

        List<io.github.grabdoc.evals4j.message.ChatMessage> withInstruction = new ArrayList<>(messages);
        int last = withInstruction.size() - 1;
        io.github.grabdoc.evals4j.message.ChatMessage lastMessage = withInstruction.get(last);
        String content = lastMessage.content() instanceof String string ? string : Json.write(lastMessage.content());
        withInstruction.set(last, lastMessage.withContent(content + JsonExtraction.schemaInstruction(jsonSchema)));

        try {
            ChatResponse response = model.chat(ChatRequest.builder()
                    .messages(LangChain4jMessages.toLangChain4j(withInstruction))
                    .build());
            return JsonExtraction.firstObject(response.aiMessage().text());
        } catch (RuntimeException e) {
            throw new JudgeInvocationException("The judge model call failed: " + e.getMessage(), e);
        }
    }
}
