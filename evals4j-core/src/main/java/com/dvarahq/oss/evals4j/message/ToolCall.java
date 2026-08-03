package com.dvarahq.oss.evals4j.message;

import java.util.Objects;

/**
 * A tool call on an assistant message, in OpenAI wire shape.
 *
 * <p>{@code arguments} is a JSON <em>string</em>, matching the wire format — comparisons parse it
 * first. Following OpenEvals, a missing {@code id} normalizes to the empty string rather than
 * failing, so hand-written reference trajectories need not invent ids.
 */
public record ToolCall(String id, String name, String arguments) {

    public ToolCall {
        id = id == null ? "" : id;
        Objects.requireNonNull(name, "name");
        arguments = arguments == null ? "{}" : arguments;
    }

    public static ToolCall of(String name, String argumentsJson) {
        return new ToolCall("", name, argumentsJson);
    }
}
