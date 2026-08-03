package io.github.grabdoc.evals4j.prompt;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;

/**
 * The OpenEvals prompt catalog, reproduced verbatim — 33 prompts across 8 categories.
 *
 * <p>Each constant is a template whose {@code {placeholders}} are filled by {@link PromptTemplate}:
 * {@code {inputs}}, {@code {outputs}}, {@code {reference_outputs}}, and prompt-specific ones
 * such as {@code {context}}, {@code {plan}} and {@code {attachments}}. The Javadoc on each
 * constant lists the ones it uses.
 *
 * <p>These strings are copied from LangChain's OpenEvals (MIT) — see the project NOTICE. They live as
 * classpath resources rather than Java text blocks so that no escaping or incidental-indentation rule
 * can alter them, and a parity test asserts they still match upstream byte for byte. The tag names are
 * inconsistent across categories ({@code <input>} vs {@code <inputs>}, {@code <trajectory>} vs
 * {@code <conversation>}); that is upstream's doing and is preserved deliberately, because each
 * prompt was tuned against its own wording.
 */
public final class Prompts {

    private Prompts() {}

    private static String load(String resource) {
        String path = "/io/github/grabdoc/evals4j/prompt/" + resource;
        try (InputStream in = Prompts.class.getResourceAsStream(path)) {
            if (in == null) {
                throw new IllegalStateException("Missing prompt resource on the classpath: " + path);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("Could not read prompt resource: " + path, e);
        }
    }


    // ======================================================== quality
    // Response quality — correctness, conciseness, hallucination, relevance, code, plan adherence, laziness.

    /**
     * Variables: {inputs}, {outputs}
     *
     * <p>Source: openevals/python/openevals/prompts/quality/answer_relevance.py
     */
    public static final String ANSWER_RELEVANCE_PROMPT = load("quality/ANSWER_RELEVANCE_PROMPT.txt");

    /**
     * Variables: {inputs}, {outputs}
     *
     * <p>Source: openevals/python/openevals/prompts/quality/code_correctness.py
     */
    public static final String CODE_CORRECTNESS_PROMPT = load("quality/CODE_CORRECTNESS_PROMPT.txt");

    /**
     * Variables: {inputs}, {outputs}, {reference_outputs}
     *
     * <p>Source: openevals/python/openevals/prompts/quality/code_correctness.py
     */
    public static final String CODE_CORRECTNESS_PROMPT_WITH_REFERENCE_OUTPUTS = load("quality/CODE_CORRECTNESS_PROMPT_WITH_REFERENCE_OUTPUTS.txt");

    /**
     * Variables: {inputs}, {outputs}
     *
     * <p>Source: openevals/python/openevals/prompts/quality/conciseness.py
     */
    public static final String CONCISENESS_PROMPT = load("quality/CONCISENESS_PROMPT.txt");

    /**
     * Variables: {inputs}, {outputs}, {reference_outputs}
     *
     * <p>Source: openevals/python/openevals/prompts/quality/correctness.py
     */
    public static final String CORRECTNESS_PROMPT = load("quality/CORRECTNESS_PROMPT.txt");

    /**
     * Variables: {context}, {inputs}, {outputs}, {reference_outputs}
     *
     * <p>Source: openevals/python/openevals/prompts/quality/hallucination.py
     */
    public static final String HALLUCINATION_PROMPT = load("quality/HALLUCINATION_PROMPT.txt");

    /**
     * Variables: {inputs}, {outputs}
     *
     * <p>Source: openevals/python/openevals/prompts/quality/laziness.py
     */
    public static final String LAZINESS_PROMPT = load("quality/LAZINESS_PROMPT.txt");

    /**
     * Variables: {inputs}, {plan}, {outputs}
     *
     * <p>Source: openevals/python/openevals/prompts/quality/plan_adherence.py
     */
    public static final String PLAN_ADHERENCE_PROMPT = load("quality/PLAN_ADHERENCE_PROMPT.txt");

    // ======================================================== rag
    // Retrieval-augmented generation — groundedness, helpfulness, retrieval relevance.

    /**
     * Variables: {context}, {outputs}
     *
     * <p>Source: openevals/python/openevals/prompts/rag/groundedness.py
     */
    public static final String RAG_GROUNDEDNESS_PROMPT = load("rag/RAG_GROUNDEDNESS_PROMPT.txt");

    /**
     * Variables: {inputs}, {outputs}
     *
     * <p>Source: openevals/python/openevals/prompts/rag/helpfulness.py
     */
    public static final String RAG_HELPFULNESS_PROMPT = load("rag/RAG_HELPFULNESS_PROMPT.txt");

    /**
     * Variables: {inputs}, {context}
     *
     * <p>Source: openevals/python/openevals/prompts/rag/retrieval_relevance.py
     */
    public static final String RAG_RETRIEVAL_RELEVANCE_PROMPT = load("rag/RAG_RETRIEVAL_RELEVANCE_PROMPT.txt");

    // ======================================================== safety
    // Safety — toxicity and fairness.

    /**
     * Variables: {inputs}, {outputs}
     *
     * <p>Source: openevals/python/openevals/prompts/safety/fairness.py
     */
    public static final String FAIRNESS_PROMPT = load("safety/FAIRNESS_PROMPT.txt");

    /**
     * Variables: {inputs}, {outputs}
     *
     * <p>Source: openevals/python/openevals/prompts/safety/toxicity.py
     */
    public static final String TOXICITY_PROMPT = load("safety/TOXICITY_PROMPT.txt");

    // ======================================================== security
    // Security — PII leakage, prompt injection, code injection. The injection prompts grade {inputs} only.

    /**
     * Variables: {inputs}
     *
     * <p>Source: openevals/python/openevals/prompts/security/code_injection.py
     */
    public static final String CODE_INJECTION_PROMPT = load("security/CODE_INJECTION_PROMPT.txt");

    /**
     * Variables: {inputs}, {outputs}
     *
     * <p>Source: openevals/python/openevals/prompts/security/pii_leakage.py
     */
    public static final String PII_LEAKAGE_PROMPT = load("security/PII_LEAKAGE_PROMPT.txt");

    /**
     * Variables: {inputs}
     *
     * <p>Source: openevals/python/openevals/prompts/security/prompt_injection.py
     */
    public static final String PROMPT_INJECTION_PROMPT = load("security/PROMPT_INJECTION_PROMPT.txt");

    // ======================================================== trajectory
    // Agent trajectories — overall accuracy and tool selection. {outputs} is a message list.

    /**
     * Variables: {reference_outputs}, {outputs}
     *
     * <p>Source: openevals/python/openevals/prompts/trajectory/accuracy.py
     */
    public static final String TRAJECTORY_ACCURACY_PROMPT_WITH_REFERENCE = load("trajectory/TRAJECTORY_ACCURACY_PROMPT_WITH_REFERENCE.txt");

    /**
     * Variables: {outputs}
     *
     * <p>Source: openevals/python/openevals/prompts/trajectory/accuracy.py
     */
    public static final String TRAJECTORY_ACCURACY_PROMPT = load("trajectory/TRAJECTORY_ACCURACY_PROMPT.txt");

    /**
     * Variables: {outputs}
     *
     * <p>Source: openevals/python/openevals/prompts/trajectory/tool_selection.py
     */
    public static final String TOOL_SELECTION_PROMPT = load("trajectory/TOOL_SELECTION_PROMPT.txt");

    // ======================================================== conversation
    // Whole-thread conversation analysis. {outputs} is a message list.

    /**
     * Variables: {outputs}
     *
     * <p>Source: openevals/python/openevals/prompts/conversation/agent_tone.py
     */
    public static final String AGENT_TONE_PROMPT = load("conversation/AGENT_TONE_PROMPT.txt");

    /**
     * Variables: {outputs}
     *
     * <p>Source: openevals/python/openevals/prompts/conversation/knowledge_retention.py
     */
    public static final String KNOWLEDGE_RETENTION_PROMPT = load("conversation/KNOWLEDGE_RETENTION_PROMPT.txt");

    /**
     * Variables: {outputs}
     *
     * <p>Source: openevals/python/openevals/prompts/conversation/language_detection.py
     */
    public static final String LANGUAGE_DETECTION_PROMPT = load("conversation/LANGUAGE_DETECTION_PROMPT.txt");

    /**
     * Variables: {outputs}
     *
     * <p>Source: openevals/python/openevals/prompts/conversation/perceived_error.py
     */
    public static final String PERCEIVED_ERROR_PROMPT = load("conversation/PERCEIVED_ERROR_PROMPT.txt");

    /**
     * Variables: {outputs}
     *
     * <p>Source: openevals/python/openevals/prompts/conversation/support_intent.py
     */
    public static final String SUPPORT_INTENT_PROMPT = load("conversation/SUPPORT_INTENT_PROMPT.txt");

    /**
     * Variables: {outputs}
     *
     * <p>Source: openevals/python/openevals/prompts/conversation/task_completion.py
     */
    public static final String TASK_COMPLETION_PROMPT = load("conversation/TASK_COMPLETION_PROMPT.txt");

    /**
     * Variables: {outputs}
     *
     * <p>Source: openevals/python/openevals/prompts/conversation/user_satisfaction.py
     */
    public static final String USER_SATISFACTION_PROMPT = load("conversation/USER_SATISFACTION_PROMPT.txt");

    /**
     * Variables: {outputs}
     *
     * <p>Source: openevals/python/openevals/prompts/conversation/wins.py
     */
    public static final String WINS_PROMPT = load("conversation/WINS_PROMPT.txt");

    // ======================================================== image
    // Image moderation. Requires {attachments}.

    /**
     * Variables: {inputs}, {outputs}, {attachments}
     *
     * <p>Source: openevals/python/openevals/prompts/image/explicit_content.py
     */
    public static final String EXPLICIT_CONTENT_PROMPT = load("image/EXPLICIT_CONTENT_PROMPT.txt");

    /**
     * Variables: {inputs}, {outputs}, {attachments}
     *
     * <p>Source: openevals/python/openevals/prompts/image/sensitive_imagery.py
     */
    public static final String SENSITIVE_IMAGERY_PROMPT = load("image/SENSITIVE_IMAGERY_PROMPT.txt");

    // ======================================================== voice
    // Voice and audio quality. Requires {attachments}.

    /**
     * Variables: {inputs}, {outputs}, {attachments}
     *
     * <p>Source: openevals/python/openevals/prompts/voice/audio_quality.py
     */
    public static final String AUDIO_QUALITY_PROMPT = load("voice/AUDIO_QUALITY_PROMPT.txt");

    /**
     * Variables: {inputs}, {outputs}, {attachments}
     *
     * <p>Source: openevals/python/openevals/prompts/voice/transcription_accuracy.py
     */
    public static final String TRANSCRIPTION_ACCURACY_PROMPT = load("voice/TRANSCRIPTION_ACCURACY_PROMPT.txt");

    /**
     * Variables: {inputs}, {outputs}, {attachments}
     *
     * <p>Source: openevals/python/openevals/prompts/voice/user_interrupts.py
     */
    public static final String USER_INTERRUPTS_PROMPT = load("voice/USER_INTERRUPTS_PROMPT.txt");

    /**
     * Variables: {inputs}, {outputs}, {attachments}
     *
     * <p>Source: openevals/python/openevals/prompts/voice/vocal_affect.py
     */
    public static final String VOCAL_AFFECT_PROMPT = load("voice/VOCAL_AFFECT_PROMPT.txt");
}
