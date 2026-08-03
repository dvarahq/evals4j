package io.github.grabdoc.evals4j.message;

import java.util.List;
import java.util.StringJoiner;

/**
 * Renders a conversation as the XML-ish text an LLM judge reads.
 *
 * <p>Byte-for-byte port of OpenEvals' {@code _chat_completion_messages_to_string}. The trajectory and
 * conversation prompts were written against exactly this rendering, so the tag names, ordering and
 * the blank line between messages are load-bearing — do not "tidy" them.
 */
public final class MessageRenderer {

    private MessageRenderer() {}

    public static String render(List<ChatMessage> messages) {
        StringJoiner joiner = new StringJoiner("\n\n");
        for (ChatMessage message : messages) {
            joiner.add(renderOne(message));
        }
        return joiner.toString();
    }

    private static String renderOne(ChatMessage message) {
        String content = message.content() instanceof String string ? string : String.valueOf(message.content());
        if (content == null) {
            content = "";
        }

        if (message.hasToolCalls()) {
            StringJoiner calls = new StringJoiner("\n");
            for (ToolCall call : message.toolCalls()) {
                calls.add("<tool_call>\n<name>" + call.name() + "</name>\n<arguments>"
                        + call.arguments() + "</arguments>\n</tool_call>");
            }
            content = content.isEmpty() ? calls.toString() : content + "\n" + calls;
        }

        if (message.toolCallId() != null && !message.toolCallId().isEmpty()) {
            content = "<tool_result>\n<id>" + message.toolCallId() + "</id>\n<content>"
                    + content + "</content>\n</tool_result>";
        }

        return "<" + message.role() + ">\n" + content + "\n</" + message.role() + ">";
    }
}
