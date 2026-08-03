package com.dvarahq.oss.evals4j.springai;

import com.dvarahq.oss.evals4j.EvalRequest;
import com.dvarahq.oss.evals4j.judge.LlmAsJudge;
import com.dvarahq.oss.evals4j.prompt.Prompts;
import com.dvarahq.oss.evals4j.result.EvaluatorResult;
import com.dvarahq.oss.evals4j.spi.JudgeModel;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import com.openai.client.OpenAIClient;
import com.openai.client.OpenAIClientImpl;
import com.openai.core.ClientOptions;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.http.okhttp.SpringAiOpenAiHttpClient;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end checks against a real model, through Spring AI.
 *
 * <p>Run with {@code ./mvnw -Pit verify} and {@code OPENAI_API_KEY} set. Skipped otherwise, which is
 * why the ordinary build stays offline.
 *
 * <p>The assertions are deliberately loose — a judge is not deterministic, so these check that the
 * plumbing works and that clear-cut cases land on the right side, not that a particular score comes
 * back.
 */
@EnabledIfEnvironmentVariable(named = "OPENAI_API_KEY", matches = ".+")
class SpringAiJudgeModelIT {

    private static final String MODEL = System.getenv().getOrDefault("EVALS4J_IT_MODEL", "gpt-5.4");

    private static JudgeModel judge;

    @BeforeAll
    static void setUp() {
        // Spring AI 2.0 builds on the official OpenAI Java client and ships its own HTTP client,
        // so no extra openai-java-client-okhttp dependency is needed.
        OpenAIClient client = new OpenAIClientImpl(ClientOptions.builder()
                .apiKey(System.getenv("OPENAI_API_KEY"))
                .httpClient(SpringAiOpenAiHttpClient.builder().build())
                .build());

        judge = SpringAiJudgeModel.of(OpenAiChatModel.builder()
                .openAiClient(client)
                .options(OpenAiChatOptions.builder().model(MODEL).build())
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
    void scoresAWrongAnswerAsIncorrect() {
        EvaluatorResult result = LlmAsJudge.builder()
                .prompt(Prompts.CORRECTNESS_PROMPT)
                .feedbackKey("correctness")
                .model(judge)
                .build()
                .evaluate("What is the capital of France?", "The capital of France is Berlin.", "Paris");

        assertThat(result.passed()).isFalse();
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
    void returnsAContinuousScoreInRange() {
        EvaluatorResult result = LlmAsJudge.builder()
                .prompt(Prompts.RAG_GROUNDEDNESS_PROMPT)
                .feedbackKey("groundedness")
                .model(judge)
                .continuous(true)
                .build()
                .evaluate(EvalRequest.builder()
                        .outputs("Paris is the capital of France and has 40 million residents.")
                        .variable("context", "Paris is the capital of France. Its population is 2.1 million.")
                        .build());

        assertThat(result.score().doubleValue()).isBetween(0.0, 1.0);
    }
}
