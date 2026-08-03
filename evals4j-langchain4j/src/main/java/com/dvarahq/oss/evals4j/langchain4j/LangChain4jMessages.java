package com.dvarahq.oss.evals4j.langchain4j;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.Content;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.TextContent;
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

    /**
     * Converts a LangChain4j conversation into evals4j messages.
     *
     * <p>This is how a real agent run reaches the trajectory evaluators: take the messages the agent
     * produced, convert them, and hand them to {@code TrajectoryMatchEvaluator} or
     * {@code TrajectoryLlmAsJudge} as the outputs.
     *
     * <pre>{@code
     * List<ChatMessage> trajectory = LangChain4jMessages.fromLangChain4j(chatMemory.messages());
     * evaluator.evaluate(EvalRequest.of(null, trajectory, referenceTrajectory));
     * }</pre>
     *
     * <p>Tool calls are preserved with their ids, names and raw argument JSON, which is what the
     * trajectory matchers compare on.
     */
    public static List<ChatMessage> fromLangChain4j(
            List<dev.langchain4j.data.message.ChatMessage> messages) {
        List<ChatMessage> converted = new ArrayList<>(messages.size());
        for (dev.langchain4j.data.message.ChatMessage message : messages) {
            converted.add(fromLangChain4j(message));
        }
        return converted;
    }

    /** Converts a single LangChain4j message. */
    public static ChatMessage fromLangChain4j(dev.langchain4j.data.message.ChatMessage message) {
        return switch (message.type()) {
            case SYSTEM -> ChatMessage.system(((SystemMessage) message).text());
            case AI -> fromLangChain4j((AiMessage) message);
            case TOOL_EXECUTION_RESULT -> {
                ToolExecutionResultMessage result = (ToolExecutionResultMessage) message;
                yield ChatMessage.tool(result.text(), result.id());
            }
            default -> ChatMessage.user(textOf((UserMessage) message));
        };
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

    /**
     * The text of a user message.
     *
     * <p>A multimodal user message has several contents; only the textual ones are joined, matching
     * how the outbound direction flattens content. The judge prompts read text.
     */
    private static String textOf(UserMessage message) {
        StringBuilder text = new StringBuilder();
        for (Content content : message.contents()) {
            if (content instanceof TextContent textContent) {
                if (text.length() > 0) {
                    text.append('\n');
                }
                text.append(textContent.text());
            }
        }
        return text.toString();
    }
}
