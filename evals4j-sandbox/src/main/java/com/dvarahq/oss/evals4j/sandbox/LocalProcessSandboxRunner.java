package com.dvarahq.oss.evals4j.sandbox;

import com.dvarahq.oss.evals4j.spi.SandboxRunner;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

/**
 * Runs code as a local subprocess in a temporary directory.
 *
 * <p><strong>This is not a security boundary.</strong> The code runs as your user, with your
 * filesystem, network and credentials. It exists for CI and for code you wrote yourself; for code a
 * model produced, use {@link DockerSandboxRunner}.
 */
public final class LocalProcessSandboxRunner implements SandboxRunner {

    public static LocalProcessSandboxRunner create() {
        return new LocalProcessSandboxRunner();
    }

    @Override
    public Result run(Request request) {
        if (request.command().isEmpty()) {
            throw new IllegalArgumentException("A command is required to run code in the sandbox");
        }
        Path directory = null;
        try {
            directory = Files.createTempDirectory("evals4j-sandbox");
            Files.writeString(directory.resolve(request.fileName()), request.code(), StandardCharsets.UTF_8);

            ProcessBuilder builder = new ProcessBuilder(request.command()).directory(directory.toFile());
            builder.environment().putAll(request.environment());

            Processes.Outcome outcome = Processes.run(builder, request.timeout(), null);
            return new Result(
                    outcome.exitCode(), outcome.stdout(), outcome.stderr(), outcome.timedOut());
        } catch (IOException e) {
            throw new IllegalStateException(
                    "Could not run " + String.join(" ", request.command())
                            + ". Is the interpreter installed and on the PATH?", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while running the sandboxed code", e);
        } finally {
            deleteRecursively(directory);
        }
    }

    static void deleteRecursively(Path directory) {
        if (directory == null) {
            return;
        }
        try (Stream<Path> paths = Files.walk(directory)) {
            List<Path> ordered = new ArrayList<>(paths.sorted(Comparator.reverseOrder()).toList());
            for (Path path : ordered) {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException ignored) {
                    // A leftover temp file is not worth failing an evaluation over.
                }
            }
        } catch (IOException ignored) {
            // Same.
        }
    }
}
