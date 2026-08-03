package io.github.grabdoc.evals4j.spi;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.grabdoc.evals4j.message.ChatMessage;

import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * The one interface an AI framework has to implement for evals4j to drive it.
 *
 * <p>Implementations live in {@code evals4j-springai} and {@code evals4j-langchain4j}; core never
 * depends on either.
 *
 * <p>The contract is deliberately schema-in, JSON-out rather than typed-object-out: evaluators build
 * a different schema per call (the JSON-match evaluator generates one property per rubric key), so a
 * fixed response type would not work.
 */
@FunctionalInterface
public interface JudgeModel {

    /**
     * Invokes the model and returns its response parsed as JSON conforming to {@code jsonSchema}.
     *
     * @param messages the prompt, already formatted
     * @param jsonSchema a JSON Schema describing the required response object
     * @return the parsed response
     * @throws JudgeInvocationException if the model call fails or the response is not usable JSON
     */
    JsonNode invokeStructured(List<ChatMessage> messages, JsonNode jsonSchema);

    /**
     * Asynchronous variant. The default runs {@link #invokeStructured} on the common pool; adapters
     * over a natively async client should override.
     */
    default CompletableFuture<JsonNode> invokeStructuredAsync(
            List<ChatMessage> messages, JsonNode jsonSchema) {
        return CompletableFuture.supplyAsync(() -> invokeStructured(messages, jsonSchema));
    }

    /** Thrown when the judge model cannot be called or its response cannot be read. */
    class JudgeInvocationException extends RuntimeException {
        public JudgeInvocationException(String message) {
            super(message);
        }

        public JudgeInvocationException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
