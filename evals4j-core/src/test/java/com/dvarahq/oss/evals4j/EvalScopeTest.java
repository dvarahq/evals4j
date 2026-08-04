package com.dvarahq.oss.evals4j;

import com.dvarahq.oss.evals4j.code.CodeLlmAsJudge;
import com.dvarahq.oss.evals4j.judge.LlmAsJudge;
import com.dvarahq.oss.evals4j.message.ChatMessage;
import com.dvarahq.oss.evals4j.result.EvaluatorResult;
import com.dvarahq.oss.evals4j.result.Score;
import com.dvarahq.oss.evals4j.simulate.MultiturnSimulation;
import com.dvarahq.oss.evals4j.spi.EvalTracer;
import com.dvarahq.oss.evals4j.string.ExactMatch;
import com.dvarahq.oss.evals4j.testing.FakeJudgeModel;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

class EvalScopeTest {

    /** Records what it was told, so a test can prove where a score was reported. */
    private static final class RecordingTracer implements EvalTracer {

        private final List<String> keys = Collections.synchronizedList(new ArrayList<>());

        @Override
        public void onFinish(Object token, String runName, List<EvaluatorResult> results) {
            results.forEach(result -> keys.add(result.key()));
        }
    }

    private static EvalRequest matching() {
        return EvalRequest.of(null, "same", "same");
    }

    @Test
    void anEvaluatorWithNoTracerReportsToTheOpenScope() {
        RecordingTracer tracer = new RecordingTracer();

        try (EvalScope.Handle ignored = EvalScope.open(tracer)) {
            ExactMatch.create().evaluate(matching());
        }

        assertThat(tracer.keys).containsExactly(ExactMatch.FEEDBACK_KEY);
    }

    @Test
    void nothingIsReportedOutsideTheScope() {
        RecordingTracer tracer = new RecordingTracer();

        try (EvalScope.Handle ignored = EvalScope.open(tracer)) {
            // deliberately empty — the scope must not outlive the block
        }
        ExactMatch.create().evaluate(matching());

        assertThat(tracer.keys).isEmpty();
        assertThat(EvalScope.current()).isNull();
    }

    @Test
    void aConfiguredTracerBeatsTheAmbientOne() {
        RecordingTracer ambient = new RecordingTracer();
        RecordingTracer configured = new RecordingTracer();

        try (EvalScope.Handle ignored = EvalScope.open(ambient)) {
            ExactMatch.create().withTracer(configured).evaluate(matching());
        }

        assertThat(configured.keys).containsExactly(ExactMatch.FEEDBACK_KEY);
        assertThat(ambient.keys).isEmpty();
    }

    @Test
    void anEvaluatorSilencedWithNoOpStaysSilent() {
        RecordingTracer ambient = new RecordingTracer();

        try (EvalScope.Handle ignored = EvalScope.open(ambient)) {
            ExactMatch.create().withTracer(EvalTracer.NO_OP).evaluate(matching());
        }

        // NO_OP is a deliberate choice, not an absence, so the scope must not override it.
        assertThat(ambient.keys).isEmpty();
    }

    @Test
    void aCustomEvaluatorReportsToTheOpenScope() {
        RecordingTracer tracer = new RecordingTracer();
        Evaluator custom = Evaluator.from(
                "custom", "custom_key", request -> new ScorerOutput.Single(Score.of(true), null, Map.of(), null));

        try (EvalScope.Handle ignored = EvalScope.open(tracer)) {
            custom.evaluate(matching());
        }

        // Evaluator.from(runName, feedbackKey, scorer) is the documented entry point for a custom
        // evaluator. It once passed NO_OP rather than null, which opted every one of them out.
        assertThat(tracer.keys).containsExactly("custom_key");
    }

