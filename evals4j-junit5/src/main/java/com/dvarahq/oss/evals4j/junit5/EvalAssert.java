package com.dvarahq.oss.evals4j.junit5;

import com.dvarahq.oss.evals4j.result.EvaluatorResult;
import org.opentest4j.AssertionFailedError;

import java.util.List;

/**
 * Assertions for eval results, so an eval suite reads like a test suite.
 *
 * <pre>{@code
 * EvalAssert.assertPassed(conciseness.evaluate(question, answer));
 * EvalAssert.assertScoreAtLeast(0.8, correctness.evaluate(question, answer, reference));
 * }</pre>
 *
 * <p>Failure messages include the judge's reasoning, which is the whole point of asserting on an LLM
 * score — "expected 0.8 but was 0.4" is not actionable, but the reasoning behind the 0.4 is.
 */
public final class EvalAssert {

    private EvalAssert() {}

    /** Fails unless the result scored true (or 1.0). */
    public static void assertPassed(EvaluatorResult result) {
        if (!result.passed()) {
            throw new AssertionFailedError(describe(result, "expected to pass"));
        }
    }

    /** Fails unless the result scored false (or below 1.0). */
    public static void assertFailed(EvaluatorResult result) {
        if (result.passed()) {
            throw new AssertionFailedError(describe(result, "expected to fail"));
        }
    }

    /** Fails unless the score is at least {@code minimum}. */
    public static void assertScoreAtLeast(double minimum, EvaluatorResult result) {
        if (result.score().doubleValue() < minimum) {
            throw new AssertionFailedError(describe(result, "expected a score of at least " + minimum));
        }
    }

    /** Fails unless the score is at most {@code maximum}. Useful for toxicity and laziness checks. */
    public static void assertScoreAtMost(double maximum, EvaluatorResult result) {
        if (result.score().doubleValue() > maximum) {
            throw new AssertionFailedError(describe(result, "expected a score of at most " + maximum));
        }
    }

    /** Fails unless every result passed, reporting all the failures rather than just the first. */
    public static void assertAllPassed(List<EvaluatorResult> results) {
        List<EvaluatorResult> failures = results.stream().filter(result -> !result.passed()).toList();
        if (!failures.isEmpty()) {
            StringBuilder message = new StringBuilder(
                    failures.size() + " of " + results.size() + " evaluations failed:\n");
            for (EvaluatorResult failure : failures) {
                message.append("  - ").append(describe(failure, "failed")).append('\n');
            }
            throw new AssertionFailedError(message.toString());
        }
    }

    private static String describe(EvaluatorResult result, String expectation) {
        StringBuilder message = new StringBuilder(result.key())
                .append(": ")
                .append(expectation)
                .append(", but scored ")
                .append(result.score());
        if (result.comment() != null && !result.comment().isBlank()) {
            message.append("\n  reasoning: ").append(result.comment());
        }
        if (!result.metadata().isEmpty()) {
            message.append("\n  metadata: ").append(result.metadata());
        }
        return message.toString();
    }
}
