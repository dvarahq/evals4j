package com.dvarahq.oss.evals4j.junit5;

import org.junit.jupiter.api.extension.AfterAllCallback;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.extension.ParameterContext;
import org.junit.jupiter.api.extension.ParameterResolver;

import java.nio.file.Path;

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

    private static final ExtensionContext.Namespace NAMESPACE =
            ExtensionContext.Namespace.create(EvalReportExtension.class);

    private static final String REPORT_KEY = "report";

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
        EvalReport report = reportFor(context);
        // Classes may finish concurrently under parallel execution, and every one of them writes the
        // whole accumulated report. Serialising on the report keeps two writers from interleaving
        // into the same file; the last to finish writes the most complete version.
        synchronized (report) {
            // An empty report says nothing, and writing one would overwrite a good report from an
            // earlier class in the same run with an empty table.
            if (report.results().isEmpty()) {
                return;
            }
            report.writeMarkdown(reportPath());
        }
    }

    /**
     * The run's report, creating it on first use.
     *
     * <p>Public so a suite that cannot take a parameter — a {@code @RegisterExtension} field, say, or
     * a helper called from several classes — can still reach the same instance.
     */
    public static EvalReport reportFor(ExtensionContext context) {
        return context.getRoot()
                .getStore(NAMESPACE)
                .getOrComputeIfAbsent(REPORT_KEY, key -> new EvalReport(), EvalReport.class);
    }

    /** Where the report will be written, honouring {@value #REPORT_PATH_PROPERTY}. */
    public static Path reportPath() {
        String configured = System.getProperty(REPORT_PATH_PROPERTY);
        return Path.of(configured == null || configured.isBlank() ? DEFAULT_REPORT_PATH : configured);
    }
}
