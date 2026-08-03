package io.github.grabdoc.evals4j.sandbox;

import io.github.grabdoc.evals4j.EvalRequest;
import io.github.grabdoc.evals4j.code.ExecutionEvaluator;
import io.github.grabdoc.evals4j.result.EvaluatorResult;
import io.github.grabdoc.evals4j.spi.SandboxRunner;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SandboxRunnerTest {

    /** A shell is present on every platform this project supports, so no interpreter is needed. */
    private static SandboxRunner.Request shellScript(String script, Duration timeout) {
        return new SandboxRunner.Request(
                script, "outputs.sh", List.of("sh", "outputs.sh"), Map.of(), timeout);
    }

    @Test
    void localRunnerReportsSuccess() {
        SandboxRunner.Result result = LocalProcessSandboxRunner.create()
                .run(shellScript("echo hello", Duration.ofSeconds(30)));

        assertThat(result.succeeded()).isTrue();
        assertThat(result.stdout()).contains("hello");
    }

    @Test
    void localRunnerReportsFailure() {
        SandboxRunner.Result result = LocalProcessSandboxRunner.create()
                .run(shellScript("echo 'bad things' >&2; exit 3", Duration.ofSeconds(30)));

        assertThat(result.succeeded()).isFalse();
        assertThat(result.exitCode()).isEqualTo(3);
        assertThat(result.diagnostics()).contains("bad things");
    }

    @Test
    void localRunnerPassesEnvironmentVariables() {
        SandboxRunner.Result result = LocalProcessSandboxRunner.create()
                .run(new SandboxRunner.Request(
                        "echo \"$EVALS4J_GREETING\"",
                        "outputs.sh",
                        List.of("sh", "outputs.sh"),
                        Map.of("EVALS4J_GREETING", "from the environment"),
                        Duration.ofSeconds(30)));

        assertThat(result.stdout()).contains("from the environment");
    }

    @Test
    void localRunnerRequiresACommand() {
        assertThatThrownBy(() -> LocalProcessSandboxRunner.create()
                        .run(new SandboxRunner.Request("echo hi", "outputs.sh", List.of(), Map.of(), null)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("command is required");
    }

    @Test
    void executionEvaluatorRunsThroughTheLocalRunner() {
        EvaluatorResult result = ExecutionEvaluator.builder()
                .sandbox(LocalProcessSandboxRunner.create())
                .fileName("outputs.sh")
                .command("sh", "outputs.sh")
                .build()
                .evaluate(EvalRequest.ofOutputs("exit 0"));

        assertThat(result.key()).isEqualTo("execution_succeeded");
        assertThat(result.passed()).isTrue();
    }

    // ---- Docker: skipped unless a daemon is reachable ----------------------------------------

    @Test
    @EnabledIf("io.github.grabdoc.evals4j.sandbox.DockerSandboxRunner#isDockerAvailable")
    void dockerRunnerExecutesInAContainer() {
        SandboxRunner.Result result = DockerSandboxRunner.builder()
                .image("alpine:3.20")
                .build()
                .run(shellScript("echo from-the-container", Duration.ofMinutes(2)));

        assertThat(result.succeeded()).isTrue();
        assertThat(result.stdout()).contains("from-the-container");
    }

    @Test
    @EnabledIf("io.github.grabdoc.evals4j.sandbox.DockerSandboxRunner#isDockerAvailable")
    void dockerRunnerBlocksNetworkAccessByDefault() {
        SandboxRunner.Result result = DockerSandboxRunner.builder()
                .image("alpine:3.20")
                .build()
                .run(shellScript("wget -q -T 3 -O - https://example.com", Duration.ofMinutes(2)));

        assertThat(result.succeeded()).isFalse();
    }
}
