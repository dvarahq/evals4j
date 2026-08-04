package com.dvarahq.oss.evals4j.string;

import com.dvarahq.oss.evals4j.EvalRequest;
import com.dvarahq.oss.evals4j.Evaluator;
import com.dvarahq.oss.evals4j.ScorerOutput;
import com.dvarahq.oss.evals4j.ScorerRunner;
import com.dvarahq.oss.evals4j.internal.Json;
import com.dvarahq.oss.evals4j.result.EvaluatorResult;
import com.dvarahq.oss.evals4j.spi.EvalTracer;

import java.util.List;

/**
 * Scores string similarity as {@code 1 - distance / max(length)}.
 *
 * <p>Despite the name — kept for continuity with OpenEvals — the score is a <em>similarity</em> in
 * [0,1], where 1.0 means identical. Two empty strings score 1.0. Non-string values are JSON-encoded
 * first.
 */
public final class LevenshteinDistance implements Evaluator {

    public static final String FEEDBACK_KEY = "levenshtein_distance";

    // null, not NO_OP — see ExactMatch: an unconfigured tracer is what enables EvalScope fallback.
    private static final LevenshteinDistance INSTANCE = new LevenshteinDistance(null);

    private final EvalTracer tracer;

    private LevenshteinDistance(EvalTracer tracer) {
        // Null means "none configured", which lets ScorerRunner fall back to an open EvalScope.
        this.tracer = tracer;
    }

    public static LevenshteinDistance create() {
        return INSTANCE;
    }

    /** A copy that reports to {@code tracer}. */
    public LevenshteinDistance withTracer(EvalTracer tracer) {
        return new LevenshteinDistance(tracer);
    }

    @Override
    public List<EvaluatorResult> evaluateAll(EvalRequest request) {
        return ScorerRunner.run(
                FEEDBACK_KEY, FEEDBACK_KEY, LevenshteinDistance::score, request, tracer);
    }

    private static ScorerOutput score(EvalRequest request) {
        if (request.outputs() == null || request.referenceOutputs() == null) {
            throw new IllegalArgumentException(
                    "Levenshtein distance requires both outputs and reference_outputs");
        }
        String outputs = asString(request.outputs());
        String reference = asString(request.referenceOutputs());
        return ScorerOutput.of(similarity(outputs, reference));
    }

    private static String asString(Object value) {
        return value instanceof String string ? string : Json.write(value);
    }

    /** Normalized similarity in [0,1]. */
    public static double similarity(String a, String b) {
        int max = Math.max(a.length(), b.length());
        if (max == 0) {
            return 1.0;
        }
        return 1.0 - ((double) distance(a, b) / max);
    }

    /** Classic edit distance with unit insert, delete and substitute costs. */
    public static int distance(String a, String b) {
        int m = a.length();
        int n = b.length();
        // Two rows suffice; the full matrix is never inspected.
        int[] previous = new int[n + 1];
        int[] current = new int[n + 1];
        for (int j = 0; j <= n; j++) {
            previous[j] = j;
        }
        for (int i = 1; i <= m; i++) {
            current[0] = i;
            for (int j = 1; j <= n; j++) {
                int substitution = previous[j - 1] + (a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1);
                current[j] = Math.min(Math.min(previous[j] + 1, current[j - 1] + 1), substitution);
            }
            int[] swap = previous;
            previous = current;
            current = swap;
        }
        return previous[n];
    }
}
