package com.dvarahq.oss.evals4j.judge;

import com.fasterxml.jackson.databind.JsonNode;
import com.dvarahq.oss.evals4j.EvalRequest;
import com.dvarahq.oss.evals4j.Evaluator;
import com.dvarahq.oss.evals4j.ScorerOutput;
import com.dvarahq.oss.evals4j.ScorerRunner;
import com.dvarahq.oss.evals4j.internal.Json;
import com.dvarahq.oss.evals4j.message.Attachment;
import com.dvarahq.oss.evals4j.message.ChatMessage;
import com.dvarahq.oss.evals4j.prompt.FewShotExample;
import com.dvarahq.oss.evals4j.prompt.PromptTemplate;
import com.dvarahq.oss.evals4j.prompt.PromptValues;
import com.dvarahq.oss.evals4j.result.EvaluatorResult;
import com.dvarahq.oss.evals4j.result.Score;
import com.dvarahq.oss.evals4j.spi.EvalTracer;
import com.dvarahq.oss.evals4j.spi.JudgeModel;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;

/**
 * Scores output by asking a model, with structured output so the score is parsed rather than
 * scraped.
 *
 * <pre>{@code
 * LlmAsJudge judge = LlmAsJudge.builder()
 *         .prompt(Prompts.CONCISENESS_PROMPT)
 *         .model(judgeModel)
 *         .feedbackKey("conciseness")
 *         .build();
 *
 * EvaluatorResult result = judge.evaluate(
 *         "How is the weather in San Francisco?",
 *         "Thanks for asking! It is currently sunny and 90 degrees.");
 * }</pre>
 *
 * <p>Port of OpenEvals' {@code create_llm_as_judge}.
 */
public final class LlmAsJudge implements Evaluator {

    private static final String DEFAULT_FEEDBACK_KEY = "score";
    private static final String ATTACHMENTS_PLACEHOLDER = "{attachments}";

    private final String promptTemplate;
    private final PromptFunction promptFunction;
    private final String feedbackKey;
    private final JudgeModel model;
    private final String system;
    private final boolean continuous;
    private final List<Double> choices;
    private final boolean useReasoning;
    private final List<FewShotExample> fewShotExamples;
    private final JsonNode outputSchema;
    private final EvalTracer tracer;
    private final String runName;

    private LlmAsJudge(Builder builder) {
        this.promptTemplate = builder.promptTemplate;
        this.promptFunction = builder.promptFunction;
        this.feedbackKey = builder.feedbackKey;
        this.model = Objects.requireNonNull(builder.model, "A judge model is required — call .model(...)");
        this.system = builder.system;
        this.continuous = builder.continuous;
        this.choices = builder.choices == null ? List.of() : List.copyOf(builder.choices);
        this.useReasoning = builder.useReasoning;
        this.fewShotExamples = List.copyOf(builder.fewShotExamples);
        this.outputSchema = builder.outputSchema;
        this.tracer = builder.tracer == null ? EvalTracer.NO_OP : builder.tracer;
        this.runName = DEFAULT_FEEDBACK_KEY.equals(feedbackKey)
                ? "llm_as_judge"
                : "llm_as_" + feedbackKey + "_judge";

        if (promptTemplate == null && promptFunction == null) {
            throw new IllegalArgumentException("A prompt is required — call .prompt(...)");
        }
        if (system != null && promptTemplate == null) {
            throw new IllegalArgumentException(
                    "`system` is only supported when the prompt is a string template");
        }
    }

    public static Builder builder() {
        return new Builder();
    }

    public String feedbackKey() {
        return feedbackKey;
    }

    public String runName() {
        return runName;
    }

    @Override
    public List<EvaluatorResult> evaluateAll(EvalRequest request) {
        return ScorerRunner.run(runName, feedbackKey, this::score, request, tracer);
    }

    @Override
    public CompletableFuture<List<EvaluatorResult>> evaluateAllAsync(EvalRequest request) {
        return ScorerRunner.runAsync(runName, feedbackKey, this::scoreAsync, request, tracer);
    }

