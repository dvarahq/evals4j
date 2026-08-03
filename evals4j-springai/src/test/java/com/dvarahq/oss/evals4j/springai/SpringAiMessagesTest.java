package com.dvarahq.oss.evals4j.springai;

import com.dvarahq.oss.evals4j.EvalRequest;
import com.dvarahq.oss.evals4j.message.ChatMessage;
import com.dvarahq.oss.evals4j.message.ToolCall;
import com.dvarahq.oss.evals4j.result.EvaluatorResult;
import com.dvarahq.oss.evals4j.trajectory.TrajectoryMatchEvaluator;
import com.dvarahq.oss.evals4j.trajectory.TrajectoryMatchMode;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SpringAiMessagesTest {

    /** What a Spring AI agent that called one tool leaves behind. */
    private static List<Message> agentConversation() {
        return List.of(
                new SystemMessage("You are a travel agent."),
                new UserMessage("Book me a flight to Berlin"),
                AssistantMessage.builder()
                        .content("")
                        .toolCalls(List.of(new AssistantMessage.ToolCall(
                                "call_1", "function", "book_flight", "{\"destination\":\"Berlin\"}")))
                        .build(),
                ToolResponseMessage.builder()
                        .responses(List.of(
                                new ToolResponseMessage.ToolResponse("call_1", "book_flight", "BA123")))
                        .build(),
                new AssistantMessage("You are booked on BA123."));
    }

    @Test
    void preservesRolesAndText() {
        List<ChatMessage> converted = SpringAiMessages.fromSpringAi(agentConversation());

        assertThat(converted).hasSize(5);
        assertThat(converted.get(0).role()).isEqualTo(ChatMessage.ROLE_SYSTEM);
        assertThat(converted.get(1).text()).isEqualTo("Book me a flight to Berlin");
        assertThat(converted.get(3).role()).isEqualTo(ChatMessage.ROLE_TOOL);
        assertThat(converted.get(3).toolCallId()).isEqualTo("call_1");
        assertThat(converted.get(4).text()).isEqualTo("You are booked on BA123.");
    }

    @Test
    void preservesToolCallsWithNamesAndArguments() {
        List<ChatMessage> converted = SpringAiMessages.fromSpringAi(agentConversation());

        ChatMessage assistant = converted.get(2);
        assertThat(assistant.hasToolCalls()).isTrue();
        assertThat(assistant.toolCalls())
                .containsExactly(new ToolCall("call_1", "book_flight", "{\"destination\":\"Berlin\"}"));
    }

    @Test
    void aToolResponseMessageWithSeveralResponsesExpandsToOneMessageEach() {
        List<Message> messages = List.of(ToolResponseMessage.builder()
                .responses(List.of(
                        new ToolResponseMessage.ToolResponse("call_1", "book_flight", "BA123"),
                        new ToolResponseMessage.ToolResponse("call_2", "book_hotel", "Hotel Adlon")))
                .build());

        List<ChatMessage> converted = SpringAiMessages.fromSpringAi(messages);

        assertThat(converted).hasSize(2);
        assertThat(converted).extracting(ChatMessage::toolCallId).containsExactly("call_1", "call_2");
    }

    @Test
    void aConvertedAgentRunCanBeScoredByTheTrajectoryEvaluator() {
        // The point of the converter: a real Spring AI run, judged against a reference trajectory
        // without the caller hand-rolling the mapping.
        List<ChatMessage> actual = SpringAiMessages.fromSpringAi(agentConversation());

        List<ChatMessage> reference = List.of(
                ChatMessage.system("You are a travel agent."),
                ChatMessage.user("Book me a flight to Berlin"),
                ChatMessage.assistant(
                        "",
                        List.of(new ToolCall("ref_1", "book_flight", "{\"destination\":\"Berlin\"}"))),
                ChatMessage.tool("BA123", "ref_1"),
                ChatMessage.assistant("You are booked on BA123."));

        EvaluatorResult result = TrajectoryMatchEvaluator.builder()
                .matchMode(TrajectoryMatchMode.STRICT)
                .build()
                .evaluate(EvalRequest.of(null, actual, reference));

        assertThat(result.passed())
                .as("trajectory comparison said: %s", result.comment())
                .isTrue();
    }

    @Test
    void roundTripsBackToSpringAiWithoutLosingRolesOrText() {
        List<ChatMessage> converted = SpringAiMessages.fromSpringAi(agentConversation());
        List<Message> back = SpringAiMessages.toSpringAi(converted);

        assertThat(back).extracting(Message::getMessageType)
                .containsExactlyElementsOf(
                        agentConversation().stream().map(Message::getMessageType).toList());
        assertThat(back.get(4).getText()).isEqualTo("You are booked on BA123.");
    }
}
