package com.faforever.testharness.shared.logging;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

/**
 * Routes stdout and stderr of a child process through SLF4J.
 *
 * <p>Call {@link #captureAsync(Process, String)} immediately after starting a subprocess. Two
 * daemon threads drain the process streams and emit SLF4J records tagged with the given component
 * name, so their output is indistinguishable in format from the parent process's own log lines.
 *
 * <p>Multi-line output such as Java stack traces is recognised by leading tab characters and {@code
 * "Caused by:"} prefixes, and is buffered into a single log event rather than emitted as a stream
 * of unrelated lines.
 *
 * <p>A buffered block is logged when the next line starts a new one, when the stream ends, or once
 * {@link #IDLE_FLUSH} passes with nothing added to it (WBS 2.3.6-fix, #450). Without that last rule
 * a child that went quiet kept its latest line out of the log until it wrote again, and that is the
 * line that says where a stalled child stopped. The idle case is logged by one daemon thread shared
 * by every capture, under the reader's MDC, so its event carries the same component and instance
 * label as the reader's own; only the thread name differs.
 *
 * <p><b>Usage:</b>
 *
 * <pre>{@code
 * Process ice = new ProcessBuilder("faf-ice-adapter", "--args").start();
 * ExecutorService readers = ProcessOutputLogger.captureAsync(ice, "ICEAdapter");
 * // Later, after process.waitFor():
 * readers.shutdown();
 * }</pre>
 */
public final class ProcessOutputLogger {

    /** Logger used only for internal capture errors, not for subprocess output. */
    private static final Logger LOG = LoggerFactory.getLogger(ProcessOutputLogger.class);

    /** Number of reader threads started per process (one for stdout, one for stderr). */
    private static final int READER_THREAD_COUNT = 2;

    /**
     * How long a held block waits for a continuation line before it is logged on its own. A stack
     * trace printed a line at a time arrives well inside this, so it stays one event, while a
     * child's last line before a quiet spell reaches the log this long after it was read.
     */
    private static final Duration IDLE_FLUSH = Duration.ofMillis(200);

    /**
     * How often the idle flusher looks at the held blocks, so a quiet line is logged within {@link
     * #IDLE_FLUSH} plus this.
     */
    private static final Duration IDLE_CHECK_INTERVAL = Duration.ofMillis(20);

    /** Name of the idle flusher's thread, which shows in the log records it writes. */
    private static final String IDLE_FLUSHER_NAME = "process-output-idle-flush";

    /** The block every open stream is holding; each reader adds its own and removes it on exit. */
    private static final Set<HeldBlock> HELD_BLOCKS = ConcurrentHashMap.newKeySet();

    /** One daemon thread for the whole JVM that logs held blocks gone quiet; runs until exit. */
    private static final ScheduledThreadPoolExecutor IDLE_FLUSHER = startIdleFlusher();

    private ProcessOutputLogger() {}

    /**
     * Starts background daemon threads that drain and log the stdout and stderr of the given
     * process.
     *
     * <p>Each stream is read on a separate thread so that neither stream can block the other. Both
     * threads set the SLF4J MDC component key to {@code componentTag} so every captured line
     * appears tagged in the logs. They also carry the calling thread's instance label, if any
     * ({@link InstanceLabel}), so a subprocess launched for one of several in-process instances is
     * attributed to that instance.
     *
     * @param process the child process whose output to capture; must be started before this call
     * @param componentTag component label applied to every captured log line, e.g. {@code
     *     "ICEAdapter"} or {@code "MockGame"}
     * @return the {@link ExecutorService} managing the reader threads; the caller should invoke
     *     {@code shutdown()} after the process exits
     */
    public static ExecutorService captureAsync(final Process process, final String componentTag) {
        return captureAsync(process, componentTag, line -> {});
    }

