package io.github.grabdoc.evals4j.simulate;

import io.github.grabdoc.evals4j.EvalRequest;
import io.github.grabdoc.evals4j.Evaluator;
import io.github.grabdoc.evals4j.message.ChatMessage;
import io.github.grabdoc.evals4j.message.Messages;
import io.github.grabdoc.evals4j.result.EvaluatorResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.BiPredicate;

/**
 * Runs a conversation between a simulated user and the application, then scores the transcript.
 *
 * <pre>{@code
 * SimulationResult result = MultiturnSimulation.builder()
 *         .app((message, threadId) -> myAgent.chat(threadId, message.text()))
 *         .user(SimulatedUser.llm("You are a customer whose order never arrived.", judgeModel))
 *         .maxTurns(5)
 *         .stopWhen((trajectory, turn) -> lastMessageMentionsRefund(trajectory))
 *         .trajectoryEvaluator(taskCompletionJudge)
 *         .run();
 * }</pre>
 *
 * <p>Port of OpenEvals' {@code run_multiturn_simulation}.
 */
public final class MultiturnSimulation {

    private static final Logger log = LoggerFactory.getLogger(MultiturnSimulation.class);

    private final SimulatedApp app;
    private final SimulatedUser user;
    private final Integer maxTurns;
    private final BiPredicate<List<ChatMessage>, Integer> stoppingCondition;
    private final List<Evaluator> trajectoryEvaluators;
    private final Object referenceOutputs;
    private final String threadId;

    private MultiturnSimulation(Builder builder) {
        this.app = Objects.requireNonNull(builder.app, "An app is required — call .app(...)");
        this.user = Objects.requireNonNull(builder.user, "A user is required — call .user(...)");
        this.maxTurns = builder.maxTurns;
        this.stoppingCondition = builder.stoppingCondition;
        this.trajectoryEvaluators = List.copyOf(builder.trajectoryEvaluators);
        this.referenceOutputs = builder.referenceOutputs;
        this.threadId = builder.threadId == null ? UUID.randomUUID().toString() : builder.threadId;

        if (maxTurns == null && stoppingCondition == null) {
            throw new IllegalArgumentException(
                    "At least one of maxTurns or stopWhen must be provided, otherwise the simulation "
                            + "would never end.");
        }
    }

    public static Builder builder() {
        return new Builder();
    }

    /**
     * Runs the conversation to completion and scores it.
     *
     * <p>A turn is one user message plus the app's reply. {@code maxTurns} is checked before the user
     * speaks and the stopping condition after the app answers, so a run ends on a complete exchange
     * rather than mid-turn.
     */
    public SimulationResult run() {
        List<ChatMessage> trajectory = new ArrayList<>();
        Set<String> seenIds = new LinkedHashSet<>();
        int turnCounter = 0;

        while (true) {
            if (maxTurns != null && turnCounter >= maxTurns) {
                break;
            }

            ChatMessage userMessage = firstOf(
                    user.respond(List.copyOf(trajectory), turnCounter, threadId),
                    "the simulated user");
            append(trajectory, seenIds, List.of(userMessage));

            Object appResponse = app.respond(userMessage, threadId);
            append(trajectory, seenIds, normalize(appResponse, "the app"));
            turnCounter++;

            if (stoppingCondition != null && stoppingCondition.test(List.copyOf(trajectory), turnCounter)) {
                break;
            }
        }

        List<EvaluatorResult> results = new ArrayList<>();
        for (Evaluator evaluator : trajectoryEvaluators) {
            try {
                results.addAll(evaluator.evaluateAll(
                        EvalRequest.of(null, List.copyOf(trajectory), referenceOutputs)));
            } catch (RuntimeException e) {
                // One failing evaluator should not discard the transcript or the other scores.
                log.warn("Trajectory evaluator {} failed", evaluator, e);
            }
        }
        return new SimulationResult(trajectory, results);
    }

    /**
     * Appends messages, skipping ones already present by id.
     *
     * <p>An app that returns its whole history each turn would otherwise duplicate every message.
     * Messages without an id get a fresh one and so always append — the same trade-off upstream makes.
     */
    private static void append(List<ChatMessage> trajectory, Set<String> seenIds, List<ChatMessage> updates) {
        for (ChatMessage update : updates) {
            ChatMessage withId = Messages.withGeneratedId(update);
            if (seenIds.add(withId.id())) {
                trajectory.add(withId);
            }
        }
    }

    private static List<ChatMessage> normalize(Object response, String source) {
        try {
            List<ChatMessage> messages = Messages.normalize(response);
            if (messages.isEmpty()) {
                throw new IllegalArgumentException("no messages");
            }
            return messages;
        } catch (RuntimeException e) {
            throw new IllegalArgumentException(
                    "Received an unexpected update from " + source + ": " + response
                            + ". Expected a message, a list of messages, or a map with a 'messages' key.",
                    e);
        }
    }

    private static ChatMessage firstOf(Object response, String source) {
        return normalize(response, source).get(0);
    }

    /** Builder for {@link MultiturnSimulation}. */
    public static final class Builder {
        private SimulatedApp app;
        private SimulatedUser user;
        private Integer maxTurns;
        private BiPredicate<List<ChatMessage>, Integer> stoppingCondition;
        private final List<Evaluator> trajectoryEvaluators = new ArrayList<>();
        private Object referenceOutputs;
        private String threadId;

        public Builder app(SimulatedApp app) {
            this.app = app;
            return this;
        }

        public Builder user(SimulatedUser user) {
            this.user = user;
            return this;
        }

        /** A fixed script of user messages. Sets {@code maxTurns} to the script length if unset. */
        public Builder user(List<String> scriptedMessages) {
            this.user = SimulatedUser.scripted(scriptedMessages);
            if (this.maxTurns == null) {
                this.maxTurns = scriptedMessages.size();
            }
            return this;
        }

        /** A hard cap on exchanges. Required unless a stopping condition is set. */
        public Builder maxTurns(int maxTurns) {
            this.maxTurns = maxTurns;
            return this;
        }

        /** Ends the conversation early when it returns true, checked after each complete exchange. */
        public Builder stopWhen(BiPredicate<List<ChatMessage>, Integer> stoppingCondition) {
            this.stoppingCondition = stoppingCondition;
            return this;
        }

        /** Scores the finished transcript. Evaluator failures are logged, not thrown. */
        public Builder trajectoryEvaluator(Evaluator evaluator) {
            this.trajectoryEvaluators.add(evaluator);
            return this;
        }

        public Builder trajectoryEvaluators(List<Evaluator> evaluators) {
            this.trajectoryEvaluators.addAll(evaluators);
            return this;
        }

        /** Passed to the trajectory evaluators as their reference. */
        public Builder referenceOutputs(Object referenceOutputs) {
            this.referenceOutputs = referenceOutputs;
            return this;
        }

        /** The conversation id handed to the app each turn. Generated when unset. */
        public Builder threadId(String threadId) {
            this.threadId = threadId;
            return this;
        }

        public MultiturnSimulation build() {
            return new MultiturnSimulation(this);
        }

        public SimulationResult run() {
            return build().run();
        }
    }
}
