package io.github.grabdoc.evals4j.examples;

import com.openai.client.OpenAIClient;
import com.openai.client.OpenAIClientImpl;
import com.openai.core.ClientOptions;
import io.github.grabdoc.evals4j.langchain4j.LangChain4jJudgeModel;
import io.github.grabdoc.evals4j.spi.EmbeddingProvider;
import io.github.grabdoc.evals4j.spi.JudgeModel;
import io.github.grabdoc.evals4j.springai.SpringAiEmbeddingProvider;
import io.github.grabdoc.evals4j.springai.SpringAiJudgeModel;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.http.okhttp.SpringAiOpenAiHttpClient;

/**
 * Builds the judge models the examples use.
 *
 * <p>Kept out of the examples themselves so each one shows evals4j rather than provider set-up.
 * In a real application this is a Spring bean or whatever your framework already gives you — and
 * with {@code evals4j-spring-boot-starter} you skip it entirely.
 */
final class Models {

    /** Which model does the judging. Any capable model works; a cheap one is usually fine. */
    static final String JUDGE_MODEL = System.getenv().getOrDefault("EVALS4J_MODEL", "gpt-5.4");

    private Models() {}

    /** The API key, or a clear message and exit if it is missing. */
    static String requireApiKey() {
        String key = System.getenv("OPENAI_API_KEY");
        if (key == null || key.isBlank()) {
            System.err.println("Set OPENAI_API_KEY to run this example.");
            System.exit(1);
        }
        return key;
    }

    static JudgeModel springAiJudge() {
        return SpringAiJudgeModel.of(springAiChatModel());
    }

    static OpenAiChatModel springAiChatModel() {
        OpenAIClient client = new OpenAIClientImpl(ClientOptions.builder()
                .apiKey(requireApiKey())
                .httpClient(SpringAiOpenAiHttpClient.builder().build())
                .build());
        return OpenAiChatModel.builder()
                .openAiClient(client)
                .options(OpenAiChatOptions.builder().model(JUDGE_MODEL).build())
                .build();
    }

    static JudgeModel langChain4jJudge() {
        return LangChain4jJudgeModel.of(dev.langchain4j.model.openai.OpenAiChatModel.builder()
                .apiKey(requireApiKey())
                .modelName(JUDGE_MODEL)
                .strictJsonSchema(true)
                .build());
    }

    static EmbeddingProvider springAiEmbeddings() {
        OpenAIClient client = new OpenAIClientImpl(ClientOptions.builder()
                .apiKey(requireApiKey())
                .httpClient(SpringAiOpenAiHttpClient.builder().build())
                .build());
        return SpringAiEmbeddingProvider.of(
                org.springframework.ai.openai.OpenAiEmbeddingModel.builder()
                        .openAiClient(client)
                        .build());
    }

    /** Prints a result the way these examples do. */
    static void print(io.github.grabdoc.evals4j.result.EvaluatorResult result) {
        System.out.printf("  %-24s %s%n", result.key(), result.score());
        if (result.comment() != null && !result.comment().isBlank()) {
            System.out.printf("  %-24s %s%n", "", result.comment().replace("\n", " "));
        }
    }
}
