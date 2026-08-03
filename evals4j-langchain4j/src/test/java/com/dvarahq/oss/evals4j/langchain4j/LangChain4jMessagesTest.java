package com.dvarahq.oss.evals4j.langchain4j;

import com.dvarahq.oss.evals4j.EvalRequest;
import com.dvarahq.oss.evals4j.message.ChatMessage;
import com.dvarahq.oss.evals4j.message.ToolCall;
import com.dvarahq.oss.evals4j.result.EvaluatorResult;
import com.dvarahq.oss.evals4j.trajectory.TrajectoryMatchEvaluator;
import com.dvarahq.oss.evals4j.trajectory.TrajectoryMatchMode;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.data.message.UserMessage;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class LangChain4jMessagesTest {

    /** What a LangChain4j agent that called one tool leaves in its chat memory. */
    private static List<dev.langchain4j.data.message.ChatMessage> agentConversation() {
        return List.of(
                SystemMessage.from("You are a travel agent."),
                UserMessage.from("Book me a flight to Berlin"),
                AiMessage.from(ToolExecutionRequest.builder()
                        .id("call_1")
                        .name("book_flight")
                        .arguments("{\"destination\":\"Berlin\"}")
                        .build()),
                ToolExecutionResultMessage.from("call_1", "book_flight", "BA123"),
                AiMessage.from("You are booked on BA123."));
    }

    @Test
    void preservesRolesAndText() {
        List<ChatMessage> converted = LangChain4jMessages.fromLangChain4j(agentConversation());

        assertThat(converted).hasSize(5);
        assertThat(converted.get(0).role()).isEqualTo(ChatMessage.ROLE_SYSTEM);
        assertThat(converted.get(1).text()).isEqualTo("Book me a flight to Berlin");
        assertThat(converted.get(3).role()).isEqualTo(ChatMessage.ROLE_TOOL);
        assertThat(converted.get(3).toolCallId()).isEqualTo("call_1");
        assertThat(converted.get(4).text()).isEqualTo("You are booked on BA123.");
    }

    @Test
    void preservesToolCallsWithNamesAndArguments() {
        List<ChatMessage> converted = LangChain4jMessages.fromLangChain4j(agentConversation());

        ChatMessage assistant = converted.get(2);
        assertThat(assistant.hasToolCalls()).isTrue();
        assertThat(assistant.toolCalls())
                .containsExactly(new ToolCall("call_1", "book_flight", "{\"destination\":\"Berlin\"}"));
    }

    @Test
    void aConvertedAgentRunCanBeScoredByTheTrajectoryEvaluator() {
        // The point of the converter: a real LangChain4j run, judged against a reference trajectory
        // without the caller hand-rolling the mapping.
        List<ChatMessage> actual = LangChain4jMessages.fromLangChain4j(agentConversation());

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
    void roundTripsBackToLangChain4jWithoutLosingRolesOrText() {
        List<ChatMessage> converted = LangChain4jMessages.fromLangChain4j(agentConversation());
        List<dev.langchain4j.data.message.ChatMessage> back =
                LangChain4jMessages.toLangChain4j(converted);

        assertThat(back).extracting(dev.langchain4j.data.message.ChatMessage::type)
                .containsExactlyElementsOf(
                        agentConversation().stream()
                                .map(dev.langchain4j.data.message.ChatMessage::type)
                                .toList());
        assertThat(((AiMessage) back.get(4)).text()).isEqualTo("You are booked on BA123.");
    }
}
