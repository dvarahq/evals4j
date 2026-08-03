package com.dvarahq.oss.evals4j.trajectory;

import com.dvarahq.oss.evals4j.EvalRequest;
import com.dvarahq.oss.evals4j.Evaluator;
import com.dvarahq.oss.evals4j.ScorerOutput;
import com.dvarahq.oss.evals4j.ScorerRunner;
import com.dvarahq.oss.evals4j.internal.Json;
import com.dvarahq.oss.evals4j.message.ChatMessage;
import com.dvarahq.oss.evals4j.message.Messages;
import com.dvarahq.oss.evals4j.message.ToolCall;
import com.dvarahq.oss.evals4j.result.EvaluatorResult;
import com.dvarahq.oss.evals4j.spi.EvalTracer;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Compares an agent's tool-call trajectory against a reference, without an LLM.
 *
 * <pre>{@code
 * Evaluator evaluator = TrajectoryMatchEvaluator.builder()
 *         .matchMode(TrajectoryMatchMode.SUPERSET)
 *         .toolArgsMatchMode(ToolArgsMatchMode.IGNORE)
 *         .override("book_flight", ToolArgsMatchers.onKeys("flight_no"))
 *         .build();
 * }</pre>
 *
 * <p>Port of OpenEvals' {@code create_trajectory_match_evaluator}.
 */
public final class TrajectoryMatchEvaluator implements Evaluator {

    private final TrajectoryMatchMode matchMode;
    private final ToolArgsMatchMode toolArgsMatchMode;
    private final Map<String, ToolArgsMatcher> overrides;
    private final EvalTracer tracer;

    private TrajectoryMatchEvaluator(Builder builder) {
        this.matchMode = builder.matchMode;
        this.toolArgsMatchMode = builder.toolArgsMatchMode;
        this.overrides = Map.copyOf(builder.overrides);
        this.tracer = builder.tracer == null ? EvalTracer.NO_OP : builder.tracer;
    }

    public static Builder builder() {
        return new Builder();
    }

    public static TrajectoryMatchEvaluator create(TrajectoryMatchMode matchMode) {
        return builder().matchMode(matchMode).build();
    }

    public String feedbackKey() {
        return matchMode.feedbackKey();
    }

    @Override
    public List<EvaluatorResult> evaluateAll(EvalRequest request) {
        String key = matchMode.feedbackKey();
        return ScorerRunner.run(key, key, this::score, request, tracer);
    }

    private ScorerOutput score(EvalRequest request) {
        if (request.outputs() == null || request.referenceOutputs() == null) {
            throw new IllegalArgumentException(
                    "Trajectory " + matchMode.name().toLowerCase(java.util.Locale.ROOT)
                            + " match requires both outputs and reference_outputs");
        }
        List<ChatMessage> outputs = Messages.normalize(request.outputs());
        List<ChatMessage> reference = Messages.normalize(request.referenceOutputs());

        boolean matched = switch (matchMode) {
            case STRICT -> strictMatch(outputs, reference);
            case UNORDERED -> isSuperset(outputs, reference) && isSuperset(reference, outputs);
            case SUBSET -> isSuperset(reference, outputs);
            case SUPERSET -> isSuperset(outputs, reference);
        };
        return ScorerOutput.of(matched);
    }

    private ToolArgsMatcher matcherFor(String toolName) {
        ToolArgsMatcher override = overrides.get(toolName);
        return override != null ? override : ToolArgsMatchers.of(toolArgsMatchMode);
    }

    /**
     * True when every tool call in {@code reference} has a distinct counterpart in {@code outputs}.
     *
     * <p>Greedy first-fit, matching upstream: each reference call takes the first not-yet-consumed
     * output call with the same name and matching arguments. This is not maximum bipartite matching,
     * and on adversarial inputs a maximum matching could succeed where this fails — the greedy
     * behaviour is reproduced on purpose so scores agree with OpenEvals.
     */
    private boolean isSuperset(List<ChatMessage> outputs, List<ChatMessage> reference) {
        List<ToolCall> outputCalls = flattenToolCalls(outputs);
        List<ToolCall> referenceCalls = flattenToolCalls(reference);

        Set<Integer> consumed = new HashSet<>();
        for (ToolCall referenceCall : referenceCalls) {
            boolean found = false;
            ToolArgsMatcher matcher = matcherFor(referenceCall.name());
            for (int i = 0; i < outputCalls.size(); i++) {
                if (consumed.contains(i)) {
                    continue;
                }
                ToolCall outputCall = outputCalls.get(i);
                if (!outputCall.name().equals(referenceCall.name())) {
                    continue;
                }
                if (matcher.matches(argsOf(outputCall), argsOf(referenceCall))) {
                    consumed.add(i);
                    found = true;
                    break;
                }
            }
            if (!found) {
                return false;
            }
        }
        return true;
    }

