package com.dvarahq.oss.evals4j.micrometer;

import com.dvarahq.oss.evals4j.EvalRequest;
import com.dvarahq.oss.evals4j.Evaluator;
import com.dvarahq.oss.evals4j.ScorerOutput;
import com.dvarahq.oss.evals4j.autoconfigure.Evals4jAutoConfiguration;
import com.dvarahq.oss.evals4j.autoconfigure.Evals4jMicrometerAutoConfiguration;
import com.dvarahq.oss.evals4j.result.EvaluatorResult;
import com.dvarahq.oss.evals4j.result.Score;
import com.dvarahq.oss.evals4j.spi.EvalTracer;
import com.dvarahq.oss.evals4j.spi.Slf4jEvalTracer;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MicrometerEvalTracerTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(
                    Evals4jMicrometerAutoConfiguration.class, Evals4jAutoConfiguration.class));

    @Configuration(proxyBeanMethods = false)
    static class MeterRegistryConfiguration {
        @Bean
        MeterRegistry meterRegistry() {
            return new SimpleMeterRegistry();
        }
    }

    @Test
    void recordsScoresTaggedByEvaluatorAndKey() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        Evaluator evaluator = Evaluator.from(
                "llm_as_conciseness_judge",
                "conciseness",
                request -> ScorerOutput.of(0.75, "a bit long"),
                new MicrometerEvalTracer(registry));

        evaluator.evaluate(EvalRequest.ofOutputs("an answer"));

        assertThat(registry.get(MicrometerEvalTracer.SCORE_METER)
                        .tag("evaluator", "llm_as_conciseness_judge")
                        .tag("key", "conciseness")
                        .summary()
                        .totalAmount())
                .isEqualTo(0.75);
    }

    @Test
    void recordsBooleanScoresAsZeroOrOneSoTheMeanIsAPassRate() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        MicrometerEvalTracer tracer = new MicrometerEvalTracer(registry);

        Object token = tracer.onStart("exact_match", "exact_match");
        tracer.onFinish(token, "exact_match", List.of(
                EvaluatorResult.of("exact_match", Score.of(true)),
                EvaluatorResult.of("exact_match", Score.of(false))));

        assertThat(registry.get(MicrometerEvalTracer.SCORE_METER).summary().mean()).isEqualTo(0.5);
    }

    @Test
    void timesEvaluationsAndSeparatesFailuresByOutcome() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        MicrometerEvalTracer tracer = new MicrometerEvalTracer(registry);
        Evaluator exploding = Evaluator.from(
                "llm_as_judge",
                "score",
                request -> {
                    throw new IllegalStateException("judge is down");
                },
                tracer);

        assertThatThrownBy(() -> exploding.evaluate(EvalRequest.ofOutputs("x")))
                .isInstanceOf(IllegalStateException.class);

        assertThat(registry.get(MicrometerEvalTracer.DURATION_METER)
                        .tag("evaluator", "llm_as_judge")
                        .tag("outcome", "error")
                        .timer()
                        .count())
                .isEqualTo(1);
    }

    @Test
    void isAutoConfiguredWhenAMeterRegistryIsPresent() {
        runner.withUserConfiguration(MeterRegistryConfiguration.class)
                .run(context -> assertThat(context.getBean(EvalTracer.class))
                        .isInstanceOf(MicrometerEvalTracer.class));
    }

    @Test
    void fallsBackToSlf4jWithoutAMeterRegistry() {
        runner.run(context ->
                assertThat(context.getBean(EvalTracer.class)).isInstanceOf(Slf4jEvalTracer.class));
    }

    @Test
    void backsOffEntirelyWhenTracingIsDisabled() {
        runner.withUserConfiguration(MeterRegistryConfiguration.class)
                .withPropertyValues("evals4j.tracing-enabled=false")
                .run(context -> assertThat(context).doesNotHaveBean(EvalTracer.class));
    }

    @Test
    void aUserSuppliedTracerStillWins() {
        EvalTracer mine = new EvalTracer() {};
        runner.withUserConfiguration(MeterRegistryConfiguration.class)
                .withBean(EvalTracer.class, () -> mine)
                .run(context -> assertThat(context.getBean(EvalTracer.class)).isSameAs(mine));
    }
}
