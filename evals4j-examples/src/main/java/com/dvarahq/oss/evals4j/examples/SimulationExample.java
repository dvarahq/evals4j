package com.dvarahq.oss.evals4j.examples;

import com.dvarahq.oss.evals4j.judge.LlmAsJudge;
import com.dvarahq.oss.evals4j.message.ChatMessage;
import com.dvarahq.oss.evals4j.prompt.Prompts;
import com.dvarahq.oss.evals4j.result.EvaluatorResult;
import com.dvarahq.oss.evals4j.simulate.MultiturnSimulation;
import com.dvarahq.oss.evals4j.simulate.SimulatedUser;
import com.dvarahq.oss.evals4j.simulate.SimulationResult;
import com.dvarahq.oss.evals4j.spi.JudgeModel;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Driving a support agent through a whole conversation, then scoring the transcript.
 *
 * <p>Single-turn evals miss the failures that only appear over several turns: forgetting what the
 * user said two messages ago, or never actually resolving anything. The simulated user reacts to
 * whatever your app says, so the conversation is not a fixed script.
 *
 * <pre>
 * OPENAI_API_KEY=... ./mvnw -q -pl evals4j-examples exec:java \
 *     -Dexec.mainClass=com.dvarahq.oss.evals4j.examples.SimulationExample
 * </pre>
 */
public final class SimulationExample {

    /**
     * Stands in for a real agent.
     *
     * <p>It keeps history per thread id, which is the contract the simulator expects — the app is
     * handed only the newest message, so this exercises the app's own memory rather than replaying a
     * transcript into it.
     */
    private static final Map<String, Integer> turnsPerThread = new HashMap<>();

    private static ChatMessage supportAgent(ChatMessage userMessage, String threadId) {
        int turn = turnsPerThread.merge(threadId, 1, Integer::sum);
        String text = userMessage.text().toLowerCase(Locale.ROOT);

        if (turn == 1) {
            return ChatMessage.assistant(
                    "I'm sorry to hear that. Could you give me your order number?");
        }
        if (text.matches(".*\\b(ord|order)[- ]?\\d+.*") || text.matches(".*\\d{4,}.*")) {
            return ChatMessage.assistant(
                    "Thank you. I've found the order and issued a full refund — it will reach your "
                            + "account within three working days. Anything else I can help with?");
        }
        return ChatMessage.assistant(
                "I still need the order number from your confirmation email to look this up.");
    }

    public static void main(String[] args) {
        JudgeModel judge = Models.springAiJudge();

        SimulationResult result = MultiturnSimulation.builder()
                .app(SimulationExample::supportAgent)
                .user(SimulatedUser.llm(
                        """
                        You are a customer whose order never arrived and you want a refund.
                        Your order number is ORD-4471, but only give it when asked.
                        Keep your messages to one or two sentences.
                        """,
                        judge))
                .maxTurns(4)
                // Stop as soon as the agent says it has refunded — no point burning further turns.
                .stopWhen((trajectory, turn) ->
                        trajectory.get(trajectory.size() - 1).text().toLowerCase(Locale.ROOT)
                                .contains("refund"))
                .trajectoryEvaluator(LlmAsJudge.builder()
                        .prompt(Prompts.TASK_COMPLETION_PROMPT)
                        .feedbackKey("task_completion")
                        .model(judge)
                        .build())
                .trajectoryEvaluator(LlmAsJudge.builder()
                        .prompt(Prompts.PERCEIVED_ERROR_PROMPT)
                        .feedbackKey("perceived_error")
                        .model(judge)
                        .build())
                .trajectoryEvaluator(LlmAsJudge.builder()
                        .prompt(Prompts.AGENT_TONE_PROMPT)
                        .feedbackKey("agent_tone")
                        .model(judge)
                        .build())
                .build()
                .run();

        System.out.println("Transcript:");
        for (ChatMessage message : result.trajectory()) {
            System.out.printf("  %-10s %s%n", message.role() + ":", message.text());
        }

        System.out.printf("%n%d turns%n%nScores:%n", result.turns());
        for (EvaluatorResult score : result.evaluatorResults()) {
            Models.print(score);
        }
    }
}
