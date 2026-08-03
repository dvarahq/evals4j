package com.dvarahq.oss.evals4j;

import com.dvarahq.oss.evals4j.result.Score;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * What a scorer hands back before it is turned into {@link com.dvarahq.oss.evals4j.result.EvaluatorResult}s.
 *
 * <p>OpenEvals lets a scorer return a bare value, a {@code (score, reasoning)} tuple, a
 * {@code (score, reasoning, metadata)} triple, or a dict of key to any of those — and
 * {@code _run_evaluator} normalizes all four. This type makes those shapes explicit instead of
 * inspecting tuples at runtime; {@link ScorerRunner} performs the normalization.
 */
public sealed interface ScorerOutput {

    /** One score, recorded under the evaluator's own feedback key. */
    record Single(Score score, String comment, Map<String, Object> metadata, String sourceRunId)
            implements ScorerOutput {

        public Single {
            Objects.requireNonNull(score, "score");
            metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
        }

        public Single withSourceRunId(String runId) {
            return new Single(score, comment, metadata, runId);
        }
    }

    /**
     * Several scores, each under its own key. The map keys become the feedback keys and the
     * evaluator's own {@code feedbackKey} is not used — matching OpenEvals.
     */
    record Keyed(Map<String, List<Single>> byKey) implements ScorerOutput {

        public Keyed {
            Objects.requireNonNull(byKey, "byKey");
            Map<String, List<Single>> copy = new LinkedHashMap<>();
            byKey.forEach((key, values) -> copy.put(key, List.copyOf(values)));
            byKey = Map.copyOf(copy);
        }
    }

    static Single of(boolean score) {
        return new Single(Score.of(score), null, null, null);
    }

    static Single of(double score) {
        return new Single(Score.of(score), null, null, null);
    }

    static Single of(Score score) {
        return new Single(score, null, null, null);
    }

    static Single of(boolean score, String comment) {
        return new Single(Score.of(score), comment, null, null);
    }

    static Single of(double score, String comment) {
        return new Single(Score.of(score), comment, null, null);
    }

    static Single of(Score score, String comment) {
        return new Single(score, comment, null, null);
    }

    static Single of(Score score, String comment, Map<String, Object> metadata) {
        return new Single(score, comment, metadata, null);
    }

    /** Builds a multi-key output from single values, preserving iteration order. */
    static Keyed keyed(Map<String, Single> singles) {
        Map<String, List<Single>> byKey = new LinkedHashMap<>();
        singles.forEach((key, value) -> byKey.put(key, List.of(value)));
        return new Keyed(byKey);
    }
}
