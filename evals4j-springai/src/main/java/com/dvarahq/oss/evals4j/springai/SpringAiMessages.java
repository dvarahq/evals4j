package com.dvarahq.oss.evals4j.springai;

import com.dvarahq.oss.evals4j.internal.Json;
import com.dvarahq.oss.evals4j.message.ChatMessage;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;

import java.util.ArrayList;
import java.util.List;

/** Converts between evals4j messages and Spring AI's. */
public final class SpringAiMessages {

    private SpringAiMessages() {}

    public static List<Message> toSpringAi(List<ChatMessage> messages) {
        List<Message> converted = new ArrayList<>(messages.size());
        for (ChatMessage message : messages) {
            converted.add(toSpringAi(message));
        }
        return converted;
    }

    public static Message toSpringAi(ChatMessage message) {
        String text = textOf(message);
        return switch (message.role()) {
            case ChatMessage.ROLE_SYSTEM -> new SystemMessage(text);
            case ChatMessage.ROLE_ASSISTANT -> new AssistantMessage(text);
            case ChatMessage.ROLE_TOOL -> ToolResponseMessage.builder()
                    .responses(List.of(
                            new ToolResponseMessage.ToolResponse(message.toolCallId(), "", text)))
                    .build();
            default -> new UserMessage(text);
        };
    }

    /**
     * Flattens content to text.
     *
     * <p>Multimodal blocks are JSON-encoded rather than dropped: the image and voice prompts embed
     * URLs and data URIs, and a model handed the JSON can still act on them, whereas a silently empty
     * message would score nothing at all.
     */
    private static String textOf(ChatMessage message) {
        return message.content() instanceof String string ? string : Json.write(message.content());
    }
}
