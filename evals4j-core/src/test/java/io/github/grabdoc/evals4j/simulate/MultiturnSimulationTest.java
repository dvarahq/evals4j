package io.github.grabdoc.evals4j.simulate;

import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.grabdoc.evals4j.Evaluator;
import io.github.grabdoc.evals4j.ScorerOutput;
import io.github.grabdoc.evals4j.internal.Json;
import io.github.grabdoc.evals4j.message.ChatMessage;
import io.github.grabdoc.evals4j.testing.FakeJudgeModel;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MultiturnSimulationTest {

    @Test
    void alternatesUserAndAppMessagesForTheGivenNumberOfTurns() {
        SimulationResult result = MultiturnSimulation.builder()
                .user(List.of("hello", "and again", "last one"))
                .app((message, threadId) -> ChatMessage.assistant("you said: " + message.text()))
                .run();

        assertThat(result.trajectory()).hasSize(6);
        assertThat(result.turns()).isEqualTo(3);
        assertThat(result.trajectory().get(0).text()).isEqualTo("hello");
        assertThat(result.trajectory().get(1).text()).isEqualTo("you said: hello");
        assertThat(result.trajectory().get(5).text()).isEqualTo("you said: last one");
    }

    @Test
    void capsTheConversationAtMaxTurns() {
        AtomicInteger calls = new AtomicInteger();
        SimulationResult result = MultiturnSimulation.builder()
                .user((trajectory, turn, threadId) -> ChatMessage.user("turn " + turn))
                .app((message, threadId) -> {
                    calls.incrementAndGet();
                    return ChatMessage.assistant("ok");
                })
                .maxTurns(2)
                .run();

        assertThat(calls).hasValue(2);
        assertThat(result.trajectory()).hasSize(4);
    }

    @Test
    void stopsEarlyOnTheStoppingCondition() {
        SimulationResult result = MultiturnSimulation.builder()
                .user((trajectory, turn, threadId) -> ChatMessage.user("turn " + turn))
                .app((message, threadId) ->
                        ChatMessage.assistant(message.text().equals("turn 1") ? "DONE" : "working"))
                .maxTurns(10)
                .stopWhen((trajectory, turn) ->
                        trajectory.get(trajectory.size() - 1).text().equals("DONE"))
                .run();

        // The condition is checked after a complete exchange, so turn 1 finishes before stopping.
        assertThat(result.turns()).isEqualTo(2);
        assertThat(result.trajectory()).hasSize(4);
    }

    @Test
    void requiresATerminationCondition() {
        assertThatThrownBy(() -> MultiturnSimulation.builder()
                        .user((trajectory, turn, threadId) -> ChatMessage.user("hi"))
                        .app((message, threadId) -> ChatMessage.assistant("hi"))
                        .build())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("maxTurns or stopWhen");
    }

    @Test
    void passesAStableThreadIdToTheApp() {
        List<String> seen = new ArrayList<>();
        MultiturnSimulation.builder()
                .user(List.of("a", "b"))
                .app((message, threadId) -> {
                    seen.add(threadId);
                    return ChatMessage.assistant("ok");
                })
                .threadId("thread-42")
                .run();

        assertThat(seen).containsExactly("thread-42", "thread-42");
    }

    @Test
    void generatesAThreadIdWhenNoneIsGiven() {
        List<String> seen = new ArrayList<>();
        MultiturnSimulation.builder()
                .user(List.of("a", "b"))
                .app((message, threadId) -> {
                    seen.add(threadId);
                    return ChatMessage.assistant("ok");
                })
                .run();

        assertThat(seen.get(0)).isNotBlank().isEqualTo(seen.get(1));
    }

    @Test
    void dedupesMessagesAnAppRepeatsById() {
        // An app that returns its whole history each turn would otherwise duplicate every message.
        List<ChatMessage> history = new ArrayList<>();
        SimulationResult result = MultiturnSimulation.builder()
                .user(List.of("one", "two"))
                .app((message, threadId) -> {
                    history.add(new ChatMessage("reply-" + history.size(), ChatMessage.ROLE_ASSISTANT,
                            "reply " + history.size(), null, null));
                    return Map.of("messages", List.copyOf(history));
                })
                .run();

        assertThat(result.trajectory().stream().map(ChatMessage::text))
                .containsExactly("one", "reply 0", "two", "reply 1");
    }

    @Test
    void handlesAnAppThatReturnsSeveralMessages() {
        SimulationResult result = MultiturnSimulation.builder()
                .user(List.of("go"))
                .app((message, threadId) -> List.of(
                        ChatMessage.assistant("thinking..."), ChatMessage.assistant("done")))
                .run();

        assertThat(result.trajectory()).hasSize(3);
    }

    @Test
    void scoresTheFinishedTranscript() {
        Evaluator counter = Evaluator.from(
                "message_count", "message_count",
                request -> ScorerOutput.of((double) ((List<?>) request.outputs()).size()));

        SimulationResult result = MultiturnSimulation.builder()
                .user(List.of("a", "b"))
                .app((message, threadId) -> ChatMessage.assistant("ok"))
                .trajectoryEvaluator(counter)
                .run();

        assertThat(result.evaluatorResults()).singleElement().satisfies(evaluated -> {
            assertThat(evaluated.key()).isEqualTo("message_count");
            assertThat(evaluated.score().doubleValue()).isEqualTo(4.0);
        });
    }

    @Test
    void keepsTheTranscriptWhenAnEvaluatorFails() {
        Evaluator broken = Evaluator.from("broken", "broken", request -> {
            throw new IllegalStateException("evaluator blew up");
        });

        SimulationResult result = MultiturnSimulation.builder()
                .user(List.of("a"))
                .app((message, threadId) -> ChatMessage.assistant("ok"))
                .trajectoryEvaluator(broken)
                .run();

        assertThat(result.trajectory()).hasSize(2);
        assertThat(result.evaluatorResults()).isEmpty();
    }

    @Test
    void refusesToRunPastTheEndOfAScript() {
        assertThatThrownBy(() -> MultiturnSimulation.builder()
                        .user(SimulatedUser.scripted("only one"))
                        .app((message, threadId) -> ChatMessage.assistant("ok"))
                        .maxTurns(3)
                        .run())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("scripted user responses");
    }

    // ---- LLM-driven user --------------------------------------------------------------------

    @Test
    void llmUserBootstrapsTheConversation() {
        ObjectNode response = Json.object();
        response.put("message", "My order never arrived.");
        FakeJudgeModel model = FakeJudgeModel.returning(response);

        SimulationResult result = MultiturnSimulation.builder()
                .user(SimulatedUser.llm("You are a frustrated customer.", model))
                .app((message, threadId) -> ChatMessage.assistant("Sorry to hear that."))
                .maxTurns(1)
                .run();

        assertThat(result.trajectory().get(0).text()).isEqualTo("My order never arrived.");
        assertThat(model.lastPromptText()).contains(SimulatedUser.LlmSimulatedUser.BOOTSTRAP);
    }

    @Test
    void llmUserUsesFixedResponsesBeforeImprovising() {
        ObjectNode response = Json.object();
        response.put("message", "improvised");
        FakeJudgeModel model = FakeJudgeModel.returning(response);

        SimulationResult result = MultiturnSimulation.builder()
                .user(SimulatedUser.llm("persona", model, List.of("scripted opener")))
                .app((message, threadId) -> ChatMessage.assistant("ok"))
                .maxTurns(2)
                .run();

        assertThat(result.trajectory().get(0).text()).isEqualTo("scripted opener");
        assertThat(result.trajectory().get(2).text()).isEqualTo("improvised");
        assertThat(model.callCount()).isEqualTo(1);
    }

    @Test
    void llmUserSeesTheConversationFromItsOwnPerspective() {
        List<ChatMessage> trajectory = List.of(
                ChatMessage.system("internal"),
                ChatMessage.user("my order is late"),
                ChatMessage.assistant("", List.of(new io.github.grabdoc.evals4j.message.ToolCall(
                        "1", "lookup_order", "{}"))),
                ChatMessage.tool("order #7", "1"),
                ChatMessage.assistant("It ships tomorrow."));

        List<ChatMessage> inverted = SimulatedUser.LlmSimulatedUser.invertRoles(trajectory);

        // The user's own turns become assistant messages; the app's replies become user messages.
        // Tool traffic and system messages are dropped — a person would not see them.
        assertThat(inverted).hasSize(2);
        assertThat(inverted.get(0).role()).isEqualTo(ChatMessage.ROLE_ASSISTANT);
        assertThat(inverted.get(0).text()).isEqualTo("my order is late");
        assertThat(inverted.get(1).role()).isEqualTo(ChatMessage.ROLE_USER);
        assertThat(inverted.get(1).text()).isEqualTo("It ships tomorrow.");
    }
}
