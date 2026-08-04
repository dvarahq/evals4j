package com.dvarahq.oss.evals4j.junit5;

import org.junit.jupiter.api.extension.AfterAllCallback;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.extension.ParameterContext;
import org.junit.jupiter.api.extension.ParameterResolver;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Supplies an {@link EvalReport} to a test suite and writes it out, so the suite does not have to
 * hold the tracer itself or remember an {@code @AfterAll}.
 *
 * <pre>{@code
 * @EvalSuite                      // or @ExtendWith(EvalReportExtension.class)
 * class ConcisenessEvalTest {
 *
 *     @Test
 *     void staysConcise(EvalReport report) {
 *         LlmAsJudge judge = LlmAsJudge.builder()
 *                 .prompt(Prompts.CONCISENESS_PROMPT)
 *                 .model(model)
 *                 .tracer(report)          // still explicit — see below
 *                 .build();
 *         EvalAssert.assertPassed(judge.evaluate(question, answer));
 *     }
 * }
 * }</pre>
 *
 * <p><strong>The tracer wiring stays explicit.</strong> The extension cannot attach itself to an
 * evaluator the test builds, because an evaluator takes its tracer at construction and evals4j has no
 * ambient one — a global default tracer would be shared mutable state reaching across every module.
 * So the extension owns the report's lifetime and output, and the suite says which evaluators report
 * into it.
 *
 * <p><strong>One report per run, not per class.</strong> The report lives in the root context, so
 * every test class in the run accumulates into it and the summary covers the whole suite. That is the
 * point of the report: a mean that moves from 0.9 to 0.7 over a month is the signal, and a per-class
 * file would fragment it.
 *
 * <p>The report is written after each test class, so the file is complete once the last one finishes.
 * Writing only at the very end would mean a crashed run left nothing behind.
 *
 * <p>Set {@value #REPORT_PATH_PROPERTY} to choose where it goes; it defaults to
 * {@value #DEFAULT_REPORT_PATH}. Nothing is written when no results were collected, so registering
 * the extension on a class that records none leaves no empty file.
 *
 * <p><strong>The trend.</strong> A machine-readable twin is written beside the Markdown — same name,
 * {@code .json} — and read back at the start of the next run as its baseline, which is what puts the
 * change column in the table. Keep that file between runs (commit it, or cache it in CI) and the
 * report shows movement rather than a single snapshot; delete it and the next run simply starts over.
 * Set {@value #MAX_DROP_PROPERTY} to fail the run when a key falls further than you will tolerate.
 *
 * <p><strong>Automatic registration.</strong> This extension is declared in {@code META-INF/services},
 * so a build that sets {@code junit.jupiter.extensions.autodetection.enabled=true} gets it on every
 * test class without any annotation — which is what you want when a whole module is eval suites.
 * JUnit leaves autodetection off by default, so putting this jar on the classpath changes nothing on
 * its own. Even when it is on, a class that records no results writes no file, so ordinary unit tests
 * in the same run are unaffected.
 */
public final class EvalReportExtension implements ParameterResolver, AfterAllCallback {

    /** System property naming the file the report is written to. */
    public static final String REPORT_PATH_PROPERTY = "evals4j.report";

    /** Where the report goes when {@value #REPORT_PATH_PROPERTY} is not set. */
    public static final String DEFAULT_REPORT_PATH = "target/evals4j-report.md";

    /**
     * Fail the run if any key's mean falls more than this below the previous run's. Unset means no
     * gate, which is the default — a threshold is a policy decision, not something to assume.
     */
    public static final String MAX_DROP_PROPERTY = "evals4j.report.maxDrop";

    private static final ExtensionContext.Namespace NAMESPACE =
            ExtensionContext.Namespace.create(EvalReportExtension.class);

    private static final String REPORT_KEY = "report";

    /**
     * The report and the baseline it is measured against.
     *
     * <p>The baseline is read once, when the run's report is created, and never re-read. Reading it
     * at write time instead would be wrong in a way that is easy to miss: every class writes the
     * whole report, so the second class would read back the file the first class had just written
     * and every delta after the first class would come out as zero.
     *
     * <p>Closing it runs the regression gate. That happens when the run ends rather than in
     * {@code afterAll}, because a key scored by two classes is only complete once both have run —
     * gating per class would fail on a partial mean.
     */
    private record Run(EvalReport report, Map<String, EvalReport.KeySummary> baseline)
            implements AutoCloseable {

        @Override
        public void close() {
            gate(this);
        }
    }

    @Override
    public boolean supportsParameter(ParameterContext parameterContext, ExtensionContext context) {
        return parameterContext.getParameter().getType() == EvalReport.class;
    }

    @Override
    public Object resolveParameter(ParameterContext parameterContext, ExtensionContext context) {
        return reportFor(context);
    }

    @Override
    public void afterAll(ExtensionContext context) {
        Run run = runFor(context);
        // Classes may finish concurrently under parallel execution, and every one of them writes the
        // whole accumulated report. Serialising on the report keeps two writers from interleaving
        // into the same file; the last to finish writes the most complete version.
        synchronized (run.report()) {
            // An empty report says nothing, and writing one would overwrite a good report from an
            // earlier class in the same run with an empty table.
            if (run.report().results().isEmpty()) {
                return;
            }
            run.report().writeMarkdown(reportPath(), run.baseline());
            run.report().writeJson(jsonPath());
        }
    }

    /**
     * The run's report, creating it on first use.
     *
     * <p>Public so a suite that cannot take a parameter — a {@code @RegisterExtension} field, say, or
     * a helper called from several classes — can still reach the same instance.
     */
    public static EvalReport reportFor(ExtensionContext context) {
        return runFor(context).report();
    }

    private static Run runFor(ExtensionContext context) {
        return context.getRoot()
                .getStore(NAMESPACE)
                .getOrComputeIfAbsent(
                        REPORT_KEY,
                        key -> new Run(new EvalReport(), EvalReport.readJson(jsonPath())),
                        Run.class);
    }

    /** Where the report will be written, honouring {@value #REPORT_PATH_PROPERTY}. */
    public static Path reportPath() {
        String configured = System.getProperty(REPORT_PATH_PROPERTY);
        return Path.of(configured == null || configured.isBlank() ? DEFAULT_REPORT_PATH : configured);
    }

    /**
     * Where the machine-readable report goes: the markdown path with a {@code .json} extension.
     *
     * <p>Derived rather than separately configurable, so the pair cannot drift apart and leave a run
     * comparing itself against some other suite's history.
     */
    public static Path jsonPath() {
        Path markdown = reportPath();
        String name = markdown.getFileName().toString();
        int dot = name.lastIndexOf('.');
        String json = (dot < 0 ? name : name.substring(0, dot)) + ".json";
        return markdown.getParent() == null ? Path.of(json) : markdown.getParent().resolve(json);
    }

    /** Fails the run when a key dropped further than {@value #MAX_DROP_PROPERTY} allows. */
    private static void gate(Run run) {
        String configured = System.getProperty(MAX_DROP_PROPERTY);
        if (configured == null || configured.isBlank()) {
            return;
        }
        double maxDrop;
        try {
            maxDrop = Double.parseDouble(configured.trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(
                    MAX_DROP_PROPERTY + " must be a number, but was \"" + configured + "\"", e);
        }

        List<String> regressions = new ArrayList<>();
        run.report().summaries().forEach((key, now) -> {
            EvalReport.KeySummary before = run.baseline().get(key);
            if (before == null) {
                return;
            }
            double drop = before.mean() - now.mean();
            if (drop > maxDrop) {
                regressions.add(String.format(
                        "%s: %.3f -> %.3f, down %.3f", key, before.mean(), now.mean(), drop));
            }
        });

        if (!regressions.isEmpty()) {
            throw new AssertionError("Eval scores dropped by more than " + maxDrop + ":\n  "
                    + String.join("\n  ", regressions));
        }
    }
}
