package com.faforever.testharness.shared.logging;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
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
     * (stack traces) are merged into a block for the log event. It runs on a reader thread, so it
     * must not block: a slow or hung observer stalls that stream's draining exactly as a slow SLF4J
     * appender would. An observer that throws is logged and otherwise ignored, so it cannot stop
     * output capture.
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
     * into a single log block.
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
        StringBuilder block = new StringBuilder();
        String line;
        while ((line = reader.readLine()) != null) {
            observeLine(lineObserver, line, componentTag);
            if (isContinuationLine(line) && block.length() > 0) {
                block.append('\n').append(line);
            } else {
                flushBlock(block, isStderr);
                block.setLength(0);
                block.append(line);
            }
        }
        flushBlock(block, isStderr);
    }

    /**
     * Invokes {@code lineObserver} for {@code line}, containing any exception it throws so a broken
     * observer cannot stop the reader thread from continuing to drain and log output.
     *
     * @param lineObserver the observer to invoke
     * @param line the raw line just read
     * @param componentTag identifies the stream in the failure log
     */
    private static void observeLine(
            final Consumer<String> lineObserver, final String line, final String componentTag) {
        try {
            lineObserver.accept(line);
        } catch (RuntimeException e) {
            LOG.warn(
                    "Subprocess line observer for {} threw; output capture continues",
                    componentTag,
                    e);
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
     * @param block the buffered text to emit; cleared after logging
     * @param isStderr if {@code true}, logs at WARN level; otherwise INFO
     */
    private static void flushBlock(final StringBuilder block, final boolean isStderr) {
        if (block.length() == 0) {
            return;
        }
        String text = block.toString();
        if (isStderr) {
            LOG.warn(text);
        } else {
            LOG.info(text);
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
