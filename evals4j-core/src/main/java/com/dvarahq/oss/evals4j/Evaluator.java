package com.dvarahq.oss.evals4j;

import com.dvarahq.oss.evals4j.result.EvaluatorResult;
import com.dvarahq.oss.evals4j.spi.EvalTracer;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

/**
 * Scores a system's output.
 *
 * <p>Most evaluators produce exactly one result and callers should use {@link #evaluate}; the
 * JSON-match evaluator produces one per key, hence {@link #evaluateAll}.
 */
public interface Evaluator {

    List<EvaluatorResult> evaluateAll(EvalRequest request);

    /**
     * The single result of this evaluation.
     *
     * @throws IllegalStateException if the evaluator produced no results
     */
    default EvaluatorResult evaluate(EvalRequest request) {
        return first(evaluateAll(request));
    }

    /** Convenience for the common {@code (inputs, outputs)} case. */
    default EvaluatorResult evaluate(Object inputs, Object outputs) {
        return evaluate(EvalRequest.of(inputs, outputs));
    }

    /** Convenience for the common {@code (inputs, outputs, referenceOutputs)} case. */
    default EvaluatorResult evaluate(Object inputs, Object outputs, Object referenceOutputs) {
        return evaluate(EvalRequest.of(inputs, outputs, referenceOutputs));
    }

    /**
     * Evaluates without blocking the calling thread.
     *
     * <p>Model-backed evaluators override this to compose over
     * {@link com.dvarahq.oss.evals4j.spi.JudgeModel#invokeStructuredAsync} instead of parking a
     * blocking call on a thread. For evaluators that have no asynchronous path — and for a judge
     * whose adapter has not overridden {@code invokeStructuredAsync} — the work still runs on the
     * common pool, which is sized for computation rather than for waiting on a network. Prefer
     * {@link #evaluateAllAsync(EvalRequest, Executor)} when running a suite.
     */
    default CompletableFuture<List<EvaluatorResult>> evaluateAllAsync(EvalRequest request) {
        // capturing() carries an open EvalScope onto the pool thread; without it an ambient tracer
        // would be lost at the hand-off and the evaluation would report nowhere.
        return CompletableFuture.supplyAsync(EvalScope.capturing(() -> evaluateAll(request)));
    }

    /** Runs the blocking evaluation on {@code executor}, for a pool sized for IO. */
    default CompletableFuture<List<EvaluatorResult>> evaluateAllAsync(
            EvalRequest request, Executor executor) {
        return CompletableFuture.supplyAsync(EvalScope.capturing(() -> evaluateAll(request)), executor);
    }

    default CompletableFuture<EvaluatorResult> evaluateAsync(EvalRequest request) {
        return evaluateAllAsync(request).thenApply(Evaluator::first);
    }

    default CompletableFuture<EvaluatorResult> evaluateAsync(EvalRequest request, Executor executor) {
        return evaluateAllAsync(request, executor).thenApply(Evaluator::first);
    }

    private static EvaluatorResult first(List<EvaluatorResult> results) {
        if (results.isEmpty()) {
            throw new IllegalStateException("Evaluator returned no results");
        }
        return results.get(0);
    }

    /** Wraps a scorer as an evaluator. The entry point for custom evaluators. */
    static Evaluator from(String runName, String feedbackKey, Scorer scorer) {
        // null, not NO_OP — see ExactMatch. This overload is how custom evaluators are built, so
        // collapsing to NO_OP here would opt every one of them out of EvalScope fallback.
        return from(runName, feedbackKey, scorer, null);
    }

    static Evaluator from(String runName, String feedbackKey, Scorer scorer, EvalTracer tracer) {
        return request -> ScorerRunner.run(runName, feedbackKey, scorer, request, tracer);
    }
}
