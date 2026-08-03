package com.dvarahq.oss.evals4j.autoconfigure;

import com.dvarahq.oss.evals4j.micrometer.MicrometerEvalTracer;
import com.dvarahq.oss.evals4j.spi.EvalTracer;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;

/**
 * Registers a {@link MicrometerEvalTracer} when the application has a {@link MeterRegistry}.
 *
 * <p>Separate from {@link Evals4jAutoConfiguration}, and declared to run {@code before} it, so that
 * its slf4j tracer backs off through {@code @ConditionalOnMissingBean}. Auto-configuration ordering
 * is the supported way to express "this one wins"; relying on the order two bean methods happen to
 * be declared in is not.
 */
@AutoConfiguration(before = Evals4jAutoConfiguration.class)
@ConditionalOnClass(MeterRegistry.class)
@ConditionalOnProperty(prefix = "evals4j", name = "enabled", havingValue = "true", matchIfMissing = true)
public class Evals4jMicrometerAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean(EvalTracer.class)
    @ConditionalOnBean(MeterRegistry.class)
    @ConditionalOnProperty(prefix = "evals4j", name = "tracing-enabled", havingValue = "true",
            matchIfMissing = true)
    public EvalTracer evals4jTracer(MeterRegistry meterRegistry) {
        return new MicrometerEvalTracer(meterRegistry);
    }
}
