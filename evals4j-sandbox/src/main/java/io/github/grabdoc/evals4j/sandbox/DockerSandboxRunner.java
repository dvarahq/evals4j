package io.github.grabdoc.evals4j.sandbox;

import io.github.grabdoc.evals4j.spi.SandboxRunner;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Runs code inside a throwaway Docker container.
 *
 * <p>The JVM replacement for OpenEvals' E2B integration. The container is created for the run and
 * removed after it, with the code mounted read-only into {@code /workspace}.
 *
 * <pre>{@code
 * SandboxRunner sandbox = DockerSandboxRunner.builder()
 *         .image("python:3.12-slim")
 *         .build();
 * }</pre>
 *
 * <p>Defaults are restrictive on purpose — no network, a memory cap, a read-only root filesystem and
 * all Linux capabilities dropped. Model-generated code is untrusted input; loosen these only for a
 * specific need, such as code that has to install a package.
 *
 * <p>Shells out to the {@code docker} CLI rather than depending on a Docker client library, so the
 * module stays dependency-free and works with anything CLI-compatible (Podman via an alias, Colima,
 * Rancher Desktop).
 */
public final class DockerSandboxRunner implements SandboxRunner {

    private final String image;
    private final String dockerCommand;
    private final boolean networkEnabled;
    private final String memoryLimit;
    private final String cpuLimit;
    private final boolean readOnlyRootFilesystem;
    private final List<String> extraDockerArgs;

    private DockerSandboxRunner(Builder builder) {
        this.image = java.util.Objects.requireNonNull(builder.image, "An image is required — call .image(...)");
        this.dockerCommand = builder.dockerCommand;
        this.networkEnabled = builder.networkEnabled;
        this.memoryLimit = builder.memoryLimit;
        this.cpuLimit = builder.cpuLimit;
        this.readOnlyRootFilesystem = builder.readOnlyRootFilesystem;
        this.extraDockerArgs = List.copyOf(builder.extraDockerArgs);
    }

    public static Builder builder() {
        return new Builder();
    }

    /** True when the Docker daemon is reachable. Use it to skip sandbox tests where it is not. */
    public static boolean isDockerAvailable() {
        return isDockerAvailable("docker");
    }

