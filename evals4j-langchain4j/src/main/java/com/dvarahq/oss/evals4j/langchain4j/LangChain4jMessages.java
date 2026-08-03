package com.dvarahq.oss.evals4j.langchain4j;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.data.message.UserMessage;
import com.dvarahq.oss.evals4j.internal.Json;
import com.dvarahq.oss.evals4j.message.ChatMessage;
import com.dvarahq.oss.evals4j.message.ToolCall;

import java.util.ArrayList;
import java.util.List;

/** Converts between evals4j messages and LangChain4j's. */
public final class LangChain4jMessages {

    private LangChain4jMessages() {}

    public static List<dev.langchain4j.data.message.ChatMessage> toLangChain4j(List<ChatMessage> messages) {
        List<dev.langchain4j.data.message.ChatMessage> converted = new ArrayList<>(messages.size());
        for (ChatMessage message : messages) {
            converted.add(toLangChain4j(message));
        }
        return converted;
    }

    public static dev.langchain4j.data.message.ChatMessage toLangChain4j(ChatMessage message) {
        String text = textOf(message);
        return switch (message.role()) {
            case ChatMessage.ROLE_SYSTEM -> SystemMessage.from(text);
            case ChatMessage.ROLE_ASSISTANT -> AiMessage.from(text);
            case ChatMessage.ROLE_TOOL -> ToolExecutionResultMessage.from(
                    message.toolCallId(), null, text);
            default -> UserMessage.from(text);
        };
    }

    /**
     * Flattens content to text.
     *
     * <p>Multimodal blocks are JSON-encoded rather than dropped: the judge prompts that use them
     * embed URLs and data URIs, and a model given the JSON can still act on it, whereas a silently
     * empty message would score nothing at all.
     */
    private static String textOf(ChatMessage message) {
        return message.content() instanceof String string ? string : Json.write(message.content());
    }

    public static ChatMessage fromLangChain4j(AiMessage message) {
        List<ToolCall> toolCalls = new ArrayList<>();
        if (message.hasToolExecutionRequests()) {
            message.toolExecutionRequests().forEach(request ->
                    toolCalls.add(new ToolCall(request.id(), request.name(), request.arguments())));
        }
        return new ChatMessage(
                null, ChatMessage.ROLE_ASSISTANT, message.text() == null ? "" : message.text(),
                toolCalls, null);
    }
}
