package com.dvarahq.oss.evals4j.langchain4j;

import dev.langchain4j.model.embedding.EmbeddingModel;
import com.dvarahq.oss.evals4j.spi.EmbeddingProvider;

import java.util.Objects;

/** Backs the embedding-similarity evaluator with a LangChain4j {@link EmbeddingModel}. */
public final class LangChain4jEmbeddingProvider implements EmbeddingProvider {

    private final EmbeddingModel model;

    private LangChain4jEmbeddingProvider(EmbeddingModel model) {
        this.model = Objects.requireNonNull(model, "model");
    }

    public static LangChain4jEmbeddingProvider of(EmbeddingModel model) {
        return new LangChain4jEmbeddingProvider(model);
    }

    @Override
    public float[] embed(String text) {
        return model.embed(text).content().vector();
    }
}
