package io.github.grabdoc.evals4j.code;

import io.github.grabdoc.evals4j.EvalRequest;
import io.github.grabdoc.evals4j.Evaluator;
import io.github.grabdoc.evals4j.ScorerOutput;
import io.github.grabdoc.evals4j.result.EvaluatorResult;
import io.github.grabdoc.evals4j.result.Score;
import io.github.grabdoc.evals4j.spi.EvalTracer;
import io.github.grabdoc.evals4j.spi.JudgeModel;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.stream.Stream;

/**
 * Runs an external checker over the extracted code and scores on its exit status.
 *
 * <p>The general form of OpenEvals' pyright and mypy evaluators: point it at whatever CLI matches
 * the language the model was asked to write — {@code mypy}, {@code tsc}, {@code ruff},
 * {@code shellcheck}. For Java, {@link JavacEvaluator} is faster and needs nothing installed.
 *
 * <p>The checker runs as a local subprocess with no isolation. That is appropriate for a linter or
 * type checker, which reads the file rather than executing it — but do not point this at an
 * interpreter. Use {@link ExecutionEvaluator} with a sandbox for that.
 */
public final class CliCheckEvaluator implements Evaluator {

    private final CodeEvaluator delegate;

    private CliCheckEvaluator(Builder builder) {
        if (builder.command.isEmpty()) {
            throw new IllegalArgumentException("A command is required — call .command(...)");
        }
        this.delegate = new CodeEvaluator(
                builder.runName,
                builder.feedbackKey,
                code -> check(code, builder),
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

    private static ScorerOutput check(String code, Builder builder) {
        Path directory = null;
        try {
            directory = Files.createTempDirectory("evals4j-check");
            Path file = directory.resolve(builder.fileName);
            Files.writeString(file, code, StandardCharsets.UTF_8);

            List<String> command = new ArrayList<>(builder.command);
            command.add(file.toString());

            Process process = new ProcessBuilder(command)
                    .directory(directory.toFile())
                    .redirectErrorStream(true)
                    .start();

            String output;
            try (var stream = process.getInputStream()) {
                output = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
            }
            boolean finished = process.waitFor(builder.timeout.toMillis(), TimeUnit.MILLISECONDS);
            if (!finished) {
                process.destroyForcibly();
                return new ScorerOutput.Single(
                        Score.of(false), "The checker timed out.", Map.of("timed_out", true), null);
            }

            int exitCode = process.exitValue();
            // Paths leak the temp directory into the comment; replace them with a stable name.
            String comment = output.replace(file.toString(), builder.fileName).strip();
            return new ScorerOutput.Single(
                    Score.of(exitCode == 0),
                    exitCode == 0 ? null : (comment.isEmpty() ? "Checker exited with " + exitCode : comment),
                    Map.of("exit_code", exitCode),
                    null);
        } catch (IOException e) {
            throw new IllegalStateException(
                    "Could not run the checker: " + String.join(" ", builder.command)
                            + ". Is it installed and on the PATH?", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while waiting for the checker", e);
        } finally {
            deleteRecursively(directory);
        }
    }

    private static void deleteRecursively(Path directory) {
        if (directory == null) {
            return;
        }
        try (Stream<Path> paths = Files.walk(directory)) {
            paths.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException ignored) {
                    // Not worth failing an evaluation over.
                }
            });
        } catch (IOException ignored) {
            // Same.
        }
    }

    /** Builder for {@link CliCheckEvaluator}. */
    public static final class Builder {
        private List<String> command = List.of();
        private String fileName = "outputs.txt";
        private String feedbackKey = "check_succeeded";
        private String runName = "cli_check_evaluator";
        private Duration timeout = Duration.ofMinutes(2);
        private CodeExtractionStrategy strategy = CodeExtractionStrategy.NONE;
        private Function<Object, String> customExtractor;
        private JudgeModel extractionModel;
        private EvalTracer tracer;

        /** The checker and its flags. The file path is appended as the final argument. */
        public Builder command(String... command) {
            this.command = List.of(command);
            return this;
        }

        /** The name the code is written under, which decides the file extension the checker sees. */
        public Builder fileName(String fileName) {
            this.fileName = fileName;
            return this;
        }

        public Builder feedbackKey(String feedbackKey) {
            this.feedbackKey = feedbackKey;
            return this;
        }

        public Builder runName(String runName) {
            this.runName = runName;
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

        public CliCheckEvaluator build() {
            return new CliCheckEvaluator(this);
        }
    }
}
