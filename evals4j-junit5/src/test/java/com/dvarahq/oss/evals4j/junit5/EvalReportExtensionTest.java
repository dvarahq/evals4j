package com.dvarahq.oss.evals4j.junit5;

import com.dvarahq.oss.evals4j.EvalRequest;
import com.dvarahq.oss.evals4j.string.ExactMatch;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.junit.platform.engine.TestExecutionResult;
import org.junit.platform.testkit.engine.EngineTestKit;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.platform.engine.discovery.DiscoverySelectors.selectClass;

/**
 * Runs the extension through the real Jupiter engine.
 *
 * <p>The suites below are executed by {@link EngineTestKit}, not by surefire — their names avoid the
 * {@code *Test} pattern deliberately, so they do not also run standalone and write stray reports.
 */
class EvalReportExtensionTest {

    @AfterEach
    void clearTheConfiguredPath() {
        System.clearProperty(EvalReportExtension.REPORT_PATH_PROPERTY);
        System.clearProperty(EvalReportExtension.MAX_DROP_PROPERTY);
    }

    /** Writes a baseline as if a previous run had left it. */
    private static void givenPreviousRun(Path json, String key, double mean, long count)
            throws Exception {
        Files.writeString(
                json,
                """
                {"schema":1,"generatedAt":"2026-01-01T00:00:00Z","keys":{"%s":{"mean":%s,"count":%d}}}
                """
                        .formatted(key, mean, count));
    }

    @Test
    void writesAJsonSnapshotAlongsideTheMarkdown(@TempDir Path directory) throws Exception {
        Path report = directory.resolve("evals.md");
        System.setProperty(EvalReportExtension.REPORT_PATH_PROPERTY, report.toString());

        EngineTestKit.engine("junit-jupiter")
                .selectors(selectClass(ScoringSuite.class))
                .execute()
                .testEvents()
                .assertStatistics(stats -> stats.succeeded(1).failed(0));

        Path json = directory.resolve("evals.json");
        assertThat(json).exists();
        assertThat(EvalReport.readJson(json))
                .containsKey(ExactMatch.FEEDBACK_KEY)
                .extractingByKey(ExactMatch.FEEDBACK_KEY)
                .extracting(EvalReport.KeySummary::mean)
                .isEqualTo(1.0);
    }

    @Test
    void showsTheChangeAgainstThePreviousRun(@TempDir Path directory) throws Exception {
        System.setProperty(
                EvalReportExtension.REPORT_PATH_PROPERTY, directory.resolve("evals.md").toString());
        givenPreviousRun(directory.resolve("evals.json"), ExactMatch.FEEDBACK_KEY, 1.0, 1);

        // This run scores 0.0 against a baseline of 1.0.
        EngineTestKit.engine("junit-jupiter")
                .selectors(selectClass(MismatchSuite.class))
                .execute()
                .testEvents()
                .assertStatistics(stats -> stats.succeeded(1).failed(0));

        assertThat(Files.readString(directory.resolve("evals.md"))).contains("-1.000");
    }

    @Test
    void readsTheBaselineOncePerRunRatherThanPerClass(@TempDir Path directory) throws Exception {
        System.setProperty(
                EvalReportExtension.REPORT_PATH_PROPERTY, directory.resolve("evals.md").toString());
        givenPreviousRun(directory.resolve("evals.json"), ExactMatch.FEEDBACK_KEY, 0.0, 1);

        // Two classes, each scoring 1.0. The first one's afterAll overwrites evals.json with 1.000.
        // If the baseline were re-read per class, the second class would compare against that and
        // report +0.000; against the real baseline of 0.0 it must be +1.000.
        EngineTestKit.engine("junit-jupiter")
                .selectors(selectClass(ScoringSuite.class), selectClass(SecondScoringSuite.class))
                .execute()
                .testEvents()
                .assertStatistics(stats -> stats.succeeded(2).failed(0));

        assertThat(Files.readString(directory.resolve("evals.md"))).contains("+1.000");
    }

    @Test
    void aCorruptBaselineIsIgnoredRatherThanFatal(@TempDir Path directory) throws Exception {
        System.setProperty(
                EvalReportExtension.REPORT_PATH_PROPERTY, directory.resolve("evals.md").toString());
        Files.writeString(directory.resolve("evals.json"), "{ this is not json");

        EngineTestKit.engine("junit-jupiter")
                .selectors(selectClass(ScoringSuite.class))
                .execute()
                .testEvents()
                .assertStatistics(stats -> stats.succeeded(1).failed(0));

        // No baseline, so no delta — but the run itself must not have been harmed by the bad file.
        assertThat(Files.readString(directory.resolve("evals.md"))).contains("—");
    }

