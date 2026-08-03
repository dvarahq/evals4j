package com.dvarahq.oss.evals4j.spi;

import com.dvarahq.oss.evals4j.result.EvaluatorResult;

import java.util.List;

/**
 * Observability hook fired around every evaluation.
 *
 * <p>This is the seam where OpenEvals writes LangSmith run-tree metadata. There is no LangSmith Java
 * SDK, so evals4j ships {@link Slf4jEvalTracer} here, a Micrometer implementation in the Spring Boot
 * starter, and {@code EvalReport} in {@code evals4j-junit5} — and leaves this interface open for
 * anything else.
 *
 * <p>Both {@link #onFinish} and {@link #onError} are called for asynchronous evaluations too, when
 * the future settles rather than when it is created.
 */
public interface EvalTracer {

    EvalTracer NO_OP = new EvalTracer() {};

    /**
     * Called before the scorer runs.
     *
     * @param runName the evaluator's run name, e.g. {@code llm_as_conciseness_judge}
     * @return a token passed back to {@link #onFinish} and {@link #onError}; may be {@code null}.
     *     Return a {@link CharSequence} to have it recorded as each result's
     *     {@code sourceRunId} — which is how a tracer that owns a run id ties scores back to it.
     */
    default Object onStart(String runName, String feedbackKey) {
        return null;
    }

    default void onFinish(Object token, String runName, List<EvaluatorResult> results) {}

    default void onError(Object token, String runName, Throwable error) {}
}
