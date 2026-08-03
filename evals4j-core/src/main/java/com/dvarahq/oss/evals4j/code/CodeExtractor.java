package com.dvarahq.oss.evals4j.code;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.dvarahq.oss.evals4j.internal.Json;
import com.dvarahq.oss.evals4j.message.ChatMessage;
import com.dvarahq.oss.evals4j.message.Messages;
import com.dvarahq.oss.evals4j.spi.JudgeModel;

import java.util.List;
import java.util.function.Function;

/**
 * Gets source code out of a model's response.
 *
 * <p>Every code evaluator faces this first — the output is prose with fenced blocks, or a chat
 * message, or already-bare code — so the strategies live here rather than being duplicated per
 * evaluator.
 */
public final class CodeExtractor {

    static final String LLM_EXTRACTION_SYSTEM_PROMPT =
            """
            You are an expert software auditor. Extract the code from the provided text.

            Extract the code exactly as written, without modifications, even if it contains errors.
            Omit installation instructions and shell commands. If the text contains no code, set
            has_code to false and leave code empty.
            """;

    static final String LLM_EXTRACTION_USER_PROMPT = "Extract code from the following:\n\n<text>\n%s\n</text>";

    private final CodeExtractionStrategy strategy;
    private final Function<Object, String> custom;
    private final JudgeModel model;

    private CodeExtractor(
            CodeExtractionStrategy strategy, Function<Object, String> custom, JudgeModel model) {
        this.strategy = strategy == null ? CodeExtractionStrategy.NONE : strategy;
        this.custom = custom;
        this.model = model;

        if (custom != null && this.strategy != CodeExtractionStrategy.NONE) {
            throw new IllegalArgumentException(
                    "A custom code extractor and a code extraction strategy cannot both be provided");
        }
        if (this.strategy == CodeExtractionStrategy.LLM && model == null) {
            throw new IllegalArgumentException(
                    "Code extraction strategy LLM requires a model — call .extractionModel(...)");
        }
    }

    public static CodeExtractor of(CodeExtractionStrategy strategy) {
        return new CodeExtractor(strategy, null, null);
    }

    public static CodeExtractor llm(JudgeModel model) {
        return new CodeExtractor(CodeExtractionStrategy.LLM, null, model);
    }

    public static CodeExtractor custom(Function<Object, String> extractor) {
        return new CodeExtractor(CodeExtractionStrategy.NONE, extractor, null);
    }

    static CodeExtractor from(
            CodeExtractionStrategy strategy, Function<Object, String> custom, JudgeModel model) {
        return new CodeExtractor(strategy, custom, model);
    }

    /** The extracted code, or {@code null} when the output contains none. */
    public String extract(Object outputs) {
        if (custom != null) {
            return custom.apply(outputs);
        }
        String text = Messages.finalOutputAsString(outputs);
        return switch (strategy) {
            case NONE -> text;
            case MARKDOWN_CODE_BLOCKS -> CodeExtraction.fromMarkdownCodeBlocks(text);
            case LLM -> extractWithLlm(text);
        };
    }

    /**
     * Asks the model for the code as structured output.
     *
     * <p>OpenEvals binds two tools ({@code ExtractCode} / {@code NoCode}) and inspects which was
     * called. A single schema with a {@code has_code} flag expresses the same choice while staying
     * within the {@link JudgeModel} SPI, so it works with every adapter rather than only those that
     * expose tool binding.
     */
    private String extractWithLlm(String text) {
        JsonNode response = model.invokeStructured(
                List.of(
                        ChatMessage.system(LLM_EXTRACTION_SYSTEM_PROMPT),
                        ChatMessage.user(LLM_EXTRACTION_USER_PROMPT.formatted(text))),
                extractionSchema());

        if (!response.path("has_code").asBoolean(false)) {
            return null;
        }
        String extracted = response.path("code").asText("");
        return extracted.isEmpty() ? null : extracted;
    }

    static ObjectNode extractionSchema() {
        ObjectNode schema = Json.object();
        schema.put("type", "object");
        schema.put("title", "extracted_code");
        ObjectNode properties = Json.object();
        ObjectNode hasCode = Json.object();
        hasCode.put("type", "boolean");
        hasCode.put("description", "Whether the text contains any code to extract.");
        properties.set("has_code", hasCode);
        ObjectNode code = Json.object();
        code.put("type", "string");
        code.put("description", "The extracted code, exactly as written, or an empty string.");
        properties.set("code", code);
        schema.set("properties", properties);
        ArrayNode required = schema.putArray("required");
        required.add("has_code");
        required.add("code");
        schema.put("additionalProperties", false);
        return schema;
    }
}
