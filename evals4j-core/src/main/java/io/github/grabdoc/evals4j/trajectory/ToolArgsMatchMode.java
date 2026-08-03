package io.github.grabdoc.evals4j.trajectory;

/**
 * How strictly a tool call's arguments must match the reference.
 *
 * <p>Directionality is from the output's point of view: {@link #SUBSET} means the output's arguments
 * are a subset of the reference's, {@link #SUPERSET} means they are a superset.
 */
public enum ToolArgsMatchMode {

    /** Argument maps must be equal. */
    EXACT,

    /** Arguments are not compared; only that the tool was called. */
    IGNORE,

    /** Every output argument must appear in the reference with the same value. Extra reference args are fine. */
    SUBSET,

    /** Every reference argument must appear in the output with the same value. Extra output args are fine. */
    SUPERSET
}
