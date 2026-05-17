package com.faforever.testharness.shared.process;

import com.faforever.testharness.shared.logging.ProcessOutputLogger;
import java.io.IOException;
import java.time.Duration;
import java.util.Objects;
import java.util.OptionalInt;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Reusable lifecycle wrapper around a started child {@link Process}.
 *
 * <p>Bundles a started process with its {@link ProcessOutputLogger} reader executor so that
 * subprocess launchers do not need to repeat output-capture wiring, reader shutdown, and
 * SIGTERM/SIGKILL escalation. Constructed via the static {@link #start} factory; the constructor
 * is private.
 *
 * <p>Intended consumers: the ICE adapter launcher (WBS 3.1.2.2), the Mock Game launcher (WBS
 * 3.1.2.3), and the N-client test harness orchestrator that spawns multiple Mock Client instances
 * for 2–4 player simulation (client spec §Advanced Extensions). This is why the class lives in
 * {@code shared/} rather than {@code mock-client/}.
 *
 * <p>Continuations chained onto {@link #onExit()} run on the JDK's exit-completion thread.
 * Listeners that perform non-trivial work should hand it off to their own executor rather than
 * blocking the completion thread.
 */
public final class SubprocessManager {

    /** Diagnostic logger used by terminate; subprocess output goes through ProcessOutputLogger. */
    private static final Logger LOG = LoggerFactory.getLogger(SubprocessManager.class);

    /** The wrapped child process. */
    private final Process process;

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
     * @param pb fully-configured ProcessBuilder for the child
     * @param componentTag MDC component label applied to every captured log line; must be non-blank
     * @param terminateGrace per-call default grace between SIGTERM and SIGKILL used by the no-arg
     *     {@link #terminate()}; must be positive
     * @return a manager wrapping the started process
     * @throws IOException if {@link ProcessBuilder#start()} fails
     */
    public static SubprocessManager start(
            final ProcessBuilder pb, final String componentTag, final Duration terminateGrace)
            throws IOException {
        Objects.requireNonNull(pb, "pb");
        Objects.requireNonNull(componentTag, "componentTag");
        Objects.requireNonNull(terminateGrace, "terminateGrace");
        if (componentTag.isBlank()) {
            throw new IllegalArgumentException("componentTag must not be blank");
        }
        if (terminateGrace.isNegative() || terminateGrace.isZero()) {
            throw new IllegalArgumentException("terminateGrace must be positive");
        }
        Process process = pb.start();
        ExecutorService readers = ProcessOutputLogger.captureAsync(process, componentTag);
        SubprocessManager manager =
                new SubprocessManager(process, readers, componentTag, terminateGrace);
        try {
            SubprocessRegistry.register(manager);
        } catch (IllegalStateException e) {
            manager.terminate();
            throw e;
        }
        return manager;
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
     * executor is shut down as part of the completion chain.
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
     * {@code grace}, then escalates to SIGKILL if still alive. No-op if the process has already
     * exited. Safe to call concurrently; overlapping calls simply re-await the existing exit.
     *
     * @param grace time to wait between the SIGTERM and SIGKILL strikes; must be positive
     */
    public void terminate(final Duration grace) {
        Objects.requireNonNull(grace, "grace");
        if (grace.isNegative() || grace.isZero()) {
            throw new IllegalArgumentException("grace must be positive");
        }
        if (!process.isAlive()) {
            return;
        }
        long graceMs = grace.toMillis();
        LOG.debug("Terminating {} (pid={}, grace={}ms)", componentTag, process.pid(), graceMs);
        process.destroy();
        if (awaitExit(graceMs)) {
            return;
        }
        LOG.debug(
                "{} (pid={}) did not exit on SIGTERM within {}ms; forcing",
                componentTag,
                process.pid(),
                graceMs);
        process.destroyForcibly();
        awaitExit(graceMs);
    }

    private boolean awaitExit(final long millis) {
        try {
            exitFuture.get(millis, TimeUnit.MILLISECONDS);
            return true;
        } catch (TimeoutException e) {
            return false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        } catch (ExecutionException e) {
            // exitFuture cannot fail under our wiring; treat as exited
            return true;
        }
    }
}
