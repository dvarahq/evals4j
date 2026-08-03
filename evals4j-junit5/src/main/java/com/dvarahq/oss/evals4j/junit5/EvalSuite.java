package com.dvarahq.oss.evals4j.junit5;

import org.junit.jupiter.api.extension.ExtendWith;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Inherited;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a class as an eval suite, registering {@link EvalReportExtension}.
 *
 * <pre>{@code
 * @EvalSuite
 * class ConcisenessEvalTest {
 *
 *     @Test
 *     void staysConcise(EvalReport report) {
 *         ...
 *     }
 * }
 * }</pre>
 *
 * <p>Exactly equivalent to {@code @ExtendWith(EvalReportExtension.class)}, and reads as what it is:
 * these tests score a model rather than assert on a function.
 *
 * <p><strong>No attributes, deliberately.</strong> The obvious one would be a report path, but the
 * report is one per run rather than one per class — two annotated classes naming different files
 * would have no defensible answer as to which wins. The path is configured once for the run, through
 * {@link EvalReportExtension#REPORT_PATH_PROPERTY}, matching the scope of the thing it names.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Documented
@Inherited
@ExtendWith(EvalReportExtension.class)
public @interface EvalSuite {}
