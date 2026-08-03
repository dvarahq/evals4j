package com.dvarahq.oss.evals4j.trajectory;

import com.fasterxml.jackson.databind.JsonNode;
import com.dvarahq.oss.evals4j.EvalRequest;
import com.dvarahq.oss.evals4j.Evaluator;
import com.dvarahq.oss.evals4j.internal.Json;
import com.dvarahq.oss.evals4j.judge.LlmAsJudge;
import com.dvarahq.oss.evals4j.message.MessageRenderer;
import com.dvarahq.oss.evals4j.message.Messages;
import com.dvarahq.oss.evals4j.prompt.FewShotExample;
import com.dvarahq.oss.evals4j.prompt.Prompts;
import com.dvarahq.oss.evals4j.result.EvaluatorResult;
import com.dvarahq.oss.evals4j.spi.EvalTracer;
import com.dvarahq.oss.evals4j.spi.JudgeModel;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * Judges a trajectory with an LLM rather than by matching against a reference.
 *
 * <p>Useful when there is no single correct path — the judge is asked whether the steps taken were
 * reasonable for the goal. Both trajectories are rendered with {@link MessageRenderer} before being
 * put into the prompt, which is the format the trajectory prompts were written against.
 *
 * <p>Port of OpenEvals' {@code create_trajectory_llm_as_judge}.
 */
public final class TrajectoryLlmAsJudge implements Evaluator {

    public static final String DEFAULT_FEEDBACK_KEY = "trajectory_accuracy";

    private final LlmAsJudge delegate;

    private TrajectoryLlmAsJudge(LlmAsJudge delegate) {
        this.delegate = delegate;
    }

    public static Builder builder() {
        return new Builder();
    }

    @Override
    public List<EvaluatorResult> evaluateAll(EvalRequest request) {
        return delegate.evaluateAll(renderTrajectories(request));
    }

    @Override
    public CompletableFuture<List<EvaluatorResult>> evaluateAllAsync(EvalRequest request) {
        // Rendering is local work, so only the judge call needs to be asynchronous.
        return delegate.evaluateAllAsync(renderTrajectories(request));
    }

    /** The key the score is recorded under. */
    public String feedbackKey() {
        return delegate.feedbackKey();
    }

    public String runName() {
        return delegate.runName();
    }

    /**
     * The judge's raw response, for use with a custom {@link Builder#outputSchema}. The trajectories
     * are rendered first, exactly as they are for {@link #evaluateAll}.
     */
    public JsonNode evaluateRaw(EvalRequest request) {
        return delegate.evaluateRaw(renderTrajectories(request));
    }

    /**
     * Renders the message lists to text before the judge sees them.
     *
     * <p>A missing reference renders as the empty string rather than being dropped, so
     * {@code TRAJECTORY_ACCURACY_PROMPT_WITH_REFERENCE} does not fail on a request that has none.
     */
    private static EvalRequest renderTrajectories(EvalRequest request) {
        EvalRequest.Builder rendered = request.toBuilder();
        rendered.outputs(MessageRenderer.render(Messages.normalize(request.outputs())));
        rendered.referenceOutputs(
                request.referenceOutputs() == null
                        ? ""
                        : MessageRenderer.render(Messages.normalize(request.referenceOutputs())));
        return rendered.build();
    }

    /** Builder for {@link TrajectoryLlmAsJudge}. */
    public static final class Builder {
        private String prompt = Prompts.TRAJECTORY_ACCURACY_PROMPT_WITH_REFERENCE;
        private String feedbackKey = DEFAULT_FEEDBACK_KEY;
        private JudgeModel model;
        private boolean continuous;
        private List<Double> choices;
        private boolean useReasoning = true;
        private final List<FewShotExample> fewShotExamples = new ArrayList<>();
        private String system;
        private JsonNode outputSchema;
        private EvalTracer tracer;

        /**
         * Defaults to {@link Prompts#TRAJECTORY_ACCURACY_PROMPT_WITH_REFERENCE}. Use
         * {@link Prompts#TRAJECTORY_ACCURACY_PROMPT} when there is no reference trajectory, or
         * {@link Prompts#TOOL_SELECTION_PROMPT} to judge tool choice specifically.
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

        /** A system message prepended to the prompt. */
        public Builder system(String system) {
            this.system = system;
            return this;
        }

        /**
         * Replaces the generated score schema with your own. The result is then only reachable via
         * {@link TrajectoryLlmAsJudge#evaluateRaw}, unless your schema also declares a {@code score}
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

        public Builder tracer(EvalTracer tracer) {
            this.tracer = tracer;
            return this;
        }

        public TrajectoryLlmAsJudge build() {
            LlmAsJudge.Builder judge = LlmAsJudge.builder()
                    .prompt(prompt)
                    .feedbackKey(feedbackKey)
                    .model(model)
                    .system(system)
                    .continuous(continuous)
                    .useReasoning(useReasoning)
                    .fewShotExamples(fewShotExamples)
                    .outputSchema(outputSchema)
                    .tracer(tracer);
            if (choices != null) {
                judge.choices(choices);
            }
            return new TrajectoryLlmAsJudge(judge.build());
        }
    }
}
