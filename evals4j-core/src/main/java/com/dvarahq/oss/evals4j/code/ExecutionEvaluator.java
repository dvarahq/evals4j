package com.dvarahq.oss.evals4j.code;

import com.dvarahq.oss.evals4j.EvalRequest;
import com.dvarahq.oss.evals4j.Evaluator;
import com.dvarahq.oss.evals4j.ScorerOutput;
import com.dvarahq.oss.evals4j.result.EvaluatorResult;
import com.dvarahq.oss.evals4j.result.Score;
import com.dvarahq.oss.evals4j.spi.EvalTracer;
import com.dvarahq.oss.evals4j.spi.JudgeModel;
import com.dvarahq.oss.evals4j.spi.SandboxRunner;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * Scores whether generated code runs to completion.
 *
 * <p>Replaces OpenEvals' E2B integration with the {@link SandboxRunner} SPI — supply a Docker-backed
 * runner from {@code evals4j-sandbox}, or your own.
 *
 * <p>This runs code a model wrote. Use an isolated runner unless you have a specific reason not to.
 */
public final class ExecutionEvaluator implements Evaluator {

    public static final String FEEDBACK_KEY = "execution_succeeded";
    public static final String RUN_NAME = "execution_evaluator";

    private final CodeEvaluator delegate;

    private ExecutionEvaluator(Builder builder) {
        SandboxRunner runner = java.util.Objects.requireNonNull(
                builder.sandbox, "A sandbox runner is required — call .sandbox(...)");
        this.delegate = new CodeEvaluator(
                RUN_NAME,
                FEEDBACK_KEY,
                code -> run(runner, code, builder),
                CodeExtractor.from(builder.strategy, builder.customExtractor, builder.extractionModel),
                builder.tracer);
    }

    public static Builder builder() {
        return new Builder();
    }

    @Override
    public List<EvaluatorResult> evaluateAll(EvalRequest request) {
        return delegate.evaluateAll(request);
    }

    private static ScorerOutput run(SandboxRunner runner, String code, Builder builder) {
        SandboxRunner.Result result = runner.run(new SandboxRunner.Request(
                code, builder.fileName, builder.command, builder.environment, builder.timeout));

        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("exit_code", result.exitCode());
        if (result.timedOut()) {
            metadata.put("timed_out", true);
        }
        return new ScorerOutput.Single(
                Score.of(result.succeeded()),
                result.succeeded() ? null : result.diagnostics(),
                metadata,
                null);
    }

    /** Builder for {@link ExecutionEvaluator}. */
    public static final class Builder {
        private SandboxRunner sandbox;
        private String fileName = "outputs.py";
        private List<String> command = List.of("python", "outputs.py");
        private boolean fileNameSet;
        private boolean commandSet;
        private Map<String, String> environment = Map.of();
        private Duration timeout = Duration.ofMinutes(2);
        private CodeExtractionStrategy strategy = CodeExtractionStrategy.NONE;
        private Function<Object, String> customExtractor;
        private JudgeModel extractionModel;
        private EvalTracer tracer;

        public Builder sandbox(SandboxRunner sandbox) {
            this.sandbox = sandbox;
            return this;
        }

        /**
         * The file the extracted code is written to inside the sandbox.
         *
         * <p>Setting this obliges you to set {@link #command} too — the default command names the
         * default file, and changing one without the other would run a file that does not exist.
         */
        public Builder fileName(String fileName) {
            this.fileName = fileName;
            this.fileNameSet = true;
            return this;
        }

        /** The command to run, e.g. {@code "node", "outputs.js"}. */
        public Builder command(String... command) {
            this.command = List.of(command);
            this.commandSet = true;
            return this;
        }

        public Builder environment(Map<String, String> environment) {
            this.environment = environment;
            return this;
        }

        public Builder timeout(Duration timeout) {
            this.timeout = timeout;
            return this;
        }

        public Builder extractionStrategy(CodeExtractionStrategy strategy) {
            this.strategy = strategy;
            return this;
        }

        public Builder extractor(Function<Object, String> extractor) {
            this.customExtractor = extractor;
            return this;
        }

        public Builder extractionModel(JudgeModel model) {
            this.extractionModel = model;
            return this;
        }

        public Builder tracer(EvalTracer tracer) {
            this.tracer = tracer;
            return this;
        }

        public ExecutionEvaluator build() {
            // Catching this here beats a "file not found" from inside a container, which is a
            // confusing way to learn that the two settings disagree.
            if (fileNameSet != commandSet) {
                throw new IllegalArgumentException(
                        "fileName and command must be set together: the defaults are \"outputs.py\" and "
                                + "[python, outputs.py], so changing one alone runs the wrong file. "
                                + "Set both, or neither.");
            }
            return new ExecutionEvaluator(this);
        }
    }
}
