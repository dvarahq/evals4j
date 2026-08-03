package com.dvarahq.oss.evals4j.autoconfigure;

import com.dvarahq.oss.evals4j.spi.EmbeddingProvider;
import com.dvarahq.oss.evals4j.spi.EvalTracer;
import com.dvarahq.oss.evals4j.spi.JudgeModel;
import com.dvarahq.oss.evals4j.spi.Slf4jEvalTracer;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;

/**
 * Wires evals4j to whichever chat model the application already has.
 *
 * <p>Adding the starter is meant to be enough: an application with a Spring AI {@code ChatModel} bean
 * gets a {@link JudgeModel} without writing any adapter code, and so does one using LangChain4j.
 *
 * <p>When both frameworks are on the classpath with a chat-model bean each, Spring AI wins by
 * default. Set {@code evals4j.provider=langchain4j} to choose otherwise — or define your own
 * {@link JudgeModel} bean, which always takes precedence.
 */
@AutoConfiguration
@EnableConfigurationProperties(Evals4jProperties.class)
@ConditionalOnProperty(prefix = "evals4j", name = "enabled", havingValue = "true", matchIfMissing = true)
public class Evals4jAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(prefix = "evals4j", name = "tracing-enabled", havingValue = "true",
            matchIfMissing = true)
    public EvalTracer evals4jTracer() {
        return new Slf4jEvalTracer();
    }

    /** Spring AI. Declared first so it wins when both frameworks are present under AUTO. */
    @Order(0)
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass({
        org.springframework.ai.chat.model.ChatModel.class,
        com.dvarahq.oss.evals4j.springai.SpringAiJudgeModel.class
    })
    @Conditional(OnProviderCondition.OnSpringAi.class)
    static class SpringAiConfiguration {

        @Bean
        @ConditionalOnMissingBean(JudgeModel.class)
        @ConditionalOnBean(org.springframework.ai.chat.model.ChatModel.class)
        JudgeModel evals4jJudgeModel(
                org.springframework.ai.chat.model.ChatModel chatModel, Evals4jProperties properties) {
            return com.dvarahq.oss.evals4j.springai.SpringAiJudgeModel.of(
                    chatModel, properties.getStructuredOutputMode());
        }

        @Bean
        @ConditionalOnMissingBean(EmbeddingProvider.class)
        @ConditionalOnBean(org.springframework.ai.embedding.EmbeddingModel.class)
        EmbeddingProvider evals4jEmbeddingProvider(
                org.springframework.ai.embedding.EmbeddingModel embeddingModel) {
            return com.dvarahq.oss.evals4j.springai.SpringAiEmbeddingProvider.of(embeddingModel);
        }
    }

    /** LangChain4j. Skipped when Spring AI already supplied a {@link JudgeModel}. */
    @Order(1)
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass({
        dev.langchain4j.model.chat.ChatModel.class,
        com.dvarahq.oss.evals4j.langchain4j.LangChain4jJudgeModel.class
    })
    @Conditional(OnProviderCondition.OnLangChain4j.class)
    static class LangChain4jConfiguration {

        @Bean
        @ConditionalOnMissingBean(JudgeModel.class)
        @ConditionalOnBean(dev.langchain4j.model.chat.ChatModel.class)
        JudgeModel evals4jJudgeModel(
                dev.langchain4j.model.chat.ChatModel chatModel, Evals4jProperties properties) {
            return com.dvarahq.oss.evals4j.langchain4j.LangChain4jJudgeModel.of(
                    chatModel, properties.getStructuredOutputMode());
        }

        @Bean
        @ConditionalOnMissingBean(EmbeddingProvider.class)
        @ConditionalOnBean(dev.langchain4j.model.embedding.EmbeddingModel.class)
        EmbeddingProvider evals4jEmbeddingProvider(
                dev.langchain4j.model.embedding.EmbeddingModel embeddingModel) {
            return com.dvarahq.oss.evals4j.langchain4j.LangChain4jEmbeddingProvider.of(embeddingModel);
        }
    }
}