    @Test
    void theGateFailsTheRunWhenAKeyDrops(@TempDir Path directory) throws Exception {
        System.setProperty(
                EvalReportExtension.REPORT_PATH_PROPERTY, directory.resolve("evals.md").toString());
        System.setProperty(EvalReportExtension.MAX_DROP_PROPERTY, "0.1");
        givenPreviousRun(directory.resolve("evals.json"), ExactMatch.FEEDBACK_KEY, 1.0, 1);

        EngineTestKit.engine("junit-jupiter")
                .selectors(selectClass(MismatchSuite.class))
                .execute()
                .containerEvents()
                .assertStatistics(stats -> stats.failed(1));

        // The report is still written: you cannot act on a regression you cannot see.
        assertThat(directory.resolve("evals.md")).exists();
    }

    @Test
    void theGateRejectsANonNumericThreshold(@TempDir Path directory) throws Exception {
        System.setProperty(
                EvalReportExtension.REPORT_PATH_PROPERTY, directory.resolve("evals.md").toString());
        System.setProperty(EvalReportExtension.MAX_DROP_PROPERTY, "not-a-number");
        givenPreviousRun(directory.resolve("evals.json"), ExactMatch.FEEDBACK_KEY, 1.0, 1);

        var failedContainers = EngineTestKit.engine("junit-jupiter")
                .selectors(selectClass(MismatchSuite.class))
                .execute()
                .containerEvents()
                .assertStatistics(stats -> stats.failed(1))
                .failed()
                .list();

        assertThat(directory.resolve("evals.md")).exists();
        assertThat(failedContainers).singleElement().satisfies(event -> {
            Throwable failure = event.getRequiredPayload(TestExecutionResult.class)
                    .getThrowable()
                    .orElseThrow();
            assertThat(failure)
                    // Jupiter wraps extension failures, so the invalid threshold is carried as the cause.
                    .hasCauseInstanceOf(IllegalArgumentException.class)
                    .cause()
                    .hasMessageContaining(EvalReportExtension.MAX_DROP_PROPERTY)
                    .hasMessageContaining("not-a-number");
        });
    }

    @Test
    void noGateWhenTheThresholdIsUnset(@TempDir Path directory) throws Exception {
        System.setProperty(
                EvalReportExtension.REPORT_PATH_PROPERTY, directory.resolve("evals.md").toString());
        givenPreviousRun(directory.resolve("evals.json"), ExactMatch.FEEDBACK_KEY, 1.0, 1);

        EngineTestKit.engine("junit-jupiter")
                .selectors(selectClass(MismatchSuite.class))
                .execute()
                .containerEvents()
                .assertStatistics(stats -> stats.failed(0));
    }

    @Test
    void collectsFromAnEvaluatorThatWasNeverGivenTheReport(@TempDir Path directory) throws Exception {
        Path report = directory.resolve("evals.md");
        System.setProperty(EvalReportExtension.REPORT_PATH_PROPERTY, report.toString());

        // The suite below never calls withTracer/.tracer — the extension's EvalScope is the only way
        // its score can reach the report.
        EngineTestKit.engine("junit-jupiter")
                .selectors(selectClass(AmbientSuite.class))
                .execute()
                .testEvents()
                .assertStatistics(stats -> stats.succeeded(1).failed(0));

        assertThat(Files.readString(report)).contains(ExactMatch.FEEDBACK_KEY);
    }

    @Test
    void derivesTheJsonPathFromTheMarkdownPath() {
        System.setProperty(EvalReportExtension.REPORT_PATH_PROPERTY, "build/reports/evals.md");
        assertThat(EvalReportExtension.jsonPath()).isEqualTo(Path.of("build/reports/evals.json"));
    }

    @Test
    void injectsAReportAndWritesItAfterTheClass(@TempDir Path directory) throws Exception {
        Path report = directory.resolve("evals.md");
        System.setProperty(EvalReportExtension.REPORT_PATH_PROPERTY, report.toString());

        EngineTestKit.engine("junit-jupiter")
                .selectors(selectClass(ScoringSuite.class))
                .execute()
                .testEvents()
                .assertStatistics(stats -> stats.succeeded(1).failed(0));

        assertThat(report).exists();
        assertThat(Files.readString(report))
                .contains("# Eval report")
                .contains(ExactMatch.FEEDBACK_KEY);
    }

    @Test
    void accumulatesEveryClassInTheRunIntoOneReport(@TempDir Path directory) throws Exception {
        Path report = directory.resolve("evals.md");
        System.setProperty(EvalReportExtension.REPORT_PATH_PROPERTY, report.toString());

        EngineTestKit.engine("junit-jupiter")
                .selectors(selectClass(ScoringSuite.class), selectClass(SecondScoringSuite.class))
                .execute()
                .testEvents()
                .assertStatistics(stats -> stats.succeeded(2).failed(0));

        // Both classes scored once each. A per-class report would have overwritten the first.
        assertThat(Files.readString(report)).contains("| 2 |");
    }

