package io.github.grabdoc.evals4j.trajectory;

import io.github.grabdoc.evals4j.EvalRequest;
import io.github.grabdoc.evals4j.message.ChatMessage;
import io.github.grabdoc.evals4j.message.ToolCall;
import io.github.grabdoc.evals4j.result.EvaluatorResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Trajectory matching, checked against OpenEvals' own test fixtures.
 *
 * <p>Every case here is transcribed from {@code openevals/python/tests/trajectory/test_trajectory.py},
 * including the expected score for each mode. Pinning to upstream's cases rather than to
 * independently invented ones is the point — it catches a port that is self-consistent but disagrees
 * with the library it claims parity with.
 */
class TrajectoryMatchParityTest {

    private static ToolCall weather(String city) {
        return new ToolCall("", "get_weather", "{\"city\":\"" + city + "\"}");
    }

    private static boolean score(TrajectoryMatchMode mode, List<ChatMessage> outputs, List<ChatMessage> reference) {
        return score(TrajectoryMatchEvaluator.builder().matchMode(mode).build(), outputs, reference);
    }

    private static boolean score(
            TrajectoryMatchEvaluator evaluator, List<ChatMessage> outputs, List<ChatMessage> reference) {
        EvaluatorResult result = evaluator.evaluate(EvalRequest.of(null, outputs, reference));
        return result.passed();
    }

    // ---- test_trajectory_with_different_tool_message_order ----------------------------------
    // Same calls, different message ordering: every mode accepts it.

    @ParameterizedTest(name = "{0} accepts reordered tool messages")
    @CsvSource({"STRICT", "UNORDERED", "SUBSET", "SUPERSET"})
    void acceptsDifferentToolMessageOrder(TrajectoryMatchMode mode) {
        List<ChatMessage> outputs = List.of(
                ChatMessage.user("What is the weather in SF and London?"),
                ChatMessage.assistant("", List.of(weather("SF"), weather("London"))),
                ChatMessage.tool("It's 80 degrees and sunny in SF.", "1"),
                ChatMessage.tool("It's 90 degrees and rainy in London.", "2"),
                ChatMessage.assistant("The weather in SF is 80 degrees and sunny. In London, it's 90 and rainy."));
        List<ChatMessage> reference = List.of(
                ChatMessage.user("What is the weather in SF and London?"),
                ChatMessage.assistant("", List.of(weather("London"), weather("SF"))),
                ChatMessage.tool("It's 90 degrees and rainy in London.", "1"),
                ChatMessage.tool("It's 80 degrees and sunny in SF.", "2"),
                ChatMessage.assistant("The weather in London is 90˚ and rainy. In SF, it's 80˚ and sunny."));

        assertThat(score(mode, outputs, reference)).isTrue();
    }

    // ---- test_trajectory_with_different_message_count ---------------------------------------
    // Same calls split across more messages: only strict rejects it.

    @ParameterizedTest(name = "{0} -> {1}")
    @CsvSource({"UNORDERED,true", "SUPERSET,true", "SUBSET,true", "STRICT,false"})
    void handlesDifferentMessageCount(TrajectoryMatchMode mode, boolean expected) {
        List<ChatMessage> outputs = List.of(
                ChatMessage.user("What is the weather in SF and London?"),
                ChatMessage.assistant("", List.of(weather("SF"))),
                ChatMessage.tool("It's 80 degrees and sunny in SF.", "1"),
                ChatMessage.assistant("", List.of(weather("London"))),
                ChatMessage.tool("It's 90 degrees and rainy in London.", "2"),
                ChatMessage.assistant("The weather in SF is 80 and sunny. In London, it's 90 and rainy."));
        List<ChatMessage> reference = List.of(
                ChatMessage.user("What is the weather in SF and London?"),
                ChatMessage.assistant("", List.of(weather("London"), weather("SF"))),
                ChatMessage.tool("It's 90 degrees and rainy in London.", "1"),
                ChatMessage.tool("It's 80 degrees and sunny in SF.", "2"),
                ChatMessage.assistant("The weather in London is 90˚ and rainy. In SF, it's 80˚ and sunny."));

        assertThat(score(mode, outputs, reference)).isEqualTo(expected);
    }

    // ---- test_trajectory_subset_tool_call ---------------------------------------------------
    // The agent called only one of the two reference tools: subset accepts, the rest reject.

    @ParameterizedTest(name = "{0} -> {1}")
    @CsvSource({"UNORDERED,false", "SUPERSET,false", "SUBSET,true", "STRICT,false"})
    void handlesAMissingToolCall(TrajectoryMatchMode mode, boolean expected) {
        List<ChatMessage> outputs = List.of(
                ChatMessage.user("What is the weather in SF and London?"),
                ChatMessage.assistant("", List.of(weather("SF"))),
                ChatMessage.tool("It's 80 degrees and sunny in SF.", "1"),
                ChatMessage.assistant("In SF it's 80. In London it's 9000 degrees and hallucinating."));
        List<ChatMessage> reference = List.of(
                ChatMessage.user("What is the weather in SF and London?"),
                ChatMessage.assistant("", List.of(weather("London"), weather("SF"))),
                ChatMessage.tool("It's 90 degrees and rainy in London.", "1"),
                ChatMessage.tool("It's 90 degrees and rainy in London.", "2"),
                ChatMessage.assistant("The weather in London is 90˚ and rainy. In SF, it's 80˚ and sunny."));

        assertThat(score(mode, outputs, reference)).isEqualTo(expected);
    }

    // ---- test_trajectory_match_strict_params ------------------------------------------------

