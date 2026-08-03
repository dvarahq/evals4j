package com.dvarahq.oss.evals4j.sandbox;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Runs a subprocess under a wall-clock limit, collecting both its output streams.
 *
 * <p>Shared by both sandbox runners because getting this right is fiddly in two specific ways, and
 * getting it wrong makes the timeout decorative.
 *
 * <p>First, the streams must be drained <em>concurrently with</em> the wait, not before it. Reading
 * a stream to EOF returns only when the child closes it, which a hung child never does — so a read
 * placed before {@link Process#waitFor} means the timeout is consulted only after the process has
 * already finished.
 *
 * <p>Second, the two streams must be drained concurrently with <em>each other</em>. Pipe buffers are
 * small (~64 KB); a child that fills stderr blocks writing to it, and therefore never closes stdout,
 * so a reader working through stdout first waits forever.
 */
final class Processes {

    /** How long to wait for the drain threads to hand over whatever the child managed to write. */
    private static final Duration DRAIN_GRACE = Duration.ofSeconds(5);

    private Processes() {}

    record Outcome(int exitCode, String stdout, String stderr, boolean timedOut) {}

    /**
     * Starts {@code builder} and waits up to {@code timeout} for it to exit.
     *
     * @param onTimeout run before the process is destroyed, for a caller that has something else to
     *     stop as well — destroying the {@code docker} client does not stop the container it started
     * @return the outcome, carrying whatever was written even when the run was killed
     */
    static Outcome run(ProcessBuilder builder, Duration timeout, Runnable onTimeout)
            throws IOException, InterruptedException {
        Process process = builder.start();
        CompletableFuture<String> stdout = drain(process.getInputStream(), "evals4j-sandbox-stdout");
        CompletableFuture<String> stderr = drain(process.getErrorStream(), "evals4j-sandbox-stderr");

        boolean exited = process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS);
        if (!exited) {
            if (onTimeout != null) {
                onTimeout.run();
            }
            process.destroyForcibly();
            // Let the kill land so the pipes close and the drain threads can finish.
            process.waitFor(DRAIN_GRACE.toMillis(), TimeUnit.MILLISECONDS);
        }

        return new Outcome(
                exited ? process.exitValue() : -1, await(stdout), await(stderr), !exited);
    }

    private static CompletableFuture<String> drain(InputStream stream, String threadName) {
        CompletableFuture<String> collected = new CompletableFuture<>();
        Thread thread = new Thread(
                () -> {
                    ByteArrayOutputStream buffer = new ByteArrayOutputStream();
                    byte[] chunk = new byte[8192];
                    try (stream) {
                        int read;
                        while ((read = stream.read(chunk)) != -1) {
                            buffer.write(chunk, 0, read);
                        }
                    } catch (IOException ignored) {
                        // Killing the process closes its pipes mid-read. Partial output still
                        // explains the failure, so report it rather than losing it.
                    }
                    collected.complete(buffer.toString(StandardCharsets.UTF_8));
                },
                threadName);
        // Never hold up JVM shutdown for a child that will not let go of its pipes.
        thread.setDaemon(true);
        thread.start();
        return collected;
    }

    private static String await(CompletableFuture<String> collected) {
        try {
            return collected.get(DRAIN_GRACE.toSeconds(), TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return "";
        } catch (TimeoutException | java.util.concurrent.ExecutionException e) {
            // A stream still open after the process was killed is not worth blocking the caller for.
            return "";
        }
    }
}