    /**
     * The judge's raw response, for use with a custom {@link Builder#outputSchema}.
     *
     * <p>When you supply your own schema the response is not a score and cannot be shaped into an
     * {@link EvaluatorResult} — this returns it untouched, mirroring what OpenEvals does in the same
     * situation.
     */
    public JsonNode evaluateRaw(EvalRequest request) {
        return model.invokeStructured(buildMessages(request), schemaFor());
    }

    private ScorerOutput score(EvalRequest request) {
        return toScorerOutput(model.invokeStructured(buildMessages(request), schemaFor()));
    }

    private CompletableFuture<ScorerOutput> scoreAsync(EvalRequest request) {
        return model.invokeStructuredAsync(buildMessages(request), schemaFor())
                .thenApply(this::toScorerOutput);
    }

    private ScorerOutput toScorerOutput(JsonNode response) {
        JsonNode scoreNode = response.get("score");
        if (scoreNode == null) {
            if (outputSchema != null) {
                throw new JudgeModel.JudgeInvocationException(
                        "The judge response has no 'score' field. A custom outputSchema without a 'score' "
                                + "property cannot produce an EvaluatorResult — use evaluateRaw(request) "
                                + "instead. Response was: " + response);
            }
            throw new JudgeModel.JudgeInvocationException(
                    "The judge response has no 'score' field: " + response);
        }

        Score score = scoreNode.isBoolean()
                ? Score.of(scoreNode.asBoolean())
                : Score.of(scoreNode.asDouble());

        String reasoning = null;
        if (useReasoning) {
            JsonNode reasoningNode = response.get("reasoning");
            reasoning = reasoningNode == null || reasoningNode.isNull() ? null : reasoningNode.asText();
        }
        return ScorerOutput.of(score, reasoning);
    }

    private JsonNode schemaFor() {
        return outputSchema != null
                ? outputSchema
                : JudgeSchemas.named(
                        JudgeSchemas.defaultSchema(continuous, choices, useReasoning),
                        "score",
                        JudgeSchemas.scoreDescription(continuous, choices));
    }

    List<ChatMessage> buildMessages(EvalRequest request) {
        List<ChatMessage> messages = new ArrayList<>();

        if (promptFunction != null) {
            messages.addAll(promptFunction.buildPrompt(request));
        } else {
            messages.add(renderTemplate(request));
        }

        if (system != null) {
            messages.add(0, ChatMessage.system(system));
        }
        if (!fewShotExamples.isEmpty()) {
            appendFewShotExamples(messages);
        }
        return messages;
    }

    /**
     * Renders the string template into a single user message.
     *
     * <p>When the template has an {@code {attachments}} placeholder and the request carries
     * attachments, the template is split there and the attachments become content blocks between the
     * two halves — the multimodal prompts (image, voice) depend on this.
     */
    private ChatMessage renderTemplate(EvalRequest request) {
        Map<String, Object> values = promptValues(request);
        List<Attachment> attachments = request.attachments();

        if (!attachments.isEmpty() && promptTemplate.contains(ATTACHMENTS_PLACEHOLDER)) {
            int split = promptTemplate.indexOf(ATTACHMENTS_PLACEHOLDER);
            String before = PromptTemplate.format(promptTemplate.substring(0, split), values);
            String after = PromptTemplate.format(
                    promptTemplate.substring(split + ATTACHMENTS_PLACEHOLDER.length()), values);

            List<Object> blocks = new ArrayList<>();
            if (!before.isEmpty()) {
                blocks.add(Map.of("type", "text", "text", before));
            }
            for (Attachment attachment : attachments) {
                blocks.add(attachment.toContentBlock());
            }
            if (!after.isEmpty()) {
                blocks.add(Map.of("type", "text", "text", after));
            }
            return ChatMessage.user(blocks);
        }

        return ChatMessage.user(PromptTemplate.format(promptTemplate, values));
    }

    /**
     * The template variables. Null values are omitted rather than rendered as "null", so a prompt
     * that needs {@code {reference_outputs}} fails loudly when none was supplied instead of judging
     * against the string "null".
     */
    private Map<String, Object> promptValues(EvalRequest request) {
        Map<String, Object> values = new LinkedHashMap<>();
        putIfPresent(values, "inputs", request.inputs());
        putIfPresent(values, "outputs", request.outputs());
        putIfPresent(values, "reference_outputs", request.referenceOutputs());
        request.variables().forEach((name, value) -> putIfPresent(values, name, value));
        return values;
    }

