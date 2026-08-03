package com.dvarahq.oss.evals4j.sandbox;

import com.dvarahq.oss.evals4j.EvalRequest;
import com.dvarahq.oss.evals4j.code.ExecutionEvaluator;
import com.dvarahq.oss.evals4j.result.EvaluatorResult;
import com.dvarahq.oss.evals4j.spi.SandboxRunner;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

class SandboxRunnerTest {

    private static final String IMAGE = "alpine:3.20";

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
    void localRunnerEnforcesTheTimeout() {
        long startedAt = System.nanoTime();
        SandboxRunner.Result result = LocalProcessSandboxRunner.create()
                .run(shellScript("sleep 30", Duration.ofSeconds(1)));
        Duration elapsed = Duration.ofNanos(System.nanoTime() - startedAt);

        assertThat(result.timedOut()).isTrue();
        // The point of the timeout is to bound the wait, so the clock is part of the assertion.
        assertThat(elapsed).isLessThan(Duration.ofSeconds(15));
    }

    @Test
    void localRunnerDoesNotDeadlockOnLargeStderr() {
        // Draining stdout to EOF before touching stderr deadlocks once the child fills the stderr
        // pipe buffer: it blocks writing, so it never closes stdout, so the read never returns.
        assertTimeoutPreemptively(Duration.ofSeconds(30), () -> {
            SandboxRunner.Result result = LocalProcessSandboxRunner.create()
                    .run(shellScript(
                            "yes 'noisy diagnostics' | head -c 400000 >&2; echo done", Duration.ofSeconds(20)));

            assertThat(result.timedOut()).isFalse();
            assertThat(result.stdout()).contains("done");
            assertThat(result.stderr()).hasSizeGreaterThan(64 * 1024);
        });
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
    @EnabledIf("com.dvarahq.oss.evals4j.sandbox.DockerSandboxRunner#isDockerAvailable")
    void dockerRunnerExecutesInAContainer() {
        SandboxRunner.Result result = DockerSandboxRunner.builder()
                .image(IMAGE)
                .build()
                .run(shellScript("echo from-the-container", Duration.ofMinutes(2)));

        // Report what Docker actually said; "expected true but was false" is useless here.
        assertThat(result.succeeded())
                .as("docker run failed (exit %d)%nstdout: %s%nstderr: %s",
                        result.exitCode(), result.stdout(), result.stderr())
                .isTrue();
        assertThat(result.stdout()).contains("from-the-container");
    }

    @Test
    @EnabledIf("com.dvarahq.oss.evals4j.sandbox.DockerSandboxRunner#isDockerAvailable")
    void dockerRunnerKillsTheContainerOnTimeout() throws Exception {
        long startedAt = System.nanoTime();
        SandboxRunner.Result result = DockerSandboxRunner.builder()
                .image(IMAGE)
                .build()
                .run(shellScript("sleep 120", Duration.ofSeconds(3)));
        Duration elapsed = Duration.ofNanos(System.nanoTime() - startedAt);

        assertThat(result.timedOut()).isTrue();
        assertThat(elapsed).isLessThan(Duration.ofSeconds(40));

        // Killing the `docker run` client would leave the container alive holding its reservation,
        // so assert on the daemon's view rather than on the exit path.
        assertThat(runningContainerNames()).noneMatch(name -> name.startsWith("evals4j-"));
    }

    private static List<String> runningContainerNames() throws Exception {
        Process process = new ProcessBuilder("docker", "ps", "--format", "{{.Names}}")
                .redirectErrorStream(true)
                .start();
        String output = new String(process.getInputStream().readAllBytes(), UTF_8);
        process.waitFor(20, java.util.concurrent.TimeUnit.SECONDS);
        return output.lines().map(String::trim).filter(line -> !line.isEmpty()).toList();
    }

    @Test
    @EnabledIf("com.dvarahq.oss.evals4j.sandbox.DockerSandboxRunner#isDockerAvailable")
    void dockerRunnerBlocksNetworkAccessByDefault() {
        DockerSandboxRunner runner = DockerSandboxRunner.builder().image(IMAGE).build();

        // Establish that the runner works at all first, otherwise "the network call failed" would
        // also pass when Docker itself is broken.
        SandboxRunner.Result control = runner.run(shellScript("echo ok", Duration.ofMinutes(2)));
        assertThat(control.succeeded())
                .as("control run failed: %s", control.diagnostics())
                .isTrue();

        SandboxRunner.Result result =
                runner.run(shellScript("wget -q -T 3 -O - https://example.com", Duration.ofMinutes(2)));

        assertThat(result.succeeded()).isFalse();
    }
}
