package com.dvarahq.oss.evals4j.junit5;

import com.dvarahq.oss.evals4j.EvalRequest;
import com.dvarahq.oss.evals4j.Evaluator;
import com.dvarahq.oss.evals4j.ScorerOutput;
import com.dvarahq.oss.evals4j.result.EvaluatorResult;
import com.dvarahq.oss.evals4j.result.Score;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

class EvalReportTest {

    private static void record(EvalReport report, String key, double score, String comment) {
        report.onFinish(
                null,
                key,
                List.of(new EvaluatorResult(key, Score.of(score), comment, java.util.Map.of(), null)));
    }

    @Test
    void collectsResultsAsATracerOnARealEvaluator() {
        EvalReport report = new EvalReport();
        Evaluator evaluator = Evaluator.from(
                "my_evaluator", "conciseness", request -> ScorerOutput.of(true, "short"), report);

        evaluator.evaluate(EvalRequest.ofOutputs("an answer"));

        assertThat(report.results()).hasSize(1);
        assertThat(report.results().get(0).key()).isEqualTo("conciseness");
    }

    @Test
    void averagesScoresPerKeyInFirstSeenOrder() {
        EvalReport report = new EvalReport();
        record(report, "conciseness", 1.0, null);
        record(report, "correctness", 0.0, null);
        record(report, "conciseness", 0.5, null);

        assertThat(report.meanScores())
                .containsExactly(
                        org.assertj.core.data.MapEntry.entry("conciseness", 0.75),
                        org.assertj.core.data.MapEntry.entry("correctness", 0.0));
    }

    @Test
    void writesAMarkdownTableAndTheIndividualResults(@TempDir Path directory) throws IOException {
        EvalReport report = new EvalReport();
        record(report, "conciseness", 1.0, "nice and short");
        record(report, "conciseness", 0.0, "rambled");

        Path destination = directory.resolve("nested/evals.md");
        report.writeMarkdown(destination);

        String markdown = Files.readString(destination);
        assertThat(markdown).contains("# Eval report");
        assertThat(markdown).contains("| conciseness | 0.500 | 2 |");
        assertThat(markdown).contains("nice and short");
        assertThat(markdown).contains("rambled");
    }

    @Test
    void flattensNewlinesInCommentsSoTheListStaysReadable(@TempDir Path directory) throws IOException {
        EvalReport report = new EvalReport();
        record(report, "correctness", 0.0, "first line\nsecond line");

        Path destination = directory.resolve("evals.md");
        report.writeMarkdown(destination);

        assertThat(Files.readString(destination)).contains("first line second line");
    }

    @Test
    void createsTheParentDirectory(@TempDir Path directory) {
        EvalReport report = new EvalReport();
        record(report, "correctness", 1.0, null);

        Path destination = directory.resolve("a/b/c/evals.md");
        report.writeMarkdown(destination);

        assertThat(destination).exists();
    }

    @Test
    void writesAnEmptyReportRatherThanFailing(@TempDir Path directory) throws IOException {
        Path destination = directory.resolve("evals.md");

        new EvalReport().writeMarkdown(destination);

        assertThat(Files.readString(destination)).contains("# Eval report");
    }

    @Test
    void writesAnEmptyJsonReportThatReadsBackAsAnEmptyBaseline(@TempDir Path directory) {
        Path destination = directory.resolve("evals.json");

        new EvalReport().writeJson(destination);

        assertThat(EvalReport.readJson(destination)).isEmpty();
    }

    @Test
    void jsonReportRoundTripPreservesKeySummaries(@TempDir Path directory) {
        EvalReport report = new EvalReport();
        record(report, "conciseness", 1.0, null);
        record(report, "conciseness", 0.5, null);
        record(report, "correctness", 0.25, null);

        Path destination = directory.resolve("nested/evals.json");
        report.writeJson(destination);

        assertThat(EvalReport.readJson(destination))
                .containsExactly(
                        org.assertj.core.data.MapEntry.entry(
                                "conciseness", new EvalReport.KeySummary(0.75, 2)),
                        org.assertj.core.data.MapEntry.entry(
                                "correctness", new EvalReport.KeySummary(0.25, 1)));
    }

    @Test
    void collectsResultsFromParallelEvaluations() throws Exception {
        // The class documents itself as thread-safe; a suite running tests in parallel relies on it.
        EvalReport report = new EvalReport();
        int threads = 8;
        int perThread = 50;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch startTogether = new CountDownLatch(1);

        try {
            for (int t = 0; t < threads; t++) {
                pool.submit(() -> {
                    startTogether.await();
                    for (int i = 0; i < perThread; i++) {
                        record(report, "conciseness", 1.0, null);
                    }
                    return null;
                });
            }
            startTogether.countDown();
            pool.shutdown();
            assertThat(pool.awaitTermination(30, TimeUnit.SECONDS)).isTrue();
        } finally {
            pool.shutdownNow();
        }

        assertThat(report.results()).hasSize(threads * perThread);
    }
}
