package com.dvarahq.oss.evals4j.examples;

import com.dvarahq.oss.evals4j.EvalRequest;
import com.dvarahq.oss.evals4j.judge.LlmAsJudge;
import com.dvarahq.oss.evals4j.junit5.EvalAssert;
import com.dvarahq.oss.evals4j.junit5.EvalSuite;
import com.dvarahq.oss.evals4j.message.ChatMessage;
import com.dvarahq.oss.evals4j.message.ToolCall;
import com.dvarahq.oss.evals4j.prompt.Prompts;
import com.dvarahq.oss.evals4j.result.EvaluatorResult;
import com.dvarahq.oss.evals4j.spi.JudgeModel;
import com.dvarahq.oss.evals4j.trajectory.ToolArgsMatchMode;
import com.dvarahq.oss.evals4j.trajectory.TrajectoryMatchEvaluator;
import com.dvarahq.oss.evals4j.trajectory.TrajectoryMatchMode;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.List;

/**
 * An eval suite as a JUnit test class — the shape most projects want.
 *
 * <p>Two things are worth copying from this. First, the deterministic checks are separated from the
 * model-backed ones, so the cheap, never-flaky part of the suite runs on every commit while the
 * expensive part is opt-in. Second, the model-backed suite carries {@link EvalSuite}, so every judge
 * it builds reports into one run report without being wired to it, and that report is written and
 * compared against the previous run automatically: for LLM scores the trend is the signal, and a
 * green tick alone cannot show you that last week's 0.9 is now a 0.7.
 */
class EvalSuiteExampleTest {

    /**
     * Trajectory and structured-output checks need no model, so they run everywhere — including in
     * CI on a pull request, which is where an agent regression should be caught.
     */
    @Nested
    class Deterministic {

        private static final List<ChatMessage> REFERENCE = List.of(
                ChatMessage.user("What time is flight LX0112?"),
                ChatMessage.assistant("", List.of(
                        new ToolCall("", "get_flight_info", "{\"flight_no\":\"LX0112\"}"))),
                ChatMessage.tool("Departs 10:00.", "1"),
                ChatMessage.assistant("It departs at 10:00."));

        @Test
        void theAgentStillCallsTheLookupTool() {
            List<ChatMessage> actual = List.of(
                    ChatMessage.user("What time is flight LX0112?"),
                    ChatMessage.assistant("", List.of(new ToolCall(
                            "call_1", "get_flight_info",
                            "{\"flight_no\":\"LX0112\",\"trace_id\":\"abc\"}"))),
                    ChatMessage.tool("Departs 10:00.", "call_1"),
                    ChatMessage.assistant("Your flight leaves at 10:00 sharp."));

            EvalAssert.assertPassed(TrajectoryMatchEvaluator.builder()
                    .matchMode(TrajectoryMatchMode.SUPERSET)
                    .toolArgsMatchMode(ToolArgsMatchMode.EXACT)
                    // trace_id changes every run; flight_no is the part under test.
                    .overrideOnKeys("get_flight_info", "flight_no")
                    .build()
                    .evaluate(EvalRequest.of(null, actual, REFERENCE)));
        }

        @Test
        void answeringWithoutCallingTheToolIsAFailure() {
            List<ChatMessage> guessed = List.of(
                    ChatMessage.user("What time is flight LX0112?"),
                    ChatMessage.assistant("I believe it leaves around 10 in the morning."));

            EvalAssert.assertFailed(TrajectoryMatchEvaluator.builder()
                    .matchMode(TrajectoryMatchMode.SUPERSET)
                    .toolArgsMatchMode(ToolArgsMatchMode.IGNORE)
                    .build()
                    .evaluate(EvalRequest.of(null, guessed, REFERENCE)));
        }
    }

    /** Model-backed checks. Opt-in, because they cost money and are not deterministic. */
    @Nested
    @EvalSuite
    @EnabledIfEnvironmentVariable(named = "OPENAI_API_KEY", matches = ".+")
    class ModelBacked {

        private static LlmAsJudge conciseness;
        private static LlmAsJudge correctness;

        @BeforeAll
        static void setUp() {
            JudgeModel judge = Models.springAiJudge();
            // No tracer wiring: @EvalSuite opens a scope around each test and these judges, built
            // without one, report into it.
            conciseness = LlmAsJudge.builder()
                    .prompt(Prompts.CONCISENESS_PROMPT)
                    .feedbackKey("conciseness")
                    .model(judge)
                    .build();
            correctness = LlmAsJudge.builder()
                    .prompt(Prompts.CORRECTNESS_PROMPT)
                    .feedbackKey("correctness")
                    .model(judge)
                    .continuous(true)
                    .build();
        }

        /**
         * A table of cases is the usual way an eval suite grows: adding a regression means adding a
         * row, not writing another test method.
         */
        @ParameterizedTest(name = "[{index}] {0}")
        @CsvSource({
            "'What is the capital of France?','Paris.','Paris'",
            "'Who wrote Hamlet?','William Shakespeare wrote Hamlet.','William Shakespeare'",
            "'What is 2 + 2?','4','4'"
        })
        void answersAreCorrect(String question, String answer, String reference) {
            EvaluatorResult result = correctness.evaluate(question, answer, reference);
            // A threshold rather than pass/fail — judges are not deterministic, and a suite that
            // demands 1.0 on every run will flake.
            EvalAssert.assertScoreAtLeast(0.7, result);
        }

        @Test
        void answersDoNotPadWithPleasantries() {
            EvalAssert.assertPassed(conciseness.evaluate(
                    "What is the capital of France?", "Paris."));
        }

        @Test
        void everyQualityCheckPassesTogether() {
            String question = "What is the capital of France?";
            String answer = "Paris.";

            // assertAllPassed reports every failure at once, which beats fixing them one per run.
            EvalAssert.assertAllPassed(List.of(
                    conciseness.evaluate(question, answer),
                    LlmAsJudge.builder()
                            .prompt(Prompts.ANSWER_RELEVANCE_PROMPT)
                            .feedbackKey("relevance")
                            .model(Models.springAiJudge())
                            .build()
                            .evaluate(question, answer)));
        }
    }
}
