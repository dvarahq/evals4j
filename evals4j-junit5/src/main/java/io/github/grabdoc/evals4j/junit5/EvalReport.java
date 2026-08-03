package io.github.grabdoc.evals4j.junit5;

import io.github.grabdoc.evals4j.result.EvaluatorResult;
import io.github.grabdoc.evals4j.spi.EvalTracer;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Collects eval results across a run and writes a summary.
 *
 * <p>An eval suite's value is in the trend, not in a single pass or fail — a judge score that drifts
 * from 0.9 to 0.7 over a month matters, and a green/red test result cannot show that. Register this
 * as an {@link EvalTracer} and write the report at the end of the run.
 *
 * <pre>{@code
 * EvalReport report = new EvalReport();
 * LlmAsJudge judge = LlmAsJudge.builder().prompt(...).model(...).tracer(report).build();
 * // ... run evaluations ...
 * report.writeMarkdown(Path.of("target/evals.md"));
 * }</pre>
 *
 * <p>Thread-safe: evaluations may run in parallel.
 */
public final class EvalReport implements EvalTracer {

    private final List<EvaluatorResult> results = java.util.Collections.synchronizedList(new ArrayList<>());

    @Override
    public void onFinish(Object token, String runName, List<EvaluatorResult> batch) {
        results.addAll(batch);
    }

    public List<EvaluatorResult> results() {
        synchronized (results) {
            return List.copyOf(results);
        }
    }

    /** Mean score per feedback key, in first-seen order. */
    public Map<String, Double> meanScores() {
        Map<String, List<Double>> byKey = new LinkedHashMap<>();
        for (EvaluatorResult result : results()) {
            byKey.computeIfAbsent(result.key(), key -> new ArrayList<>())
                    .add(result.score().doubleValue());
        }
        Map<String, Double> means = new LinkedHashMap<>();
        byKey.forEach((key, scores) ->
                means.put(key, scores.stream().mapToDouble(Double::doubleValue).average().orElse(0.0)));
        return means;
    }

    /** Writes a Markdown summary: a mean per key, then every individual result. */
    public void writeMarkdown(Path destination) {
        StringBuilder markdown = new StringBuilder("# Eval report\n\n");

        markdown.append("| evaluator | mean score | n |\n|---|---|---|\n");
        Map<String, Double> means = meanScores();
        Map<String, Long> counts = new LinkedHashMap<>();
        results().forEach(result -> counts.merge(result.key(), 1L, Long::sum));
        means.forEach((key, mean) -> markdown.append("| ")
                .append(key)
                .append(" | ")
                .append(String.format("%.3f", mean))
                .append(" | ")
                .append(counts.get(key))
                .append(" |\n"));

        markdown.append("\n## Results\n\n");
        for (EvaluatorResult result : results()) {
            markdown.append("- **").append(result.key()).append("**: ").append(result.score());
            if (result.comment() != null && !result.comment().isBlank()) {
                markdown.append(" — ").append(result.comment().replace("\n", " "));
            }
            markdown.append('\n');
        }

        try {
            if (destination.getParent() != null) {
                Files.createDirectories(destination.getParent());
            }
            Files.writeString(destination, markdown.toString(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("Could not write the eval report to " + destination, e);
        }
    }
}