    /**
     * Same as {@link #captureAsync(Process, String)}, plus an opt-in per-line observer.
     *
     * <p>{@code lineObserver} is invoked once per raw line read from either stream, in addition to
     * — not instead of — the normal SLF4J routing, so it sees every line before continuation lines
     * (stack traces) are merged into a block for the log event. It runs on a reader thread — one
     * per stream, so the same observer instance can be called from both threads at once for a
     * process that writes to stdout and stderr concurrently, and must therefore be thread-safe;
     * order is only defined within one stream, not across both. It must also not block: a slow or
     * hung observer stalls that stream's draining exactly as a slow SLF4J appender would. Anything
     * the observer throws, including an {@link Error} such as a failed assertion, is caught and
     * logged rather than propagated, so it cannot stop output capture — which also means an
     * assertion failure inside an observer cannot fail the calling test. Record the line instead
     * and assert on it from the test thread.
     *
     * <p>Callers that want a bounded wait on a specific line (e.g. a readiness marker) rather than
     * a raw per-line callback can pass a {@link
     * com.faforever.testharness.shared.process.LineWaiter} as {@code lineObserver} and call its
     * {@code awaitLine}.
     *
     * @param process the child process whose output to capture; must be started before this call
     * @param componentTag component label applied to every captured log line, e.g. {@code
     *     "ICEAdapter"} or {@code "MockGame"}
     * @param lineObserver invoked with each raw line from stdout or stderr, in arrival order per
     *     stream; must not be {@code null}
     * @return the {@link ExecutorService} managing the reader threads; the caller should invoke
     *     {@code shutdown()} after the process exits
     */
    public static ExecutorService captureAsync(
            final Process process, final String componentTag, final Consumer<String> lineObserver) {
        Objects.requireNonNull(lineObserver, "lineObserver");
        ExecutorService executor =
                Executors.newFixedThreadPool(
                        READER_THREAD_COUNT, new DaemonThreadFactory(componentTag));
        InstanceLabel label = InstanceLabel.capture();
        executor.submit(
                label.wrap(
                        () ->
                                streamToLog(
                                        process.getInputStream(),
                                        componentTag,
                                        false,
                                        lineObserver)));
        executor.submit(
                label.wrap(
                        () ->
                                streamToLog(
                                        process.getErrorStream(),
                                        componentTag,
                                        true,
                                        lineObserver)));
        return executor;
    }

    /**
     * Reads lines from {@code stream} until EOF, buffering continuation lines (stack traces) into a
     * single log event, then logs each completed block.
     *
     * @param stream the input stream to read from
     * @param componentTag MDC component tag applied for the duration of reading
     * @param isStderr if {@code true}, blocks are logged at WARN level; otherwise at INFO level
     * @param lineObserver invoked with each raw line before it is buffered for logging
     */
    private static void streamToLog(
            final InputStream stream,
            final String componentTag,
            final boolean isStderr,
            final Consumer<String> lineObserver) {
        MDC.put(LoggingSetup.COMPONENT_MDC_KEY, componentTag);
        try (BufferedReader reader =
                new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
            drainReaderToLog(reader, isStderr, componentTag, lineObserver);
        } catch (IOException e) {
            LOG.error("Error reading subprocess stream for {}", componentTag, e);
        } finally {
            MDC.remove(LoggingSetup.COMPONENT_MDC_KEY);
        }
    }

    /**
     * Reads all lines from {@code reader}, assembling consecutive stack-trace continuation lines
     * into a single log block, and logs whatever block is still held when the reader stops, at end
     * of stream or on a read error.
     *
     * <p>The block is registered with the idle flusher for as long as the stream is open, so a
     * block nothing follows is logged after {@link #IDLE_FLUSH} rather than when the child next
     * writes. The final flush stays on this thread, which sees the pipe close before the JVM
     * records the child's exit, so a child's last line is normally logged ahead of whatever reacts
     * to that exit.
     *
     * @param reader the reader positioned at the start of the stream
     * @param isStderr if {@code true}, completed blocks are logged at WARN; otherwise at INFO
     * @param componentTag used only to identify the stream in a line-observer failure log
     * @param lineObserver invoked with each raw line before it is buffered for logging
     * @throws IOException if the reader encounters an I/O error
     */
    private static void drainReaderToLog(
            final BufferedReader reader,
            final boolean isStderr,
            final String componentTag,
            final Consumer<String> lineObserver)
            throws IOException {
        HeldBlock held = new HeldBlock(isStderr);
        HELD_BLOCKS.add(held);
        try {
            String line;
            while ((line = reader.readLine()) != null) {
                observeLine(lineObserver, line, componentTag);
                held.add(line, reader.ready());
            }
        } finally {
            HELD_BLOCKS.remove(held);
            held.flush();
        }
    }

