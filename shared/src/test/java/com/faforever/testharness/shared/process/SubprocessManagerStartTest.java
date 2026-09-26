package com.faforever.testharness.shared.process;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.faforever.testharness.shared.logging.LoggingSetup;
import java.time.Duration;
import java.util.OptionalInt;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Predicate;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.slf4j.LoggerFactory;

/** Exercises {@link SubprocessManager#start} and the read-only accessors it sets up. */
@Timeout(value = 30, unit = TimeUnit.SECONDS)
class SubprocessManagerStartTest {

    private static final Duration GRACE = Duration.ofSeconds(5);
    private static final String TAG = "TestChild";
    private static final int AWAIT_SECONDS = 10;
    private static final long POLL_BUDGET_MS = 5_000;
    private static final long POLL_INTERVAL_MS = 50;

    private ListAppender<ILoggingEvent> appender;
    private Logger root;

    @BeforeEach
    void attachAppender() {
        LoggerContext ctx = (LoggerContext) LoggerFactory.getILoggerFactory();
        root = ctx.getLogger(Logger.ROOT_LOGGER_NAME);
        appender = new ListAppender<>();
        // Subprocess output is captured asynchronously; the reader executor is shut down on process
        // exit but not awaited, so reader threads can append while the test thread polls.
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
    void normalExitCompletesFutureWithZero() throws Exception {
        SubprocessManager m =
                SubprocessManager.start(TestSupport.testChild("exit", "0"), TAG, GRACE);
        int code = m.onExit().get(AWAIT_SECONDS, TimeUnit.SECONDS);
        assertEquals(0, code);
        assertEquals(OptionalInt.of(0), m.exitCode());
        assertFalse(m.isAlive());
        assertTrue(m.pid() > 0);
    }

    @Test
    void nonZeroExitSurfacesAsThatCode() throws Exception {
        SubprocessManager m =
                SubprocessManager.start(TestSupport.testChild("exit", "7"), TAG, GRACE);
        int code = m.onExit().get(AWAIT_SECONDS, TimeUnit.SECONDS);
        assertEquals(7, code);
        assertEquals(OptionalInt.of(7), m.exitCode());
    }

    @Test
    void isAliveAndExitCodeFlipAtExit() throws Exception {
        SubprocessManager m =
                SubprocessManager.start(TestSupport.testChild("sleep", "500"), TAG, GRACE);
        assertTrue(m.isAlive());
        // Positive control for fastExitingChildDoesNotLeakIntoRegistry, which asserts a manager
        // left this registry. Without a check that one can be in it, that assertion is vacuous on
        // macOS: the only test covering registration, SubprocessManagerShutdownTest, is
        // @EnabledOnOs(LINUX) and is skipped on the platform this card exists to fix. This is the
        // one workable site — in the leak test the child is already dead and already deregistered
        // by the time start() returns — and it adds no new flakiness, because the assertions
        // either side of it fail in exactly the same window.
        assertTrue(SubprocessRegistry.contains(m), "a live child must be in the registry");
        assertEquals(OptionalInt.empty(), m.exitCode());
        m.onExit().get(AWAIT_SECONDS, TimeUnit.SECONDS);
        assertFalse(m.isAlive());
        assertEquals(OptionalInt.of(0), m.exitCode());
    }

    @Test
    void stdoutIsCapturedTaggedWithComponent() throws Exception {
        SubprocessManager m =
                SubprocessManager.start(
                        TestSupport.testChild("print", "hello-world-marker"), TAG, GRACE);
        m.onExit().get(AWAIT_SECONDS, TimeUnit.SECONDS);
        awaitLog(
                e ->
                        "hello-world-marker".equals(e.getMessage())
                                && TAG.equals(
                                        e.getMDCPropertyMap().get(LoggingSetup.COMPONENT_MDC_KEY)));
    }

    /**
     * Exercises the 4-arg {@link SubprocessManager#start(ProcessBuilder, String, Duration,
     * java.util.function.Consumer)} overload (WBS 3.1.2.10 / #225): the observer sees a line as it
     * arrives, and the existing SLF4J routing this class already covers is unaffected by passing
     * one.
     */
    @Test
    void lineObserverSeesEachLineViaStartOverload() throws Exception {
        LineWaiter waiter = new LineWaiter();
        SubprocessManager m =
                SubprocessManager.start(
                        TestSupport.testChild("lines", "first-line", "READY: hello-world-marker"),
                        TAG,
                        GRACE,
                        waiter);

        String matched =
                waiter.awaitLine(
                        line -> line.contains("hello-world-marker"), Duration.ofSeconds(10));
        assertEquals("READY: hello-world-marker", matched);

        m.onExit().get(AWAIT_SECONDS, TimeUnit.SECONDS);
        awaitLog(
                e ->
                        "READY: hello-world-marker".equals(e.getMessage())
                                && TAG.equals(
                                        e.getMDCPropertyMap().get(LoggingSetup.COMPONENT_MDC_KEY)));
    }

    /** A predicate that never matches must time out rather than block forever (WBS 3.1.2.10). */
    @Test
    void awaitLineTimesOutWhenTheMarkerNeverArrives() throws Exception {
        LineWaiter waiter = new LineWaiter();
        SubprocessManager m =
                SubprocessManager.start(TestSupport.testChild("sleep", "5000"), TAG, GRACE, waiter);
        try {
            long start = System.nanoTime();
            assertThrows(
                    TimeoutException.class,
                    () ->
                            waiter.awaitLine(
                                    line -> line.contains("never appears"),
                                    Duration.ofMillis(300)));
            long elapsedMs = (System.nanoTime() - start) / 1_000_000;
            assertTrue(elapsedMs < 5000, "awaitLine did not honour its own timeout");
        } finally {
            m.terminate();
        }
    }

    @Test
    void envOverridesArePassedToChild() throws Exception {
        ProcessBuilder pb = TestSupport.testChild("env", "TEST_HARNESS_OVERLAY");
        pb.environment().put("TEST_HARNESS_OVERLAY", "overlay-value");
        SubprocessManager m = SubprocessManager.start(pb, TAG, GRACE);
        m.onExit().get(AWAIT_SECONDS, TimeUnit.SECONDS);
        awaitLog(
                e ->
                        "overlay-value".equals(e.getMessage())
                                && TAG.equals(
                                        e.getMDCPropertyMap().get(LoggingSetup.COMPONENT_MDC_KEY)));
    }

    /**
     * Mirrors the launcher pattern documented in spec §5.3 and {@code SubprocessManagerExample}: an
     * unexpected exit (no {@code shuttingDown} flag set) should reach the onExit callback with the
     * real exit code so the launcher can post a {@code SubprocessExited} FSM event.
     */
    @Test
    void unexpectedExitDeliversCodeToOnExitCallback() throws Exception {
        AtomicBoolean shuttingDown = new AtomicBoolean();
        AtomicReference<Integer> unexpectedCode = new AtomicReference<>();
        CountDownLatch callbackRan = new CountDownLatch(1);

        SubprocessManager m =
                SubprocessManager.start(TestSupport.testChild("exit", "7"), TAG, GRACE);
        m.onExit()
                .thenAccept(
                        code -> {
                            if (!shuttingDown.get()) {
                                unexpectedCode.set(code);
                            }
                            callbackRan.countDown();
                        });

        assertTrue(
                callbackRan.await(AWAIT_SECONDS, TimeUnit.SECONDS), "onExit callback never fired");
        assertEquals(Integer.valueOf(7), unexpectedCode.get());
    }

    /**
     * Counterpart to {@link #unexpectedExitDeliversCodeToOnExitCallback()}: when the launcher sets
     * {@code shuttingDown} before {@link SubprocessManager#terminate()}, the callback runs but the
     * exit is treated as expected, so the FSM is not notified.
     */
    @Test
    void expectedShutdownSuppressesUnexpectedHandling() throws Exception {
        AtomicBoolean shuttingDown = new AtomicBoolean();
        AtomicReference<Integer> unexpectedCode = new AtomicReference<>();
        CountDownLatch callbackRan = new CountDownLatch(1);

        SubprocessManager m =
                SubprocessManager.start(TestSupport.testChild("sleep", "60000"), TAG, GRACE);
        m.onExit()
                .thenAccept(
                        code -> {
                            if (!shuttingDown.get()) {
                                unexpectedCode.set(code);
                            }
                            callbackRan.countDown();
                        });

        shuttingDown.set(true);
        m.terminate();
        assertTrue(
                callbackRan.await(AWAIT_SECONDS, TimeUnit.SECONDS), "onExit callback never fired");
        assertNull(unexpectedCode.get(), "expected shutdown should not be flagged as unexpected");
    }

    /**
     * Regression for the race where a fast-exiting child's deregister callback fires before {@link
     * SubprocessManager#start} adds the manager to {@link SubprocessRegistry}, leaving the manager
     * pinned in the active set for the JVM lifetime. The {@code true} utility is the
     * most-aggressive trigger available; {@link TestSupport#fastExitingNativeChild()} resolves it
     * from {@code PATH} rather than assuming a path, because Linux and a bare-metal macOS dev box
     * disagree about where it lives (#227).
     */
    @Test
    void fastExitingChildDoesNotLeakIntoRegistry() throws Exception {
        SubprocessManager m =
                SubprocessManager.start(TestSupport.fastExitingNativeChild(), TAG, GRACE);
        m.onExit().get(AWAIT_SECONDS, TimeUnit.SECONDS);
        awaitDeregistered(m);
    }

    /**
     * Waits for {@code m} to leave {@link SubprocessRegistry}, failing if it has not within {@link
     * #POLL_BUDGET_MS}.
     *
     * <p>Deregistration is in fact ordered <em>before</em> {@code onExit()} completes: {@code
     * deregister(this)} runs inside the {@code thenApply} that completes {@code exitFuture}, and
     * {@code onExit()} hands out a copy of it, so {@code get()} returning already implies the
     * removal ran — via the reaper path or {@code start()}'s synchronous {@code !isAlive()}
     * fallback. Measured: a 400ms child was out of the registry on the first check, 40/40, with no
     * polling at all.
     *
     * <p>The poll stays regardless. It costs nothing, it replaces a fixed 50ms sleep that was a
     * guess at how long to wait rather than a wait for the condition, and it keeps this correct if
     * that chain is ever reordered into a sibling {@code thenRun}. The leak it guards against is
     * permanent — the manager stays pinned for the JVM's lifetime — so a budget cannot mask it,
     * only delay the report.
     *
     * @param m the manager whose deregistration to await
     * @throws InterruptedException if the wait is interrupted
     */
    private static void awaitDeregistered(final SubprocessManager m) throws InterruptedException {
        long deadline = System.currentTimeMillis() + POLL_BUDGET_MS;
        while (System.currentTimeMillis() < deadline) {
            if (!SubprocessRegistry.contains(m)) {
                return;
            }
            Thread.sleep(POLL_INTERVAL_MS);
        }
        fail("manager leaked into SubprocessRegistry.ACTIVE after process exited");
    }

    private void awaitLog(Predicate<ILoggingEvent> matcher) throws InterruptedException {
        long deadline = System.currentTimeMillis() + POLL_BUDGET_MS;
        while (System.currentTimeMillis() < deadline) {
            for (ILoggingEvent e : appender.list) {
                if (matcher.test(e)) {
                    return;
                }
            }
            Thread.sleep(POLL_INTERVAL_MS);
        }
        fail("predicate never matched. captured: " + appender.list);
    }
}
