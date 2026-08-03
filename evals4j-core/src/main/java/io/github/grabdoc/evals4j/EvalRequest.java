package io.github.grabdoc.evals4j;

import io.github.grabdoc.evals4j.message.Attachment;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The arguments to one evaluation.
 *
 * <p>OpenEvals evaluators are called with keyword-only arguments ({@code inputs=}, {@code outputs=},
 * {@code reference_outputs=}, plus arbitrary {@code **kwargs} that become prompt variables). Java has
 * no keyword arguments, so this parameter object plays that role — {@link #variables()} is the
 * {@code **kwargs} channel, filling placeholders such as {@code {context}} or {@code {rubric}}.
 *
 * @param inputs what the system under test was asked; may be {@code null}
 * @param outputs what it produced
 * @param referenceOutputs the expected result, when the evaluator needs one; may be {@code null}
 * @param variables extra prompt variables
 * @param attachments images or audio spliced in at {@code {attachments}}
 */
public record EvalRequest(
        Object inputs,
        Object outputs,
        Object referenceOutputs,
        Map<String, Object> variables,
        List<Attachment> attachments) {

    public EvalRequest {
        variables = variables == null ? Map.of() : Map.copyOf(variables);
        attachments = attachments == null ? List.of() : List.copyOf(attachments);
    }

    public static EvalRequest of(Object inputs, Object outputs) {
        return builder().inputs(inputs).outputs(outputs).build();
    }

    public static EvalRequest of(Object inputs, Object outputs, Object referenceOutputs) {
        return builder().inputs(inputs).outputs(outputs).referenceOutputs(referenceOutputs).build();
    }

    /** For evaluators that only look at outputs, such as the conversation-quality judges. */
    public static EvalRequest ofOutputs(Object outputs) {
        return builder().outputs(outputs).build();
    }

    public static Builder builder() {
        return new Builder();
    }

    public Builder toBuilder() {
        return new Builder()
                .inputs(inputs)
                .outputs(outputs)
                .referenceOutputs(referenceOutputs)
                .variables(variables)
                .attachments(attachments);
    }

    public static final class Builder {
        private Object inputs;
        private Object outputs;
        private Object referenceOutputs;
        private final Map<String, Object> variables = new LinkedHashMap<>();
        private final List<Attachment> attachments = new ArrayList<>();

        public Builder inputs(Object inputs) {
            this.inputs = inputs;
            return this;
        }

        public Builder outputs(Object outputs) {
            this.outputs = outputs;
            return this;
        }

        public Builder referenceOutputs(Object referenceOutputs) {
            this.referenceOutputs = referenceOutputs;
            return this;
        }

        /** Sets a prompt variable, e.g. {@code variable("context", retrievedDocs)}. */
        public Builder variable(String name, Object value) {
            this.variables.put(name, value);
            return this;
        }

        public Builder variables(Map<String, Object> variables) {
            if (variables != null) {
                this.variables.putAll(variables);
            }
            return this;
        }

        public Builder attachment(Attachment attachment) {
            this.attachments.add(attachment);
            return this;
        }

        public Builder attachments(List<Attachment> attachments) {
            if (attachments != null) {
                this.attachments.addAll(attachments);
            }
            return this;
        }

        public EvalRequest build() {
            return new EvalRequest(inputs, outputs, referenceOutputs, variables, attachments);
        }
    }
}
