package com.faforever.testharness.shared.process;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.faforever.testharness.shared.logging.LoggingSetup;
import java.time.Duration;
import java.util.Collections;
import java.util.List;
import java.util.OptionalInt;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.slf4j.LoggerFactory;

/**
 * Exercises {@link SubprocessManager#terminate()} and {@link
 * SubprocessManager#terminate(Duration)}.
 *
 * <p>Also covers WBS 3.1.2.1-fix (#361): whichever way the child ends, what it wrote, including
 * while shutting down, is logged by the time {@code terminate} returns, with no read error. Those
 * tests park the reader in the line observer on the line in question and let it go shortly after
 * the child exits, so the line can only be logged in time if {@code terminate} waits for it.
 */
@Timeout(value = 30, unit = TimeUnit.SECONDS)
class SubprocessManagerTerminateTest {

    private static final Duration DEFAULT_GRACE = Duration.ofSeconds(2);
    private static final Duration SHORT_GRACE = Duration.ofMillis(500);

    /** Long enough that waiting out both strikes would plainly show, inside the class timeout. */
    private static final Duration LONG_GRACE = Duration.ofSeconds(10);

    /** Ample for a JVM child to run its shutdown hook, so SIGTERM is the strike that ends it. */
    private static final Duration HOOK_GRACE = Duration.ofSeconds(5);

    /**
     * How long after the child's exit a parked reader is let go. Well inside terminate's one-second
     * wait for the output, and long enough that a terminate that did not wait has plainly returned.
     */
    private static final Duration RELEASE_DELAY = Duration.ofMillis(200);

    private static final String TAG = "TestChild";
    private static final String READ_ERROR = "Error reading subprocess stream for " + TAG;
    private static final int AWAIT_SECONDS = 10;
    private static final long TERMINATE_BUDGET_MS = 3_000;

    /** Exit status of a child ended by SIGTERM (128 + 15). */
    private static final int SIGTERM_EXIT = 143;

    /** Exit status of a child ended by SIGKILL (128 + 9). */
    private static final int SIGKILL_EXIT = 137;

    private ListAppender<ILoggingEvent> appender;
    private Logger root;

    @BeforeEach
    void attachAppender() {
        LoggerContext ctx = (LoggerContext) LoggerFactory.getILoggerFactory();
        root = ctx.getLogger(Logger.ROOT_LOGGER_NAME);
        // Freeze each event as it is appended: logback resolves an event's MDC map lazily, and
        // these tests read it later on the test thread.
        appender =
                new ListAppender<>() {
                    @Override
                    protected void append(final ILoggingEvent event) {
                        event.prepareForDeferredProcessing();
                        super.append(event);
                    }
                };
        appender.list = new CopyOnWriteArrayList<>();
        appender.setContext(ctx);
        appender.start();
        root.addAppender(appender);
    }

    @AfterEach
    void detachAppender() {
        if (appender != null) {
            appender.stop();
            root.detachAppender(appender);
        }
    }

    @Test
    void terminateKillsRunningProcessWithinBudget() throws Exception {
        SubprocessManager m =
                SubprocessManager.start(TestSupport.testChild("sleep", "60000"), TAG, SHORT_GRACE);
        assertTrue(m.isAlive());
        long start = System.nanoTime();
        m.terminate();
        m.onExit().get(AWAIT_SECONDS, TimeUnit.SECONDS);
        long elapsedMs = (System.nanoTime() - start) / 1_000_000L;
        assertFalse(m.isAlive());
        assertTrue(elapsedMs < TERMINATE_BUDGET_MS, "terminate took too long: " + elapsedMs + "ms");
        assertNotEquals(0, m.exitCode().getAsInt(), "force-killed process should exit non-zero");
    }

    @Test
    void terminateAfterNaturalExitSendsNoSignal() throws Exception {
        SubprocessManager m =
                SubprocessManager.start(TestSupport.testChild("exit", "0"), TAG, DEFAULT_GRACE);
        m.onExit().get(AWAIT_SECONDS, TimeUnit.SECONDS);
        m.terminate();
        m.terminate(Duration.ofMillis(100));
        assertFalse(m.isAlive());
        assertEquals(OptionalInt.of(0), m.exitCode());
    }

    /**
     * A busy common pool does not stretch {@code terminate}. The JDK completes {@code
     * Process.onExit()} with a common-pool task, and {@code terminate} used to wait on it: with
     * every worker blocked it waited out both strikes' full grace for a child that died at the
     * first. It now waits on the reaper's exit record, which needs no pool thread.
     */
    @Test
    void terminateIsNotStretchedByABusyCommonPool() throws Exception {
        // With a parallelism below two, CompletableFuture runs async stages on fresh threads rather
        // than the common pool, so there is nothing to starve.
        assumeTrue(ForkJoinPool.getCommonPoolParallelism() > 1, "no common pool to occupy");
        SubprocessManager m =
                SubprocessManager.start(TestSupport.testChild("sleep", "60000"), TAG, LONG_GRACE);
        CountDownLatch release = new CountDownLatch(1);
        try {
            CommonPoolOccupier.occupy(release);
            long start = System.nanoTime();
            m.terminate();
            long elapsedMs = (System.nanoTime() - start) / 1_000_000L;
            assertFalse(m.isAlive());
            assertTrue(
                    elapsedMs < LONG_GRACE.toMillis() / 2,
                    "terminate waited on the busy common pool: " + elapsedMs + "ms");
        } finally {
            release.countDown();
        }
    }

