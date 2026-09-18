package com.faforever.testharness.shared.logging;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.faforever.testharness.shared.process.LineWaiter;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.slf4j.LoggerFactory;

/**
 * Exercises {@link ProcessOutputLogger#captureAsync(Process, String, java.util.function.Consumer)}
 * against a real scripted child, covering the WBS 3.1.2.10 / #225 acceptance criteria: the observer
 * sees output as it arrives, and the existing SLF4J routing is neither duplicated nor lost.
 */
@Timeout(value = 30, unit = TimeUnit.SECONDS)
class ProcessOutputLoggerLineObserverTest {

    private static final String TAG = "ObservedChild";

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
    void observerSeesRawLinesAndSlf4jStillGetsThem() throws Exception {
        Process process =
                testChildCommand("lines", "one", "\tstack-frame-continuation", "two").start();
        LineWaiter waiter = new LineWaiter();
        ExecutorService readers = ProcessOutputLogger.captureAsync(process, TAG, waiter);
        try {
            // The observer sees the raw line the moment it arrives, independent of the
            // continuation-line buffering SLF4J applies before logging a block.
            String tabLine = waiter.awaitLine(line -> line.startsWith("\t"), Duration.ofSeconds(10));
            assertEquals("\tstack-frame-continuation", tabLine);
            waiter.awaitLine(line -> "two".equals(line), Duration.ofSeconds(10));

            assertTrue(process.waitFor(10, TimeUnit.SECONDS), "child did not exit in time");
        } finally {
            readers.shutdown();
        }

        // SLF4J still receives the merged block: "one" then the continuation line joined into it.
        awaitLog(e -> e.getMessage() != null && e.getMessage().contains("stack-frame-continuation"));
    }

    @Test
    void observerExceptionDoesNotStopSlf4jRoutingOrTheOtherStream() throws Exception {
        Process process = testChildCommand("lines", "will-still-log").start();
        ExecutorService readers =
                ProcessOutputLogger.captureAsync(
                        process,
                        TAG,
                        line -> {
                            throw new RuntimeException("observer boom for: " + line);
                        });
        try {
            assertTrue(process.waitFor(10, TimeUnit.SECONDS), "child did not exit in time");
        } finally {
            readers.shutdown();
        }

        awaitLog(e -> "will-still-log".equals(e.getMessage()));
    }

    /** Mirrors {@code TestSupport.forMain} (shared.process, package-private) for this package. */
    private static ProcessBuilder testChildCommand(String... testChildArgs) {
        String javaBin =
                ProcessHandle.current()
                        .info()
                        .command()
                        .orElse(System.getProperty("java.home") + "/bin/java");
        List<String> cmd = new java.util.ArrayList<>();
        cmd.add(javaBin);
        cmd.add("-cp");
        cmd.add(System.getProperty("java.class.path"));
        cmd.add("com.faforever.testharness.shared.process.TestChild");
        for (String arg : testChildArgs) {
            cmd.add(arg);
        }
        return new ProcessBuilder(cmd);
    }

    private void awaitLog(Predicate<ILoggingEvent> matcher) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 5_000;
        while (System.currentTimeMillis() < deadline) {
            for (ILoggingEvent e : appender.list) {
                if (matcher.test(e)) {
                    return;
                }
            }
            Thread.sleep(50);
        }
        fail("predicate never matched. captured: " + appender.list);
    }
}
