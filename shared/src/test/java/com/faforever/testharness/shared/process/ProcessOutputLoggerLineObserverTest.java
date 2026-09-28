package com.faforever.testharness.shared.process;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.faforever.testharness.shared.logging.LoggingSetup;
import com.faforever.testharness.shared.logging.ProcessOutputLogger;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

/**
 * Exercises {@link ProcessOutputLogger#captureAsync(Process, String, java.util.function.Consumer)}
 * against a real scripted child, covering the WBS 3.1.2.10 / #225 acceptance criteria exactly: the
 * observer sees output as it arrives, and the existing SLF4J routing is neither duplicated nor
 * lost, with reader threads shut down on exit.
 *
 * <p>Also covers WBS 2.3.6-fix (#450): a line that nothing follows is logged after the idle
 * interval, under the reader's component tag, instance label and stream level; a stack trace is
 * still one event whether its lines arrive apart or the reader is slow to take them; and a read
 * error still logs the line the reader was holding.
 */
@Timeout(value = 30, unit = TimeUnit.SECONDS)
class ProcessOutputLoggerLineObserverTest {

    private static final String TAG = "ObservedChild";

    /** Text of {@code ProcessOutputLogger}'s own line-observer-failure warning, unsubstituted. */
    private static final String OBSERVER_THREW_MESSAGE =
            "Subprocess line observer for {} threw; output capture continues";

    /** Instance label the silence test captures under. */
    private static final String INSTANCE = "peer-quiet";

    /** Far longer than the idle interval, so a wait that ends in time was not a slow flush. */
    private static final Duration LOG_WAIT = Duration.ofSeconds(10);

    /**
     * Ceiling on the time from reading a quiet line to logging it. The idle interval is 200 ms, so
     * only a flush several times slower than designed trips this, not a busy machine.
     */
    private static final Duration LOG_LATENCY_CEILING = Duration.ofSeconds(2);

    /** Twice the idle interval: how long the one-piece trace test holds its reader up. */
    private static final Duration READER_STALL = Duration.ofMillis(400);

    private static final Duration POLL_INTERVAL = Duration.ofMillis(20);

    /** Lines the read-error test's child writes, one every {@link #TICK_MILLIS}. */
    private static final int TICKS = 60;

    private static final int TICK_MILLIS = 50;

    private ListAppender<ILoggingEvent> appender;
    private Logger root;

