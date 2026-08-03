package io.github.grabdoc.evals4j.prompt;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.InputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Guards the prompt catalog against drift.
 *
 * <p>The prompts are reproduced verbatim from OpenEvals, and their exact wording is what the scores
 * were tuned against — a stray edit would change results without changing any code. The checksums in
 * {@code parity/prompt-checksums.txt} were computed from the upstream Python sources at the commit
 * recorded in that file.
 */
class PromptParityTest {

    private static Map<String, String> expectedChecksums() {
        try (InputStream in = PromptParityTest.class.getResourceAsStream("/parity/prompt-checksums.txt")) {
            assertThat(in).as("prompt-checksums.txt on the test classpath").isNotNull();
            Map<String, String> checksums = new LinkedHashMap<>();
            String content = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            for (String line : content.split("\n")) {
                String trimmed = line.strip();
                if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                    continue;
                }
                String[] parts = trimmed.split("\\s+");
                checksums.put(parts[0], parts[1]);
            }
            return checksums;
        } catch (Exception e) {
            throw new IllegalStateException("Could not read the prompt checksum manifest", e);
        }
    }

    static List<String> resourceNames() {
        return new ArrayList<>(expectedChecksums().keySet());
    }

    @ParameterizedTest(name = "{0} matches upstream")
    @MethodSource("resourceNames")
    void promptMatchesUpstreamByteForByte(String resource) throws Exception {
        String path = "/io/github/grabdoc/evals4j/prompt/" + resource;
        byte[] bytes;
        try (InputStream in = Prompts.class.getResourceAsStream(path)) {
            assertThat(in).as("prompt resource " + path).isNotNull();
            bytes = in.readAllBytes();
        }

        MessageDigest sha256 = MessageDigest.getInstance("SHA-256");
        StringBuilder hex = new StringBuilder();
        for (byte b : sha256.digest(bytes)) {
            hex.append(String.format("%02x", b));
        }

        assertThat(hex.toString())
                .as("%s no longer matches the upstream text it was copied from", resource)
                .isEqualTo(expectedChecksums().get(resource));
    }

    @Test
    void everyConstantLoadsAndIsNonEmpty() throws Exception {
        int count = 0;
        for (Field field : Prompts.class.getDeclaredFields()) {
            if (!Modifier.isPublic(field.getModifiers()) || field.getType() != String.class) {
                continue;
            }
            String value = (String) field.get(null);
            assertThat(value).as("prompt %s", field.getName()).isNotNull().isNotBlank();
            count++;
        }
        assertThat(count).as("number of exported prompts").isEqualTo(33);
    }

    @Test
    void checksumManifestCoversEveryConstant() {
        assertThat(expectedChecksums()).hasSize(33);
    }

    @Test
    void promptsDeclareTheVariablesTheyAreDocumentedToUse() {
        // A spot check that the templating engine reads the same placeholders a caller would supply.
        assertThat(PromptTemplate.variablesOf(Prompts.CORRECTNESS_PROMPT))
                .containsExactlyInAnyOrder("inputs", "outputs", "reference_outputs");
        assertThat(PromptTemplate.variablesOf(Prompts.CONCISENESS_PROMPT))
                .containsExactlyInAnyOrder("inputs", "outputs");
        assertThat(PromptTemplate.variablesOf(Prompts.RAG_GROUNDEDNESS_PROMPT))
                .containsExactlyInAnyOrder("context", "outputs");
        assertThat(PromptTemplate.variablesOf(Prompts.RAG_RETRIEVAL_RELEVANCE_PROMPT))
                .containsExactlyInAnyOrder("context", "inputs");
        assertThat(PromptTemplate.variablesOf(Prompts.PLAN_ADHERENCE_PROMPT))
                .contains("plan");
        // The injection prompts grade the user's input, not the model's answer.
        assertThat(PromptTemplate.variablesOf(Prompts.PROMPT_INJECTION_PROMPT))
                .containsExactly("inputs");
        assertThat(PromptTemplate.variablesOf(Prompts.CODE_INJECTION_PROMPT))
                .containsExactly("inputs");
    }

    @Test
    void multimodalPromptsDeclareAnAttachmentsPlaceholder() {
        Set<String> multimodal = Set.of(
                Prompts.EXPLICIT_CONTENT_PROMPT,
                Prompts.SENSITIVE_IMAGERY_PROMPT,
                Prompts.AUDIO_QUALITY_PROMPT,
                Prompts.TRANSCRIPTION_ACCURACY_PROMPT,
                Prompts.USER_INTERRUPTS_PROMPT,
                Prompts.VOCAL_AFFECT_PROMPT);

        for (String prompt : multimodal) {
            assertThat(PromptTemplate.variablesOf(prompt)).contains("attachments");
        }
    }

    @Test
    void threadStylePromptsTakeTheWholeConversation() {
        // These grade a transcript, so they take outputs and nothing else.
        assertThat(PromptTemplate.variablesOf(Prompts.PERCEIVED_ERROR_PROMPT)).containsExactly("outputs");
        assertThat(PromptTemplate.variablesOf(Prompts.USER_SATISFACTION_PROMPT)).containsExactly("outputs");
        assertThat(PromptTemplate.variablesOf(Prompts.TRAJECTORY_ACCURACY_PROMPT)).containsExactly("outputs");
        assertThat(PromptTemplate.variablesOf(Prompts.TRAJECTORY_ACCURACY_PROMPT_WITH_REFERENCE))
                .containsExactlyInAnyOrder("outputs", "reference_outputs");
    }
}
