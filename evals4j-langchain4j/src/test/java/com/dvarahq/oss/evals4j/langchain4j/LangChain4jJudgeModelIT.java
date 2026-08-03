package com.dvarahq.oss.evals4j.langchain4j;

import dev.langchain4j.model.openai.OpenAiChatModel;
import com.dvarahq.oss.evals4j.EvalRequest;
import com.dvarahq.oss.evals4j.judge.LlmAsJudge;
import com.dvarahq.oss.evals4j.prompt.Prompts;
import com.dvarahq.oss.evals4j.result.EvaluatorResult;
import com.dvarahq.oss.evals4j.spi.JudgeModel;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end checks against a real model, through LangChain4j.
 *
 * <p>Run with {@code ./mvnw -Pit verify} and {@code OPENAI_API_KEY} set. Skipped otherwise.
 *
 * <p>Assertions are deliberately loose — a judge is not deterministic, so these check the plumbing
 * and clear-cut cases, not exact scores.
 */
@EnabledIfEnvironmentVariable(named = "OPENAI_API_KEY", matches = ".+")
class LangChain4jJudgeModelIT {

    private static final String MODEL = System.getenv().getOrDefault("EVALS4J_IT_MODEL", "gpt-5.4");

    private static JudgeModel judge;

    @BeforeAll
    static void setUp() {
        judge = LangChain4jJudgeModel.of(OpenAiChatModel.builder()
                .apiKey(System.getenv("OPENAI_API_KEY"))
                .modelName(MODEL)
                .strictJsonSchema(true)
                .build());
    }

    @Test
    void scoresAVerboseAnswerAsNotConcise() {
        EvaluatorResult result = LlmAsJudge.builder()
                .prompt(Prompts.CONCISENESS_PROMPT)
                .feedbackKey("conciseness")
                .model(judge)
                .build()
                .evaluate(
                        "How is the weather in San Francisco?",
                        "Thank you so much for reaching out with your question! I really appreciate you "
                                + "taking the time to ask. Let me tell you all about it. The weather in the "
                                + "beautiful city of San Francisco is, as of right now, sunny, and the "
                                + "temperature is approximately 90 degrees Fahrenheit. Hope that helps!");

        assertThat(result.key()).isEqualTo("conciseness");
        assertThat(result.passed()).isFalse();
        assertThat(result.comment()).isNotBlank();
    }

    @Test
    void scoresACorrectAnswerAsCorrect() {
        EvaluatorResult result = LlmAsJudge.builder()
                .prompt(Prompts.CORRECTNESS_PROMPT)
                .feedbackKey("correctness")
                .model(judge)
                .build()
                .evaluate("What is the capital of France?", "Paris is the capital of France.", "Paris");

        assertThat(result.passed()).isTrue();
    }

    @Test
    void honoursTheChoicesEnum() {
        EvaluatorResult result = LlmAsJudge.builder()
                .prompt(Prompts.RAG_GROUNDEDNESS_PROMPT)
                .feedbackKey("groundedness")
                .model(judge)
                .choices(0.0, 0.5, 1.0)
                .build()
                .evaluate(EvalRequest.builder()
                        .outputs("Paris is the capital of France.")
                        .variable("context", "Paris is the capital of France.")
                        .build());

        assertThat(result.score().doubleValue()).isIn(0.0, 0.5, 1.0);
    }
}
