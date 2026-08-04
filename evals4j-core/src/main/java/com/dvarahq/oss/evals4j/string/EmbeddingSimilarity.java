package com.dvarahq.oss.evals4j.string;

import com.dvarahq.oss.evals4j.EvalRequest;
import com.dvarahq.oss.evals4j.Evaluator;
import com.dvarahq.oss.evals4j.ScorerOutput;
import com.dvarahq.oss.evals4j.ScorerRunner;
import com.dvarahq.oss.evals4j.internal.Json;
import com.dvarahq.oss.evals4j.result.EvaluatorResult;
import com.dvarahq.oss.evals4j.spi.EmbeddingProvider;
import com.dvarahq.oss.evals4j.spi.EvalTracer;

import java.util.List;
import java.util.Objects;

/**
 * Scores semantic closeness between output and reference using embeddings.
 *
 * <p>Cosine similarity by default, or raw dot product. The score is rounded to two decimals, matching
 * OpenEvals — embedding similarity is not precise enough for the extra digits to mean anything.
 */
public final class EmbeddingSimilarity implements Evaluator {

    public static final String FEEDBACK_KEY = "embedding_similarity";

    /** How two embedding vectors are compared. */
    public enum Algorithm {
        COSINE,
        DOT_PRODUCT
    }

    private final EmbeddingProvider embeddings;
    private final Algorithm algorithm;
    private final EvalTracer tracer;

    private EmbeddingSimilarity(EmbeddingProvider embeddings, Algorithm algorithm, EvalTracer tracer) {
        this.embeddings = Objects.requireNonNull(embeddings, "embeddings");
        this.algorithm = Objects.requireNonNull(algorithm, "algorithm");
        // Null means "none configured", which lets ScorerRunner fall back to an open EvalScope.
        this.tracer = tracer;
    }

    public static EmbeddingSimilarity create(EmbeddingProvider embeddings) {
        return new EmbeddingSimilarity(embeddings, Algorithm.COSINE, null);
    }

    public static EmbeddingSimilarity create(EmbeddingProvider embeddings, Algorithm algorithm) {
        return new EmbeddingSimilarity(embeddings, algorithm, null);
    }

    public EmbeddingSimilarity withTracer(EvalTracer tracer) {
        return new EmbeddingSimilarity(embeddings, algorithm, tracer);
    }

    @Override
    public List<EvaluatorResult> evaluateAll(EvalRequest request) {
        return ScorerRunner.run(FEEDBACK_KEY, FEEDBACK_KEY, this::score, request, tracer);
    }

    private ScorerOutput score(EvalRequest request) {
        if (request.outputs() == null || request.referenceOutputs() == null) {
            throw new IllegalArgumentException(
                    "Embedding similarity requires both outputs and reference_outputs");
        }
        float[] received = embeddings.embed(asString(request.outputs()));
        float[] expected = embeddings.embed(asString(request.referenceOutputs()));
        double similarity = switch (algorithm) {
            case COSINE -> cosine(received, expected);
            case DOT_PRODUCT -> dot(received, expected);
        };
        return ScorerOutput.of(Math.round(similarity * 100.0) / 100.0);
    }

    private static String asString(Object value) {
        return value instanceof String string ? string : Json.write(value);
    }

    static double dot(float[] a, float[] b) {
        requireSameLength(a, b);
        double sum = 0.0;
        for (int i = 0; i < a.length; i++) {
            sum += (double) a[i] * b[i];
        }
        return sum;
    }

    static double cosine(float[] a, float[] b) {
        double magnitude = magnitude(a) * magnitude(b);
        return magnitude == 0.0 ? 0.0 : dot(a, b) / magnitude;
    }

    private static double magnitude(float[] vector) {
        double sum = 0.0;
        for (float value : vector) {
            sum += (double) value * value;
        }
        return Math.sqrt(sum);
    }

    private static void requireSameLength(float[] a, float[] b) {
        if (a.length != b.length) {
            throw new IllegalArgumentException(
                    "Embedding vectors have different lengths (" + a.length + " vs " + b.length
                            + "). Both sides must come from the same embedding model.");
        }
    }
}
