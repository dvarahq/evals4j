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

    @ExtendWith(EvalReportExtension.class)
    static class SilentSuite {

        @Test
        void recordsNothing(EvalReport report) {
            assertThat(report.results()).isEmpty();
        }
    }
}
