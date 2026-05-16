package com.faforever.testharness.shared.process;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.faforever.testharness.shared.logging.LoggingSetup;
import java.time.Duration;
import java.util.OptionalInt;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

/** Exercises {@link SubprocessManager#start} and the read-only accessors it sets up. */
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
