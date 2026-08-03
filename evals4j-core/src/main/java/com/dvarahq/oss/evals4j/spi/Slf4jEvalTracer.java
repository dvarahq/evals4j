package com.dvarahq.oss.evals4j.spi;

import com.dvarahq.oss.evals4j.result.EvaluatorResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/** Logs every evaluation at DEBUG, and failures at WARN. */
public final class Slf4jEvalTracer implements EvalTracer {

    private static final Logger log = LoggerFactory.getLogger("com.dvarahq.oss.evals4j");

    @Override
    public Object onStart(String runName, String feedbackKey) {
        log.debug("eval start: run={} key={}", runName, feedbackKey);
        return System.nanoTime();
    }

    @Override
    public void onFinish(Object token, String runName, List<EvaluatorResult> results) {
        if (!log.isDebugEnabled()) {
            return;
        }
        long micros = token instanceof Long start ? (System.nanoTime() - start) / 1000 : -1;
        for (EvaluatorResult result : results) {
            log.debug("eval done: run={} key={} score={} ({}us)", runName, result.key(), result.score(), micros);
        }
    }

    @Override
    public void onError(Object token, String runName, Throwable error) {
        log.warn("eval failed: run={}", runName, error);
    }
}
