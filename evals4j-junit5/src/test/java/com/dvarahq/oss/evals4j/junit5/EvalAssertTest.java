package com.dvarahq.oss.evals4j.junit5;

import com.dvarahq.oss.evals4j.result.EvaluatorResult;
import com.dvarahq.oss.evals4j.result.Score;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class EvalAssertTest {

    private static EvaluatorResult result(String key, boolean passed, String comment) {
        return new EvaluatorResult(key, Score.of(passed), comment, Map.of(), null);
    }

    @Test
    void passesWhenTheScoreIsTrue() {
        assertThatCode(() -> EvalAssert.assertPassed(result("conciseness", true, "terse")))
                .doesNotThrowAnyException();
    }

    @Test
    void failureMessageCarriesTheJudgeReasoning() {
        // The reasoning is the actionable part; a bare "expected true but was false" is not.
        assertThatThrownBy(() -> EvalAssert.assertPassed(
                        result("conciseness", false, "The answer padded three sentences of preamble.")))
                .hasMessageContaining("conciseness")
                .hasMessageContaining("expected to pass")
                .hasMessageContaining("padded three sentences of preamble");
    }

    @Test
    void checksScoreThresholds() {
        EvaluatorResult scored =
                new EvaluatorResult("correctness", Score.of(0.6), "partly right", Map.of(), null);

        assertThatCode(() -> EvalAssert.assertScoreAtLeast(0.5, scored)).doesNotThrowAnyException();
        assertThatCode(() -> EvalAssert.assertScoreAtMost(0.7, scored)).doesNotThrowAnyException();
        assertThatThrownBy(() -> EvalAssert.assertScoreAtLeast(0.8, scored))
                .hasMessageContaining("at least 0.8");
        assertThatThrownBy(() -> EvalAssert.assertScoreAtMost(0.5, scored))
                .hasMessageContaining("at most 0.5");
    }

    @Test
    void reportsEveryFailureNotJustTheFirst() {
        assertThatThrownBy(() -> EvalAssert.assertAllPassed(List.of(
                        result("a", true, null),
                        result("b", false, "b was wrong"),
                        result("c", false, "c was wrong"))))
                .hasMessageContaining("2 of 3 evaluations failed")
                .hasMessageContaining("b was wrong")
                .hasMessageContaining("c was wrong");
    }

    @Test
    void reportAveragesScoresPerKey(@TempDir Path directory) throws Exception {
        EvalReport report = new EvalReport();
        report.onFinish(null, "run", List.of(result("conciseness", true, "terse")));
        report.onFinish(null, "run", List.of(result("conciseness", false, "wordy")));
        report.onFinish(null, "run", List.of(result("correctness", true, "right")));

        assertThat(report.meanScores())
                .containsEntry("conciseness", 0.5)
                .containsEntry("correctness", 1.0);

        Path destination = directory.resolve("nested/evals.md");
        report.writeMarkdown(destination);

        String markdown = Files.readString(destination);
        assertThat(markdown).contains("# Eval report").contains("conciseness").contains("0.500");
    }
}
