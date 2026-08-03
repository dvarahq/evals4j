package io.github.grabdoc.evals4j.springai;

import io.github.grabdoc.evals4j.spi.EmbeddingProvider;
import org.springframework.ai.embedding.EmbeddingModel;

import java.util.Objects;

/** Backs the embedding-similarity evaluator with a Spring AI {@link EmbeddingModel}. */
public final class SpringAiEmbeddingProvider implements EmbeddingProvider {

    private final EmbeddingModel model;

    private SpringAiEmbeddingProvider(EmbeddingModel model) {
        this.model = Objects.requireNonNull(model, "model");
    }

    public static SpringAiEmbeddingProvider of(EmbeddingModel model) {
        return new SpringAiEmbeddingProvider(model);
    }

    @Override
    public float[] embed(String text) {
        return model.embed(text);
    }
}
