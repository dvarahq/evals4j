package io.github.grabdoc.evals4j.message;

import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * A chat message in OpenAI wire shape — the lingua franca every adapter converts to and from.
 *
 * <p>{@code content} is {@link Object} rather than {@code String} because a multimodal message
 * carries a list of content blocks instead of text. Use {@link #text()} when only the textual part
 * matters.
 */
public record ChatMessage(
        String id, String role, Object content, List<ToolCall> toolCalls, String toolCallId) {

    public static final String ROLE_SYSTEM = "system";
    public static final String ROLE_USER = "user";
    public static final String ROLE_ASSISTANT = "assistant";
    public static final String ROLE_TOOL = "tool";

    public ChatMessage {
        Objects.requireNonNull(role, "role");
        content = content == null ? "" : content;
        toolCalls = toolCalls == null ? Collections.emptyList() : List.copyOf(toolCalls);
    }

    public static ChatMessage system(String content) {
        return new ChatMessage(null, ROLE_SYSTEM, content, null, null);
    }

    public static ChatMessage user(String content) {
        return new ChatMessage(null, ROLE_USER, content, null, null);
    }

    public static ChatMessage user(Object content) {
        return new ChatMessage(null, ROLE_USER, content, null, null);
    }

    public static ChatMessage assistant(String content) {
        return new ChatMessage(null, ROLE_ASSISTANT, content, null, null);
    }

    public static ChatMessage assistant(String content, List<ToolCall> toolCalls) {
        return new ChatMessage(null, ROLE_ASSISTANT, content, toolCalls, null);
    }

    public static ChatMessage tool(String content, String toolCallId) {
        return new ChatMessage(null, ROLE_TOOL, content, null, toolCallId);
    }

    public boolean hasToolCalls() {
        return !toolCalls.isEmpty();
    }

    /** The textual content, or the empty string when the content is a block list. */
    public String text() {
        return content instanceof String string ? string : "";
    }

    public ChatMessage withContent(Object newContent) {
        return new ChatMessage(id, role, newContent, toolCalls, toolCallId);
    }

    public ChatMessage withRole(String newRole) {
        return new ChatMessage(id, newRole, content, toolCalls, toolCallId);
    }

    public ChatMessage withId(String newId) {
        return new ChatMessage(newId, role, content, toolCalls, toolCallId);
    }
}
