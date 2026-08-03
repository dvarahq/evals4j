package com.dvarahq.oss.evals4j.examples;

import com.dvarahq.oss.evals4j.EvalRequest;
import com.dvarahq.oss.evals4j.message.ChatMessage;
import com.dvarahq.oss.evals4j.message.ToolCall;
import com.dvarahq.oss.evals4j.trajectory.ToolArgsMatchMode;
import com.dvarahq.oss.evals4j.trajectory.TrajectoryMatchEvaluator;
import com.dvarahq.oss.evals4j.trajectory.TrajectoryMatchMode;

import java.util.List;

/**
 * Checking which tools an agent called, and with what arguments — no model, no API key.
 *
 * <p>Trajectory matching is deterministic and free, which makes it the cheapest agent regression
 * test you can run: it catches "the agent stopped calling the lookup tool" long before an
 * LLM-as-judge suite would, and it never flakes.
 *
 * <pre>
 * ./mvnw -q -pl evals4j-examples exec:java \
 *     -Dexec.mainClass=com.dvarahq.oss.evals4j.examples.AgentTrajectoryExample
 * </pre>
 */
public final class AgentTrajectoryExample {

    public static void main(String[] args) {
        // What the agent actually did: looked up the flight, then quoted a price.
        List<ChatMessage> actual = List.of(
                ChatMessage.user("What time is flight LX0112, and what does an upgrade cost?"),
                ChatMessage.assistant("", List.of(
                        new ToolCall("1", "get_flight_info", "{\"flight_no\":\"LX0112\",\"verbose\":true}"),
                        new ToolCall("2", "price_upgrade",
                                "{\"flight_no\":\"LX0112\",\"requested_at\":\"2026-08-03T09:15:00Z\"}"))),
                ChatMessage.tool("Departs 10:00.", "1"),
                ChatMessage.tool("Upgrade costs 220 CHF.", "2"),
                ChatMessage.assistant("Your flight leaves at 10:00 and an upgrade is 220 CHF."));

        // What we expected. Note the reference has no `verbose` flag, and a different timestamp.
        List<ChatMessage> expected = List.of(
                ChatMessage.user("What time is flight LX0112, and what does an upgrade cost?"),
                ChatMessage.assistant("", List.of(
                        new ToolCall("a", "get_flight_info", "{\"flight_no\":\"LX0112\"}"),
                        new ToolCall("b", "price_upgrade",
                                "{\"flight_no\":\"LX0112\",\"requested_at\":\"2026-01-01T00:00:00Z\"}"))),
                ChatMessage.tool("Departs 10:00.", "a"),
                ChatMessage.tool("Upgrade costs 220 CHF.", "b"),
                ChatMessage.assistant("It departs at 10:00; the upgrade is 220 CHF."));

        EvalRequest request = EvalRequest.of(null, actual, expected);

        System.out.println("Exact argument matching — fails on the extra flag and the timestamp:");
        for (TrajectoryMatchMode mode : TrajectoryMatchMode.values()) {
            System.out.printf("  %-10s %s%n", mode, TrajectoryMatchEvaluator.builder()
                    .matchMode(mode)
                    .toolArgsMatchMode(ToolArgsMatchMode.EXACT)
                    .build()
                    .evaluate(request)
                    .score());
        }

        // SUPERSET on arguments: every reference argument must appear, extras are allowed.
        // That absorbs the `verbose` flag but not the differing timestamp.
        System.out.println("\nSuperset argument matching — extras allowed, but the timestamp still differs:");
        System.out.printf("  %-10s %s%n", "SUPERSET", TrajectoryMatchEvaluator.builder()
                .matchMode(TrajectoryMatchMode.SUPERSET)
                .toolArgsMatchMode(ToolArgsMatchMode.SUPERSET)
                .build()
                .evaluate(request)
                .score());

        // The realistic configuration: pin the argument that matters per tool and ignore the rest.
        // A wall-clock timestamp should never fail a test.
        System.out.println("\nPer-tool overrides — pin flight_no, ignore incidental arguments:");
        System.out.printf("  %-10s %s%n", "SUPERSET", TrajectoryMatchEvaluator.builder()
                .matchMode(TrajectoryMatchMode.SUPERSET)
                .toolArgsMatchMode(ToolArgsMatchMode.EXACT)
                .overrideOnKeys("get_flight_info", "flight_no")
                .overrideOnKeys("price_upgrade", "flight_no")
                .build()
                .evaluate(request)
                .score());

        // Catching a regression: the agent stops calling one of the tools.
        List<ChatMessage> regressed = List.of(
                ChatMessage.user("What time is flight LX0112, and what does an upgrade cost?"),
                ChatMessage.assistant("", List.of(
                        new ToolCall("1", "get_flight_info", "{\"flight_no\":\"LX0112\"}"))),
                ChatMessage.tool("Departs 10:00.", "1"),
                ChatMessage.assistant("It leaves at 10:00. Upgrades are usually around 200 CHF."));

        System.out.println("\nAgent skipped the pricing tool and guessed instead:");
        System.out.printf("  %-10s %s  <- the regression this test exists to catch%n", "SUPERSET",
                TrajectoryMatchEvaluator.builder()
                        .matchMode(TrajectoryMatchMode.SUPERSET)
                        .toolArgsMatchMode(ToolArgsMatchMode.IGNORE)
                        .build()
                        .evaluate(EvalRequest.of(null, regressed, expected))
                        .score());
    }
}
