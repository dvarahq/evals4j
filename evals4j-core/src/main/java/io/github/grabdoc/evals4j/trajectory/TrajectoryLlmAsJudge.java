package io.github.grabdoc.evals4j.trajectory;

import io.github.grabdoc.evals4j.EvalRequest;
import io.github.grabdoc.evals4j.Evaluator;
import io.github.grabdoc.evals4j.judge.LlmAsJudge;
import io.github.grabdoc.evals4j.message.MessageRenderer;
import io.github.grabdoc.evals4j.message.Messages;
import io.github.grabdoc.evals4j.prompt.FewShotExample;
import io.github.grabdoc.evals4j.prompt.Prompts;
import io.github.grabdoc.evals4j.result.EvaluatorResult;
import io.github.grabdoc.evals4j.spi.EvalTracer;
import io.github.grabdoc.evals4j.spi.JudgeModel;

import java.util.ArrayList;
import java.util.List;

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

        public Builder useReasoning(boolean useReasoning) {
            this.useReasoning = useReasoning;
            return this;
        }

        public Builder fewShotExample(FewShotExample example) {
            this.fewShotExamples.add(example);
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
                    .continuous(continuous)
                    .useReasoning(useReasoning)
                    .fewShotExamples(fewShotExamples)
                    .tracer(tracer);
            if (choices != null) {
                judge.choices(choices);
            }
            return new TrajectoryLlmAsJudge(judge.build());
        }
    }
}
