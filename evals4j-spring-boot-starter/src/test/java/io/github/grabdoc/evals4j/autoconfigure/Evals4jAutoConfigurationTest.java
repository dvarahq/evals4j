package io.github.grabdoc.evals4j.autoconfigure;

import io.github.grabdoc.evals4j.judge.LlmAsJudge;
import io.github.grabdoc.evals4j.prompt.Prompts;
import io.github.grabdoc.evals4j.result.EvaluatorResult;
import io.github.grabdoc.evals4j.spi.EmbeddingProvider;
import io.github.grabdoc.evals4j.spi.EvalTracer;
import io.github.grabdoc.evals4j.spi.JudgeModel;
import io.github.grabdoc.evals4j.spi.Slf4jEvalTracer;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class Evals4jAutoConfigurationTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(Evals4jAutoConfiguration.class));

    @Configuration(proxyBeanMethods = false)
    static class SpringAiChatModelConfiguration {
        @Bean
        ChatModel chatModel() {
            return prompt -> new ChatResponse(List.of(
                    new Generation(new AssistantMessage("{\"reasoning\": \"fine\", \"score\": true}"))));
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class SpringAiEmbeddingModelConfiguration {
        @Bean
        org.springframework.ai.embedding.EmbeddingModel embeddingModel() {
            return new org.springframework.ai.embedding.EmbeddingModel() {
                @Override
                public org.springframework.ai.embedding.EmbeddingResponse call(
                        org.springframework.ai.embedding.EmbeddingRequest request) {
                    return new org.springframework.ai.embedding.EmbeddingResponse(List.of());
                }

                @Override
                public float[] embed(org.springframework.ai.document.Document document) {
                    return new float[] {1f};
                }
            };
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class LangChain4jChatModelConfiguration {
        @Bean
        dev.langchain4j.model.chat.ChatModel langChain4jChatModel() {
            // ChatModel has default methods on both chat() overloads, so it is not a SAM type.
            return new dev.langchain4j.model.chat.ChatModel() {
                @Override
                public dev.langchain4j.model.chat.response.ChatResponse chat(
                        dev.langchain4j.model.chat.request.ChatRequest request) {
                    return dev.langchain4j.model.chat.response.ChatResponse.builder()
                            .aiMessage(dev.langchain4j.data.message.AiMessage.from("{\"score\": true}"))
                            .build();
                }
            };
        }
    }

    @Test
    void doesNothingWithoutAChatModel() {
        runner.run(context -> assertThat(context).doesNotHaveBean(JudgeModel.class));
    }

    @Test
    void bindsToASpringAiChatModel() {
        runner.withUserConfiguration(SpringAiChatModelConfiguration.class).run(context -> {
            assertThat(context).hasSingleBean(JudgeModel.class);
            assertThat(context.getBean(JudgeModel.class))
                    .isInstanceOf(io.github.grabdoc.evals4j.springai.SpringAiJudgeModel.class);
        });
    }

    @Test
    void bindsToALangChain4jChatModel() {
        runner.withUserConfiguration(LangChain4jChatModelConfiguration.class).run(context -> {
            assertThat(context).hasSingleBean(JudgeModel.class);
            assertThat(context.getBean(JudgeModel.class))
                    .isInstanceOf(io.github.grabdoc.evals4j.langchain4j.LangChain4jJudgeModel.class);
        });
    }

    @Test
    void prefersSpringAiWhenBothArePresent() {
        runner.withUserConfiguration(
                        SpringAiChatModelConfiguration.class, LangChain4jChatModelConfiguration.class)
                .run(context -> assertThat(context.getBean(JudgeModel.class))
                        .isInstanceOf(io.github.grabdoc.evals4j.springai.SpringAiJudgeModel.class));
    }

    @Test
    void honoursAnExplicitProviderChoice() {
        runner.withUserConfiguration(
                        SpringAiChatModelConfiguration.class, LangChain4jChatModelConfiguration.class)
                .withPropertyValues("evals4j.provider=langchain4j")
                .run(context -> assertThat(context.getBean(JudgeModel.class))
                        .isInstanceOf(io.github.grabdoc.evals4j.langchain4j.LangChain4jJudgeModel.class));
    }

    @Test
    void backsOffWhenTheApplicationDefinesItsOwnJudge() {
        runner.withUserConfiguration(SpringAiChatModelConfiguration.class)
                .withBean(JudgeModel.class, () -> (messages, schema) -> null)
                .run(context -> assertThat(context).hasSingleBean(JudgeModel.class));
    }

    @Test
    void canBeDisabledEntirely() {
        runner.withUserConfiguration(SpringAiChatModelConfiguration.class)
                .withPropertyValues("evals4j.enabled=false")
                .run(context -> assertThat(context).doesNotHaveBean(JudgeModel.class));
    }

    @Test
    void registersAnEmbeddingProviderWhenAnEmbeddingModelIsPresent() {
        runner.withUserConfiguration(
                        SpringAiChatModelConfiguration.class, SpringAiEmbeddingModelConfiguration.class)
                .run(context -> assertThat(context).hasSingleBean(EmbeddingProvider.class));
    }

    @Test
    void registersATracerByDefault() {
        runner.run(context -> assertThat(context.getBean(EvalTracer.class))
                .isInstanceOf(Slf4jEvalTracer.class));
    }

    @Test
    void tracingCanBeTurnedOff() {
        runner.withPropertyValues("evals4j.tracing-enabled=false")
                .run(context -> assertThat(context).doesNotHaveBean(EvalTracer.class));
    }

    @Test
    void theAutoConfiguredJudgeActuallyScores() {
        // The point of the starter: this is the whole set-up an application needs.
        runner.withUserConfiguration(SpringAiChatModelConfiguration.class).run(context -> {
            EvaluatorResult result = LlmAsJudge.builder()
                    .prompt(Prompts.CONCISENESS_PROMPT)
                    .feedbackKey("conciseness")
                    .model(context.getBean(JudgeModel.class))
                    .build()
                    .evaluate("How is the weather?", "Sunny.");

            assertThat(result.key()).isEqualTo("conciseness");
            assertThat(result.passed()).isTrue();
        });
    }
}
