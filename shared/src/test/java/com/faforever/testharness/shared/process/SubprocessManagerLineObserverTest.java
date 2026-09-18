package com.faforever.testharness.shared.process;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.faforever.testharness.shared.logging.LoggingSetup;
import java.time.Duration;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.slf4j.LoggerFactory;

/**
 * Exercises {@link SubprocessManager#start(ProcessBuilder, String, Duration,
 * java.util.function.Consumer)} end to end against {@link TestChild}, covering the acceptance
 * criteria for WBS 3.1.2.10 / #225: an observer sees subprocess output as it arrives, existing
 * SLF4J routing is unaffected, and a predicate that never matches times out rather than blocking
 * forever.
 */
@Timeout(value = 30, unit = TimeUnit.SECONDS)
class SubprocessManagerLineObserverTest {

    private static final Duration GRACE = Duration.ofSeconds(5);
    private static final String TAG = "TestChild";
    private static final int AWAIT_SECONDS = 10;

    private ListAppender<ILoggingEvent> appender;
    private Logger root;

    @BeforeEach
    void attachAppender() {
        LoggerContext ctx = (LoggerContext) LoggerFactory.getILoggerFactory();
        root = ctx.getLogger(Logger.ROOT_LOGGER_NAME);
        appender = new ListAppender<>();
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
    void observerSeesEachLineAndSlf4jRoutingIsUnaffected() throws Exception {
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
    void defaultThreeArgStartStillLogsWithoutAnObserver() throws Exception {
        SubprocessManager m =
                SubprocessManager.start(
                        TestSupport.testChild("print", "unobserved-marker"), TAG, GRACE);
        m.onExit().get(AWAIT_SECONDS, TimeUnit.SECONDS);
        awaitLog(
                e ->
                        "unobserved-marker".equals(e.getMessage())
                                && TAG.equals(
                                        e.getMDCPropertyMap().get(LoggingSetup.COMPONENT_MDC_KEY)));
    }

    private void awaitLog(java.util.function.Predicate<ILoggingEvent> matcher)
            throws InterruptedException {
        long deadline = System.currentTimeMillis() + 5_000;
        while (System.currentTimeMillis() < deadline) {
            for (ILoggingEvent e : appender.list) {
                if (matcher.test(e)) {
                    return;
                }
            }
            Thread.sleep(50);
        }
        org.junit.jupiter.api.Assertions.fail(
                "predicate never matched. captured: " + appender.list);
    }
}