    public static boolean isDockerAvailable(String dockerCommand) {
        try {
            Process process = new ProcessBuilder(dockerCommand, "info")
                    .redirectErrorStream(true)
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .start();
            return process.waitFor(20, TimeUnit.SECONDS) && process.exitValue() == 0;
        } catch (IOException e) {
            return false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    @Override
    public Result run(Request request) {
        if (request.command().isEmpty()) {
            throw new IllegalArgumentException("A command is required to run code in the sandbox");
        }
        Path directory = null;
        try {
            directory = Files.createTempDirectory("evals4j-docker");
            Path file = directory.resolve(request.fileName());
            Files.writeString(file, request.code(), StandardCharsets.UTF_8);
            makeWorldReadable(directory, file);

            List<String> command = new ArrayList<>(List.of(
                    dockerCommand, "run", "--rm",
                    "--workdir", "/workspace",
                    "--volume", directory.toAbsolutePath() + ":/workspace:ro"));

            if (!networkEnabled) {
                command.addAll(List.of("--network", "none"));
            }
            if (memoryLimit != null) {
                command.addAll(List.of("--memory", memoryLimit));
            }
            if (cpuLimit != null) {
                command.addAll(List.of("--cpus", cpuLimit));
            }
            if (readOnlyRootFilesystem) {
                // A writable /tmp keeps interpreters working without giving up the read-only root.
                command.addAll(List.of("--read-only", "--tmpfs", "/tmp:rw,size=64m"));
            }
            command.addAll(List.of("--cap-drop", "ALL", "--security-opt", "no-new-privileges"));
            for (Map.Entry<String, String> variable : request.environment().entrySet()) {
                command.addAll(List.of("--env", variable.getKey() + "=" + variable.getValue()));
            }
            command.addAll(extraDockerArgs);
            command.add(image);
            command.addAll(request.command());

            Process process = new ProcessBuilder(command).start();
            String stdout;
            String stderr;
            try (var out = process.getInputStream();
                    var err = process.getErrorStream()) {
                stdout = new String(out.readAllBytes(), StandardCharsets.UTF_8);
                stderr = new String(err.readAllBytes(), StandardCharsets.UTF_8);
            }

            if (!process.waitFor(request.timeout().toMillis(), TimeUnit.MILLISECONDS)) {
                process.destroyForcibly();
                return new Result(-1, stdout, stderr, true);
            }
            return new Result(process.exitValue(), stdout, stderr, false);
        } catch (IOException e) {
            throw new IllegalStateException(
                    "Could not run " + dockerCommand + ". Is Docker installed and running?", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while running the sandboxed code", e);
        } finally {
            LocalProcessSandboxRunner.deleteRecursively(directory);
        }
    }

    /**
     * Makes the mounted code readable by whatever uid the container runs as.
     *
     * <p>Java creates a temp directory mode {@code 0700} owned by the current user. Container root
     * would normally read it anyway by bypassing permission checks, but that bypass is
     * {@code CAP_DAC_OVERRIDE} — which {@code --cap-drop ALL} takes away. Without this the container
     * fails with "Permission denied" on Linux, while macOS hides the problem because Docker
     * Desktop's file sharing normalizes ownership.
     *
     * <p>Widening these permissions is safe: the directory is a throwaway holding only the code that
     * was just written to it, and it is mounted read-only.
     */
    private static void makeWorldReadable(Path directory, Path file) {
        try {
            Files.setPosixFilePermissions(
                    directory, PosixFilePermissions.fromString("rwxr-xr-x"));
            Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-r--r--"));
        } catch (UnsupportedOperationException | IOException e) {
            // Non-POSIX filesystem (Windows); permissions are not the gate there.
        }
    }

    /** Builder for {@link DockerSandboxRunner}. */
    public static final class Builder {
        private String image;
        private String dockerCommand = "docker";
        private boolean networkEnabled;
        private String memoryLimit = "512m";
        private String cpuLimit = "1";
        private boolean readOnlyRootFilesystem = true;
        private final List<String> extraDockerArgs = new ArrayList<>();

        /** The container image, e.g. {@code python:3.12-slim} or {@code node:22-alpine}. */
        public Builder image(String image) {
            this.image = image;
            return this;
        }

        /** The CLI to invoke. Set to {@code podman} to use Podman instead. */
        public Builder dockerCommand(String dockerCommand) {
            this.dockerCommand = dockerCommand;
            return this;
        }

        /** Gives the container network access. Off by default. */
        public Builder networkEnabled(boolean networkEnabled) {
            this.networkEnabled = networkEnabled;
            return this;
        }

        /** Docker memory limit, e.g. {@code "512m"}. Null removes the cap. */
        public Builder memoryLimit(String memoryLimit) {
            this.memoryLimit = memoryLimit;
            return this;
        }

        /** Docker CPU limit, e.g. {@code "1"}. Null removes the cap. */
        public Builder cpuLimit(String cpuLimit) {
            this.cpuLimit = cpuLimit;
            return this;
        }

        /** Mounts the container root read-only, with a writable {@code /tmp}. On by default. */
        public Builder readOnlyRootFilesystem(boolean readOnlyRootFilesystem) {
            this.readOnlyRootFilesystem = readOnlyRootFilesystem;
            return this;
        }

        /** Extra arguments inserted before the image name. */
        public Builder dockerArgs(String... args) {
            this.extraDockerArgs.addAll(List.of(args));
            return this;
        }

        public DockerSandboxRunner build() {
            return new DockerSandboxRunner(this);
        }
    }

    /** A request for the common case of running a script with an interpreter. */
    public static Request script(String code, String fileName, String interpreter, Duration timeout) {
        return new Request(code, fileName, List.of(interpreter, fileName), Map.of(), timeout);
    }
}
