package com.dvarahq.oss.evals4j.spi;

import com.dvarahq.oss.evals4j.result.EvaluatorResult;

import java.util.List;

/**
 * Observability hook fired around every evaluation.
 *
 * <p>This is the seam where OpenEvals writes LangSmith run-tree metadata. There is no LangSmith Java
 * SDK, so evals4j ships an slf4j implementation and (via the Spring Boot starter) a Micrometer one,
 * and leaves this interface open for anything else.
 */
public interface EvalTracer {

    EvalTracer NO_OP = new EvalTracer() {};

    /**
     * Called before the scorer runs.
     *
     * @param runName the evaluator's run name, e.g. {@code llm_as_conciseness_judge}
     * @return a token passed back to {@link #onFinish} and {@link #onError}; may be {@code null}
     */
    default Object onStart(String runName, String feedbackKey) {
        return null;
    }

    default void onFinish(Object token, String runName, List<EvaluatorResult> results) {}

    default void onError(Object token, String runName, Throwable error) {}
}
