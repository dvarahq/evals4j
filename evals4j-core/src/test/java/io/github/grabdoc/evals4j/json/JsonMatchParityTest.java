package io.github.grabdoc.evals4j.json;

import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.grabdoc.evals4j.EvalRequest;
import io.github.grabdoc.evals4j.internal.Json;
import io.github.grabdoc.evals4j.result.EvaluatorResult;
import io.github.grabdoc.evals4j.testing.FakeJudgeModel;
import org.junit.jupiter.api.Test;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

/**
 * JSON matching, checked against OpenEvals' own test fixtures in
 * {@code openevals/python/tests/test_json.py} — same inputs, same expected keys and scores.
 */
class JsonMatchParityTest {

    private static Map<String, Object> map(Object... keyValues) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (int i = 0; i < keyValues.length; i += 2) {
            map.put((String) keyValues[i], keyValues[i + 1]);
        }
        return map;
    }

    private static List<EvaluatorResult> sorted(List<EvaluatorResult> results) {
        return results.stream().sorted(Comparator.comparing(EvaluatorResult::key)).toList();
    }

    private static List<EvaluatorResult> run(JsonMatchEvaluator evaluator, Object outputs, Object reference) {
        return sorted(evaluator.evaluateAll(EvalRequest.of(null, outputs, reference)));
    }

    // ---- scalar objects ---------------------------------------------------------------------

    @Test
    void scoresEachKeySeparatelyByDefault() {
        List<EvaluatorResult> results =
                run(JsonMatchEvaluator.builder().build(), map("a", 1, "b", 2), map("a", 1, "b", 2));

        assertThat(results).hasSize(2);
        assertThat(results.get(0).key()).isEqualTo("json_match:a");
        assertThat(results.get(0).score().doubleValue()).isEqualTo(1.0);
        assertThat(results.get(1).key()).isEqualTo("json_match:b");
        assertThat(results.get(1).score().doubleValue()).isEqualTo(1.0);
    }

    @Test
    void averagesAcrossKeys() {
        List<EvaluatorResult> results = run(
                JsonMatchEvaluator.builder().aggregator(JsonMatchEvaluator.Aggregator.AVERAGE).build(),
                map("a", 1, "b", 2),
                map("a", 1, "b", 3));

        assertThat(results.get(0).key()).isEqualTo("json_match:average");
        assertThat(results.get(0).score().doubleValue()).isEqualTo(0.5);
    }

    @Test
    void excludedKeysDoNotCount() {
        List<EvaluatorResult> results = run(
                JsonMatchEvaluator.builder()
                        .aggregator(JsonMatchEvaluator.Aggregator.AVERAGE)
                        .excludeKeys("b")
                        .build(),
                map("a", 1, "b", 2),
                map("a", 1, "b", 3));

        assertThat(results.get(0).key()).isEqualTo("json_match:average");
        assertThat(results.get(0).score().doubleValue()).isEqualTo(1.0);
    }

    @Test
    void allRequiresEveryKeyToMatch() {
        List<EvaluatorResult> results = run(
                JsonMatchEvaluator.builder().aggregator(JsonMatchEvaluator.Aggregator.ALL).build(),
                map("a", 1, "b", 2),
                map("a", 1, "b", 3));

        assertThat(results.get(0).key()).isEqualTo("json_match:all");
        assertThat(results.get(0).score().doubleValue()).isEqualTo(0.0);
    }

    @Test
    void referenceOnlyKeysScoreZero() {
        List<EvaluatorResult> results =
                run(JsonMatchEvaluator.builder().build(), map("a", 1), map("a", 1, "b", 2));

        assertThat(results).hasSize(2);
        assertThat(results.get(1).key()).isEqualTo("json_match:b");
        assertThat(results.get(1).score().doubleValue()).isEqualTo(0.0);
    }

    // ---- lists ------------------------------------------------------------------------------

    @Test
    void listAllNone() {
        List<EvaluatorResult> results = run(
                JsonMatchEvaluator.builder().build(),
                List.of(map("a", 1, "b", 2), map("a", 1, "b", 2)),
                List.of(map("a", 1, "b", 2), map("a", 1, "b", 2)));

        assertThat(results).hasSize(2);
        assertThat(results.get(0).key()).isEqualTo("json_match:a");
        assertThat(results.get(0).score().doubleValue()).isEqualTo(1.0);
        assertThat(results.get(1).key()).isEqualTo("json_match:b");
        assertThat(results.get(1).score().doubleValue()).isEqualTo(1.0);
    }

    @Test
    void listAverageNone() {
        List<EvaluatorResult> results = run(
                JsonMatchEvaluator.builder()
                        .listAggregator(JsonMatchEvaluator.ListAggregator.AVERAGE)
                        .build(),
                List.of(map("a", 1, "b", 2), map("a", 1, "b", 2)),
                List.of(map("a", 1, "b", 2), map("a", 1, "b", 3)));

        assertThat(results).hasSize(2);
        assertThat(results.get(0).key()).isEqualTo("json_match:a");
        assertThat(results.get(0).score().doubleValue()).isEqualTo(1.0);
        assertThat(results.get(1).key()).isEqualTo("json_match:b");
        assertThat(results.get(1).score().doubleValue()).isEqualTo(0.5);
    }

    @Test
    void listAllAll() {
        List<EvaluatorResult> results = run(
                JsonMatchEvaluator.builder().aggregator(JsonMatchEvaluator.Aggregator.ALL).build(),
                List.of(map("a", 1, "b", 2), map("a", 1, "b", 2)),
                List.of(map("a", 1, "b", 2), map("a", 1, "b", 2)));

        assertThat(results.get(0).key()).isEqualTo("json_match:all");
        assertThat(results.get(0).score().doubleValue()).isEqualTo(1.0);
    }

    @Test
    void listAverageAll() {
        List<EvaluatorResult> results = run(
                JsonMatchEvaluator.builder()
                        .aggregator(JsonMatchEvaluator.Aggregator.ALL)
                        .listAggregator(JsonMatchEvaluator.ListAggregator.AVERAGE)
                        .build(),
                List.of(map("a", 1, "b", 2), map("a", 1, "b", 2)),
                List.of(map("a", 1, "b", 2), map("a", 1, "b", 3)));

        assertThat(results.get(0).key()).isEqualTo("json_match:all");
        assertThat(results.get(0).score().doubleValue()).isEqualTo(0.5);
    }

    @Test
    void listAllAverage() {
        List<EvaluatorResult> results = run(
                JsonMatchEvaluator.builder().aggregator(JsonMatchEvaluator.Aggregator.AVERAGE).build(),
                List.of(map("a", 1, "b", 2), map("a", 1, "b", 2)),
                List.of(map("a", 1, "b", 2), map("a", 2, "b", 2)));

        assertThat(results.get(0).key()).isEqualTo("json_match:average");
        assertThat(results.get(0).score().doubleValue()).isEqualTo(0.0);
    }

    @Test
    void listAverageAverage() {
        List<EvaluatorResult> results = run(
                JsonMatchEvaluator.builder()
                        .aggregator(JsonMatchEvaluator.Aggregator.AVERAGE)
                        .listAggregator(JsonMatchEvaluator.ListAggregator.AVERAGE)
                        .build(),
                List.of(map("a", 1, "b", 2), map("a", 1, "b", 2)),
                List.of(map("a", 1, "b", 2), map("a", 1, "b", 3)));

        assertThat(results.get(0).key()).isEqualTo("json_match:average");
        assertThat(results.get(0).score().doubleValue()).isEqualTo(0.75, within(1e-9));
    }

    @Test
    void listMismatchAllNone() {
        List<EvaluatorResult> results = run(
                JsonMatchEvaluator.builder().build(),
                List.of(map("a", 1, "d", 2), map("a", 1, "b", 2)),
                List.of(map("a", 1, "b", 2), map("a", 1, "b", 2, "c", 3)));

        assertThat(results).hasSize(4);
        assertThat(results.stream().map(EvaluatorResult::key))
                .containsExactly("json_match:a", "json_match:b", "json_match:c", "json_match:d");
        assertThat(results.get(0).score().doubleValue()).isEqualTo(1.0);
        assertThat(results.get(1).score().doubleValue()).isEqualTo(0.0);
        assertThat(results.get(2).score().doubleValue()).isEqualTo(0.0);
        assertThat(results.get(3).score().doubleValue()).isEqualTo(0.0);
    }

    @Test
    void listMismatchAverageNone() {
        List<EvaluatorResult> results = run(
                JsonMatchEvaluator.builder()
                        .listAggregator(JsonMatchEvaluator.ListAggregator.AVERAGE)
                        .build(),
                List.of(map("a", 1, "d", 2), map("a", 1, "b", 2)),
                List.of(map("a", 1, "b", 2), map("a", 1, "b", 2, "c", 3)));

        assertThat(results).hasSize(4);
        assertThat(results.get(0).score().doubleValue()).isEqualTo(1.0);
        assertThat(results.get(1).score().doubleValue()).isEqualTo(0.5);
        assertThat(results.get(2).score().doubleValue()).isEqualTo(0.0);
        assertThat(results.get(3).score().doubleValue()).isEqualTo(0.0);
    }

    // ---- rubric keys ------------------------------------------------------------------------

    @Test
    void mixesExactKeysWithARubricJudgedKey() {
        // Upstream test_json_match_mix: "a" is judged and passes, "b" mismatches -> average 0.5.
        ObjectNode response = Json.object();
        ObjectNode judged = response.putObject("a_judged_placeholder");
        judged.put("score", true);

        ObjectNode keyed = Json.object();
        ObjectNode a = keyed.putObject("a");
        a.put("score", true);
        a.put("reasoning", "Both mention Mango and Bananas.");

        List<EvaluatorResult> results = run(
                JsonMatchEvaluator.builder()
                        .model(FakeJudgeModel.returning(keyed))
                        .rubric("a", "Does the answer mention all the fruits in the reference answer?")
                        .aggregator(JsonMatchEvaluator.Aggregator.AVERAGE)
                        .build(),
                map("a", "Mango, Bananas", "b", 2),
                map("a", "Bananas, Mango", "b", 3));

        assertThat(results.get(0).key()).isEqualTo("json_match:average");
        assertThat(results.get(0).score().doubleValue()).isEqualTo(0.5);
    }

    @Test
    void carriesTheJudgeReasoningIntoTheComment() {
        ObjectNode keyed = Json.object();
        ObjectNode description = keyed.putObject("description");
        description.put("score", false);
        description.put("reasoning", "The description omits the colour.");

        List<EvaluatorResult> results = run(
                JsonMatchEvaluator.builder()
                        .model(FakeJudgeModel.returning(keyed))
                        .rubric("description", "Does the description mention the colour?")
                        .build(),
                map("description", "a car"),
                map("description", "a red car"));

        assertThat(results.get(0).key()).isEqualTo("json_match:description");
        assertThat(results.get(0).score().doubleValue()).isEqualTo(0.0);
        assertThat(results.get(0).comment()).isEqualTo("The description omits the colour.");
    }

    @Test
    void dropsReasoningFromTheSchemaWhenDisabled() {
        ObjectNode keyed = Json.object();
        keyed.put("description", false);

        FakeJudgeModel model = FakeJudgeModel.returning(keyed);
        List<EvaluatorResult> results = run(
                JsonMatchEvaluator.builder()
                        .model(model)
                        .rubric("description", "Does the description mention the colour?")
                        .useReasoning(false)
                        .build(),
                map("description", "a car"),
                map("description", "a red car"));

        assertThat(model.lastSchema().path("properties").path("description").path("type").asText())
                .isEqualTo("boolean");
        assertThat(results.get(0).comment()).isNull();
    }

    @Test
    void judgesEachRubricKeySeparatelyWithoutAnAggregator() {
        ObjectNode first = Json.object();
        first.putObject("description").put("score", false).put("reasoning", "missing colour");
        ObjectNode second = Json.object();
        second.putObject("name").put("score", true).put("reasoning", "same name");

        FakeJudgeModel model = FakeJudgeModel.returning(first).andThen(second);

        List<EvaluatorResult> results = run(
                JsonMatchEvaluator.builder()
                        .model(model)
                        .rubric("description", "Does it mention the colour?")
                        .rubric("name", "Is the name equivalent?")
                        .build(),
                map("description", "a car", "name", "Fiesta"),
                map("description", "a red car", "name", "the Fiesta"));

        // One judge call per key, so a strict rubric on one key cannot bleed into the other.
        assertThat(model.callCount()).isEqualTo(2);
        assertThat(results.get(0).key()).isEqualTo("json_match:description");
        assertThat(results.get(0).score().doubleValue()).isEqualTo(0.0);
        assertThat(results.get(1).key()).isEqualTo("json_match:name");
        assertThat(results.get(1).score().doubleValue()).isEqualTo(1.0);
    }

    @Test
    void judgesAllRubricKeysInOneCallWithAnAggregator() {
        ObjectNode keyed = Json.object();
        keyed.putObject("description").put("score", true).put("reasoning", "ok");
        keyed.putObject("name").put("score", true).put("reasoning", "ok");

        FakeJudgeModel model = FakeJudgeModel.returning(keyed);
        run(
                JsonMatchEvaluator.builder()
                        .model(model)
                        .rubric("description", "Does it mention the colour?")
                        .rubric("name", "Is the name equivalent?")
                        .aggregator(JsonMatchEvaluator.Aggregator.ALL)
                        .build(),
                map("description", "a red car", "name", "Fiesta"),
                map("description", "a red car", "name", "the Fiesta"));

        assertThat(model.callCount()).isEqualTo(1);
    }

    // ---- validation -------------------------------------------------------------------------

    @Test
    void requiresAModelWhenARubricIsSet() {
        assertThatThrownBy(() -> JsonMatchEvaluator.builder().rubric("a", "criteria").build())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("judge model is required");
    }

    @Test
    void rejectsAModelWithNoRubric() {
        assertThatThrownBy(() ->
                        JsonMatchEvaluator.builder().model(FakeJudgeModel.scoring(true, "x")).build())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("only used when grading specific keys");
    }

    @Test
    void requiresBothSidesToBeListsTogether() {
        assertThatThrownBy(() -> JsonMatchEvaluator.builder()
                        .build()
                        .evaluateAll(EvalRequest.of(null, List.of(map("a", 1)), map("a", 1))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("reference_outputs must also be a list");
    }
}
