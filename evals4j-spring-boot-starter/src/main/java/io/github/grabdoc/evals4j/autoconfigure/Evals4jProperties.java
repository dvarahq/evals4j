package io.github.grabdoc.evals4j.autoconfigure;

import io.github.grabdoc.evals4j.spi.StructuredOutputMode;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** Configuration for the auto-configured evals4j beans, under {@code evals4j.*}. */
@ConfigurationProperties(prefix = "evals4j")
public class Evals4jProperties {

    /** Whether to auto-configure evals4j at all. */
    private boolean enabled = true;

    /**
     * How the judge asks the model for schema-conforming JSON. {@code AUTO} tries the provider's own
     * support first and falls back to putting the schema in the prompt.
     */
    private StructuredOutputMode structuredOutputMode = StructuredOutputMode.AUTO;

    /**
     * Which framework to bind the judge to when both a Spring AI and a LangChain4j chat model are on
     * the classpath. Leave unset to let auto-configuration pick, which fails fast if it is ambiguous.
     */
    private Provider provider = Provider.AUTO;

    /** Log every evaluation through an slf4j tracer. */
    private boolean tracingEnabled = true;

    /** Which chat-model framework backs the judge. */
    public enum Provider {
        AUTO,
        SPRING_AI,
        LANGCHAIN4J
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public StructuredOutputMode getStructuredOutputMode() {
        return structuredOutputMode;
    }

    public void setStructuredOutputMode(StructuredOutputMode structuredOutputMode) {
        this.structuredOutputMode = structuredOutputMode;
    }

    public Provider getProvider() {
        return provider;
    }

    public void setProvider(Provider provider) {
        this.provider = provider;
    }

    public boolean isTracingEnabled() {
        return tracingEnabled;
    }

    public void setTracingEnabled(boolean tracingEnabled) {
        this.tracingEnabled = tracingEnabled;
    }
}
