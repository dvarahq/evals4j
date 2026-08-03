package com.dvarahq.oss.evals4j;

import com.dvarahq.oss.evals4j.result.EvaluatorResult;
import com.dvarahq.oss.evals4j.spi.EvalTracer;

import java.util.List;
import java.util.concurrent.CompletableFuture;

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
        List<EvaluatorResult> results = evaluateAll(request);
        if (results.isEmpty()) {
            throw new IllegalStateException("Evaluator returned no results");
        }
        return results.get(0);
    }

    /** Convenience for the common {@code (inputs, outputs)} case. */
    default EvaluatorResult evaluate(Object inputs, Object outputs) {
        return evaluate(EvalRequest.of(inputs, outputs));
    }

    /** Convenience for the common {@code (inputs, outputs, referenceOutputs)} case. */
    default EvaluatorResult evaluate(Object inputs, Object outputs, Object referenceOutputs) {
        return evaluate(EvalRequest.of(inputs, outputs, referenceOutputs));
    }

    default CompletableFuture<List<EvaluatorResult>> evaluateAllAsync(EvalRequest request) {
        return CompletableFuture.supplyAsync(() -> evaluateAll(request));
    }

    default CompletableFuture<EvaluatorResult> evaluateAsync(EvalRequest request) {
        return CompletableFuture.supplyAsync(() -> evaluate(request));
    }

    /** Wraps a scorer as an evaluator. The entry point for custom evaluators. */
    static Evaluator from(String runName, String feedbackKey, Scorer scorer) {
        return from(runName, feedbackKey, scorer, EvalTracer.NO_OP);
    }

    static Evaluator from(String runName, String feedbackKey, Scorer scorer, EvalTracer tracer) {
        return request -> ScorerRunner.run(runName, feedbackKey, scorer, request, tracer);
    }
}
