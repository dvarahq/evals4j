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
 * Scores true when the output equals the reference.
 *
 * <p>Comparison is on canonical JSON with map keys sorted, so {@code {"a":1,"b":2}} matches
 * {@code {"b":2,"a":1}} — but list order and value types still matter. Port of OpenEvals'
 * {@code exact_match}.
 */
public final class ExactMatch implements Evaluator {

    public static final String FEEDBACK_KEY = "exact_match";

    // null, not NO_OP: the shared instance has no tracer configured, which is what lets it report to
    // an open EvalScope. NO_OP here would read as a deliberate silence and opt out of that.
    private static final ExactMatch INSTANCE = new ExactMatch(null);

    private final EvalTracer tracer;

    private ExactMatch(EvalTracer tracer) {
        // Kept null rather than collapsed to NO_OP: ScorerRunner reads null as "none configured" and
        // falls back to any open EvalScope. Substituting NO_OP here would silently opt out of that.
        this.tracer = tracer;
    }

    public static ExactMatch create() {
        return INSTANCE;
    }

    /**
     * A copy that reports to {@code tracer}.
     *
     * <p>A wither rather than a builder because the shared instance is stateless and worth keeping:
     * this is the evaluator most likely to be called in a tight loop.
     */
    public ExactMatch withTracer(EvalTracer tracer) {
        return new ExactMatch(tracer);
    }

    @Override
    public List<EvaluatorResult> evaluateAll(EvalRequest request) {
        return ScorerRunner.run(FEEDBACK_KEY, FEEDBACK_KEY, ExactMatch::score, request, tracer);
    }

    private static ScorerOutput score(EvalRequest request) {
        if (request.outputs() == null || request.referenceOutputs() == null) {
            throw new IllegalArgumentException(
                    "Exact match requires both outputs and reference_outputs");
        }
        String outputs = Json.writeCanonical(request.outputs());
        String reference = Json.writeCanonical(request.referenceOutputs());
        return ScorerOutput.of(outputs.equals(reference));
    }
}
