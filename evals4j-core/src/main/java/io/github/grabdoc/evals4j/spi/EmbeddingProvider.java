package io.github.grabdoc.evals4j.spi;

/** Produces an embedding vector for text. Required only by the embedding-similarity evaluator. */
@FunctionalInterface
public interface EmbeddingProvider {

    float[] embed(String text);
}
