package com.dvarahq.oss.evals4j;

import com.dvarahq.oss.evals4j.result.EvaluatorResult;
import com.dvarahq.oss.evals4j.spi.EvalTracer;

import java.util.ArrayList;
import java.util.List;

/**
 * Runs a {@link Scorer} and turns its output into {@link EvaluatorResult}s, firing tracer callbacks
 * around the call.
 *
 * <p>Port of OpenEvals' {@code _run_evaluator}. Every evaluator in the library funnels through here,
 * which is what makes their result shapes consistent — a single-value scorer is recorded under the
 * evaluator's feedback key, and a keyed scorer's map keys become the feedback keys.
 */
public final class ScorerRunner {

    private ScorerRunner() {}

    public static List<EvaluatorResult> run(
            String runName, String feedbackKey, Scorer scorer, EvalRequest request, EvalTracer tracer) {

        EvalTracer effectiveTracer = tracer == null ? EvalTracer.NO_OP : tracer;
        Object token = effectiveTracer.onStart(runName, feedbackKey);
        try {
            List<EvaluatorResult> results = toResults(feedbackKey, scorer.score(request));
            effectiveTracer.onFinish(token, runName, results);
            return results;
        } catch (RuntimeException e) {
            effectiveTracer.onError(token, runName, e);
            throw e;
        }
    }

    /** Exposed so evaluators that compute a {@link ScorerOutput} out-of-band can reuse the shaping. */
    public static List<EvaluatorResult> toResults(String feedbackKey, ScorerOutput output) {
        if (output instanceof ScorerOutput.Single single) {
            return List.of(toResult(feedbackKey, single));
        }
        ScorerOutput.Keyed keyed = (ScorerOutput.Keyed) output;
        List<EvaluatorResult> results = new ArrayList<>();
        keyed.byKey().forEach((key, singles) -> {
            for (ScorerOutput.Single single : singles) {
                results.add(toResult(key, single));
            }
        });
        return List.copyOf(results);
    }

    private static EvaluatorResult toResult(String key, ScorerOutput.Single single) {
        return new EvaluatorResult(
                key, single.score(), single.comment(), single.metadata(), single.sourceRunId());
    }
}
