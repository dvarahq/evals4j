package com.dvarahq.oss.evals4j;

/**
 * The scoring logic of an evaluator, without the result-shaping and tracing that
 * {@link ScorerRunner} adds around it.
 *
 * <p>Implement this to write a custom evaluator; wrap it with
 * {@link Evaluator#from(String, String, Scorer)} to get an {@link Evaluator}.
 */
@FunctionalInterface
public interface Scorer {

    ScorerOutput score(EvalRequest request);
}
