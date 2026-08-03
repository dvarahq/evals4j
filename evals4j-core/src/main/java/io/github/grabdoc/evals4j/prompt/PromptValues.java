package io.github.grabdoc.evals4j.prompt;

import io.github.grabdoc.evals4j.internal.Json;
import io.github.grabdoc.evals4j.message.ChatMessage;
import io.github.grabdoc.evals4j.message.Messages;
import io.github.grabdoc.evals4j.message.ToolCall;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Renders an evaluation value into the text a judge sees.
 *
 * <p>Port of OpenEvals' {@code _stringify_prompt_param}: a string passes through untouched, and
 * anything else is JSON-serialized. Messages are converted to their OpenAI wire form first, so a
 * trajectory reads as the model's own transcript format rather than as a dump of Java records.
 */
public final class PromptValues {

    private PromptValues() {}

    public static String stringify(Object value) {
        if (value == null) {
            return "";
        }
        if (value instanceof String string) {
            return string;
        }
        if (value instanceof ChatMessage message) {
            return Json.write(toWire(message));
        }
        if (value instanceof List<?> list && containsMessages(list)) {
            List<Map<String, Object>> wire = new ArrayList<>(list.size());
            for (ChatMessage message : Messages.normalize(list)) {
                wire.add(toWire(message));
            }
            return Json.write(wire);
        }
        if (value instanceof Map<?, ?> map && map.get("messages") instanceof List<?> nested) {
            Map<String, Object> copy = new LinkedHashMap<>();
            map.forEach((key, entry) -> copy.put(String.valueOf(key), entry));
            List<Map<String, Object>> wire = new ArrayList<>(nested.size());
            for (ChatMessage message : Messages.normalize(nested)) {
                wire.add(toWire(message));
            }
            copy.put("messages", wire);
            return Json.write(copy);
        }
        return Json.write(value);
    }

    private static boolean containsMessages(List<?> list) {
        for (Object item : list) {
            if (item instanceof ChatMessage) {
                return true;
            }
        }
        return false;
    }

    /** A message in OpenAI wire shape, omitting keys that carry no information. */
    public static Map<String, Object> toWire(ChatMessage message) {
        Map<String, Object> wire = new LinkedHashMap<>();
        wire.put("role", message.role());
        wire.put("content", message.content());
        if (message.id() != null && !message.id().isEmpty()) {
            wire.put("id", message.id());
        }
        if (message.hasToolCalls()) {
            List<Map<String, Object>> calls = new ArrayList<>();
            for (ToolCall call : message.toolCalls()) {
                calls.add(Map.of(
                        "id", call.id(),
                        "type", "function",
                        "function", Map.of("name", call.name(), "arguments", call.arguments())));
            }
            wire.put("tool_calls", calls);
        }
        if (message.toolCallId() != null && !message.toolCallId().isEmpty()) {
            wire.put("tool_call_id", message.toolCallId());
        }
        return wire;
    }
}
