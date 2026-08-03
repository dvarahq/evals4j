package com.dvarahq.oss.evals4j.testing;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.dvarahq.oss.evals4j.internal.Json;
import com.dvarahq.oss.evals4j.message.ChatMessage;
import com.dvarahq.oss.evals4j.spi.JudgeModel;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * A judge that returns scripted responses, so the whole judge path can be tested offline.
 *
 * <p>Also records every prompt and schema it was handed, which is how the prompt-formatting,
 * few-shot and schema-construction tests assert what the model would actually have seen.
 */
public final class FakeJudgeModel implements JudgeModel {

    private final Deque<JsonNode> responses = new ArrayDeque<>();
    private final List<List<ChatMessage>> receivedPrompts = new ArrayList<>();
    private final List<JsonNode> receivedSchemas = new ArrayList<>();

    public static FakeJudgeModel returning(JsonNode... responses) {
        FakeJudgeModel model = new FakeJudgeModel();
        for (JsonNode response : responses) {
            model.responses.add(response);
        }
        return model;
    }

    /** Scripts a default-schema response: {@code {"reasoning": ..., "score": ...}}. */
    public static FakeJudgeModel scoring(boolean score, String reasoning) {
        ObjectNode node = Json.object();
        node.put("reasoning", reasoning);
        node.put("score", score);
        return returning(node);
    }

    public static FakeJudgeModel scoring(double score, String reasoning) {
        ObjectNode node = Json.object();
        node.put("reasoning", reasoning);
        node.put("score", score);
        return returning(node);
    }

    public FakeJudgeModel andThen(JsonNode response) {
        responses.add(response);
        return this;
    }

    @Override
    public JsonNode invokeStructured(List<ChatMessage> messages, JsonNode jsonSchema) {
        receivedPrompts.add(List.copyOf(messages));
        receivedSchemas.add(jsonSchema);
        if (responses.isEmpty()) {
            throw new JudgeInvocationException(
                    "FakeJudgeModel ran out of scripted responses after " + receivedPrompts.size() + " call(s)");
        }
        // The last scripted response repeats, so tests that fan out over many keys need only one.
        return responses.size() == 1 ? responses.peek() : responses.poll();
    }

    public List<ChatMessage> lastPrompt() {
        return receivedPrompts.get(receivedPrompts.size() - 1);
    }

    public String lastPromptText() {
        StringBuilder text = new StringBuilder();
        for (ChatMessage message : lastPrompt()) {
            text.append(message.role()).append(": ").append(message.content()).append('\n');
        }
        return text.toString();
    }

    public JsonNode lastSchema() {
        return receivedSchemas.get(receivedSchemas.size() - 1);
    }

    public int callCount() {
        return receivedPrompts.size();
    }
}
