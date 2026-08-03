package com.dvarahq.oss.evals4j.internal;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The recovery path behind {@code PROMPTED} structured output, previously exercised only by accident
 * through the adapters.
 */
class JsonExtractionTest {

    @Test
    void readsABareJsonObject() {
        JsonNode node = JsonExtraction.firstObject("{\"score\": true}");

        assertThat(node.get("score").asBoolean()).isTrue();
    }

    @Test
    void recoversJsonFromAFencedReply() {
        JsonNode node = JsonExtraction.firstObject(
                "Here you go:\n```json\n{\"score\": 0.5, \"reasoning\": \"partly\"}\n```\nHope that helps!");

        assertThat(node.get("score").asDouble()).isEqualTo(0.5);
        assertThat(node.get("reasoning").asText()).isEqualTo("partly");
    }

    @Test
    void handlesNestedObjects() {
        JsonNode node = JsonExtraction.firstObject("prefix {\"outer\": {\"inner\": {\"deep\": 1}}} suffix");

        assertThat(node.get("outer").get("inner").get("deep").asInt()).isEqualTo(1);
    }

    @Test
    void isNotFooledByBracesInsideStringValues() {
        // A "first { to last }" scan would stop at the brace inside the string and produce garbage.
        JsonNode node = JsonExtraction.firstObject("{\"reasoning\": \"the shape is {a: 1}\", \"score\": true}");

        assertThat(node.get("reasoning").asText()).isEqualTo("the shape is {a: 1}");
        assertThat(node.get("score").asBoolean()).isTrue();
    }

    @Test
    void isNotFooledByEscapedQuotes() {
        JsonNode node = JsonExtraction.firstObject("{\"reasoning\": \"they said \\\"no\\\"\", \"score\": false}");

        assertThat(node.get("reasoning").asText()).isEqualTo("they said \"no\"");
        assertThat(node.get("score").asBoolean()).isFalse();
    }

    @Test
    void takesTheFirstCompleteObjectWhenThereAreSeveral() {
        JsonNode node = JsonExtraction.firstObject("{\"score\": 1} and then {\"score\": 0}");

        assertThat(node.get("score").asInt()).isEqualTo(1);
    }

    @Test
    void skipsAnUnbalancedCandidateAndFindsTheRealOne() {
        JsonNode node = JsonExtraction.firstObject("{ this never closes... actually {\"score\": true}");

        assertThat(node.get("score").asBoolean()).isTrue();
    }

    @Test
    void rejectsAnEmptyResponse() {
        assertThatThrownBy(() -> JsonExtraction.firstObject("   "))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("empty response");
    }

    @Test
    void rejectsAReplyWithNoObjectAtAll() {
        assertThatThrownBy(() -> JsonExtraction.firstObject("I would rather not answer."))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("No JSON object found");
    }

    @Test
    void theSchemaInstructionCarriesTheSchemaAndForbidsProse() {
        String instruction = JsonExtraction.schemaInstruction(
                Json.toNode(Map.of("type", "object", "title", "score_schema")));

        assertThat(instruction).contains("score_schema");
        assertThat(instruction).contains("no code fences");
    }
}