    private static void putIfPresent(Map<String, Object> values, String name, Object value) {
        if (value != null) {
            values.put(name, PromptValues.stringify(value));
        }
    }

    /** Appends the examples to the last user message, as OpenEvals does. */
    private void appendFewShotExamples(List<ChatMessage> messages) {
        for (int i = messages.size() - 1; i >= 0; i--) {
            ChatMessage message = messages.get(i);
            if (!ChatMessage.ROLE_USER.equals(message.role())) {
                continue;
            }
            if (!(message.content() instanceof String content)) {
                throw new IllegalStateException(
                        "Few-shot examples can only be appended to a text user message, but the last user "
                                + "message has structured content. Build the examples into the prompt "
                                + "function instead.");
            }
            messages.set(i, message.withContent(content + "\n\n" + FewShotExample.render(fewShotExamples)));
            return;
        }
        throw new IllegalStateException(
                "Appending few-shot examples requires a user message in the provided prompt");
    }

    /** Builder for {@link LlmAsJudge}. */
    public static final class Builder {
        private String promptTemplate;
        private PromptFunction promptFunction;
        private String feedbackKey = DEFAULT_FEEDBACK_KEY;
        private JudgeModel model;
        private String system;
        private boolean continuous;
        private List<Double> choices;
        private boolean useReasoning = true;
        private final List<FewShotExample> fewShotExamples = new ArrayList<>();
        private JsonNode outputSchema;
        private EvalTracer tracer;

        /** A string template, typically one of the {@code Prompts} constants. */
        public Builder prompt(String template) {
            this.promptTemplate = template;
            this.promptFunction = null;
            return this;
        }

        /** Builds the prompt programmatically instead of formatting a template. */
        public Builder prompt(PromptFunction function) {
            this.promptFunction = function;
            this.promptTemplate = null;
            return this;
        }

        /** The key the score is recorded under. Defaults to {@code "score"}. */
        public Builder feedbackKey(String feedbackKey) {
            this.feedbackKey = Objects.requireNonNull(feedbackKey, "feedbackKey");
            return this;
        }

        public Builder model(JudgeModel model) {
            this.model = model;
            return this;
        }

        /** A system message prepended to the prompt. Only valid with a string template. */
        public Builder system(String system) {
            this.system = system;
            return this;
        }

        /** Score on a 0.0–1.0 scale instead of pass/fail. */
        public Builder continuous(boolean continuous) {
            this.continuous = continuous;
            return this;
        }

        /** Restrict the score to these values. Takes precedence over {@link #continuous}. */
        public Builder choices(List<Double> choices) {
            this.choices = choices;
            return this;
        }

        public Builder choices(double... choices) {
            List<Double> values = new ArrayList<>(choices.length);
            for (double choice : choices) {
                values.add(choice);
            }
            this.choices = values;
            return this;
        }

        /**
         * Require the judge to reason before scoring. On by default, and worth keeping on — the
         * reasoning lands in {@link EvaluatorResult#comment()} and is what makes a low score
         * actionable.
         */
        public Builder useReasoning(boolean useReasoning) {
            this.useReasoning = useReasoning;
            return this;
        }

        public Builder fewShotExamples(List<FewShotExample> examples) {
            if (examples != null) {
                this.fewShotExamples.addAll(examples);
            }
            return this;
        }

        public Builder fewShotExample(FewShotExample example) {
            this.fewShotExamples.add(example);
            return this;
        }

        /**
         * Replaces the generated score schema with your own. The result is then only reachable via
         * {@link LlmAsJudge#evaluateRaw}, unless your schema also declares a {@code score} property.
         */
        public Builder outputSchema(JsonNode schema) {
            this.outputSchema = schema;
            return this;
        }

        public Builder outputSchema(Map<String, Object> schema) {
            this.outputSchema = Json.toNode(schema);
            return this;
        }

        public Builder tracer(EvalTracer tracer) {
            this.tracer = tracer;
            return this;
        }

        public LlmAsJudge build() {
            return new LlmAsJudge(this);
        }
    }
}
