package com.dvarahq.oss.evals4j.simulate;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.dvarahq.oss.evals4j.internal.Json;
import com.dvarahq.oss.evals4j.message.ChatMessage;
import com.dvarahq.oss.evals4j.spi.JudgeModel;

import java.util.ArrayList;
import java.util.List;

/**
 * Stands in for a person talking to the application.
 *
 * <p>Receives the whole conversation so far and produces the next user message. Use
 * {@link #scripted} for a fixed script, or {@link #llm} to have a model improvise in character.
 */
@FunctionalInterface
public interface SimulatedUser {

    /**
     * @param trajectory the conversation so far; empty on the first turn
     * @param turnCounter how many exchanges have completed
     * @param threadId the simulation's thread id
     */
    Object respond(List<ChatMessage> trajectory, int turnCounter, String threadId);

    /**
     * A user that reads from a fixed list of messages.
     *
     * <p>Running past the end of the script is an error rather than a silent stop — it means the
     * simulation was configured for more turns than there are lines.
     */
    static SimulatedUser scripted(List<String> messages) {
        List<String> script = List.copyOf(messages);
        return (trajectory, turnCounter, threadId) -> {
            if (turnCounter >= script.size()) {
                throw new IllegalStateException(
                        "The simulation reached turn " + turnCounter + " but only "
                                + script.size() + " scripted user responses were provided. "
                                + "Lower maxTurns or add more responses.");
            }
            return ChatMessage.user(script.get(turnCounter));
        };
    }

    static SimulatedUser scripted(String... messages) {
        return scripted(List.of(messages));
    }

    /**
     * A user improvised by a model from a persona.
     *
     * @param system the persona, e.g. "You are a frustrated customer whose order never arrived."
     * @param model the model that plays the user
     */
    static SimulatedUser llm(String system, JudgeModel model) {
        return llm(system, model, List.of());
    }

    /**
     * A model-driven user that first works through {@code fixedResponses}, then improvises.
     *
     * <p>Useful for pinning the opening of a conversation — the exact complaint that starts it — while
     * letting the rest react to whatever the app says.
     */
    static SimulatedUser llm(String system, JudgeModel model, List<String> fixedResponses) {
        List<String> fixed = List.copyOf(fixedResponses);
        return (trajectory, turnCounter, threadId) -> {
            if (turnCounter < fixed.size()) {
                return ChatMessage.user(fixed.get(turnCounter));
            }
            return LlmSimulatedUser.next(system, model, trajectory);
        };
    }

    /** Implementation detail of {@link SimulatedUser#llm}. */
    final class LlmSimulatedUser {

        static final String BOOTSTRAP =
                "Generate an initial query to start a conversation based on your instructions. "
                        + "Do not respond with other text.";

        private LlmSimulatedUser() {}

        static ChatMessage next(String system, JudgeModel model, List<ChatMessage> trajectory) {
            List<ChatMessage> messages = new ArrayList<>();
            messages.add(ChatMessage.system(system));
            messages.addAll(invertRoles(trajectory));
            if (messages.size() == 1) {
                messages.add(ChatMessage.user(BOOTSTRAP));
            }

            ObjectNode schema = Json.object();
            schema.put("type", "object");
            ObjectNode properties = Json.object();
            ObjectNode message = Json.object();
            message.put("type", "string");
            message.put("description", "Your next message in the conversation, in character.");
            properties.set("message", message);
            schema.set("properties", properties);
            schema.putArray("required").add("message");
            schema.put("additionalProperties", false);

            JsonNode response = model.invokeStructured(messages, schema);
            return ChatMessage.user(response.path("message").asText(""));
        }

        /**
         * Swaps the perspective so the model sees the conversation as its own.
         *
         * <p>The simulated user's previous turns become assistant messages and the app's replies
         * become user messages. Tool traffic and system messages are dropped — they are the app's
         * internals, and a person would not see them.
         */
        static List<ChatMessage> invertRoles(List<ChatMessage> trajectory) {
            List<ChatMessage> inverted = new ArrayList<>();
            for (ChatMessage message : trajectory) {
                if (ChatMessage.ROLE_USER.equals(message.role())) {
                    inverted.add(message.withRole(ChatMessage.ROLE_ASSISTANT));
                } else if (ChatMessage.ROLE_ASSISTANT.equals(message.role()) && !message.hasToolCalls()) {
                    inverted.add(message.withRole(ChatMessage.ROLE_USER));
                }
            }
            return inverted;
        }
    }
}
