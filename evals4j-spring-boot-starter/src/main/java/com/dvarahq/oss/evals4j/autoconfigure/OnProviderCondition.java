package com.dvarahq.oss.evals4j.autoconfigure;

import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.core.type.AnnotatedTypeMetadata;

/**
 * Matches when {@code evals4j.provider} selects this framework, or is left on {@code AUTO}.
 *
 * <p>{@code @ConditionalOnProperty} cannot express "this value or the default", which is what the
 * two-framework choice needs.
 */
abstract class OnProviderCondition implements Condition {

    private final Evals4jProperties.Provider target;

    protected OnProviderCondition(Evals4jProperties.Provider target) {
        this.target = target;
    }

    @Override
    public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
        String configured = context.getEnvironment().getProperty("evals4j.provider");
        if (configured == null || configured.isBlank()) {
            return true;
        }
        Evals4jProperties.Provider provider;
        try {
            provider = Evals4jProperties.Provider.valueOf(
                    configured.trim().toUpperCase(java.util.Locale.ROOT).replace('-', '_'));
        } catch (IllegalArgumentException e) {
            // An unreadable value should not silently disable everything.
            return true;
        }
        return provider == Evals4jProperties.Provider.AUTO || provider == target;
    }

    static final class OnSpringAi extends OnProviderCondition {
        OnSpringAi() {
            super(Evals4jProperties.Provider.SPRING_AI);
        }
    }

    static final class OnLangChain4j extends OnProviderCondition {
        OnLangChain4j() {
            super(Evals4jProperties.Provider.LANGCHAIN4J);
        }
    }
}
