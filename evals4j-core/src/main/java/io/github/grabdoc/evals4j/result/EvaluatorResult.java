package io.github.grabdoc.evals4j.result;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * The outcome of one evaluation, under one feedback key.
 *
 * <p>Mirrors OpenEvals' {@code EvaluatorResult}. {@code comment} carries the judge's reasoning (the
 * Python field is named {@code reasoning} at the scorer boundary and renamed on the way out).
 *
 * @param key the feedback key this score is recorded under
 * @param score the score
 * @param comment reasoning or diagnostics; may be {@code null}
 * @param metadata extra structured detail; never {@code null}, possibly empty
 * @param sourceRunId id of the judge invocation that produced this score; may be {@code null}
 */
public record EvaluatorResult(
        String key, Score score, String comment, Map<String, Object> metadata, String sourceRunId) {

    public EvaluatorResult {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(score, "score");
        metadata = metadata == null
                ? Collections.emptyMap()
                : Collections.unmodifiableMap(new LinkedHashMap<>(metadata));
    }

    public static EvaluatorResult of(String key, Score score) {
        return new EvaluatorResult(key, score, null, null, null);
    }

    public static EvaluatorResult of(String key, Score score, String comment) {
        return new EvaluatorResult(key, score, comment, null, null);
    }

    /** Convenience for the common assertion {@code result.passed()} on a boolean-scored evaluator. */
    public boolean passed() {
        return score.doubleValue() >= 1.0;
    }

    public EvaluatorResult withSourceRunId(String runId) {
        return new EvaluatorResult(key, score, comment, metadata, runId);
    }

    public EvaluatorResult withMetadata(Map<String, Object> extra) {
        if (extra == null || extra.isEmpty()) {
            return this;
        }
        Map<String, Object> merged = new LinkedHashMap<>(metadata);
        merged.putAll(extra);
        return new EvaluatorResult(key, score, comment, merged, sourceRunId);
    }
}
