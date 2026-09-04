package com.faforever.testharness.shared.logging;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * A log file path Logback cannot use must cost the operator their filename, not their logging
 * (WBS-2.3.6-fix, #305).
 *
 * <p>{@code ${LOG_FILE}} is spliced into the rolling appender's {@code fileNamePattern}, which
 * Logback converts into a regular expression — unescaped. A path holding a regex metacharacter
 * therefore failed {@code LoggerContext} initialisation outright, and every component reports its
 * own failures through the logger, so an operator who mistyped a path got a 45-line {@code
 * LogbackException} and no word about why the run then failed.
 *
 * <p>Child JVMs, because the property is read once when Logback initialises and no in-process test
 * can un-initialise it. Follows {@link InstanceLabelEndToEndTest}.
 */
final class UnusableLogFileEndToEndTest {

    /** How long a child gets to configure logging, emit its record and flush. */
    private static final int CHILD_TIMEOUT_SECONDS = 30;

    /** Substring of the single-line notice {@code LoggingSetup} prints for a bad path. */
    private static final String NOTICE = "cannot be used by the log rotator";

    @TempDir private Path tempDir;

    /**
     * The two shapes the card names, plus the other metacharacters that break the same way. Each
     * one is a legal filename on Linux and macOS, which is what makes this reachable by typo.
     */
    @ParameterizedTest
    @ValueSource(strings = {"a[b.jsonl", "${LOG_FILE}", "a{b.jsonl", "a(b.jsonl"})
    void anUnusableLogFileDegradesToTheDefaultInsteadOfKillingLogging(final String badPath)
            throws Exception {
        String console = runChild(badPath);

        assertFalse(
                console.contains("LogbackException"),
                "logging failed to initialise instead of degrading: " + console);
        assertTrue(
                console.contains(NOTICE),
                "no single-line notice explaining the ignored path: " + console);
        assertTrue(
                console.contains(badPath), "the notice must name the path it rejected: " + console);

        Path fallback = tempDir.resolve("logs").resolve("mockclient.jsonl");
        assertTrue(
                Files.exists(fallback), "no records were written anywhere; expected " + fallback);
        assertTrue(
                Files.readString(fallback).contains(UnusableLogFileChild.MARKER),
                "the child's own record must still reach the log");
    }

    /** The control: a usable path is untouched, and produces no notice. */
    @Test
    void aUsableLogFileIsLeftAlone() throws Exception {
        Path good = tempDir.resolve("good.jsonl");
        String console = runChild(good.toString());

        assertFalse(console.contains(NOTICE), "a usable path must not be second-guessed");
        assertTrue(Files.exists(good), "the requested path should have been used");
        assertTrue(Files.readString(good).contains(UnusableLogFileChild.MARKER));
    }

    /**
     * Runs {@link UnusableLogFileChild} with {@code logFile} in the environment.
     *
     * <p>The value goes in as an environment variable rather than a {@code -D} so the child
     * exercises the same channel an operator's {@code LOG_FILE} export would, and so the parent's
     * own Logback state cannot leak into it.
     *
     * @param logFile the {@code LOG_FILE} value to hand the child.
     * @return the child's merged stdout and stderr.
     * @throws IOException if the child cannot be started or read.
     * @throws InterruptedException if the wait is interrupted.
     */
    private String runChild(final String logFile) throws IOException, InterruptedException {
        String javaBin =
                ProcessHandle.current()
                        .info()
                        .command()
                        .orElse(System.getProperty("java.home") + "/bin/java");
        ProcessBuilder pb =
                new ProcessBuilder(
                        javaBin,
                        "-cp",
                        System.getProperty("java.class.path"),
                        UnusableLogFileChild.class.getName());
        pb.directory(tempDir.toFile());
        pb.environment().remove(LoggingSetup.INSTANCE_NAME_ENV);
        pb.environment().put(LoggingSetup.LOG_FILE_ENV, logFile);
        pb.redirectErrorStream(true);

        Process child = pb.start();
        String console = new String(child.getInputStream().readAllBytes());
        assertTrue(
                child.waitFor(CHILD_TIMEOUT_SECONDS, TimeUnit.SECONDS),
                "child JVM did not exit in time");
        assertEquals(0, child.exitValue(), "child JVM failed. console output: " + console);
        return console;
    }
}
