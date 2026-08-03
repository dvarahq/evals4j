package com.dvarahq.oss.evals4j.junit5;

import com.dvarahq.oss.evals4j.EvalRequest;
import com.dvarahq.oss.evals4j.string.ExactMatch;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
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
