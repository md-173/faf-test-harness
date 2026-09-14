package com.faforever.testharness.shared.logging;

import java.util.concurrent.Executor;
import org.slf4j.MDC;

/**
 * Carries one component instance's label onto the threads that instance owns (WBS-4.3.3).
 *
 * <p>{@link LoggingSetup#configure} labels a whole JVM, which is enough while each JVM runs one
 * instance. Several Mock Clients in one JVM (the multi-peer live test, and later an in-process
 * session command) share that JVM, and Logback's MDC is a plain {@code ThreadLocal}, so the label a
 * caller puts on its own thread never reaches the lobby listener, the adapter reader or a
 * process-exit continuation. This captures the caller's label once and re-applies it on those
 * threads.
 *
 * <p>Only the {@value LoggingSetup#INSTANCE_MDC_KEY} key is carried. {@link ProcessOutputLogger}
 * owns the component key on its threads and must keep doing so. When nothing is labelled at capture
 * time every method here is a no-op, so single-instance runs behave exactly as before.
 */
public final class InstanceLabel {

    /** The captured label, or {@code null} when the capturing thread had none. */
    private final String label;

    private InstanceLabel(final String label) {
        this.label = label;
    }

    /**
     * Captures the calling thread's instance label.
     *
     * @return the captured label, possibly empty
     */
    public static InstanceLabel capture() {
        String current = MDC.get(LoggingSetup.INSTANCE_MDC_KEY);
        return new InstanceLabel(current == null || current.isEmpty() ? null : current);
    }

    /**
     * The captured label.
     *
     * @return the label, or {@code null} when none was captured
     */
    public String value() {
        return label;
    }

    /**
     * Applies the label to the current thread until the returned scope is closed, then restores
     * whatever that thread had before.
     *
     * @return the scope to close; never {@code null}
     */
    public Scope apply() {
        if (label == null) {
            return () -> {};
        }
        String previous = MDC.get(LoggingSetup.INSTANCE_MDC_KEY);
        MDC.put(LoggingSetup.INSTANCE_MDC_KEY, label);
        return () -> {
            if (previous == null) {
                MDC.remove(LoggingSetup.INSTANCE_MDC_KEY);
            } else {
                MDC.put(LoggingSetup.INSTANCE_MDC_KEY, previous);
            }
        };
    }

    /**
     * Wraps a task so it runs under the label, whichever thread runs it.
     *
     * @param task the task
     * @return the wrapped task, or {@code task} itself when no label was captured
     */
    public Runnable wrap(final Runnable task) {
        if (label == null) {
            return task;
        }
        return () -> {
            try (Scope ignored = apply()) {
                task.run();
            }
        };
    }

    /**
     * Wraps an executor so every task it runs carries the label. The tasks still run on {@code
     * executor}'s own threads.
     *
     * @param executor the executor
     * @return the wrapped executor, or {@code executor} itself when no label was captured
     */
    public Executor wrap(final Executor executor) {
        if (label == null) {
            return executor;
        }
        return task -> executor.execute(wrap(task));
    }

    /** A label applied to one thread; closing it restores that thread's previous label. */
    @FunctionalInterface
    public interface Scope extends AutoCloseable {
        @Override
        void close();
    }
}
