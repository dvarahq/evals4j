package com.dvarahq.oss.evals4j.code;

import com.dvarahq.oss.evals4j.EvalRequest;
import com.dvarahq.oss.evals4j.Evaluator;
import com.dvarahq.oss.evals4j.ScorerOutput;
import com.dvarahq.oss.evals4j.result.EvaluatorResult;
import com.dvarahq.oss.evals4j.spi.EvalTracer;
import com.dvarahq.oss.evals4j.spi.JudgeModel;

import javax.tools.Diagnostic;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.SimpleJavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;
import java.io.IOException;
import java.io.OutputStream;
import java.io.StringWriter;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Scores whether generated Java actually compiles.
 *
 * <p>The JVM analogue of OpenEvals' pyright and mypy evaluators. Rather than shelling out to a type
 * checker, it uses the JDK Compiler API in-process, so there is nothing to install and the
 * diagnostics come back structured.
 *
 * <p>Compilation is to a temporary output directory that is deleted afterwards; the code is compiled
 * but never run — see {@link ExecutionEvaluator} for that, and note that running model-generated
 * code needs a sandbox.
 */
public final class JavacEvaluator implements Evaluator {

    public static final String FEEDBACK_KEY = "compilation_succeeded";
    public static final String RUN_NAME = "javac_evaluator";

    /** Finds the public (or otherwise first) top-level type, to name the in-memory source file. */
    private static final Pattern TYPE_DECLARATION = Pattern.compile(
            "(?m)^\\s*(?:public\\s+|final\\s+|abstract\\s+|sealed\\s+|non-sealed\\s+)*"
                    + "(?:class|interface|enum|record)\\s+(\\w+)");

    private final CodeEvaluator delegate;

    private JavacEvaluator(Builder builder) {
        this.delegate = new CodeEvaluator(
                RUN_NAME,
                FEEDBACK_KEY,
                code -> compile(code, builder.compilerOptions),
                CodeExtractor.from(builder.strategy, builder.customExtractor, builder.extractionModel),
                builder.tracer);
    }

    public static Builder builder() {
        return new Builder();
    }

    public static JavacEvaluator create() {
        return builder().build();
    }

    @Override
    public List<EvaluatorResult> evaluateAll(EvalRequest request) {
        return delegate.evaluateAll(request);
    }

    /** Compiles a snippet and reports whether it produced any errors. */
    static ScorerOutput compile(String code, List<String> options) {
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        if (compiler == null) {
            throw new IllegalStateException(
                    "No Java compiler is available. JavacEvaluator needs a JDK, not a JRE.");
        }

        DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();
        Path outputDir = null;
        try (StandardJavaFileManager fileManager =
                compiler.getStandardFileManager(diagnostics, null, null)) {

            outputDir = Files.createTempDirectory("evals4j-javac");
            List<String> allOptions = new ArrayList<>(List.of("-d", outputDir.toString()));
            allOptions.addAll(options);

            JavaFileObject source = new InMemorySource(typeNameOf(code), code);
            // Discard compiler chatter; the diagnostics collector is the only output that matters.
            StringWriter sink = new StringWriter();
            boolean success = compiler
                    .getTask(sink, fileManager, diagnostics, allOptions, null, List.of(source))
                    .call();

            List<Diagnostic<? extends JavaFileObject>> errors = diagnostics.getDiagnostics().stream()
                    .filter(diagnostic -> diagnostic.getKind() == Diagnostic.Kind.ERROR)
                    .toList();

            if (success && errors.isEmpty()) {
                return ScorerOutput.of(true);
            }
            StringBuilder report = new StringBuilder();
            for (Diagnostic<? extends JavaFileObject> error : errors) {
                report.append(error.getLineNumber())
                        .append(':')
                        .append(error.getColumnNumber())
                        .append(": ")
                        .append(error.getMessage(Locale.ROOT))
                        .append('\n');
            }
            String comment = report.length() == 0 ? "Compilation failed." : report.toString().strip();
            return new ScorerOutput.Single(
                    com.dvarahq.oss.evals4j.result.Score.of(false),
                    comment,
                    Map.of("error_count", errors.size()),
                    null);
        } catch (IOException e) {
            throw new IllegalStateException("Could not compile the extracted code", e);
        } finally {
            deleteRecursively(outputDir);
        }
    }

    /**
     * The name javac expects the file to have.
     *
     * <p>A public type must live in a file of the same name, so a wrong guess produces a spurious
     * error about the filename rather than the real problem with the code.
     */
    static String typeNameOf(String code) {
        Matcher matcher = TYPE_DECLARATION.matcher(code);
        return matcher.find() ? matcher.group(1) : "Snippet";
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
                    // A leftover temp file is not worth failing an evaluation over.
                }
            });
        } catch (IOException ignored) {
            // Same.
        }
    }

    /** A source file held in a string rather than on disk. */
    private static final class InMemorySource extends SimpleJavaFileObject {
        private final String code;

        InMemorySource(String typeName, String code) {
            super(URI.create("string:///" + typeName + Kind.SOURCE.extension), Kind.SOURCE);
            this.code = code;
        }

        @Override
        public CharSequence getCharContent(boolean ignoreEncodingErrors) {
            return code;
        }

        @Override
        public OutputStream openOutputStream() {
            throw new UnsupportedOperationException();
        }
    }

    /** Builder for {@link JavacEvaluator}. */
    public static final class Builder {
        private CodeExtractionStrategy strategy = CodeExtractionStrategy.NONE;
        private Function<Object, String> customExtractor;
        private JudgeModel extractionModel;
        private final List<String> compilerOptions = new ArrayList<>(List.of("-Xlint:none", "-nowarn"));
        private EvalTracer tracer;

        public Builder extractionStrategy(CodeExtractionStrategy strategy) {
            this.strategy = strategy;
            return this;
        }

        public Builder extractor(Function<Object, String> extractor) {
            this.customExtractor = extractor;
            return this;
        }

        /** Required when the extraction strategy is {@link CodeExtractionStrategy#LLM}. */
        public Builder extractionModel(JudgeModel model) {
            this.extractionModel = model;
            return this;
        }

        /**
         * Extra javac options, e.g. {@code --release 17} or a {@code -classpath}. Appended to the
         * defaults {@code -Xlint:none -nowarn}; use {@link #replaceCompilerOptions} to drop those.
         */
        public Builder compilerOptions(String... options) {
            this.compilerOptions.addAll(List.of(options));
            return this;
        }

        /**
         * Replaces the option list outright, defaults included.
         *
         * <p>The defaults silence warnings, which is right for judging whether generated code
         * compiles but wrong if you want the warnings themselves — and appending cannot undo them.
         */
        public Builder replaceCompilerOptions(String... options) {
            this.compilerOptions.clear();
            this.compilerOptions.addAll(List.of(options));
            return this;
        }

        public Builder tracer(EvalTracer tracer) {
            this.tracer = tracer;
            return this;
        }

        public JavacEvaluator build() {
            return new JavacEvaluator(this);
        }
    }
}