    /**
     * Positional message comparison.
     *
     * <p>Requires equal message counts and identical role sequences. Content is never compared —
     * "SF" and "San Francisco" in prose both pass — while tool calls within one message are matched
     * order-insensitively and their ids are ignored.
     */
    private boolean strictMatch(List<ChatMessage> outputs, List<ChatMessage> reference) {
        if (outputs.size() != reference.size()) {
            return false;
        }
        for (int i = 0; i < outputs.size(); i++) {
            ChatMessage output = outputs.get(i);
            ChatMessage expected = reference.get(i);
            if (!output.role().equals(expected.role())) {
                return false;
            }
            if (output.hasToolCalls() != expected.hasToolCalls()) {
                return false;
            }
            if (output.hasToolCalls() && !toolCallsMatch(output.toolCalls(), expected.toolCalls())) {
                return false;
            }
        }
        return true;
    }

    private boolean toolCallsMatch(List<ToolCall> outputCalls, List<ToolCall> referenceCalls) {
        if (outputCalls.size() != referenceCalls.size()) {
            return false;
        }
        boolean[] seen = new boolean[referenceCalls.size()];
        for (ToolCall outputCall : outputCalls) {
            boolean found = false;
            ToolArgsMatcher matcher = matcherFor(outputCall.name());
            for (int i = 0; i < referenceCalls.size(); i++) {
                if (seen[i] || !referenceCalls.get(i).name().equals(outputCall.name())) {
                    continue;
                }
                if (matcher.matches(argsOf(outputCall), argsOf(referenceCalls.get(i)))) {
                    seen[i] = true;
                    found = true;
                    break;
                }
            }
            if (!found) {
                return false;
            }
        }
        return true;
    }

    private static List<ToolCall> flattenToolCalls(List<ChatMessage> messages) {
        List<ToolCall> calls = new ArrayList<>();
        for (ChatMessage message : messages) {
            calls.addAll(message.toolCalls());
        }
        return calls;
    }

    private static Map<String, Object> argsOf(ToolCall call) {
        return Json.parseArguments(call.arguments());
    }

    /** Builder for {@link TrajectoryMatchEvaluator}. */
    public static final class Builder {
        private TrajectoryMatchMode matchMode = TrajectoryMatchMode.STRICT;
        private ToolArgsMatchMode toolArgsMatchMode = ToolArgsMatchMode.EXACT;
        private final Map<String, ToolArgsMatcher> overrides = new HashMap<>();
        private EvalTracer tracer;

        public Builder matchMode(TrajectoryMatchMode matchMode) {
            this.matchMode = java.util.Objects.requireNonNull(matchMode, "matchMode");
            return this;
        }

        public Builder toolArgsMatchMode(ToolArgsMatchMode toolArgsMatchMode) {
            this.toolArgsMatchMode = java.util.Objects.requireNonNull(toolArgsMatchMode, "toolArgsMatchMode");
            return this;
        }

        /** Overrides argument matching for one tool by name. */
        public Builder override(String toolName, ToolArgsMatcher matcher) {
            this.overrides.put(toolName, matcher);
            return this;
        }

        public Builder override(String toolName, ToolArgsMatchMode mode) {
            return override(toolName, ToolArgsMatchers.of(mode));
        }

        /** Matches only the given dotted argument paths for this tool. */
        public Builder overrideOnKeys(String toolName, String... keyPaths) {
            return override(toolName, ToolArgsMatchers.onKeys(keyPaths));
        }

        public Builder tracer(EvalTracer tracer) {
            this.tracer = tracer;
            return this;
        }

        public TrajectoryMatchEvaluator build() {
            return new TrajectoryMatchEvaluator(this);
        }
    }
}
