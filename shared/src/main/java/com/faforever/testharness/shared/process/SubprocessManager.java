package com.faforever.testharness.shared.process;

import com.faforever.testharness.shared.logging.ProcessOutputLogger;
import java.io.IOException;
import java.time.Duration;
import java.util.Objects;
import java.util.OptionalInt;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Reusable lifecycle wrapper around a started child {@link Process}.
 *
 * <p>Bundles a started process with its {@link ProcessOutputLogger} reader executor so that
 * subprocess launchers do not need to repeat output-capture wiring, reader shutdown, and
 * SIGTERM/SIGKILL escalation. Constructed via the static {@link #start} factory; the constructor is
 * private.
 *
 * <p>Intended consumers: the ICE adapter launcher (WBS 3.1.2.2), the Mock Game launcher (WBS
 * 3.1.2.3), and the N-client test harness orchestrator that spawns multiple Mock Client instances
 * for 2–4 player simulation (client spec §Advanced Extensions). This is why the class lives in
 * {@code shared/} rather than {@code mock-client/}.
 *
 * <p>Continuations chained onto {@link #onExit()} run on the thread that completes it, a
 * common-pool thread: the JDK completes {@code Process.onExit()} through {@code handleAsync}, and
 * only the exit record behind {@link #isAlive()}, {@link #exitCode()} and {@link
 * #waitFor(Duration)} is written by its process reaper. Listeners that perform non-trivial work
 * should hand it off to their own executor rather than blocking that thread, and a caller that must
 * see an exit while the common pool may be busy waits with {@link #waitFor(Duration)}.
 */
public final class SubprocessManager {

    /** Diagnostic logger used by terminate; subprocess output goes through ProcessOutputLogger. */
    private static final Logger LOG = LoggerFactory.getLogger(SubprocessManager.class);

    /**
     * Longest {@link #terminate(Duration)} waits, once the process has exited, for the rest of its
     * output to reach the log (WBS 3.1.2.1-fix, #361). The readers normally finish within
     * milliseconds of the exit; this bounds the case where a descendant that inherited the pipes
     * keeps them open.
     */
    private static final Duration OUTPUT_DRAIN_TIMEOUT = Duration.ofSeconds(1);

    /**
     * The one thread every child is started from (WBS 3.1.2.1-fix, #378). The kernel sends a child
     * its parent-death signal ({@link ParentDeathSignal}) when the thread that started it exits,
     * and callers start children from threads that come and go, such as the lobby's HttpClient
     * worker that delivers {@code game_launch}. So this is one platform thread that never ends: a
     * pooled thread, a common-pool thread or a virtual thread's carrier can retire at any time and
     * take every child it started with it. Daemon, so it never holds the JVM open.
     */
    private static final ExecutorService SPAWNER =
            Executors.newSingleThreadExecutor(
                    task -> {
                        Thread thread = new Thread(task, "subprocess-spawner");
                        thread.setDaemon(true);
                        return thread;
                    });

    /** The wrapped child process. */
    private final Process process;

    /** Reader threads logging the child's stdout and stderr; terminate waits on them. */
    private final ExecutorService readers;

    /**
     * MDC component label applied to captured subprocess log lines and to terminate diagnostics.
     */
    private final String componentTag;

    /** Grace used by the no-arg {@link #terminate()} between SIGTERM and SIGKILL. */
    private final Duration defaultGrace;

    /** Completes with the exit code once the process exits; also shuts down the reader executor. */
    private final CompletableFuture<Integer> exitFuture;

    private SubprocessManager(
            final Process process,
            final ExecutorService readers,
            final String componentTag,
            final Duration defaultGrace) {
        this.process = process;
        this.readers = readers;
        this.componentTag = componentTag;
        this.defaultGrace = defaultGrace;
        this.exitFuture =
                process.onExit()
                        .thenApply(
                                p -> {
                                    readers.shutdown();
                                    SubprocessRegistry.deregister(this);
                                    return p.exitValue();
                                });
    }

    /**
     * Starts the child process described by {@code pb} and wires its stdout/stderr to the harness
     * logging framework, tagging every captured line with {@code componentTag}.
     *
     * <p>The caller owns {@code pb}'s configuration (argv, working directory, environment,
     * redirection). This method does not modify {@code pb}.
     *
     * <p><b>Side effects.</b> The first successful call across the JVM installs a shutdown hook
     * that terminates all currently-active managers when the JVM exits. Every successful call also
     * enrols the returned manager in a JVM-wide registry; the manager deregisters itself
     * automatically when its process exits. The process itself is started on one JVM-wide thread,
     * not the caller's, so a {@link ParentDeathSignal} prefix in {@code pb} ties the child to that
     * thread rather than to one that may end first.
     *
     * @param pb fully-configured ProcessBuilder for the child
     * @param componentTag MDC component label applied to every captured log line; must be non-blank
     * @param terminateGrace per-call default grace between SIGTERM and SIGKILL used by the no-arg
     *     {@link #terminate()}; must be positive
     * @return a manager wrapping the started process
     * @throws IOException if {@link ProcessBuilder#start()} fails
     * @throws IllegalStateException if the JVM is already shutting down when this is called
     */
    public static SubprocessManager start(
            final ProcessBuilder pb, final String componentTag, final Duration terminateGrace)
            throws IOException {
        return start(pb, componentTag, terminateGrace, line -> {});
    }

    /**
     * Same as {@link #start(ProcessBuilder, String, Duration)}, plus an opt-in per-line observer on
     * the child's stdout/stderr — see {@link ProcessOutputLogger#captureAsync(Process, String,
     * Consumer)}. The default SLF4J routing is unaffected either way; {@code lineObserver} is an
     * addition to it, not a replacement.
     *
     * @param pb fully-configured ProcessBuilder for the child
     * @param componentTag MDC component label applied to every captured log line; must be non-blank
     * @param terminateGrace per-call default grace between SIGTERM and SIGKILL used by the no-arg
     *     {@link #terminate()}; must be positive
     * @param lineObserver invoked with each raw line of subprocess output; must not be {@code
     *     null}. Called from one reader thread per stream, so it may be called from two threads at
     *     once for a process that writes to stdout and stderr concurrently, and must be
     *     thread-safe. Pass a {@link LineWaiter} to wait on a specific line with a timeout.
     * @return a manager wrapping the started process
     * @throws IOException if {@link ProcessBuilder#start()} fails
     * @throws IllegalStateException if the JVM is already shutting down when this is called
     */
    public static SubprocessManager start(
            final ProcessBuilder pb,
            final String componentTag,
            final Duration terminateGrace,
            final Consumer<String> lineObserver)
            throws IOException {
        Objects.requireNonNull(pb, "pb");
        Objects.requireNonNull(componentTag, "componentTag");
        Objects.requireNonNull(terminateGrace, "terminateGrace");
        Objects.requireNonNull(lineObserver, "lineObserver");
        if (componentTag.isBlank()) {
            throw new IllegalArgumentException("componentTag must not be blank");
        }
        if (terminateGrace.isNegative() || terminateGrace.isZero()) {
            throw new IllegalArgumentException("terminateGrace must be positive");
        }
        Process process = startOnSpawner(pb);
        ExecutorService readers =
                ProcessOutputLogger.captureAsync(process, componentTag, lineObserver);
        SubprocessManager manager =
                new SubprocessManager(process, readers, componentTag, terminateGrace);
        try {
            SubprocessRegistry.register(manager);
        } catch (IllegalStateException e) {
            manager.terminate();
            throw e;
        }
        // The constructor's onExit chain calls deregister(this), but for a fast-exiting child
        // that chain may fire before register() above, concurrently on the common-pool thread
        // that completes Process.onExit(). The deregister then becomes a no-op and the just-added
        // entry stays in ACTIVE forever. Drop it now if the process is already gone; deregister
        // is idempotent so a late lambda is fine.
        if (!process.isAlive()) {
            SubprocessRegistry.deregister(manager);
        }
        return manager;
    }

    /**
     * Runs {@code pb.start()} on {@link #SPAWNER} and waits for it. The wait does not give up on an
     * interrupt: the start takes milliseconds, {@link ProcessBuilder#start()} never gave up on one
     * either, and abandoning it would leave a started child that nothing manages. The caller keeps
     * its interrupt flag and gets whatever the start threw, unwrapped. The start is submitted
     * rather than executed, so a failed start cannot end the spawner thread.
     *
     * @param pb the configured builder, handed over with the submit
     * @return the started process
     * @throws IOException if {@link ProcessBuilder#start()} fails
     */
    private static Process startOnSpawner(final ProcessBuilder pb) throws IOException {
        Future<Process> started = SPAWNER.submit(pb::start);
        boolean interrupted = false;
        try {
            while (true) {
                try {
                    return started.get();
                } catch (InterruptedException e) {
                    interrupted = true;
                } catch (ExecutionException e) {
                    Throwable cause = e.getCause();
                    if (cause instanceof IOException io) {
                        throw io;
                    }
                    if (cause instanceof RuntimeException runtime) {
                        throw runtime;
                    }
                    // ProcessBuilder.start() throws nothing else that is checked.
                    throw (Error) cause;
                }
            }
        } finally {
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }

    /**
     * Returns {@code true} while the underlying process is still running.
     *
     * @return whether the wrapped process is alive
     */
    public boolean isAlive() {
        return process.isAlive();
    }

    /**
     * Returns the process exit code wrapped in an {@link OptionalInt}, or empty while the process
     * is still running. Chosen over a plain {@code int} so that callers cannot trigger {@link
     * IllegalThreadStateException} by reading before exit.
     *
     * @return the exit code if the process has exited, otherwise {@link OptionalInt#empty()}
     */
    public OptionalInt exitCode() {
        return process.isAlive() ? OptionalInt.empty() : OptionalInt.of(process.exitValue());
    }

    /**
     * Returns a future that completes with the process exit code once the process exits. The reader
     * executor is shut down as part of the completion chain, which does not wait for the readers,
     * so the child's last lines can reach the log after this completes. A caller that reports the
     * exit calls {@link #terminate()} first: an exited process gets no signal, only the wait of up
     * to a second for those lines (#495).
     *
     * <p>Each call returns an independent copy; cancelling or externally completing the returned
     * future does not affect the internal completion chain or other callers.
     *
     * @return future resolving to the exit code
     */
    public CompletableFuture<Integer> onExit() {
        return exitFuture.copy();
    }

    /**
     * Waits up to {@code timeout} for the process to exit.
     *
     * <p>Reads the exit the JDK's process reaper records, as {@link #isAlive()} and {@link
     * #exitCode()} do, rather than waiting on {@link #onExit()}. That future is completed by a
     * common-pool task, so a caller waiting on it while every common-pool worker is busy, or
     * blocked on a monitor the caller holds, can wait out its whole bound for a process that has
     * already exited. This wait ends when the reaper sees the exit, whatever the pool is doing.
     *
     * @param timeout the longest to wait; zero or negative checks without waiting
     * @return {@code true} if the process has exited
     * @throws InterruptedException if interrupted while waiting
     */
    public boolean waitFor(final Duration timeout) throws InterruptedException {
        return process.waitFor(timeout.toNanos(), TimeUnit.NANOSECONDS);
    }

    /**
     * Returns the OS-level process id of the wrapped child.
     *
     * @return the PID
     */
    public long pid() {
        return process.pid();
    }

    /** Calls {@link #terminate(Duration)} with the grace passed at {@link #start}. */
    public void terminate() {
        terminate(defaultGrace);
    }

    /**
     * Asks the process to exit via SIGTERM (POSIX) or TerminateProcess (Windows), waits up to
     * {@code grace}, then escalates to SIGKILL if still alive. Once the process has exited, waits
     * up to {@link #OUTPUT_DRAIN_TIMEOUT} (one second) for the rest of its output, including
     * anything it wrote while shutting down, to reach the log. Sends no signal if the process has
     * already exited, but still waits for its output. Returns within twice {@code grace} plus that
     * wait. Safe to call concurrently; overlapping calls simply re-await the existing exit.
     *
     * <p>The signals go through the process handle rather than {@link Process#destroy()}, which on
     * Linux and macOS also closes the child's pipes: what the child wrote after the signal was lost
     * and its reader logged a read error (#361).
     *
     * @param grace time to wait between the SIGTERM and SIGKILL strikes; must be positive
     */
    public void terminate(final Duration grace) {
        Objects.requireNonNull(grace, "grace");
        if (grace.isNegative() || grace.isZero()) {
            throw new IllegalArgumentException("grace must be positive");
        }
        if (!process.isAlive()) {
            awaitOutput();
            return;
        }
        long graceMs = grace.toMillis();
        LOG.debug("Terminating {} (pid={}, grace={}ms)", componentTag, process.pid(), graceMs);
        process.toHandle().destroy();
        if (awaitExit(graceMs)) {
            awaitOutput();
            return;
        }
        LOG.debug(
                "{} (pid={}) did not exit on SIGTERM within {}ms; forcing",
                componentTag,
                process.pid(),
                graceMs);
        process.toHandle().destroyForcibly();
        if (awaitExit(graceMs)) {
            awaitOutput();
        }
    }

    /**
     * Waits up to {@link #OUTPUT_DRAIN_TIMEOUT} for the readers to log the exited child's remaining
     * output. Gives up rather than closing the streams: a close does not wake a reader parked on a
     * pipe that a descendant still holds, and it would turn that descendant's next line into a read
     * error.
     */
    private void awaitOutput() {
        readers.shutdown();
        try {
            if (!readers.awaitTermination(OUTPUT_DRAIN_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)) {
                LOG.debug(
                        "{} (pid={}) exited but its output was still open after {}ms",
                        componentTag,
                        process.pid(),
                        OUTPUT_DRAIN_TIMEOUT.toMillis());
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * Waits for the exit through {@link #waitFor(Duration)}, not {@link #exitFuture}, so a busy
     * common pool cannot stretch either strike of {@link #terminate(Duration)} to its full grace.
     *
     * @param millis the longest to wait
     * @return {@code true} if the process has exited
     */
    private boolean awaitExit(final long millis) {
        try {
            return waitFor(Duration.ofMillis(millis));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }
}