    /**
     * Invokes {@code lineObserver} for {@code line}, containing anything it throws so a broken
     * observer cannot stop the reader thread from continuing to drain and log output.
     *
     * <p>Catches {@link Throwable}, not just {@link RuntimeException}: an {@link Error} such as
     * {@code AssertionError} is exactly the kind of thing a test's observer throws, and letting it
     * escape would close this method's caller's {@code try}-with-resources reader, closing the
     * child's pipe with it — silently losing every later line on this stream and, for a native
     * child still writing to it, risking SIGPIPE.
     *
     * @param lineObserver the observer to invoke
     * @param line the raw line just read
     * @param componentTag identifies the stream in the failure log
     */
    private static void observeLine(
            final Consumer<String> lineObserver, final String line, final String componentTag) {
        try {
            lineObserver.accept(line);
        } catch (Throwable t) {
            LOG.warn(
                    "Subprocess line observer for {} threw; output capture continues",
                    componentTag,
                    t);
        }
    }

    /**
     * Returns {@code true} if {@code line} is a continuation of a Java stack trace and should be
     * appended to the current log block rather than starting a new one.
     *
     * <p>Recognised patterns: lines beginning with a tab character (stack frame entries and {@code
     * "... N more"} lines) and lines beginning with {@code "Caused by:"} (chained exception
     * headers).
     *
     * @param line the line to test; must not be {@code null}
     * @return {@code true} if the line continues a previous block
     */
    private static boolean isContinuationLine(final String line) {
        return line.startsWith("\t") || line.startsWith("Caused by:");
    }

    /**
     * Emits the accumulated content of {@code block} as a single log event, then clears the
     * builder. Does nothing if {@code block} is empty.
     *
     * <p>The builder is cleared before the event is logged, so a block whose logging throws is
     * dropped rather than offered to the idle flusher again every time it looks.
     *
     * @param block the buffered text to emit; cleared before logging
     * @param isStderr if {@code true}, logs at WARN level; otherwise INFO
     */
    private static void flushBlock(final StringBuilder block, final boolean isStderr) {
        if (block.length() == 0) {
            return;
        }
        String text = block.toString();
        block.setLength(0);
        if (isStderr) {
            LOG.warn(text);
        } else {
            LOG.info(text);
        }
    }

    /**
     * Starts the idle flusher: one daemon thread that runs {@link #flushIdleBlocks()} every {@link
     * #IDLE_CHECK_INTERVAL} for as long as the JVM lives.
     *
     * @return the running flusher
     */
    private static ScheduledThreadPoolExecutor startIdleFlusher() {
        ScheduledThreadPoolExecutor flusher =
                new ScheduledThreadPoolExecutor(
                        1,
                        task -> {
                            Thread thread = new Thread(task, IDLE_FLUSHER_NAME);
                            thread.setDaemon(true);
                            return thread;
                        });
        long interval = IDLE_CHECK_INTERVAL.toMillis();
        flusher.scheduleWithFixedDelay(
                ProcessOutputLogger::flushIdleBlocks, interval, interval, TimeUnit.MILLISECONDS);
        return flusher;
    }

    /**
     * Logs every held block that has been quiet for {@link #IDLE_FLUSH}; the idle flusher's task.
     *
     * <p>Catches {@link Throwable} per block, as {@link #observeLine} does: logback contains an
     * appender's exceptions but not its errors, and anything escaping this method would cancel the
     * schedule for the rest of the JVM's life, so every capture would silently go back to holding a
     * quiet child's last line.
     */
    private static void flushIdleBlocks() {
        long now = System.nanoTime();
        for (HeldBlock held : HELD_BLOCKS) {
            try {
                held.flushIfIdle(now);
            } catch (Throwable t) {
                LOG.warn("Idle flush of subprocess output failed; output capture continues", t);
            }
        }
    }

