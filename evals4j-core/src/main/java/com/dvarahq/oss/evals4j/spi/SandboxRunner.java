package com.dvarahq.oss.evals4j.spi;

import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * Executes generated code somewhere the caller is willing to run it.
 *
 * <p>Replaces OpenEvals' E2B integration, which is Python/TypeScript only. Implementations ship in
 * {@code evals4j-sandbox}.
 */
public interface SandboxRunner {

    Result run(Request request);

    /**
     * @param code the source to execute
     * @param fileName the file the source is written to inside the sandbox
     * @param command argv to run, e.g. {@code ["python", "outputs.py"]}
     * @param environment extra environment variables
     * @param timeout wall-clock limit
     */
    record Request(
            String code,
            String fileName,
            List<String> command,
            Map<String, String> environment,
            Duration timeout) {

        public Request {
            fileName = fileName == null ? "outputs.txt" : fileName;
            command = command == null ? List.of() : List.copyOf(command);
            environment = environment == null ? Map.of() : Map.copyOf(environment);
            timeout = timeout == null ? Duration.ofMinutes(2) : timeout;
        }
    }

    /** @param timedOut true when the run was killed at the timeout rather than exiting on its own */
    record Result(int exitCode, String stdout, String stderr, boolean timedOut) {

        public boolean succeeded() {
            return exitCode == 0 && !timedOut;
        }

        /** stderr when present, else stdout — what a failing run should report as its comment. */
        public String diagnostics() {
            if (timedOut) {
                return "Execution timed out.\n" + (stderr.isBlank() ? stdout : stderr);
            }
            return stderr.isBlank() ? stdout : stderr;
        }
    }
}
