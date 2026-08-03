package io.github.grabdoc.evals4j.examples;

import io.github.grabdoc.evals4j.judge.LlmAsJudge;
import io.github.grabdoc.evals4j.prompt.Prompts;
import io.github.grabdoc.evals4j.result.EvaluatorResult;
import io.github.grabdoc.evals4j.spi.JudgeModel;

/**
 * The smallest useful eval: score one answer for conciseness and correctness.
 *
 * <pre>
 * OPENAI_API_KEY=... ./mvnw -q -pl evals4j-examples exec:java \
 *     -Dexec.mainClass=io.github.grabdoc.evals4j.examples.QuickstartExample
 * </pre>
 */
public final class QuickstartExample {

    public static void main(String[] args) {
        JudgeModel judge = Models.springAiJudge();

        String question = "How is the weather in San Francisco?";
        String verbose = "Thank you so much for reaching out with your question! I really appreciate "
                + "you taking the time to ask. The weather in the beautiful city of San Francisco is, "
                + "as of right now, sunny, with a temperature of approximately 90 degrees Fahrenheit.";
        String terse = "Sunny and 90°F.";

        LlmAsJudge conciseness = LlmAsJudge.builder()
                .prompt(Prompts.CONCISENESS_PROMPT)
                .feedbackKey("conciseness")
                .model(judge)
                .build();

        System.out.println("Verbose answer:");
        Models.print(conciseness.evaluate(question, verbose));

        System.out.println("\nTerse answer:");
        Models.print(conciseness.evaluate(question, terse));

        // Correctness needs something to compare against, so it takes a third argument.
        LlmAsJudge correctness = LlmAsJudge.builder()
                .prompt(Prompts.CORRECTNESS_PROMPT)
                .feedbackKey("correctness")
                .model(judge)
                .build();

        System.out.println("\nCorrectness against a reference:");
        Models.print(correctness.evaluate(
                "What is the capital of France?", "The capital of France is Lyon.", "Paris"));

        // A 0-1 score says more than pass/fail when you are tracking a trend across releases.
        LlmAsJudge gradedCorrectness = LlmAsJudge.builder()
                .prompt(Prompts.CORRECTNESS_PROMPT)
                .feedbackKey("correctness")
                .model(judge)
                .continuous(true)
                .build();

        System.out.println("\nSame answer, scored 0.0-1.0:");
        EvaluatorResult graded = gradedCorrectness.evaluate(
                "Name two French cities.", "Paris and Lyon are in Germany.", "Paris and Lyon");
        Models.print(graded);
        System.out.printf("%nscore as a double: %.2f%n", graded.score().doubleValue());
    }
}