    @Test
    void waitForReportsWhetherTheProcessHasExited() throws Exception {
        SubprocessManager m =
                SubprocessManager.start(TestSupport.testChild("sleep", "60000"), TAG, SHORT_GRACE);
        assertFalse(m.waitFor(Duration.ofMillis(100)), "a live child has not exited");
        m.terminate();
        assertTrue(m.waitFor(Duration.ZERO), "an exited child says so without waiting");
    }

    @Test
    @EnabledOnOs(
            value = {OS.LINUX, OS.MAC},
            disabledReason = "POSIX-only: needs a SIGTERM, which runs the child's shutdown hook")
    void whatTheChildWritesWhileShuttingDownIsLoggedBeforeTerminateReturns() throws Exception {
        LineWaiter waiter = new LineWaiter();
        CountDownLatch release = new CountDownLatch(1);
        SubprocessManager m =
                SubprocessManager.start(
                        TestSupport.testChild("hook", "before-signal", "after-signal"),
                        TAG,
                        HOOK_GRACE,
                        waiter.andThen(parkOn("after-signal", release)));
        try {
            // Printed once the hook is registered, so the SIGTERM runs it.
            waiter.awaitLine("before-signal"::equals, Duration.ofSeconds(AWAIT_SECONDS));
            releaseAfterExit(m, release);
            m.terminate();

            assertEquals(OptionalInt.of(SIGTERM_EXIT), m.exitCode(), "SIGTERM should end it");
            assertEquals(1, countLogged("before-signal"), "got " + loggedMessages());
            assertEquals(1, countLogged("after-signal"), "got " + loggedMessages());
            assertEquals(0, countLogged(READ_ERROR), "the pipes must stay open for the reader");
        } finally {
            release.countDown();
            m.terminate(SHORT_GRACE);
        }
    }

    @Test
    void terminateAfterTheChildExitedStillWaitsForItsOutput() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        SubprocessManager m =
                SubprocessManager.start(
                        TestSupport.testChild("lines", "last-line"),
                        TAG,
                        DEFAULT_GRACE,
                        parkOn("last-line", release));
        try {
            m.onExit().get(AWAIT_SECONDS, TimeUnit.SECONDS);
            releaseAfterExit(m, release);
            m.terminate();

            assertEquals(1, countLogged("last-line"), "got " + loggedMessages());
        } finally {
            release.countDown();
        }
    }

    @Test
    @EnabledOnOs(
            value = {OS.LINUX, OS.MAC},
            disabledReason = "POSIX-only: runs sh, and needs a child that ignores SIGTERM")
    void terminateWaitsForTheOutputAfterASigkillToo() throws Exception {
        LineWaiter waiter = new LineWaiter();
        CountDownLatch release = new CountDownLatch(1);
        // exec, so the child that ignores SIGTERM is the one holding the pipes, and the SIGKILL
        // closes them.
        ProcessBuilder stubborn =
                new ProcessBuilder("sh", "-c", "trap '' TERM; echo held-line; exec sleep 60");
        SubprocessManager m =
                SubprocessManager.start(
                        stubborn, TAG, SHORT_GRACE, waiter.andThen(parkOn("held-line", release)));
        try {
            // Printed once the trap is set, so the SIGTERM is ignored.
            waiter.awaitLine("held-line"::equals, Duration.ofSeconds(AWAIT_SECONDS));
            releaseAfterExit(m, release);
            m.terminate();

            assertEquals(OptionalInt.of(SIGKILL_EXIT), m.exitCode(), "only SIGKILL should end it");
            assertEquals(1, countLogged("held-line"), "got " + loggedMessages());
            assertEquals(0, countLogged(READ_ERROR), "the pipes must stay open for the reader");
        } finally {
            release.countDown();
            m.terminate(SHORT_GRACE);
        }
    }

    /**
     * A line observer that parks the reader thread when it sees {@code line}, before the line is
     * logged, until {@code release} opens.
     */
    private static Consumer<String> parkOn(final String line, final CountDownLatch release) {
        return seen -> {
            if (!line.equals(seen)) {
                return;
            }
            try {
                release.await(AWAIT_SECONDS, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        };
    }

    /** Opens {@code release} {@link #RELEASE_DELAY} after the child exits, off the test thread. */
    private static void releaseAfterExit(final SubprocessManager m, final CountDownLatch release) {
        m.onExit()
                .thenRunAsync(
                        release::countDown,
                        CompletableFuture.delayedExecutor(
                                RELEASE_DELAY.toMillis(), TimeUnit.MILLISECONDS, Runnable::run));
    }

    private List<String> loggedMessages() {
        return appender.list.stream()
                .filter(e -> TAG.equals(e.getMDCPropertyMap().get(LoggingSetup.COMPONENT_MDC_KEY)))
                .map(ILoggingEvent::getFormattedMessage)
                .toList();
    }

    private int countLogged(final String message) {
        return Collections.frequency(loggedMessages(), message);
    }
}
