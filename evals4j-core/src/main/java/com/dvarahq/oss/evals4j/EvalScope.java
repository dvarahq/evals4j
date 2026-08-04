package com.dvarahq.oss.evals4j;

import com.dvarahq.oss.evals4j.spi.EvalTracer;

import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Makes a tracer available to evaluators that were not given one, for the duration of a block.
 *
 * <pre>{@code
 * try (EvalScope.Handle ignored = EvalScope.open(report)) {
 *     judge.evaluate(question, answer);      // reports to `report` without being told to
 * }
 * }</pre>
 *
 * <p>An evaluator takes its tracer at construction, which means a test suite that builds its
 * evaluators as fields cannot hand them one it only obtains later — the reason {@code @EvalSuite}
 * originally could not attach itself, and why every suite repeated {@code .tracer(report)}.
 *
 * <p><strong>This is a dynamic scope, not a global default.</strong> The distinction is the whole
 * point. A static default tracer would be shared mutable state that any module could set and every
 * other module would silently inherit, with no way to tell from a call site whether it was in effect.
 * A scope is opened by a caller, confined to the thread that opened it, and closed again; nothing
 * outside the block can observe it, and nesting restores what was there before.
 *
 * <p><strong>An explicitly configured tracer always wins.</strong> {@link ScorerRunner} consults this
 * only when an evaluator has none, so opening a scope can never redirect an evaluator that was told
 * where to report — including one deliberately given {@link EvalTracer#NO_OP} to keep it quiet.
 *
 * <p><strong>Crossing threads.</strong> A thread-confined value does not follow work handed to an
 * executor, so an evaluation dispatched to a pool would lose the scope and report nowhere. The
 * {@code capturing} helpers carry it across that boundary; the asynchronous paths in this library use
 * them, and anything dispatching evaluations itself should too.
 */
public final class EvalScope {

    private static final ThreadLocal<EvalTracer> CURRENT = new ThreadLocal<>();

    private EvalScope() {}

    /** An open scope. Closing it restores whatever was in effect before. */
    @FunctionalInterface
    public interface Handle extends AutoCloseable {

        /** Never throws, so it is safe in a try-with-resources that already handles other failures. */
        @Override
        void close();
    }

    /**
     * Makes {@code tracer} the ambient tracer on this thread until the handle is closed.
     *
     * @param tracer the tracer to install; {@code null} clears the scope for the block, which is how
     *     you exclude a section from an enclosing scope
     */
    public static Handle open(EvalTracer tracer) {
        EvalTracer previous = CURRENT.get();
        set(tracer);
        return () -> set(previous);
    }

    /** The tracer in scope on this thread, or {@code null} if none. */
    public static EvalTracer current() {
        return CURRENT.get();
    }

    /**
     * Wraps {@code task} so it runs under the scope in effect right now, wherever it later runs.
     *
     * <p>Capture happens when this is called — on the dispatching thread — not when the task runs.
     */
    public static <T> Supplier<T> capturing(Supplier<T> task) {
        EvalTracer captured = CURRENT.get();
        if (captured == null) {
            return task;
        }
        return () -> {
            try (Handle ignored = open(captured)) {
                return task.get();
            }
        };
    }

    /** {@link #capturing(Supplier)} for a function, e.g. the body of a {@code thenCompose}. */
    public static <T, R> Function<T, R> capturing(Function<T, R> task) {
        EvalTracer captured = CURRENT.get();
        if (captured == null) {
            return task;
        }
        return value -> {
            try (Handle ignored = open(captured)) {
                return task.apply(value);
            }
        };
    }

    private static void set(EvalTracer tracer) {
        if (tracer == null) {
            // remove() rather than set(null) so the thread does not retain an empty entry; these
            // threads are usually pooled and long-lived.
            CURRENT.remove();
        } else {
            CURRENT.set(tracer);
        }
    }
}
