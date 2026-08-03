package com.dvarahq.oss.evals4j.trajectory;

import java.util.Locale;

/**
 * How closely an agent's trajectory must match the reference.
 *
 * <p>All modes except {@link #STRICT} compare only the flattened sequence of tool calls — message
 * roles, prose content and the grouping of calls into messages are ignored. That is deliberate:
 * whether the agent explained itself in one message or three is not usually what you are testing.
 */
public enum TrajectoryMatchMode {

    /**
     * Same message count and role sequence, and the same tool calls within each message.
     *
     * <p>Message <em>content</em> is still not compared — only structure and tool calls. Tool calls
     * within a single message may be in any order.
     */
    STRICT,

    /** The same set of tool calls, in any order and any message grouping. */
    UNORDERED,

    /** Every tool call the agent made also appears in the reference. The agent may do less. */
    SUBSET,

    /** Every reference tool call was made by the agent. The agent may do more. */
    SUPERSET;

    /** The feedback key and run name this mode reports under, e.g. {@code trajectory_strict_match}. */
    public String feedbackKey() {
        return "trajectory_" + name().toLowerCase(Locale.ROOT) + "_match";
    }
}
