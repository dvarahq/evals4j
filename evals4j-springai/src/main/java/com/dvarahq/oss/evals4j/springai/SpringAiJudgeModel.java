package com.dvarahq.oss.evals4j.springai;

import com.fasterxml.jackson.databind.JsonNode;
import com.dvarahq.oss.evals4j.internal.Json;
import com.dvarahq.oss.evals4j.internal.JsonExtraction;
import com.dvarahq.oss.evals4j.spi.JudgeModel;
import com.dvarahq.oss.evals4j.spi.StructuredOutputMode;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.StructuredOutputChatOptions;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Drives evals4j evaluators with a Spring AI {@link ChatModel}.
 *
 * <pre>{@code
 * JudgeModel judge = SpringAiJudgeModel.of(chatModel);
 *
 * LlmAsJudge conciseness = LlmAsJudge.builder()
 *         .prompt(Prompts.CONCISENESS_PROMPT)
 *         .model(judge)
 *         .build();
 * }</pre>
 *
 * <p>Schema enforcement goes through Spring AI's own {@link StructuredOutputChatOptions}, so no
 * provider-specific dependency is needed. Providers that ignore or reject it fall back to putting the
 * schema in the prompt.
 */
public final class SpringAiJudgeModel implements JudgeModel {

    private final ChatModel model;
    private final StructuredOutputMode mode;

    private SpringAiJudgeModel(ChatModel model, StructuredOutputMode mode) {
        this.model = Objects.requireNonNull(model, "model");
        this.mode = Objects.requireNonNull(mode, "mode");
    }

    /** Uses provider-side schema enforcement when available, falling back to prompting. */
    public static SpringAiJudgeModel of(ChatModel model) {
        return new SpringAiJudgeModel(model, StructuredOutputMode.AUTO);
    }

    public static SpringAiJudgeModel of(ChatModel model, StructuredOutputMode mode) {
        return new SpringAiJudgeModel(model, mode);
    }

    @Override
    public JsonNode invokeStructured(
            List<com.dvarahq.oss.evals4j.message.ChatMessage> messages, JsonNode jsonSchema) {

        if (mode == StructuredOutputMode.PROMPTED) {
            return invokePrompted(messages, jsonSchema);
        }
        try {
            return invokeNative(messages, jsonSchema);
        } catch (RuntimeException e) {
            if (mode == StructuredOutputMode.NATIVE) {
                throw new JudgeInvocationException(
                        "The model rejected the structured-output request. Use "
                                + "StructuredOutputMode.PROMPTED with this provider.", e);
            }
            // AUTO: the provider does not honour the schema, so ask for the JSON in the prompt.
            return invokePrompted(messages, jsonSchema);
        }
    }

    private JsonNode invokeNative(
            List<com.dvarahq.oss.evals4j.message.ChatMessage> messages, JsonNode jsonSchema) {

        ChatOptions options = StructuredOutputChatOptions.builder()
                .outputSchema(jsonSchema.toString())
                .build();

        ChatResponse response = model.call(new Prompt(SpringAiMessages.toSpringAi(messages), options));
        return JsonExtraction.firstObject(textOf(response));
    }

    /** Puts the schema in the prompt and pulls the JSON back out of the reply. */
    private JsonNode invokePrompted(
            List<com.dvarahq.oss.evals4j.message.ChatMessage> messages, JsonNode jsonSchema) {

        List<com.dvarahq.oss.evals4j.message.ChatMessage> withInstruction = new ArrayList<>(messages);
        int last = withInstruction.size() - 1;
        com.dvarahq.oss.evals4j.message.ChatMessage lastMessage = withInstruction.get(last);
        String content =
                lastMessage.content() instanceof String string ? string : Json.write(lastMessage.content());
        withInstruction.set(last, lastMessage.withContent(content + JsonExtraction.schemaInstruction(jsonSchema)));

        try {
            ChatResponse response = model.call(new Prompt(SpringAiMessages.toSpringAi(withInstruction)));
            return JsonExtraction.firstObject(textOf(response));
        } catch (RuntimeException e) {
            throw new JudgeInvocationException("The judge model call failed: " + e.getMessage(), e);
        }
    }

    private static String textOf(ChatResponse response) {
        if (response == null || response.getResult() == null) {
            throw new JudgeInvocationException("The judge model returned no result");
        }
        return response.getResult().getOutput().getText();
    }
}
