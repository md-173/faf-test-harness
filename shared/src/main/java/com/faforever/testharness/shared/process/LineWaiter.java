package com.faforever.testharness.shared.process;

import java.time.Duration;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;
import java.util.function.Predicate;

/**
 * Bounded wait for a subprocess output line matching a predicate.
 *
 * <p>Register an instance as the line observer passed to {@link
 * com.faforever.testharness.shared.logging.ProcessOutputLogger#captureAsync(Process, String,
 * Consumer)} or {@link SubprocessManager#start(ProcessBuilder, String, Duration, Consumer)}, then
 * call {@link #awaitLine} to block the calling thread until a matching line arrives or the timeout
 * elapses — a bounded alternative to reading the process's stream directly, which has no timeout at
 * all.
 *
 * <p>Every line the process emits — stdout and stderr, matching or not — is queued until a call to
 * {@link #awaitLine} drains it, so this is built for one caller waiting on one readiness marker per
 * process, not several independent waiters sharing a process; a second, unrelated {@code awaitLine}
 * call on the same instance can consume a line the first was waiting for. The queue is capped at
 * {@value #MAX_QUEUED_LINES} lines so a process that keeps logging long after the marker was found
 * cannot grow it without bound; once full, further lines are dropped rather than blocking the
 * reader thread that calls {@link #accept}.
 */
public final class LineWaiter implements Consumer<String> {

    /** Caps the backlog of unconsumed lines so a long-lived process cannot leak memory here. */
    private static final int MAX_QUEUED_LINES = 1000;

    private final BlockingQueue<String> lines = new LinkedBlockingQueue<>(MAX_QUEUED_LINES);

    /**
     * Queues {@code line} for {@link #awaitLine}. Never blocks: a full queue silently drops the
     * line rather than stalling the caller, which in practice is a {@code ProcessOutputLogger}
     * reader thread that must keep draining the process's stream regardless.
     *
     * @param line a line of subprocess output, as read from stdout or stderr
     */
    @Override
    public void accept(final String line) {
        lines.offer(line);
    }

    /**
     * Blocks until a queued line satisfies {@code predicate}, or {@code timeout} elapses.
     *
     * <p>Lines that do not satisfy {@code predicate} are consumed and discarded, not replayed to a
     * later call.
     *
     * @param predicate matched against each line in arrival order
     * @param timeout maximum time to wait; must be positive
     * @return the first matching line
     * @throws TimeoutException if no line matches {@code predicate} within {@code timeout}
     * @throws InterruptedException if the calling thread is interrupted while waiting
     */
    public String awaitLine(final Predicate<String> predicate, final Duration timeout)
            throws InterruptedException, TimeoutException {
        long deadlineNanos = System.nanoTime() + timeout.toNanos();
        while (true) {
            long remainingNanos = deadlineNanos - System.nanoTime();
            if (remainingNanos <= 0) {
                throw new TimeoutException(
                        "no subprocess output line matched the predicate within " + timeout);
            }
            String line = lines.poll(remainingNanos, TimeUnit.NANOSECONDS);
            if (line != null && predicate.test(line)) {
                return line;
            }
        }
    }
}