    /**
     * One stream's lines waiting to be logged as a single event (WBS 2.3.6-fix, #450). The stream's
     * reader adds each line and logs the block a new line ends; the idle flusher logs a block that
     * nothing has been added to for {@link ProcessOutputLogger#IDLE_FLUSH}. Every method holds this
     * block's monitor, which is the whole hand-off between the two threads.
     */
    private static final class HeldBlock {

        /** Whether the block comes from stderr, which is logged at WARN rather than INFO. */
        private final boolean isStderr;

        /**
         * The reader thread's MDC, component and instance label included, applied while the idle
         * flusher logs this block so its event is tagged as the reader would have tagged it.
         */
        private final Map<String, String> readerContext;

        /** The lines of the event being held, joined by newlines; empty while nothing is held. */
        private final StringBuilder block = new StringBuilder();

        /** When the latest line was added, by {@link System#nanoTime()}. */
        private long lastLineNanos;

        /**
         * Whether more of the stream was already readable when the latest line was added. The
         * reader is then about to add the next line, so the idle flusher leaves the block alone
         * even if the reader is slow getting to it, and a stack trace that arrived in one piece
         * cannot be split by a pause on this side. A child that writes part of a line and then goes
         * quiet counts as readable too, so the block before that line stays held until the line is
         * finished; the harness's own children write whole lines.
         */
        private boolean moreReadable;

        /**
         * Creates an empty block that remembers the calling reader thread's MDC.
         *
         * @param isStderr whether the block comes from stderr
         */
        private HeldBlock(final boolean isStderr) {
            this.isStderr = isStderr;
            this.readerContext = MDC.getCopyOfContextMap();
        }

        /**
         * Adds a line just read: appended to the held block if it continues a stack trace,
         * otherwise the held block is logged and the line starts the next one.
         *
         * @param line the raw line
         * @param readableNow whether more of the stream could be read without blocking
         */
        private synchronized void add(final String line, final boolean readableNow) {
            if (isContinuationLine(line) && block.length() > 0) {
                block.append('\n').append(line);
            } else {
                flushBlock(block, isStderr);
                block.append(line);
            }
            lastLineNanos = System.nanoTime();
            moreReadable = readableNow;
        }

        /** Logs the held block, if any, on the calling reader thread. */
        private synchronized void flush() {
            flushBlock(block, isStderr);
        }

        /**
         * Logs the held block if nothing has been added to it for {@link
         * ProcessOutputLogger#IDLE_FLUSH} and the reader was not about to add more. Runs on the
         * idle flusher, which has no MDC of its own, so the reader's is applied for the event and
         * cleared again afterwards.
         *
         * @param now the current {@link System#nanoTime()}
         */
        private synchronized void flushIfIdle(final long now) {
            if (block.length() == 0 || moreReadable || now - lastLineNanos < IDLE_FLUSH.toNanos()) {
                return;
            }
            if (readerContext != null) {
                MDC.setContextMap(readerContext);
            }
            try {
                flushBlock(block, isStderr);
            } finally {
                MDC.clear();
            }
        }
    }

    /**
     * Thread factory that produces daemon threads named after the component whose output they read.
     */
    private static final class DaemonThreadFactory implements ThreadFactory {

        /** Component name used as the thread name prefix. */
        private final String namePrefix;

        /**
         * Creates a factory whose threads are named {@code <namePrefix>-output-reader}.
         *
         * @param componentTag the component tag, e.g. {@code "ICEAdapter"}
         */
        private DaemonThreadFactory(final String componentTag) {
            this.namePrefix = componentTag;
        }

        /**
         * Creates a new daemon thread with a descriptive name.
         *
         * @param r the runnable to execute in the new thread
         * @return a configured daemon thread, not yet started
         */
        @Override
        public Thread newThread(final Runnable r) {
            Thread t = new Thread(r, namePrefix + "-output-reader");
            t.setDaemon(true);
            return t;
        }
    }
}
