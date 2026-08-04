package com.dvarahq.oss.evals4j.junit5;

import com.dvarahq.oss.evals4j.internal.Json;
import com.dvarahq.oss.evals4j.result.EvaluatorResult;
import com.dvarahq.oss.evals4j.spi.EvalTracer;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
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

    /** Bumped only if the JSON shape changes incompatibly; a reader ignores what it does not know. */
    private static final int SCHEMA_VERSION = 1;

    /** Shown in the change column for a key the baseline has never seen. */
    private static final String NO_BASELINE = "—";

    private final List<EvaluatorResult> results = Collections.synchronizedList(new ArrayList<>());

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

    /** What a stored report remembers about one feedback key. */
    public record KeySummary(double mean, long count) {}

    /** Mean and result count per feedback key, in first-seen order. */
    public Map<String, KeySummary> summaries() {
        Map<String, Long> counts = new LinkedHashMap<>();
        results().forEach(result -> counts.merge(result.key(), 1L, Long::sum));

        Map<String, KeySummary> summaries = new LinkedHashMap<>();
        meanScores().forEach((key, mean) -> summaries.put(key, new KeySummary(mean, counts.get(key))));
        return Collections.unmodifiableMap(summaries);
    }

    /**
     * Writes the machine-readable form, which is what makes a trend possible: the next run reads this
     * back as its baseline. Markdown is for people and cannot be parsed back reliably.
     */
    public void writeJson(Path destination) {
        ObjectNode root = Json.object();
        root.put("schema", SCHEMA_VERSION);
        root.put("generatedAt", Instant.now().toString());
        ObjectNode keys = root.putObject("keys");
        summaries().forEach((key, summary) -> {
            ObjectNode entry = keys.putObject(key);
            entry.put("mean", summary.mean());
            entry.put("count", summary.count());
        });

        try {
            write(destination, Json.MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(root));
        } catch (IOException e) {
            throw new UncheckedIOException("Could not write the eval report to " + destination, e);
        }
    }

    /**
     * Reads a report written by {@link #writeJson}, for use as a baseline.
     *
     * <p>Absent, empty or unparseable input yields an empty map rather than throwing. A history file
     * is a convenience, and a corrupt one must never be the reason a test run fails — the run would
     * fail for a reason that has nothing to do with the code under evaluation.
     */
    public static Map<String, KeySummary> readJson(Path source) {
        if (source == null || !Files.isRegularFile(source)) {
            return Map.of();
        }
        try {
            JsonNode keys = Json.read(Files.readString(source, StandardCharsets.UTF_8)).get("keys");
            if (keys == null || !keys.isObject()) {
                return Map.of();
            }
            Map<String, KeySummary> previous = new LinkedHashMap<>();
            keys.fields().forEachRemaining(entry -> {
                JsonNode mean = entry.getValue().get("mean");
                JsonNode count = entry.getValue().get("count");
                if (mean != null && mean.isNumber()) {
                    previous.put(
                            entry.getKey(),
                            new KeySummary(mean.doubleValue(), count == null ? 0L : count.asLong()));
                }
            });
            return Collections.unmodifiableMap(previous);
        } catch (RuntimeException | IOException e) {
            return Map.of();
        }
    }

    /** Writes a Markdown summary: a mean per key, then every individual result. */
    public void writeMarkdown(Path destination) {
        writeMarkdown(destination, Map.of());
    }

    /**
     * Writes the Markdown summary with a change column against {@code previous}.
     *
     * <p>The delta is the point of keeping a report at all: one run's mean says little, and a mean
     * that slid from 0.9 to 0.7 over a month says a great deal. A key with no history shows
     * {@value #NO_BASELINE} rather than a fabricated zero.
     */
    public void writeMarkdown(Path destination, Map<String, KeySummary> previous) {
        Map<String, KeySummary> baseline = previous == null ? Map.of() : previous;
        StringBuilder markdown = new StringBuilder("# Eval report\n\n");

        markdown.append("| evaluator | mean score | n | change |\n|---|---|---|---|\n");
        summaries().forEach((key, summary) -> {
            KeySummary before = baseline.get(key);
            markdown.append("| ")
                    .append(key)
                    .append(" | ")
                    .append(String.format("%.3f", summary.mean()))
                    .append(" | ")
                    .append(summary.count())
                    .append(" | ")
                    .append(before == null
                            ? NO_BASELINE
                            : String.format("%+.3f", summary.mean() - before.mean()))
                    .append(" |\n");
        });

        markdown.append("\n## Results\n\n");
        for (EvaluatorResult result : results()) {
            markdown.append("- **").append(result.key()).append("**: ").append(result.score());
            if (result.comment() != null && !result.comment().isBlank()) {
                markdown.append(" — ").append(result.comment().replace("\n", " "));
            }
            markdown.append('\n');
        }

        try {
            write(destination, markdown.toString());
        } catch (IOException e) {
            throw new UncheckedIOException("Could not write the eval report to " + destination, e);
        }
    }

    private static void write(Path destination, String content) throws IOException {
        if (destination.getParent() != null) {
            Files.createDirectories(destination.getParent());
        }
        Files.writeString(destination, content, StandardCharsets.UTF_8);
    }
}
