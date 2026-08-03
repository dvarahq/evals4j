package com.dvarahq.oss.evals4j;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.dvarahq.oss.evals4j.internal.Json;
import com.dvarahq.oss.evals4j.json.JsonMatchEvaluator;
import com.dvarahq.oss.evals4j.judge.LlmAsJudge;
import com.dvarahq.oss.evals4j.message.ChatMessage;
import com.dvarahq.oss.evals4j.result.EvaluatorResult;
import com.dvarahq.oss.evals4j.result.Score;
import com.dvarahq.oss.evals4j.simulate.MultiturnSimulation;
import com.dvarahq.oss.evals4j.simulate.SimulationResult;
import com.dvarahq.oss.evals4j.spi.EvalTracer;
import com.dvarahq.oss.evals4j.spi.JudgeModel;
import com.dvarahq.oss.evals4j.testing.FakeJudgeModel;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The asynchronous path, which for a long time existed only as a blocking call parked on the common
 * pool. These assert that it is genuinely wired through {@link JudgeModel#invokeStructuredAsync} and
 * that tracing survives the trip.
 */
class AsyncEvaluationTest {

    private static ObjectNode scoreResponse(boolean score, String reasoning) {
        ObjectNode node = Json.object();
        node.put("reasoning", reasoning);
        node.put("score", score);
        return node;
    }

    /** Records which of the two SPI methods the evaluator actually called. */
    private static final class RecordingJudgeModel implements JudgeModel {
        private final JsonNode response;
        final AtomicBoolean blockingCalled = new AtomicBoolean();
        final AtomicBoolean asyncCalled = new AtomicBoolean();

        RecordingJudgeModel(JsonNode response) {
            this.response = response;
        }

        @Override
        public JsonNode invokeStructured(List<ChatMessage> messages, JsonNode jsonSchema) {
            blockingCalled.set(true);
            return response;
        }

        @Override
        public CompletableFuture<JsonNode> invokeStructuredAsync(
                List<ChatMessage> messages, JsonNode jsonSchema) {
            asyncCalled.set(true);
            return CompletableFuture.completedFuture(response);
        }
    }

    private static final class RecordingTracer implements EvalTracer {
        final List<String> events = new ArrayList<>();

        @Override
        public Object onStart(String runName, String feedbackKey) {
            events.add("start:" + runName);
            return runName;
        }

        @Override
        public void onFinish(Object token, String runName, List<EvaluatorResult> results) {
            events.add("finish:" + runName + ":" + results.size());
        }

        @Override
        public void onError(Object token, String runName, Throwable error) {
            events.add("error:" + runName + ":" + error.getClass().getSimpleName());
        }
    }

    @Test
    void llmAsJudgeGoesThroughTheAsynchronousModelCall() throws Exception {
        RecordingJudgeModel model = new RecordingJudgeModel(scoreResponse(true, "looks right"));

        EvaluatorResult result = LlmAsJudge.builder()
                .prompt("Grade {outputs}")
                .feedbackKey("correctness")
                .model(model)
                .build()
                .evaluateAsync(EvalRequest.ofOutputs("an answer"))
                .get(10, TimeUnit.SECONDS);

        assertThat(model.asyncCalled).isTrue();
        // The whole point: the blocking call is no longer what backs an async evaluation.
        assertThat(model.blockingCalled).isFalse();
        assertThat(result.score()).isEqualTo(Score.of(true));
        assertThat(result.comment()).isEqualTo("looks right");
    }

    @Test
    void asyncEvaluationStillFiresTracerCallbacks() throws Exception {
        RecordingTracer tracer = new RecordingTracer();

        LlmAsJudge.builder()
                .prompt("Grade {outputs}")
                .feedbackKey("correctness")
                .model(new RecordingJudgeModel(scoreResponse(true, "fine")))
                .tracer(tracer)
                .build()
                .evaluateAllAsync(EvalRequest.ofOutputs("an answer"))
                .get(10, TimeUnit.SECONDS);

        assertThat(tracer.events)
                .containsExactly("start:llm_as_correctness_judge", "finish:llm_as_correctness_judge:1");
    }

    @Test
    void aFailedAsyncEvaluationReportsThroughOnError() {
        RecordingTracer tracer = new RecordingTracer();
        JudgeModel failing = new JudgeModel() {
            @Override
            public JsonNode invokeStructured(List<ChatMessage> messages, JsonNode jsonSchema) {
                throw new JudgeInvocationException("model is down");
            }

            @Override
            public CompletableFuture<JsonNode> invokeStructuredAsync(
                    List<ChatMessage> messages, JsonNode jsonSchema) {
                return CompletableFuture.failedFuture(new JudgeInvocationException("model is down"));
            }
        };

        CompletableFuture<List<EvaluatorResult>> future = LlmAsJudge.builder()
                .prompt("Grade {outputs}")
                .feedbackKey("correctness")
                .model(failing)
                .tracer(tracer)
                .build()
                .evaluateAllAsync(EvalRequest.ofOutputs("an answer"));

        assertThatThrownBy(() -> future.get(10, TimeUnit.SECONDS))
                .isInstanceOf(ExecutionException.class)
                .hasCauseInstanceOf(JudgeModel.JudgeInvocationException.class);
        assertThat(tracer.events)
                .containsExactly(
                        "start:llm_as_correctness_judge",
                        "error:llm_as_correctness_judge:JudgeInvocationException");
    }

    @Test
    void evaluateAsyncRoutesThroughTheOverriddenEvaluateAllAsync() throws Exception {
        AtomicBoolean asyncPathUsed = new AtomicBoolean();
        Evaluator evaluator = new Evaluator() {
            @Override
            public List<EvaluatorResult> evaluateAll(EvalRequest request) {
                return List.of(EvaluatorResult.of("blocking", Score.of(false)));
            }

            @Override
            public CompletableFuture<List<EvaluatorResult>> evaluateAllAsync(EvalRequest request) {
                asyncPathUsed.set(true);
                return CompletableFuture.completedFuture(
                        List.of(EvaluatorResult.of("async", Score.of(true))));
            }
        };

        EvaluatorResult result =
                evaluator.evaluateAsync(EvalRequest.ofOutputs("x")).get(10, TimeUnit.SECONDS);

        assertThat(asyncPathUsed).isTrue();
        assertThat(result.key()).isEqualTo("async");
    }

    @Test
    void theExecutorOverloadRunsOnTheSuppliedPool() throws Exception {
        ExecutorService pool = Executors.newSingleThreadExecutor(
                runnable -> new Thread(runnable, "evals4j-test-pool"));
        try {
            Evaluator evaluator = request -> List.of(
                    EvaluatorResult.of(Thread.currentThread().getName(), Score.of(true)));

            EvaluatorResult result =
                    evaluator.evaluateAsync(EvalRequest.ofOutputs("x"), pool).get(10, TimeUnit.SECONDS);

            assertThat(result.key()).isEqualTo("evals4j-test-pool");
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void jsonMatchJudgesRubricKeysConcurrently() throws Exception {
        // Two rubric keys means two judge calls. The barrier only trips if both are in flight at
        // once, so this deadlocks — and times out — if they are made one after the other.
        CyclicBarrier bothInFlight = new CyclicBarrier(2);
        // A dedicated pool, not the common one: common-pool parallelism is derived from the core
        // count and is 1 on a single-core machine, which would make this fail for the wrong reason.
        ExecutorService judgePool = Executors.newFixedThreadPool(2);
        JudgeModel model = new JudgeModel() {
            @Override
            public JsonNode invokeStructured(List<ChatMessage> messages, JsonNode jsonSchema) {
                throw new UnsupportedOperationException("the async path should be used here");
            }

            @Override
            public CompletableFuture<JsonNode> invokeStructuredAsync(
                    List<ChatMessage> messages, JsonNode jsonSchema) {
                return CompletableFuture.supplyAsync(
                        () -> {
                            try {
                                bothInFlight.await(20, TimeUnit.SECONDS);
                            } catch (Exception e) {
                                throw new CompletionException(e);
                            }
                            ObjectNode response = Json.object();
                            ObjectNode judged = Json.object();
                            judged.put("score", true);
                            judged.put("reasoning", "close enough");
                            // The schema is narrowed per key, so answer whichever key was asked about.
                            jsonSchema.get("properties").fieldNames()
                                    .forEachRemaining(key -> response.set(key, judged));
                            return response;
                        },
                        judgePool);
            }
        };

        try {
            List<EvaluatorResult> results = JsonMatchEvaluator.builder()
                    .rubric("summary", "Does the summary match?")
                    .rubric("title", "Does the title match?")
                    .model(model)
                    .build()
                    .evaluateAllAsync(EvalRequest.of(
                            null,
                            Map.of("summary", "a short summary", "title", "a title"),
                            Map.of("summary", "the reference summary", "title", "the reference title")))
                    .get(60, TimeUnit.SECONDS);

            assertThat(results).hasSize(2);
            assertThat(results).allMatch(EvaluatorResult::passed);
        } finally {
            judgePool.shutdownNow();
        }
    }

    @Test
    void multiturnSimulationScoresTheTranscriptAsynchronously() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            SimulationResult result = MultiturnSimulation.builder()
                    .app((message, threadId) -> ChatMessage.assistant("I can help with that."))
                    .user(List.of("my order never arrived", "when will it ship?"))
                    .trajectoryEvaluator(LlmAsJudge.builder()
                            .prompt("Judge {outputs}")
                            .feedbackKey("task_completion")
                            .model(FakeJudgeModel.scoring(true, "resolved"))
                            .build())
                    .runAsync(pool)
                    .get(30, TimeUnit.SECONDS);

            assertThat(result.turns()).isEqualTo(2);
            assertThat(result.evaluatorResults()).hasSize(1);
            assertThat(result.evaluatorResults().get(0).key()).isEqualTo("task_completion");
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void aFailingTrajectoryEvaluatorDoesNotDiscardTheAsyncSimulation() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Evaluator exploding = request -> {
                throw new IllegalStateException("evaluator blew up");
            };

            SimulationResult result = MultiturnSimulation.builder()
                    .app((message, threadId) -> ChatMessage.assistant("ok"))
                    .user(List.of("hello"))
                    .trajectoryEvaluator(exploding)
                    .runAsync(pool)
                    .get(30, TimeUnit.SECONDS);

            assertThat(result.trajectory()).isNotEmpty();
            assertThat(result.evaluatorResults()).isEmpty();
        } finally {
            pool.shutdownNow();
        }
    }
}
