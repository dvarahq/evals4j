package io.github.grabdoc.evals4j.code;

import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.grabdoc.evals4j.EvalRequest;
import io.github.grabdoc.evals4j.internal.Json;
import io.github.grabdoc.evals4j.result.EvaluatorResult;
import io.github.grabdoc.evals4j.testing.FakeJudgeModel;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CodeEvaluatorsTest {

    // ---- markdown extraction ----------------------------------------------------------------

    @Test
    void extractsFencedCodeBlocks() {
        String response = """
                Here is the solution:

                ```java
                int add(int a, int b) { return a + b; }
                ```

                Install it with:

                ```bash
                mvn install
                ```
                """;

        String code = CodeExtraction.fromMarkdownCodeBlocks(response);

        assertThat(code).isEqualTo("int add(int a, int b) { return a + b; }\n");
        assertThat(code).doesNotContain("mvn install");
    }

    @Test
    void joinsMultipleBlocks() {
        String response = """
                ```java
                class A {}
                ```
                and
                ```java
                class B {}
                ```
                """;

        assertThat(CodeExtraction.fromMarkdownCodeBlocks(response))
                .isEqualTo("class A {}\n\nclass B {}\n");
    }

    @Test
    void returnsNullWhenThereIsNoCode() {
        assertThat(CodeExtraction.fromMarkdownCodeBlocks("Just prose, sorry.")).isNull();
        assertThat(CodeExtraction.fromMarkdownCodeBlocks("```json\n{\"a\":1}\n```")).isNull();
    }

    // ---- javac ------------------------------------------------------------------------------

    @Test
    void compilesValidJava() {
        EvaluatorResult result = JavacEvaluator.create()
                .evaluate(EvalRequest.ofOutputs("public class Adder { int add(int a, int b) { return a + b; } }"));

        assertThat(result.key()).isEqualTo("compilation_succeeded");
        assertThat(result.passed()).isTrue();
        assertThat(result.comment()).isNull();
    }

    @Test
    void reportsCompilationErrors() {
        EvaluatorResult result = JavacEvaluator.create()
                .evaluate(EvalRequest.ofOutputs("public class Broken { int add(int a) { return a + b; } }"));

        assertThat(result.passed()).isFalse();
        assertThat(result.comment()).contains("cannot find symbol");
        assertThat(result.metadata()).containsKey("error_count");
    }

    @Test
    void namesTheSourceFileAfterTheDeclaredType() {
        // A public type must live in a file of the same name, or javac reports the wrong problem.
        assertThat(JavacEvaluator.typeNameOf("public final class Widget {}")).isEqualTo("Widget");
        assertThat(JavacEvaluator.typeNameOf("public record Point(int x, int y) {}")).isEqualTo("Point");
        assertThat(JavacEvaluator.typeNameOf("int x = 1;")).isEqualTo("Snippet");
    }

    @Test
    void compilesCodeLiftedOutOfAMarkdownResponse() {
        EvaluatorResult result = JavacEvaluator.builder()
                .extractionStrategy(CodeExtractionStrategy.MARKDOWN_CODE_BLOCKS)
                .build()
                .evaluate(EvalRequest.ofOutputs("""
                        Sure! Here you go:

                        ```java
                        public class Greeter { String hi() { return "hi"; } }
                        ```
                        """));

        assertThat(result.passed()).isTrue();
    }

    @Test
    void scoresAResponseWithNoCodeAsAFailure() {
        EvaluatorResult result = JavacEvaluator.builder()
                .extractionStrategy(CodeExtractionStrategy.MARKDOWN_CODE_BLOCKS)
                .build()
                .evaluate(EvalRequest.ofOutputs("I'd rather not write that."));

        assertThat(result.passed()).isFalse();
        assertThat(result.metadata()).containsEntry(CodeEvaluator.EXTRACTION_FAILED, true);
    }

    // ---- LLM extraction ---------------------------------------------------------------------

    @Test
    void extractsCodeWithAModel() {
        ObjectNode response = Json.object();
        response.put("has_code", true);
        response.put("code", "public class X {}");

        EvaluatorResult result = JavacEvaluator.builder()
                .extractionStrategy(CodeExtractionStrategy.LLM)
                .extractionModel(FakeJudgeModel.returning(response))
                .build()
                .evaluate(EvalRequest.ofOutputs("somewhere in here there is a class"));

        assertThat(result.passed()).isTrue();
    }

    @Test
    void treatsNoCodeFromTheModelAsAFailedExtraction() {
        ObjectNode response = Json.object();
        response.put("has_code", false);
        response.put("code", "");

        EvaluatorResult result = JavacEvaluator.builder()
                .extractionStrategy(CodeExtractionStrategy.LLM)
                .extractionModel(FakeJudgeModel.returning(response))
                .build()
                .evaluate(EvalRequest.ofOutputs("no code here"));

        assertThat(result.passed()).isFalse();
        assertThat(result.metadata()).containsEntry(CodeEvaluator.EXTRACTION_FAILED, true);
    }

    @Test
    void rejectsAnExtractorAndAStrategyTogether() {
        assertThatThrownBy(() -> JavacEvaluator.builder()
                        .extractionStrategy(CodeExtractionStrategy.MARKDOWN_CODE_BLOCKS)
                        .extractor(outputs -> "code")
                        .build())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("cannot both be provided");
    }

    @Test
    void requiresAModelForLlmExtraction() {
        assertThatThrownBy(() -> JavacEvaluator.builder()
                        .extractionStrategy(CodeExtractionStrategy.LLM)
                        .build())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("requires a model");
    }

    @Test
    void usesACustomExtractor() {
        EvaluatorResult result = JavacEvaluator.builder()
                .extractor(outputs -> "public class FromCustom {}")
                .build()
                .evaluate(EvalRequest.ofOutputs("anything"));

        assertThat(result.passed()).isTrue();
    }

    // ---- code LLM-as-judge -------------------------------------------------------------------

    @Test
    void judgesTheExtractedCodeRatherThanTheWholeResponse() {
        FakeJudgeModel model = FakeJudgeModel.scoring(true, "Correct implementation.");

        EvaluatorResult result = CodeLlmAsJudge.builder()
                .model(model)
                .extractionStrategy(CodeExtractionStrategy.MARKDOWN_CODE_BLOCKS)
                .build()
                .evaluate(EvalRequest.builder()
                        .inputs("write an adder")
                        .outputs("""
                                Certainly, here is a lovely adder for you.

                                ```java
                                int add(int a, int b) { return a + b; }
                                ```
                                """)
                        .build());

        assertThat(result.key()).isEqualTo("code_correctness");
        assertThat(result.passed()).isTrue();
        String prompt = model.lastPromptText();
        assertThat(prompt).contains("int add(int a, int b)");
        assertThat(prompt).doesNotContain("Certainly, here is a lovely adder");
    }

    @Test
    void codeJudgeReportsAFailedExtraction() {
        EvaluatorResult result = CodeLlmAsJudge.builder()
                .model(FakeJudgeModel.scoring(true, "never called"))
                .extractionStrategy(CodeExtractionStrategy.MARKDOWN_CODE_BLOCKS)
                .build()
                .evaluate(EvalRequest.of("write an adder", "I decline."));

        assertThat(result.passed()).isFalse();
        assertThat(result.metadata()).containsEntry(CodeEvaluator.EXTRACTION_FAILED, true);
    }

    // ---- execution --------------------------------------------------------------------------

    @Test
    void scoresExecutionOnTheSandboxResult() {
        EvaluatorResult ok = ExecutionEvaluator.builder()
                .sandbox(request -> new io.github.grabdoc.evals4j.spi.SandboxRunner.Result(0, "done", "", false))
                .build()
                .evaluate(EvalRequest.ofOutputs("print('hi')"));

        assertThat(ok.key()).isEqualTo("execution_succeeded");
        assertThat(ok.passed()).isTrue();

        EvaluatorResult failed = ExecutionEvaluator.builder()
                .sandbox(request ->
                        new io.github.grabdoc.evals4j.spi.SandboxRunner.Result(1, "", "NameError: x", false))
                .build()
                .evaluate(EvalRequest.ofOutputs("print(x)"));

        assertThat(failed.passed()).isFalse();
        assertThat(failed.comment()).contains("NameError");
        assertThat(failed.metadata()).containsEntry("exit_code", 1);
    }

    @Test
    void reportsATimeoutDistinctly() {
        EvaluatorResult result = ExecutionEvaluator.builder()
                .sandbox(request ->
                        new io.github.grabdoc.evals4j.spi.SandboxRunner.Result(-1, "", "", true))
                .build()
                .evaluate(EvalRequest.ofOutputs("while True: pass"));

        assertThat(result.passed()).isFalse();
        assertThat(result.comment()).contains("timed out");
        assertThat(result.metadata()).containsEntry("timed_out", true);
    }

    @Test
    void requiresASandbox() {
        assertThatThrownBy(() -> ExecutionEvaluator.builder().build())
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("sandbox runner is required");
    }

    // ---- CLI checker ------------------------------------------------------------------------

    @Test
    void runsAnExternalChecker() {
        // `true` and `false` are POSIX utilities that do nothing but set an exit status.
        EvaluatorResult passing = CliCheckEvaluator.builder()
                .command("true")
                .fileName("outputs.txt")
                .build()
                .evaluate(EvalRequest.ofOutputs("anything"));
        assertThat(passing.passed()).isTrue();

        EvaluatorResult failing = CliCheckEvaluator.builder()
                .command("false")
                .fileName("outputs.txt")
                .build()
                .evaluate(EvalRequest.ofOutputs("anything"));
        assertThat(failing.passed()).isFalse();
        assertThat(failing.metadata()).containsEntry("exit_code", 1);
    }

    @Test
    void requiresACommand() {
        assertThatThrownBy(() -> CliCheckEvaluator.builder().build())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("command is required");
    }

    // ---- message-shaped outputs --------------------------------------------------------------

    @Test
    void readsCodeOutOfAChatResponse() {
        Object outputs = Map.of("messages", List.of(Map.of("role", "assistant", "content", "public class M {}")));

        assertThat(JavacEvaluator.create().evaluate(EvalRequest.ofOutputs(outputs)).passed()).isTrue();
    }
}
