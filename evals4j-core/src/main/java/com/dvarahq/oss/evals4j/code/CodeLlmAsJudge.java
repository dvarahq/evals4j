package com.dvarahq.oss.evals4j.code;

import com.fasterxml.jackson.databind.JsonNode;
import com.dvarahq.oss.evals4j.EvalRequest;
import com.dvarahq.oss.evals4j.Evaluator;
import com.dvarahq.oss.evals4j.ScorerRunner;
import com.dvarahq.oss.evals4j.internal.Json;
import com.dvarahq.oss.evals4j.judge.LlmAsJudge;
import com.dvarahq.oss.evals4j.prompt.FewShotExample;
import com.dvarahq.oss.evals4j.prompt.Prompts;
import com.dvarahq.oss.evals4j.result.EvaluatorResult;
import com.dvarahq.oss.evals4j.spi.EvalTracer;
import com.dvarahq.oss.evals4j.spi.JudgeModel;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;

/**
 * Judges extracted code with an LLM.
 *
 * <p>Compilation tells you the code is well-formed; this tells you whether it does what was asked.
 * The two are complementary, and running {@link JavacEvaluator} first is usually worth it — a judge
 * asked to assess code that does not compile tends to grade the intent rather than the result.
 *
 * <p>Port of OpenEvals' {@code create_code_llm_as_judge}.
 */
public final class CodeLlmAsJudge implements Evaluator {

    public static final String DEFAULT_FEEDBACK_KEY = "code_correctness";
    public static final String RUN_NAME = "code_llm_as_judge";

    private final LlmAsJudge judge;
    private final CodeExtractor extractor;
    private final String feedbackKey;
    private final EvalTracer tracer;

    private CodeLlmAsJudge(Builder builder) {
        LlmAsJudge.Builder judgeBuilder = LlmAsJudge.builder()
                .prompt(builder.prompt)
                .feedbackKey(builder.feedbackKey)
                .model(builder.model)
                .system(builder.system)
                .continuous(builder.continuous)
                .useReasoning(builder.useReasoning)
                .fewShotExamples(builder.fewShotExamples)
                .outputSchema(builder.outputSchema)
                .tracer(builder.tracer);
        if (builder.choices != null) {
            judgeBuilder.choices(builder.choices);
        }
        this.judge = judgeBuilder.build();
        // The judge doubles as the extraction model, so LLM extraction needs no second model.
        this.extractor = CodeExtractor.from(
                builder.strategy,
                builder.customExtractor,
                builder.extractionModel != null ? builder.extractionModel : builder.model);
        this.feedbackKey = builder.feedbackKey;
        this.tracer = builder.tracer == null ? EvalTracer.NO_OP : builder.tracer;
    }

    public static Builder builder() {
        return new Builder();
    }

    @Override
    public List<EvaluatorResult> evaluateAll(EvalRequest request) {
        String code = extractor.extract(request.outputs());
        if (code == null) {
            return ScorerRunner.toResults(feedbackKey, CodeEvaluator.extractionFailed());
        }
        // The judge grades the extracted code; `inputs` still carries the original request.
        return judge.evaluateAll(request.toBuilder().outputs(code).build());
    }

    @Override
    public CompletableFuture<List<EvaluatorResult>> evaluateAllAsync(EvalRequest request) {
        // Extraction is a model call under the LLM strategy, so it must not run on the caller's
        // thread either — hence the supplyAsync rather than extracting up front.
        return CompletableFuture.supplyAsync(() -> extractor.extract(request.outputs()))
                .thenCompose(code -> code == null
                        ? CompletableFuture.completedFuture(
                                ScorerRunner.toResults(feedbackKey, CodeEvaluator.extractionFailed()))
                        : judge.evaluateAllAsync(request.toBuilder().outputs(code).build()));
    }

    /** The key the score is recorded under. */
    public String feedbackKey() {
        return feedbackKey;
    }

    /**
     * The judge's raw response for the extracted code, for use with a custom
     * {@link Builder#outputSchema}.
     *
     * @throws IllegalArgumentException when no code could be extracted — unlike {@link #evaluateAll},
     *     there is no score to record the failure on
     */
    public JsonNode evaluateRaw(EvalRequest request) {
        String code = extractor.extract(request.outputs());
        if (code == null) {
            throw new IllegalArgumentException(
                    "No code could be extracted from the outputs, so there is nothing to judge. "
                            + "Use evaluateAll(request) to get a failing result instead.");
        }
        return judge.evaluateRaw(request.toBuilder().outputs(code).build());
    }

    /** Builder for {@link CodeLlmAsJudge}. */
    public static final class Builder {
        private String prompt = Prompts.CODE_CORRECTNESS_PROMPT;
        private String feedbackKey = DEFAULT_FEEDBACK_KEY;
        private JudgeModel model;
        private String system;
        private boolean continuous;
        private List<Double> choices;
        private boolean useReasoning = true;
        private final List<FewShotExample> fewShotExamples = new ArrayList<>();
        private CodeExtractionStrategy strategy = CodeExtractionStrategy.NONE;
        private Function<Object, String> customExtractor;
        private JudgeModel extractionModel;
        private JsonNode outputSchema;
        private EvalTracer tracer;

        /**
         * Defaults to {@link Prompts#CODE_CORRECTNESS_PROMPT}. Use
         * {@link Prompts#CODE_CORRECTNESS_PROMPT_WITH_REFERENCE_OUTPUTS} when there is a reference
         * implementation to compare against.
         */
        public Builder prompt(String prompt) {
            this.prompt = prompt;
            return this;
        }

        public Builder feedbackKey(String feedbackKey) {
            this.feedbackKey = feedbackKey;
            return this;
        }

        public Builder model(JudgeModel model) {
            this.model = model;
            return this;
        }

        public Builder system(String system) {
            this.system = system;
            return this;
        }

        public Builder continuous(boolean continuous) {
            this.continuous = continuous;
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

        /** Restrict the score to these values. Takes precedence over {@link #continuous}. */
        public Builder choices(List<Double> choices) {
            this.choices = choices;
            return this;
        }

        public Builder useReasoning(boolean useReasoning) {
            this.useReasoning = useReasoning;
            return this;
        }

        public Builder fewShotExample(FewShotExample example) {
            this.fewShotExamples.add(example);
            return this;
        }

        public Builder fewShotExamples(List<FewShotExample> examples) {
            if (examples != null) {
                this.fewShotExamples.addAll(examples);
            }
            return this;
        }

        /**
         * Replaces the generated score schema with your own. The result is then only reachable via
         * {@link CodeLlmAsJudge#evaluateRaw}, unless your schema also declares a {@code score}
         * property.
         */
        public Builder outputSchema(JsonNode schema) {
            this.outputSchema = schema;
            return this;
        }

        public Builder outputSchema(Map<String, Object> schema) {
            this.outputSchema = Json.toNode(schema);
            return this;
        }

        public Builder extractionStrategy(CodeExtractionStrategy strategy) {
            this.strategy = strategy;
            return this;
        }

        public Builder extractor(Function<Object, String> extractor) {
            this.customExtractor = extractor;
            return this;
        }

        /** Defaults to the judge model. */
        public Builder extractionModel(JudgeModel model) {
            this.extractionModel = model;
            return this;
        }

        public Builder tracer(EvalTracer tracer) {
            this.tracer = tracer;
            return this;
        }

        public CodeLlmAsJudge build() {
            return new CodeLlmAsJudge(this);
        }
    }
}
