package com.dvarahq.oss.evals4j.prompt;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PromptTemplateTest {

    @Test
    void fillsNamedPlaceholders() {
        assertThat(PromptTemplate.format("Grade {outputs} against {reference_outputs}",
                        Map.of("outputs", "an answer", "reference_outputs", "the truth")))
                .isEqualTo("Grade an answer against the truth");
    }

    @Test
    void leavesABraceRunThatIsNotAWellFormedPlaceholderAlone() {
        // The deliberate deviation from Python's str.format, documented in PARITY.md: interpolated
        // values are frequently JSON, and a nested object must not break formatting or raise.
        String template = "Compare {outputs} to the shape {\"a\": 1}";

        assertThat(PromptTemplate.format(template, Map.of("outputs", "x")))
                .isEqualTo("Compare x to the shape {\"a\": 1}");
    }

    @Test
    void leavesEmptyAndNumericBracesAlone() {
        assertThat(PromptTemplate.format("{} and {0} and {1x}", Map.of()))
                .isEqualTo("{} and {0} and {1x}");
    }

    @Test
    void stillThrowsForAWellFormedPlaceholderWithNoValue() {
        // Matching Python's KeyError: a prompt that needs a variable should fail loudly rather than
        // judge against the string "null".
        assertThatThrownBy(() -> PromptTemplate.format("Grade {outputs}", Map.of()))
                .isInstanceOf(PromptTemplate.MissingPromptVariableException.class)
                .hasMessageContaining("outputs");
    }

    @Test
    void reportsTheVariablesATemplateNeeds() {
        assertThat(PromptTemplate.variablesOf("Grade {outputs} against {reference_outputs}"))
                .containsExactly("outputs", "reference_outputs");
    }

    @Test
    void aJsonValueContainingBracesSurvivesInterpolation() {
        String filled = PromptTemplate.format(
                "Context: {context}", Map.of("context", "{\"docs\": [{\"id\": 1}]}"));

        assertThat(filled).isEqualTo("Context: {\"docs\": [{\"id\": 1}]}");
    }
}
