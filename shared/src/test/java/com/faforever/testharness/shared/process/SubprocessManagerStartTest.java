package com.faforever.testharness.shared.process;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.faforever.testharness.shared.logging.LoggingSetup;
import java.time.Duration;
import java.util.OptionalInt;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
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
     * pinned in the active set for the JVM lifetime. {@code /bin/true} is the most-aggressive
     * trigger and is always present on the supported Linux substrate (spec §1.1); the assertion
     * remains valid on any platform that ships the binary.
     */
    @Test
    void fastExitingChildDoesNotLeakIntoRegistry() throws Exception {
        ProcessBuilder pb = new ProcessBuilder("/bin/true");
        SubprocessManager m = SubprocessManager.start(pb, TAG, GRACE);
        m.onExit().get(AWAIT_SECONDS, TimeUnit.SECONDS);
        // Give any racing deregister on the reaper thread time to settle.
        Thread.sleep(50);
        assertFalse(
                SubprocessRegistry.contains(m),
                "manager leaked into SubprocessRegistry.ACTIVE after process exited");
    }

    private void awaitLog(Predicate<ILoggingEvent> matcher) throws InterruptedException {
        long deadline = System.currentTimeMillis() + POLL_BUDGET_MS;
        while (System.currentTimeMillis() < deadline) {
            ILoggingEvent[] snap;
            synchronized (appender) {
                snap = appender.list.toArray(new ILoggingEvent[0]);
            }
            for (ILoggingEvent e : snap) {
                if (matcher.test(e)) {
                    return;
                }
            }
            Thread.sleep(POLL_INTERVAL_MS);
        }
        fail("predicate never matched. captured: " + appender.list);
    }
}
