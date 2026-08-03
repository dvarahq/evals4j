package com.dvarahq.oss.evals4j;

import com.dvarahq.oss.evals4j.result.EvaluatorResult;
import com.dvarahq.oss.evals4j.result.Score;
import com.dvarahq.oss.evals4j.spi.EvalTracer;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The piece every evaluator funnels through, and which nothing referenced by name until now.
 */
class ScorerRunnerTest {

    private static final class RecordingTracer implements EvalTracer {
        final List<String> events = new ArrayList<>();
        private final Object token;

        RecordingTracer(Object token) {
            this.token = token;
        }

        @Override
        public Object onStart(String runName, String feedbackKey) {
            events.add("start:" + runName + ":" + feedbackKey);
            return token;
        }

        @Override
        public void onFinish(Object seen, String runName, List<EvaluatorResult> results) {
            events.add("finish:" + runName + ":" + results.size());
        }

        @Override
        public void onError(Object seen, String runName, Throwable error) {
            events.add("error:" + runName + ":" + error.getMessage());
        }
    }

    @Test
    void recordsASingleOutputUnderTheEvaluatorsFeedbackKey() {
        List<EvaluatorResult> results = ScorerRunner.run(
                "my_evaluator",
                "my_key",
                request -> ScorerOutput.of(true, "because"),
                EvalRequest.ofOutputs("x"),
                null);

        assertThat(results).hasSize(1);
        assertThat(results.get(0).key()).isEqualTo("my_key");
        assertThat(results.get(0).comment()).isEqualTo("because");
    }

    @Test
    void aKeyedOutputTurnsItsMapKeysIntoFeedbackKeys() {
        Map<String, ScorerOutput.Single> keyed = new LinkedHashMap<>();
        keyed.put("first", ScorerOutput.of(1.0));
        keyed.put("second", ScorerOutput.of(0.0));

        List<EvaluatorResult> results = ScorerRunner.toResults("ignored", ScorerOutput.keyed(keyed));

        assertThat(results).extracting(EvaluatorResult::key).containsExactly("first", "second");
    }

    @Test
    void keyOrderSurvivesEnoughKeysToRuleOutLuck() {
        // Map.copyOf returns an unordered map whose iteration order is salted per JVM run, so a
        // two-key check passes about half the time by chance. Enough keys makes that vanishingly
        // unlikely — and the order decides which result evaluate() returns.
        Map<String, ScorerOutput.Single> keyed = new LinkedHashMap<>();
        List<String> expected = new ArrayList<>();
        for (int i = 0; i < 12; i++) {
            String key = "key_" + i;
            expected.add(key);
            keyed.put(key, ScorerOutput.of(1.0));
        }

        List<EvaluatorResult> results = ScorerRunner.toResults("ignored", ScorerOutput.keyed(keyed));

        assertThat(results).extracting(EvaluatorResult::key).containsExactlyElementsOf(expected);
    }

    @Test
    void aKeyWithSeveralValuesProducesAResultEach() {
        // The Keyed record models a list per key, but no built-in evaluator produces one — so this
        // branch is otherwise unexercised.
        ScorerOutput.Keyed keyed = new ScorerOutput.Keyed(Map.of(
                "repeated",
                List.of(
                        new ScorerOutput.Single(Score.of(1.0), "first", Map.of(), null),
                        new ScorerOutput.Single(Score.of(0.0), "second", Map.of(), null))));

        List<EvaluatorResult> results = ScorerRunner.toResults("ignored", keyed);

        assertThat(results).hasSize(2);
        assertThat(results).extracting(EvaluatorResult::key).containsOnly("repeated");
        assertThat(results).extracting(EvaluatorResult::comment).containsExactly("first", "second");
    }

    @Test
    void firesStartThenFinish() {
        RecordingTracer tracer = new RecordingTracer(null);

        ScorerRunner.run(
                "my_evaluator", "my_key", request -> ScorerOutput.of(true), EvalRequest.ofOutputs("x"), tracer);

        assertThat(tracer.events).containsExactly("start:my_evaluator:my_key", "finish:my_evaluator:1");
    }

    @Test
    void reportsAFailureThroughOnErrorAndRethrows() {
        RecordingTracer tracer = new RecordingTracer(null);

        assertThatThrownBy(() -> ScorerRunner.run(
                        "my_evaluator",
                        "my_key",
                        request -> {
                            throw new IllegalStateException("scorer blew up");
                        },
                        EvalRequest.ofOutputs("x"),
                        tracer))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("scorer blew up");

        assertThat(tracer.events)
                .containsExactly("start:my_evaluator:my_key", "error:my_evaluator:scorer blew up");
    }

    @Test
    void aNullTracerIsTreatedAsNoOp() {
        assertThat(ScorerRunner.run(
                        "my_evaluator", "my_key", request -> ScorerOutput.of(true), EvalRequest.ofOutputs("x"), null))
                .hasSize(1);
    }

    @Test
    void recordsAStringTokenAsTheSourceRunId() {
        List<EvaluatorResult> results = ScorerRunner.run(
                "my_evaluator",
                "my_key",
                request -> ScorerOutput.of(true),
                EvalRequest.ofOutputs("x"),
                new RecordingTracer("run-abc123"));

        assertThat(results.get(0).sourceRunId()).isEqualTo("run-abc123");
    }

    @Test
    void leavesTheSourceRunIdAloneForANonIdentifierToken() {
        // The Micrometer tracer returns a timing sample from onStart; that is not a run id.
        List<EvaluatorResult> results = ScorerRunner.run(
                "my_evaluator",
                "my_key",
                request -> ScorerOutput.of(true),
                EvalRequest.ofOutputs("x"),
                new RecordingTracer(new Object()));

        assertThat(results.get(0).sourceRunId()).isNull();
    }

    @Test
    void doesNotOverwriteASourceRunIdTheScorerSet() {
        List<EvaluatorResult> results = ScorerRunner.run(
                "my_evaluator",
                "my_key",
                request -> new ScorerOutput.Single(Score.of(true), null, Map.of(), "set-by-scorer"),
                EvalRequest.ofOutputs("x"),
                new RecordingTracer("run-abc123"));

        assertThat(results.get(0).sourceRunId()).isEqualTo("set-by-scorer");
    }
}