    @Test
    void writesNothingWhenNoEvaluationWasRecorded(@TempDir Path directory) {
        Path report = directory.resolve("evals.md");
        System.setProperty(EvalReportExtension.REPORT_PATH_PROPERTY, report.toString());

        EngineTestKit.engine("junit-jupiter")
                .selectors(selectClass(SilentSuite.class))
                .execute()
                .testEvents()
                .assertStatistics(stats -> stats.succeeded(1).failed(0));

        // An empty report is worse than none: it would overwrite a real one from an earlier class.
        assertThat(report).doesNotExist();
    }

    @Test
    void evalSuiteRegistersTheExtension(@TempDir Path directory) throws Exception {
        Path report = directory.resolve("evals.md");
        System.setProperty(EvalReportExtension.REPORT_PATH_PROPERTY, report.toString());

        EngineTestKit.engine("junit-jupiter")
                .selectors(selectClass(AnnotatedSuite.class))
                .execute()
                .testEvents()
                .assertStatistics(stats -> stats.succeeded(1).failed(0));

        assertThat(Files.readString(report)).contains(ExactMatch.FEEDBACK_KEY);
    }

    @Test
    void autodetectionRegistersTheExtensionWithoutAnyAnnotation(@TempDir Path directory)
            throws Exception {
        Path report = directory.resolve("evals.md");
        System.setProperty(EvalReportExtension.REPORT_PATH_PROPERTY, report.toString());

        // The suite below carries no annotation at all: only the META-INF/services declaration can
        // resolve its EvalReport parameter, so this fails if that file is missing or misnamed.
        EngineTestKit.engine("junit-jupiter")
                .configurationParameter("junit.jupiter.extensions.autodetection.enabled", "true")
                .selectors(selectClass(UnannotatedSuite.class))
                .execute()
                .testEvents()
                .assertStatistics(stats -> stats.succeeded(1).failed(0));

        assertThat(Files.readString(report)).contains(ExactMatch.FEEDBACK_KEY);
    }

    @Test
    void withoutAutodetectionAnUnannotatedSuiteIsNotExtended(@TempDir Path directory) {
        System.setProperty(
                EvalReportExtension.REPORT_PATH_PROPERTY, directory.resolve("evals.md").toString());

        // Autodetection is off by default, so the jar being on the classpath must not silently
        // extend everything: the unresolvable parameter is the proof.
        EngineTestKit.engine("junit-jupiter")
                .selectors(selectClass(UnannotatedSuite.class))
                .execute()
                .testEvents()
                .assertStatistics(stats -> stats.succeeded(0).failed(1));
    }

    @Test
    void defaultsToTheTargetDirectoryWhenNothingIsConfigured() {
        assertThat(EvalReportExtension.reportPath())
                .isEqualTo(Path.of(EvalReportExtension.DEFAULT_REPORT_PATH));
    }

    @Test
    void honoursTheConfiguredPath() {
        System.setProperty(EvalReportExtension.REPORT_PATH_PROPERTY, "build/reports/evals.md");
        assertThat(EvalReportExtension.reportPath()).isEqualTo(Path.of("build/reports/evals.md"));
    }

    @ExtendWith(EvalReportExtension.class)
    static class ScoringSuite {

        @Test
        void scores(EvalReport report) {
            ExactMatch.create().withTracer(report).evaluate(EvalRequest.of(null, "hello", "hello"));
        }
    }

    @ExtendWith(EvalReportExtension.class)
    static class SecondScoringSuite {

        @Test
        void alsoScores(EvalReport report) {
            ExactMatch.create().withTracer(report).evaluate(EvalRequest.of(null, "world", "world"));
        }
    }

    /** Wires no tracer at all; relies entirely on the scope the extension opens. */
    @EvalSuite
    static class AmbientSuite {

        @Test
        void scoresWithoutWiringATracer() {
            ExactMatch.create().evaluate(EvalRequest.of(null, "ambient", "ambient"));
        }
    }

    /** Scores 0.0, for testing a drop against a baseline. */
    @ExtendWith(EvalReportExtension.class)
    static class MismatchSuite {

        @Test
        void scoresAMismatch(EvalReport report) {
            ExactMatch.create().withTracer(report).evaluate(EvalRequest.of(null, "left", "right"));
        }
    }

    @EvalSuite
    static class AnnotatedSuite {

        @Test
        void scoresUnderTheComposedAnnotation(EvalReport report) {
            ExactMatch.create().withTracer(report).evaluate(EvalRequest.of(null, "same", "same"));
        }
    }

    /** Deliberately unannotated — only autodetection can supply its parameter. */
    static class UnannotatedSuite {

        @Test
        void scoresWithoutBeingAnnotated(EvalReport report) {
            ExactMatch.create().withTracer(report).evaluate(EvalRequest.of(null, "auto", "auto"));
        }
    }

    @ExtendWith(EvalReportExtension.class)
    static class SilentSuite {

        @Test
        void recordsNothing(EvalReport report) {
            assertThat(report.results()).isEmpty();
        }
    }
}
