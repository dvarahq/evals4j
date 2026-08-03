package io.github.grabdoc.evals4j.examples;

import io.github.grabdoc.evals4j.EvalRequest;
import io.github.grabdoc.evals4j.judge.LlmAsJudge;
import io.github.grabdoc.evals4j.prompt.Prompts;
import io.github.grabdoc.evals4j.spi.JudgeModel;

import java.util.List;

/**
 * Evaluating a RAG pipeline, using LangChain4j as the judge.
 *
 * <p>RAG fails in two distinct places, and one score cannot tell them apart: the retriever can fetch
 * the wrong documents, or the generator can ignore the right ones. These four evaluators separate
 * those — a low retrieval-relevance score points at the index, a low groundedness score at the
 * prompt.
 *
 * <pre>
 * OPENAI_API_KEY=... ./mvnw -q -pl evals4j-examples exec:java \
 *     -Dexec.mainClass=io.github.grabdoc.evals4j.examples.RagExample
 * </pre>
 */
public final class RagExample {

    public static void main(String[] args) {
        JudgeModel judge = Models.langChain4jJudge();

        String question = "What is the population of Paris?";

        // Pretend these came back from your vector store.
        List<String> retrieved = List.of(
                "Paris is the capital and most populous city of France.",
                "The population of Paris was 2,102,650 in January 2023.",
                "The Eiffel Tower was completed in 1889.");
        String context = String.join("\n", retrieved);

        // The answer smuggles in a number that is not in the context.
        String answer = "Paris has a population of about 2.1 million, and it is growing by 5% a year.";

        String reference = "About 2.1 million.";

        System.out.println("1. Retrieval relevance — did we fetch the right documents?");
        Models.print(LlmAsJudge.builder()
                .prompt(Prompts.RAG_RETRIEVAL_RELEVANCE_PROMPT)
                .feedbackKey("retrieval_relevance")
                .model(judge)
                .continuous(true)
                .build()
                .evaluate(EvalRequest.builder().inputs(question).variable("context", context).build()));

        System.out.println("\n2. Groundedness — is the answer supported by what we fetched?");
        // Expect this one to fail: the growth rate appears nowhere in the context.
        Models.print(LlmAsJudge.builder()
                .prompt(Prompts.RAG_GROUNDEDNESS_PROMPT)
                .feedbackKey("groundedness")
                .model(judge)
                .build()
                .evaluate(EvalRequest.builder().outputs(answer).variable("context", context).build()));

        System.out.println("\n3. Helpfulness — does the answer address the question at all?");
        Models.print(LlmAsJudge.builder()
                .prompt(Prompts.RAG_HELPFULNESS_PROMPT)
                .feedbackKey("helpfulness")
                .model(judge)
                .build()
                .evaluate(question, answer));

        System.out.println("\n4. Correctness — does it match the expected answer?");
        Models.print(LlmAsJudge.builder()
                .prompt(Prompts.CORRECTNESS_PROMPT)
                .feedbackKey("correctness")
                .model(judge)
                .build()
                .evaluate(question, answer, reference));

        System.out.println("\n5. Hallucination — a dedicated check, given a reference.");
        Models.print(LlmAsJudge.builder()
                .prompt(Prompts.HALLUCINATION_PROMPT)
                .feedbackKey("hallucination")
                .model(judge)
                .build()
                .evaluate(EvalRequest.builder()
                        .inputs(question)
                        .outputs(answer)
                        .referenceOutputs(reference)
                        .variable("context", context)
                        .build()));
    }
}
