package com.dvarahq.oss.evals4j.string;

import com.dvarahq.oss.evals4j.EvalRequest;
import com.dvarahq.oss.evals4j.result.EvaluatorResult;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

class StringEvaluatorsTest {

    // ---- exact match ------------------------------------------------------------------------

    @Test
    void exactMatchIgnoresMapKeyOrder() {
        Map<String, Object> outputs = new LinkedHashMap<>();
        outputs.put("a", 1);
        outputs.put("b", 2);
        Map<String, Object> reference = new LinkedHashMap<>();
        reference.put("b", 2);
        reference.put("a", 1);

        EvaluatorResult result = ExactMatch.create().evaluate(EvalRequest.of(null, outputs, reference));
        assertThat(result.key()).isEqualTo("exact_match");
        assertThat(result.passed()).isTrue();
    }

    @Test
    void exactMatchRespectsListOrder() {
        assertThat(ExactMatch.create()
                        .evaluate(EvalRequest.of(null, List.of(1, 2), List.of(2, 1)))
                        .passed())
                .isFalse();
    }

    @Test
    void exactMatchNeedsBothSides() {
        assertThatThrownBy(() -> ExactMatch.create().evaluate(EvalRequest.of(null, "a", null)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("both outputs and reference_outputs");
    }

    // ---- levenshtein ------------------------------------------------------------------------

    @Test
    void levenshteinScoresIdenticalStringsAsOne() {
        assertThat(LevenshteinDistance.create()
                        .evaluate(EvalRequest.of(null, "hello", "hello"))
                        .score()
                        .doubleValue())
                .isEqualTo(1.0);
    }

    @Test
    void levenshteinNormalizesByTheLongerString() {
        // "kitten" -> "sitting" is 3 edits over a length of 7.
        EvaluatorResult result =
                LevenshteinDistance.create().evaluate(EvalRequest.of(null, "kitten", "sitting"));
        assertThat(result.key()).isEqualTo("levenshtein_distance");
        assertThat(result.score().doubleValue()).isEqualTo(1.0 - 3.0 / 7.0, within(1e-9));
    }

    @Test
    void levenshteinScoresTwoEmptyStringsAsOne() {
        assertThat(LevenshteinDistance.similarity("", "")).isEqualTo(1.0);
    }

    @Test
    void levenshteinComputesKnownDistances() {
        assertThat(LevenshteinDistance.distance("kitten", "sitting")).isEqualTo(3);
        assertThat(LevenshteinDistance.distance("", "abc")).isEqualTo(3);
        assertThat(LevenshteinDistance.distance("flaw", "lawn")).isEqualTo(2);
    }

    // ---- embedding similarity ---------------------------------------------------------------

    @Test
    void cosineSimilarityOfIdenticalVectorsIsOne() {
        EvaluatorResult result = EmbeddingSimilarity.create(text -> new float[] {1f, 2f, 3f})
                .evaluate(EvalRequest.of(null, "a", "b"));

        assertThat(result.key()).isEqualTo("embedding_similarity");
        assertThat(result.score().doubleValue()).isEqualTo(1.0);
    }

    @Test
    void cosineSimilarityOfOrthogonalVectorsIsZero() {
        EmbeddingSimilarity evaluator =
                EmbeddingSimilarity.create(text -> text.equals("a") ? new float[] {1f, 0f} : new float[] {0f, 1f});

        assertThat(evaluator.evaluate(EvalRequest.of(null, "a", "b")).score().doubleValue()).isEqualTo(0.0);
    }

    @Test
    void roundsToTwoDecimals() {
        EmbeddingSimilarity evaluator = EmbeddingSimilarity.create(
                text -> text.equals("a") ? new float[] {1f, 0f} : new float[] {1f, 1f});

        // cos = 1/sqrt(2) = 0.7071... -> 0.71
        assertThat(evaluator.evaluate(EvalRequest.of(null, "a", "b")).score().doubleValue()).isEqualTo(0.71);
    }

    @Test
    void supportsDotProduct() {
        EmbeddingSimilarity evaluator = EmbeddingSimilarity.create(
                text -> text.equals("a") ? new float[] {1f, 2f} : new float[] {3f, 4f},
                EmbeddingSimilarity.Algorithm.DOT_PRODUCT);

        assertThat(evaluator.evaluate(EvalRequest.of(null, "a", "b")).score().doubleValue()).isEqualTo(11.0);
    }

    @Test
    void rejectsMismatchedVectorLengths() {
        EmbeddingSimilarity evaluator = EmbeddingSimilarity.create(
                text -> text.equals("a") ? new float[] {1f} : new float[] {1f, 2f});

        assertThatThrownBy(() -> evaluator.evaluate(EvalRequest.of(null, "a", "b")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("same embedding model");
    }
}