    @ParameterizedTest(name = "strict with {0} args -> {1}")
    @CsvSource({"EXACT,false", "IGNORE,true"})
    void appliesToolArgsModeUnderStrict(ToolArgsMatchMode argsMode, boolean expected) {
        List<ChatMessage> outputs = List.of(
                ChatMessage.user("What is the weather in SF?"),
                ChatMessage.assistant("", List.of(new ToolCall("1234", "get_weather", "{\"city\":\"SF\"}"))),
                ChatMessage.tool("It's 80 degrees and sunny in SF.", "1234"),
                ChatMessage.assistant("The weather in SF is 80 degrees and sunny."));
        List<ChatMessage> reference = List.of(
                ChatMessage.user("What is the weather in SF?"),
                ChatMessage.assistant(
                        "", List.of(new ToolCall("1234", "get_weather", "{\"city\":\"San Francisco\"}"))),
                ChatMessage.tool("It's 80 degrees and sunny in SF.", "1234"),
                ChatMessage.assistant("The weather in SF is 80 degrees and sunny."));

        TrajectoryMatchEvaluator evaluator = TrajectoryMatchEvaluator.builder()
                .matchMode(TrajectoryMatchMode.STRICT)
                .toolArgsMatchMode(argsMode)
                .build();

        assertThat(evaluator.evaluate(EvalRequest.of(null, outputs, reference)).key())
                .isEqualTo("trajectory_strict_match");
        assertThat(score(evaluator, outputs, reference)).isEqualTo(expected);
    }

    // ---- test_tool_args_match_mode_superset -------------------------------------------------
    // Output args {is_cool, flight_no} vs reference {flight_no}.

    @ParameterizedTest(name = "{0} args -> {1}")
    @CsvSource({"EXACT,false", "IGNORE,true", "SUBSET,false", "SUPERSET,true"})
    void appliesEachToolArgsMode(ToolArgsMatchMode argsMode, boolean expected) {
        List<ChatMessage> outputs = List.of(
                ChatMessage.user("Hi there, what time is my flight?"),
                ChatMessage.assistant(
                        "",
                        List.of(new ToolCall(
                                "", "get_flight_info", "{\"is_cool\":true,\"flight_no\":\"LX0112\"}"))),
                ChatMessage.assistant("Your flight is at 10:00 AM."));
        List<ChatMessage> reference = List.of(
                ChatMessage.user("Hi there, what time is my flight?"),
                ChatMessage.assistant(
                        "", List.of(new ToolCall("", "get_flight_info", "{\"flight_no\":\"LX0112\"}"))),
                ChatMessage.assistant("Your flight is at 10:00 AM."));

        TrajectoryMatchEvaluator evaluator =
                TrajectoryMatchEvaluator.builder().toolArgsMatchMode(argsMode).build();

        assertThat(score(evaluator, outputs, reference)).isEqualTo(expected);
    }

    // ---- test_trajectory_match_with_nested_field_overrides ----------------------------------

    @Test
    @DisplayName("a dotted key-path override matches only the fields that matter")
    void appliesNestedFieldOverrides() {
        String outputArgs = "{\"query\":\"flight upgrades\","
                + "\"time\":{\"start\":\"2025-03-22T18:34:40Z\",\"end\":\"2025-03-22T20:34:40Z\"}}";
        String referenceArgs =
                "{\"query\":\"foo\",\"time\":{\"start\":\"2025-03-22T18:34:40Z\",\"end\":\"baz\"}}";

        List<ChatMessage> outputs = List.of(
                ChatMessage.user("Hi there, what time is my flight?"),
                ChatMessage.assistant("", List.of(new ToolCall("", "lookup_policy", outputArgs))),
                ChatMessage.assistant("No upgrades available."));
        List<ChatMessage> reference = List.of(
                ChatMessage.user("Hi there, what time is my flight?"),
                ChatMessage.assistant("", List.of(new ToolCall("", "lookup_policy", referenceArgs))),
                ChatMessage.assistant("Upgrades possible."));

        TrajectoryMatchEvaluator withoutOverrides =
                TrajectoryMatchEvaluator.create(TrajectoryMatchMode.STRICT);
        assertThat(score(withoutOverrides, outputs, reference)).isFalse();

        TrajectoryMatchEvaluator withOverrides = TrajectoryMatchEvaluator.builder()
                .matchMode(TrajectoryMatchMode.STRICT)
                .overrideOnKeys("lookup_policy", "time.start")
                .build();
        assertThat(score(withOverrides, outputs, reference)).isTrue();
    }

    // ---- feedback keys ----------------------------------------------------------------------

    @ParameterizedTest
    @CsvSource({
        "STRICT,trajectory_strict_match",
        "UNORDERED,trajectory_unordered_match",
        "SUBSET,trajectory_subset_match",
        "SUPERSET,trajectory_superset_match"
    })
    void usesUpstreamFeedbackKeys(TrajectoryMatchMode mode, String expectedKey) {
        assertThat(mode.feedbackKey()).isEqualTo(expectedKey);
    }

    @Test
    void acceptsMapsAsWellAsRecords() {
        List<?> outputs = List.of(
                java.util.Map.of("role", "user", "content", "hi"),
                java.util.Map.of(
                        "role", "assistant",
                        "content", "",
                        "tool_calls",
                        List.of(java.util.Map.of("name", "get_weather", "args", java.util.Map.of("city", "SF")))));
        List<ChatMessage> reference = List.of(
                ChatMessage.user("hi"), ChatMessage.assistant("", List.of(weather("SF"))));

        assertThat(TrajectoryMatchEvaluator.create(TrajectoryMatchMode.STRICT)
                        .evaluate(EvalRequest.of(null, outputs, reference))
                        .passed())
                .isTrue();
    }
}