    @Test
    void scopesNestAndRestore() {
        RecordingTracer outer = new RecordingTracer();
        RecordingTracer inner = new RecordingTracer();

        try (EvalScope.Handle ignoredOuter = EvalScope.open(outer)) {
            try (EvalScope.Handle ignoredInner = EvalScope.open(inner)) {
                ExactMatch.create().evaluate(matching());
            }
            assertThat(EvalScope.current()).isSameAs(outer);
            ExactMatch.create().evaluate(matching());
        }

        assertThat(inner.keys).hasSize(1);
        assertThat(outer.keys).hasSize(1);
    }

    @Test
    void openingWithNullExcludesASectionFromAnEnclosingScope() {
        RecordingTracer outer = new RecordingTracer();

        try (EvalScope.Handle ignoredOuter = EvalScope.open(outer)) {
            try (EvalScope.Handle ignoredInner = EvalScope.open(null)) {
                ExactMatch.create().evaluate(matching());
            }
        }

        assertThat(outer.keys).isEmpty();
    }

    @Test
    void theScopeSurvivesTheDefaultAsyncHandOff() throws Exception {
        RecordingTracer tracer = new RecordingTracer();

        try (EvalScope.Handle ignored = EvalScope.open(tracer)) {
            // The default evaluateAllAsync runs evaluateAll on the common pool, a different thread
            // from the one holding the scope.
            ExactMatch.create().evaluateAllAsync(matching()).get();
        }

        assertThat(tracer.keys).containsExactly(ExactMatch.FEEDBACK_KEY);
    }

    @Test
    void theScopeSurvivesTheExecutorOverload() throws Exception {
        RecordingTracer tracer = new RecordingTracer();
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            try (EvalScope.Handle ignored = EvalScope.open(tracer)) {
                ExactMatch.create().evaluateAllAsync(matching(), pool).get();
            }
        } finally {
            pool.shutdownNow();
        }

        assertThat(tracer.keys).containsExactly(ExactMatch.FEEDBACK_KEY);
    }

    @Test
    void theScopeSurvivesAMultiturnSimulation() throws Exception {
        RecordingTracer tracer = new RecordingTracer();
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            try (EvalScope.Handle ignored = EvalScope.open(tracer)) {
                // The trajectory evaluators run in thenCompose, off the thread holding the scope.
                MultiturnSimulation.builder()
                        .app((message, threadId) -> ChatMessage.assistant("I can help with that."))
                        .user(List.of("my order never arrived"))
                        .trajectoryEvaluator(LlmAsJudge.builder()
                                .prompt("Judge {outputs}")
                                .feedbackKey("task_completion")
                                .model(FakeJudgeModel.scoring(true, "resolved"))
                                .build())
                        .runAsync(pool)
                        .get(30, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
        }

        assertThat(tracer.keys).containsExactly("task_completion");
    }

    @Test
    void theScopeSurvivesTheCodeJudgeAsyncPath() throws Exception {
        RecordingTracer tracer = new RecordingTracer();

        try (EvalScope.Handle ignored = EvalScope.open(tracer)) {
            CodeLlmAsJudge.builder()
                    .model(FakeJudgeModel.scoring(true, "looks right"))
                    .build()
                    .evaluateAllAsync(EvalRequest.of("print a greeting", "print('hi')", null))
                    .get(30, TimeUnit.SECONDS);
        }

        assertThat(tracer.keys).containsExactly(CodeLlmAsJudge.DEFAULT_FEEDBACK_KEY);
    }

    @Test
    void aPooledThreadIsLeftCleanAfterCarryingAScope() throws Exception {
        RecordingTracer tracer = new RecordingTracer();
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            try (EvalScope.Handle ignored = EvalScope.open(tracer)) {
                ExactMatch.create().evaluateAllAsync(matching(), pool).get();
            }
            // The same pool thread is reused; a scope left behind would leak into unrelated work.
            assertThat(pool.submit(EvalScope::current).get()).isNull();
        } finally {
            pool.shutdownNow();
        }
    }
}
