package io.github.grabdoc.evals4j.code;

import io.github.grabdoc.evals4j.EvalRequest;
import io.github.grabdoc.evals4j.Evaluator;
import io.github.grabdoc.evals4j.ScorerRunner;
import io.github.grabdoc.evals4j.judge.LlmAsJudge;
import io.github.grabdoc.evals4j.prompt.FewShotExample;
import io.github.grabdoc.evals4j.prompt.Prompts;
import io.github.grabdoc.evals4j.result.EvaluatorResult;
import io.github.grabdoc.evals4j.spi.EvalTracer;
import io.github.grabdoc.evals4j.spi.JudgeModel;

import java.util.ArrayList;
import java.util.List;
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

        public Builder useReasoning(boolean useReasoning) {
            this.useReasoning = useReasoning;
            return this;
        }

        public Builder fewShotExample(FewShotExample example) {
            this.fewShotExamples.add(example);
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
