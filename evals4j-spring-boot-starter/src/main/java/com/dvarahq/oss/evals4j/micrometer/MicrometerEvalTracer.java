package com.dvarahq.oss.evals4j.micrometer;

import com.dvarahq.oss.evals4j.result.EvaluatorResult;
import com.dvarahq.oss.evals4j.spi.EvalTracer;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;

import java.util.List;
import java.util.Objects;

/**
 * Records evaluation scores and timings as Micrometer meters.
 *
 * <p>What makes evals useful is the trend, not any single run — a conciseness score that drifts from
 * 0.9 to 0.6 over a fortnight is the signal, and that only shows up if the scores go somewhere that
 * keeps history. This puts them wherever the application's metrics already go.
 *
 * <p>Two meters are published:
 *
 * <ul>
 *   <li>{@value #SCORE_METER} — a distribution summary of the scores, tagged by evaluator and
 *       feedback key. Boolean scores record as 0 or 1, so the mean is the pass rate.
 *   <li>{@value #DURATION_METER} — how long evaluations take, tagged by evaluator and outcome. Judge
 *       calls are network calls, and a slow judge is worth seeing before it becomes a timeout.
 * </ul>
 *
 * <p>Tag cardinality is bounded by the number of evaluators and feedback keys in the application, so
 * it is safe to leave on in production.
 */
public class MicrometerEvalTracer implements EvalTracer {

    public static final String SCORE_METER = "evals4j.evaluation.score";
    public static final String DURATION_METER = "evals4j.evaluation.duration";

    private final MeterRegistry registry;

    public MicrometerEvalTracer(MeterRegistry registry) {
        this.registry = Objects.requireNonNull(registry, "registry");
    }

    @Override
    public Object onStart(String runName, String feedbackKey) {
        return Timer.start(registry);
    }

    @Override
    public void onFinish(Object token, String runName, List<EvaluatorResult> results) {
        recordDuration(token, runName, "success");
        for (EvaluatorResult result : results) {
            if (result.score() == null) {
                continue;
            }
            DistributionSummary.builder(SCORE_METER)
                    .description("Scores produced by evals4j evaluators")
                    .tag("evaluator", runName)
                    .tag("key", result.key())
                    .register(registry)
                    .record(result.score().doubleValue());
        }
    }

    @Override
    public void onError(Object token, String runName, Throwable error) {
        recordDuration(token, runName, "error");
    }

    private void recordDuration(Object token, String runName, String outcome) {
        // The token is whatever onStart returned, and a caller may pass results around between
        // tracers; anything else is simply not a sample this tracer started.
        if (token instanceof Timer.Sample sample) {
            sample.stop(Timer.builder(DURATION_METER)
                    .description("Time taken by evals4j evaluations")
                    .tag("evaluator", runName)
                    .tag("outcome", outcome)
                    .register(registry));
        }
    }
}
