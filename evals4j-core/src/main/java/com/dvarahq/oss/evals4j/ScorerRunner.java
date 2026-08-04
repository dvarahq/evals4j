package com.dvarahq.oss.evals4j;

import com.dvarahq.oss.evals4j.result.EvaluatorResult;
import com.dvarahq.oss.evals4j.spi.EvalTracer;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

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

    /**
     * Picks the tracer for a run: the one the evaluator was configured with, else whatever
     * {@link EvalScope} has open, else nothing.
     *
     * <p>Configured always beats ambient, so opening a scope cannot redirect an evaluator that was
     * told where to report. An evaluator handed {@link EvalTracer#NO_OP} on purpose stays quiet.
     */
    private static EvalTracer resolve(EvalTracer tracer) {
        if (tracer != null) {
            return tracer;
        }
        EvalTracer ambient = EvalScope.current();
        return ambient == null ? EvalTracer.NO_OP : ambient;
    }

    public static List<EvaluatorResult> run(
            String runName, String feedbackKey, Scorer scorer, EvalRequest request, EvalTracer tracer) {

        EvalTracer effectiveTracer = resolve(tracer);
        Object token = effectiveTracer.onStart(runName, feedbackKey);
        try {
            List<EvaluatorResult> results = stampRunId(toResults(feedbackKey, scorer.score(request)), token);
            effectiveTracer.onFinish(token, runName, results);
            return results;
        } catch (RuntimeException e) {
            effectiveTracer.onError(token, runName, e);
            throw e;
        }
    }

    /**
     * Records the tracer's run id on each result, when it supplied one.
     *
     * <p>Only a {@link CharSequence} token counts as a run id — a tracer is free to return anything
     * from {@code onStart}, and the Micrometer one returns a timing sample, which is not an
     * identifier. A scorer that set its own id keeps it.
     */
    private static List<EvaluatorResult> stampRunId(List<EvaluatorResult> results, Object token) {
        if (!(token instanceof CharSequence runId)) {
            return results;
        }
        List<EvaluatorResult> stamped = new ArrayList<>(results.size());
        for (EvaluatorResult result : results) {
            stamped.add(result.sourceRunId() == null
                    ? result.withSourceRunId(runId.toString())
                    : result);
        }
        return List.copyOf(stamped);
    }

    /**
     * The asynchronous twin of {@link #run}, firing the same tracer callbacks around the future.
     *
     * <p>{@code onStart} fires before the scorer is invoked and {@code onFinish}/{@code onError} when
     * the future settles, so an evaluation that never blocks a thread is still traced end to end.
     *
     * <p>A scorer that throws synchronously — rather than returning a failed future — is reported
     * through {@code onError} too. Callers should not have to care which way a scorer fails.
     */
    public static CompletableFuture<List<EvaluatorResult>> runAsync(
            String runName,
            String feedbackKey,
            AsyncScorer scorer,
            EvalRequest request,
            EvalTracer tracer) {

        EvalTracer effectiveTracer = resolve(tracer);
        Object token = effectiveTracer.onStart(runName, feedbackKey);

        CompletableFuture<ScorerOutput> scored;
        try {
            scored = scorer.score(request);
        } catch (RuntimeException e) {
            effectiveTracer.onError(token, runName, e);
            throw e;
        }

        return scored.handle((output, error) -> {
            if (error != null) {
                Throwable cause = error instanceof CompletionException ? error.getCause() : error;
                effectiveTracer.onError(token, runName, cause);
                throw cause instanceof RuntimeException runtime
                        ? runtime
                        : new CompletionException(cause);
            }
            List<EvaluatorResult> results = stampRunId(toResults(feedbackKey, output), token);
            effectiveTracer.onFinish(token, runName, results);
            return results;
        });
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
