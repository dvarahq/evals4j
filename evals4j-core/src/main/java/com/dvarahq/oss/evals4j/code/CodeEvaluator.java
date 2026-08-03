package com.dvarahq.oss.evals4j.code;

import com.dvarahq.oss.evals4j.EvalRequest;
import com.dvarahq.oss.evals4j.Evaluator;
import com.dvarahq.oss.evals4j.ScorerOutput;
import com.dvarahq.oss.evals4j.ScorerRunner;
import com.dvarahq.oss.evals4j.result.EvaluatorResult;
import com.dvarahq.oss.evals4j.result.Score;
import com.dvarahq.oss.evals4j.spi.EvalTracer;

import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * Extracts code from a model's output and hands it to a checker.
 *
 * <p>The scaffolding shared by {@link JavacEvaluator}, {@link CliCheckEvaluator} and
 * {@link ExecutionEvaluator}: extract, then score, with a failed extraction reported as a failure
 * carrying {@link #EXTRACTION_FAILED} metadata rather than an exception — a model that answered in
 * prose is a result, not an error.
 */
public final class CodeEvaluator implements Evaluator {

    /** Metadata flag set when no code could be extracted. */
    public static final String EXTRACTION_FAILED = "code_extraction_failed";

    static final String NO_CODE_COMMENT = "No code could be extracted from the output.";

    private final String runName;
    private final String feedbackKey;
    private final Function<String, ScorerOutput> checker;
    private final CodeExtractor extractor;
    private final EvalTracer tracer;

    CodeEvaluator(
            String runName,
            String feedbackKey,
            Function<String, ScorerOutput> checker,
            CodeExtractor extractor,
            EvalTracer tracer) {
        this.runName = runName;
        this.feedbackKey = feedbackKey;
        this.checker = checker;
        this.extractor = extractor;
        this.tracer = tracer == null ? EvalTracer.NO_OP : tracer;
    }

    /** Wraps any code checker with the extraction step. */
    public static CodeEvaluator of(
            String runName, String feedbackKey, CodeExtractor extractor, Function<String, ScorerOutput> checker) {
        return new CodeEvaluator(runName, feedbackKey, checker, extractor, null);
    }

    @Override
    public List<EvaluatorResult> evaluateAll(EvalRequest request) {
        return ScorerRunner.run(runName, feedbackKey, this::score, request, tracer);
    }

    private ScorerOutput score(EvalRequest request) {
        String code = extractor.extract(request.outputs());
        if (code == null) {
            return extractionFailed();
        }
        return checker.apply(code);
    }

    static ScorerOutput.Single extractionFailed() {
        return new ScorerOutput.Single(
                Score.of(false), NO_CODE_COMMENT, Map.of(EXTRACTION_FAILED, true), null);
    }
}