    @BeforeEach
    void attachAppender() {
        LoggerContext ctx = (LoggerContext) LoggerFactory.getILoggerFactory();
        root = ctx.getLogger(Logger.ROOT_LOGGER_NAME);
        // Freeze each event as it is appended: logback resolves an event's MDC map and thread
        // name lazily, from whichever thread asks first, and these tests read them later on the
        // test thread.
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

    @Test
    void linesFollowedBySilenceAreLoggedWhileTheChildIsStillSilent() throws Exception {
        // Nothing follows either line and the child outlives the wait, so neither a later line
        // nor the end of the stream can push them out: only the idle flush can log them. That
        // runs on another thread, so the component tag (which awaitLogged matches on), the
        // instance label and each stream's level have to travel with the block.
        Process process =
                TestSupport.testChild("lines", "quiet-line", "err:quiet-err-line", "sleep:60000")
                        .start();
        List<String> observed = new CopyOnWriteArrayList<>();
        Map<String, Long> observedAt = new ConcurrentHashMap<>();
        List<String> loggedBeforeObserved = new CopyOnWriteArrayList<>();
        ExecutorService readers;
        MDC.put(LoggingSetup.INSTANCE_MDC_KEY, INSTANCE);
        try {
            readers =
                    ProcessOutputLogger.captureAsync(
                            process,
                            TAG,
                            line -> {
                                if (loggedMessages().contains(line)) {
                                    loggedBeforeObserved.add(line);
                                }
                                observed.add(line);
                                observedAt.put(line, System.nanoTime());
                            });
        } finally {
            MDC.remove(LoggingSetup.INSTANCE_MDC_KEY);
        }
        try {
            ILoggingEvent out = awaitLogged("quiet-line");
            Duration outLatency =
                    Duration.ofNanos(System.nanoTime() - observedAt.get("quiet-line"));
            ILoggingEvent err = awaitLogged("quiet-err-line");
            Duration errLatency =
                    Duration.ofNanos(System.nanoTime() - observedAt.get("quiet-err-line"));

            assertTrue(process.isAlive(), "the child must still be silent, not gone");
            assertEquals(1, countLogged("quiet-line"), loggedMessages().toString());
            assertEquals(1, countLogged("quiet-err-line"), loggedMessages().toString());
            assertTrue(outLatency.compareTo(LOG_LATENCY_CEILING) < 0, "logged after " + outLatency);
            assertTrue(errLatency.compareTo(LOG_LATENCY_CEILING) < 0, "logged after " + errLatency);
            assertEquals(Level.INFO, out.getLevel(), "stdout is logged at INFO");
            assertEquals(Level.WARN, err.getLevel(), "stderr is logged at WARN");
            assertEquals(INSTANCE, out.getMDCPropertyMap().get(LoggingSetup.INSTANCE_MDC_KEY));
            assertEquals(INSTANCE, err.getMDCPropertyMap().get(LoggingSetup.INSTANCE_MDC_KEY));
            assertEquals(
                    List.of("quiet-err-line", "quiet-line"), observed.stream().sorted().toList());
            assertEquals(
                    List.of(), loggedBeforeObserved, "observer must see a line before its log");
        } finally {
            process.destroyForcibly();
            readers.shutdown();
        }
        assertTrue(process.waitFor(10, TimeUnit.SECONDS), "child did not exit in time");
        assertTrue(readers.awaitTermination(5, TimeUnit.SECONDS), "readers did not finish");
    }

    @Test
    void aStackTraceWhoseLinesArriveApartIsStillOneEvent() throws Exception {
        // printStackTrace writes a line at a time. The pause after the first line stands in for
        // a gap between those writes: well inside the idle interval, but long enough that a
        // reader flushing whenever it had nothing buffered would split the trace here.
        List<String> trace =
                List.of(
                        "java.lang.IllegalStateException: boom",
                        "\tat com.example.Game.run(Game.java:1)",
                        "Caused by: java.io.IOException: pipe",
                        "\tat com.example.Pipe.read(Pipe.java:2)",
                        "\t... 1 more");
        List<String> args = new ArrayList<>(List.of("lines", trace.get(0), "sleep:50"));
        args.addAll(trace.subList(1, trace.size()));
        args.add("after-trace");
        Process process = TestSupport.testChild(args.toArray(String[]::new)).start();
        List<String> observed = new CopyOnWriteArrayList<>();
        ExecutorService readers = ProcessOutputLogger.captureAsync(process, TAG, observed::add);
        try {
            assertTrue(process.waitFor(10, TimeUnit.SECONDS), "child did not exit in time");
        } finally {
            readers.shutdown();
        }
        assertTrue(readers.awaitTermination(5, TimeUnit.SECONDS), "readers did not finish");

        List<String> logged = loggedMessages();
        assertEquals(1, countLogged(String.join("\n", trace)), logged.toString());
        assertEquals(1, countLogged("after-trace"), logged.toString());
        assertTrue(
                logged.stream().noneMatch(m -> m.startsWith("\t") || m.startsWith("Caused by:")),
                "a continuation line was logged on its own: " + logged);
        List<String> raw = new ArrayList<>(trace);
        raw.add("after-trace");
        assertEquals(raw, observed, "the observer still sees every raw line, in order");
    }

    @Test
    void aStackTraceThatArrivesInOnePieceIsNotSplitByASlowReader() throws Exception {
        // The child writes the whole trace at once, so once the reader has its first line the
        // rest is already readable. The observer then holds the reader up past the idle interval,
        // as a starved reader thread would: the idle flusher must leave the head of the trace
        // alone, because the reader was about to add more to it.
        List<String> trace =
                List.of(
                        "java.lang.IllegalStateException: boom",
                        "\tat com.example.Game.run(Game.java:1)",
                        "\t... 1 more");
        List<String> args = new ArrayList<>(List.of("chunk"));
        args.addAll(trace);
        Process process = TestSupport.testChild(args.toArray(String[]::new)).start();
        ExecutorService readers =
                ProcessOutputLogger.captureAsync(
                        process,
                        TAG,
                        line -> {
                            if (line.equals(trace.get(1))) {
                                stall(READER_STALL);
                            }
                        });
        try {
            assertTrue(process.waitFor(10, TimeUnit.SECONDS), "child did not exit in time");
        } finally {
            readers.shutdown();
        }
        assertTrue(readers.awaitTermination(5, TimeUnit.SECONDS), "readers did not finish");

        assertEquals(1, countLogged(String.join("\n", trace)), loggedMessages().toString());
    }

    @Test
    void aReadErrorStillLogsTheLineTheReaderHeld() throws Exception {
        // Closing the stream under the reader fails its next read, the way Process.destroy() did
        // at every teardown (#361). Whatever the reader was holding at that moment must still be
        // logged, by its own finally: every line the observer saw is logged exactly once.
        List<String> args = new ArrayList<>(List.of("lines"));
        for (int i = 1; i <= TICKS; i++) {
            args.add("tick-" + i);
            args.add("sleep:" + TICK_MILLIS);
        }
        Process process = TestSupport.testChild(args.toArray(String[]::new)).start();
        List<String> observed = new CopyOnWriteArrayList<>();
        LineWaiter waiter = new LineWaiter();
        ExecutorService readers =
                ProcessOutputLogger.captureAsync(
                        process,
                        TAG,
                        line -> {
                            observed.add(line);
                            waiter.accept(line);
                        });
        try {
            waiter.awaitLine("tick-5"::equals, LOG_WAIT);
            process.getInputStream().close();
            // Wait for the failed read itself: killing the child before its next tick would end
            // the stream cleanly instead, and this test would then prove nothing about the error.
            awaitLogged("Error reading subprocess stream for " + TAG);
        } finally {
            process.destroyForcibly();
            readers.shutdown();
        }
        assertTrue(process.waitFor(10, TimeUnit.SECONDS), "child did not exit in time");
        assertTrue(readers.awaitTermination(5, TimeUnit.SECONDS), "readers did not finish");

        assertTrue(observed.size() >= 5, "observed: " + observed);
        for (String line : observed) {
            assertEquals(
                    1, countLogged(line), line + " logged other than once: " + loggedMessages());
        }
    }

    /**
     * Sleeps on the calling reader thread, standing in for a reader that is slow to take its next
     * line.
     *
     * @param duration how long to hold the reader up
     */
    private static void stall(final Duration duration) {
        try {
            Thread.sleep(duration.toMillis());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * The formatted messages logged for this test's child, in logging order. Filtered by its
     * component tag, so nothing else in the JVM can add to a count.
     */
    private List<String> loggedMessages() {
        return appender.list.stream()
                .filter(e -> TAG.equals(e.getMDCPropertyMap().get(LoggingSetup.COMPONENT_MDC_KEY)))
                .map(ILoggingEvent::getFormattedMessage)
                .toList();
    }

    private int countLogged(final String message) {
        return Collections.frequency(loggedMessages(), message);
    }

    /** Polls until this test's child has logged {@code message}, or fails after LOG_WAIT. */
    private ILoggingEvent awaitLogged(final String message) throws InterruptedException {
        long deadline = System.nanoTime() + LOG_WAIT.toNanos();
        while (System.nanoTime() - deadline < 0) {
            for (ILoggingEvent event : appender.list) {
                if (message.equals(event.getFormattedMessage())
                        && TAG.equals(
                                event.getMDCPropertyMap().get(LoggingSetup.COMPONENT_MDC_KEY))) {
                    return event;
                }
            }
            Thread.sleep(POLL_INTERVAL.toMillis());
        }
        return fail("not logged within " + LOG_WAIT + ": " + message + "; got " + loggedMessages());
    }
}
