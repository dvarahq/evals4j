package io.github.grabdoc.evals4j.simulate;

import io.github.grabdoc.evals4j.message.ChatMessage;
import io.github.grabdoc.evals4j.result.EvaluatorResult;

import java.util.List;

/**
 * The outcome of a multi-turn simulation.
 *
 * @param trajectory the full conversation, user and app messages interleaved
 * @param evaluatorResults scores from the trajectory evaluators, empty if none were configured
 */
public record SimulationResult(List<ChatMessage> trajectory, List<EvaluatorResult> evaluatorResults) {

    public SimulationResult {
        trajectory = List.copyOf(trajectory);
        evaluatorResults = List.copyOf(evaluatorResults);
    }

    /** The number of user/app exchanges that took place. */
    public int turns() {
        return (int) trajectory.stream().filter(m -> ChatMessage.ROLE_USER.equals(m.role())).count();
    }
}
