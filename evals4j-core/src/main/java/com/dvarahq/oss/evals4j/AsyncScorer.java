package com.dvarahq.oss.evals4j;

import java.util.concurrent.CompletableFuture;

/**
 * The asynchronous counterpart of {@link Scorer}, for evaluators whose work is a network call rather
 * than computation.
 *
 * <p>Implementations should return a future that is already in flight, not one that will start doing
 * work when something waits on it — {@link ScorerRunner#runAsync} attaches the tracer callbacks to
 * the future it is given and does not schedule it.
 */
@FunctionalInterface
public interface AsyncScorer {

    CompletableFuture<ScorerOutput> score(EvalRequest request);
}
