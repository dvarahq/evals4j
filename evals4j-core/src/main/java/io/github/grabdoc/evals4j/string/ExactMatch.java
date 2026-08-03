package io.github.grabdoc.evals4j.string;

import io.github.grabdoc.evals4j.EvalRequest;
import io.github.grabdoc.evals4j.Evaluator;
import io.github.grabdoc.evals4j.ScorerOutput;
import io.github.grabdoc.evals4j.internal.Json;
import io.github.grabdoc.evals4j.result.EvaluatorResult;

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

    private static final ExactMatch INSTANCE = new ExactMatch();

    private ExactMatch() {}

    public static ExactMatch create() {
        return INSTANCE;
    }

    @Override
    public List<EvaluatorResult> evaluateAll(EvalRequest request) {
        return io.github.grabdoc.evals4j.ScorerRunner.run(
                FEEDBACK_KEY, FEEDBACK_KEY, ExactMatch::score, request, null);
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
