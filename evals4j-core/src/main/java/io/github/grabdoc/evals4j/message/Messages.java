package io.github.grabdoc.evals4j.message;

import io.github.grabdoc.evals4j.internal.Json;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Normalizes the many shapes callers use for "a conversation" into a canonical list of
 * {@link ChatMessage}.
 *
 * <p>Port of OpenEvals' {@code _normalize_to_openai_messages_list} together with the more lenient
 * {@code _convert_to_openai_message} used on the trajectory path: a missing tool-call {@code id} or
 * {@code tool_call_id} becomes {@code ""} and {@code null} content becomes {@code ""}, so reference
 * trajectories written by hand do not have to invent identifiers.
 */
public final class Messages {

    private Messages() {}

    /**
     * Accepts a single {@link ChatMessage}, a {@link List} of messages or maps, a map with a
     * {@code "messages"} key, or a single message-shaped map. {@code null} yields an empty list.
     */
    @SuppressWarnings("unchecked")
    public static List<ChatMessage> normalize(Object messages) {
        if (messages == null) {
            return List.of();
        }
        if (messages instanceof ChatMessage message) {
            return List.of(normalizeOne(message));
        }
        if (messages instanceof Map<?, ?> map) {
            if (map.containsKey("role")) {
                return List.of(fromMap((Map<String, Object>) map));
            }
            if (map.containsKey("messages")) {
                return normalize(map.get("messages"));
            }
            throw new IllegalArgumentException(
                    "if messages is a map, it must contain a 'messages' key");
        }
        if (messages instanceof List<?> list) {
            List<ChatMessage> normalized = new ArrayList<>(list.size());
            for (Object item : list) {
                normalized.add(toMessage(item));
            }
            return normalized;
        }
        return List.of(toMessage(messages));
    }

    @SuppressWarnings("unchecked")
    private static ChatMessage toMessage(Object item) {
        if (item instanceof ChatMessage message) {
            return normalizeOne(message);
        }
        if (item instanceof Map<?, ?> map) {
            return fromMap((Map<String, Object>) map);
        }
        throw new IllegalArgumentException(
                "Cannot read a chat message from " + (item == null ? "null" : item.getClass().getName()));
    }

    private static ChatMessage normalizeOne(ChatMessage message) {
        // The record's canonical constructor already defaults null content and tool calls.
        return message;
    }

    @SuppressWarnings("unchecked")
    private static ChatMessage fromMap(Map<String, Object> map) {
        String role = asString(map.get("role"));
        if (role == null) {
            throw new IllegalArgumentException("Chat message map is missing a 'role' key: " + map);
        }
        Object content = map.get("content");
        if (content == null) {
            content = "";
        }
        String id = asString(firstNonNull(map.get("id"), map.get("messageId")));
        String toolCallId = asString(firstNonNull(map.get("tool_call_id"), map.get("toolCallId")));
        if (toolCallId == null && ChatMessage.ROLE_TOOL.equals(role)) {
            toolCallId = "";
        }

        Object rawToolCalls = firstNonNull(map.get("tool_calls"), map.get("toolCalls"));
        List<ToolCall> toolCalls = new ArrayList<>();
        if (rawToolCalls instanceof List<?> list) {
            for (Object raw : list) {
                if (raw instanceof ToolCall toolCall) {
                    toolCalls.add(toolCall);
                } else if (raw instanceof Map<?, ?> toolCallMap) {
                    toolCalls.add(toolCallFromMap((Map<String, Object>) toolCallMap));
                }
            }
        }
        return new ChatMessage(id, role, content, toolCalls, toolCallId);
    }

    /**
     * Reads either wire shape: OpenAI's {@code {id, type, function: {name, arguments}}} or
     * LangChain's {@code {id, name, args}}. Both normalize to a JSON-string {@code arguments}.
     */
    @SuppressWarnings("unchecked")
    private static ToolCall toolCallFromMap(Map<String, Object> map) {
        String id = asString(map.get("id"));
        Object function = map.get("function");
        if (function instanceof Map<?, ?> functionMap) {
            Map<String, Object> fn = (Map<String, Object>) functionMap;
            Object arguments = fn.get("arguments");
            return new ToolCall(id, asString(fn.get("name")), argumentsToJson(arguments));
        }
        Object args = firstNonNull(map.get("args"), map.get("arguments"));
        return new ToolCall(id, asString(map.get("name")), argumentsToJson(args));
    }

    private static String argumentsToJson(Object arguments) {
        if (arguments == null) {
            return "{}";
        }
        return arguments instanceof String string ? string : Json.write(arguments);
    }

    private static Object firstNonNull(Object a, Object b) {
        return a != null ? a : b;
    }

    private static String asString(Object value) {
        return value == null ? null : value.toString();
    }

    /**
     * Reduces an application's final output to a string.
     *
     * <p>Port of {@code _normalize_final_app_outputs_as_string}: a string passes through, a map with
     * {@code content} yields that content, and a map with {@code messages} yields the last message's
     * content.
     */
    @SuppressWarnings("unchecked")
    public static String finalOutputAsString(Object outputs) {
        if (outputs instanceof String string) {
            return string;
        }
        if (outputs instanceof ChatMessage message) {
            return message.text();
        }
        if (outputs instanceof Map<?, ?> map) {
            if (map.containsKey("content")) {
                Object content = map.get("content");
                return content == null ? "" : content.toString();
            }
            if (map.get("messages") instanceof List<?> list && !list.isEmpty()) {
                List<ChatMessage> normalized = normalize(list);
                return normalized.get(normalized.size() - 1).text();
            }
        }
        if (outputs instanceof List<?> list && !list.isEmpty()) {
            List<ChatMessage> normalized = normalize(list);
            return normalized.get(normalized.size() - 1).text();
        }
        throw new IllegalArgumentException(
                "Expected a string, a message, a map with a 'content' or 'messages' key, or a list of "
                        + "messages, but got: " + outputs);
    }

    /** Assigns a random id to a message that has none, so trajectory dedup has something to key on. */
    public static ChatMessage withGeneratedId(ChatMessage message) {
        return message.id() == null || message.id().isEmpty()
                ? message.withId(UUID.randomUUID().toString())
                : message;
    }
}
