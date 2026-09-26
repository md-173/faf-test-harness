package com.faforever.testharness.shared.process;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.faforever.testharness.shared.logging.ProcessOutputLogger;
import java.time.Duration;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.slf4j.LoggerFactory;

/**
 * Exercises {@link ProcessOutputLogger#captureAsync(Process, String, java.util.function.Consumer)}
 * against a real scripted child, covering the WBS 3.1.2.10 / #225 acceptance criteria exactly: the
 * observer sees output as it arrives, and the existing SLF4J routing is neither duplicated nor
 * lost, with reader threads shut down on exit.
 */
@Timeout(value = 30, unit = TimeUnit.SECONDS)
class ProcessOutputLoggerLineObserverTest {

    private static final String TAG = "ObservedChild";

    /** Text of {@code ProcessOutputLogger}'s own line-observer-failure warning, unsubstituted. */
    private static final String OBSERVER_THREW_MESSAGE =
            "Subprocess line observer for {} threw; output capture continues";

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
    void observerSeesRawLinesAndSlf4jLogsEachBlockExactlyOnce() throws Exception {
        Process process =
                TestSupport.testChild("lines", "one", "\tstack-frame-continuation", "two").start();
        LineWaiter waiter = new LineWaiter();
        ExecutorService readers = ProcessOutputLogger.captureAsync(process, TAG, waiter);
        try {
            // The observer sees the raw line the moment it arrives, independent of the
            // continuation-line buffering SLF4J applies before logging a block.
            String tabLine =
                    waiter.awaitLine(line -> line.startsWith("\t"), Duration.ofSeconds(10));
            assertEquals("\tstack-frame-continuation", tabLine);
            waiter.awaitLine(line -> "two".equals(line), Duration.ofSeconds(10));

            assertTrue(process.waitFor(10, TimeUnit.SECONDS), "child did not exit in time");
        } finally {
            readers.shutdown();
        }
        assertTrue(readers.awaitTermination(5, TimeUnit.SECONDS), "readers did not finish");

        // SLF4J still receives "one" and its continuation joined into one block, and "two"
        // separately — each exactly once, so this catches both a doubled block and a merge that
        // stopped coalescing the continuation line.
        List<String> logged = appender.list.stream().map(ILoggingEvent::getMessage).toList();
        assertEquals(
                1,
                Collections.frequency(logged, "one\n\tstack-frame-continuation"),
                logged.toString());
        assertEquals(1, Collections.frequency(logged, "two"), logged.toString());
    }

    @Test
    void observerExceptionDoesNotStopSlf4jRoutingOnEitherStream() throws Exception {
        Process process =
                TestSupport.testChild("lines", "stdout-a", "stdout-b", "err:stderr-a").start();
        ExecutorService readers =
                ProcessOutputLogger.captureAsync(
                        process,
                        TAG,
                        line -> {
                            throw new AssertionError("observer boom for: " + line);
                        });
        try {
            assertTrue(process.waitFor(10, TimeUnit.SECONDS), "child did not exit in time");
        } finally {
            readers.shutdown();
        }
        assertTrue(readers.awaitTermination(5, TimeUnit.SECONDS), "readers did not finish");

        List<String> logged = appender.list.stream().map(ILoggingEvent::getMessage).toList();
        // Both stdout lines and the stderr line still reach SLF4J exactly once each, proving an
        // observer that throws on every line — on either stream — never stops capture of either.
        assertEquals(1, Collections.frequency(logged, "stdout-a"), logged.toString());
        assertEquals(1, Collections.frequency(logged, "stdout-b"), logged.toString());
        assertEquals(1, Collections.frequency(logged, "stderr-a"), logged.toString());
        // One observer failure per line, on both streams; catches a regression that wires one
        // stream's reader to a no-op observer instead of the one under test.
        assertEquals(3, Collections.frequency(logged, OBSERVER_THREW_MESSAGE), logged.toString());
    }
}
