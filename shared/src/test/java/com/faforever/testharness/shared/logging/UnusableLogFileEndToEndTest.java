package com.faforever.testharness.shared.logging;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
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
 * <p>A path Logback can parse but not open, such as a directory or a new file in a directory that
 * cannot be written, lost the JSONL without a word, and gets the same treatment since #524. Every
 * case counts the notice lines exactly: each problem prints one, once, however often {@link
 * UnusableLogFileChild} configures logging.
 *
 * <p>Child JVMs, because the property is read once when Logback initialises and no in-process test
 * can un-initialise it. Follows {@link InstanceLabelEndToEndTest}.
 */
final class UnusableLogFileEndToEndTest {

    /** How long a child gets to configure logging, emit its record and flush. */
    private static final int CHILD_TIMEOUT_SECONDS = 30;

    /** Substring of the single-line notice {@code LoggingSetup} prints for a bad path. */
    private static final String NOTICE = "cannot be used by the log rotator";

    /** How every notice about the log file path starts. */
    private static final String NOTICE_PREFIX = "log file path ";

    /** Substring of the notice for a path Logback cannot open, swapped for the default. */
    private static final String UNWRITABLE = "cannot be written and was ignored";

    /** Substring of the notice for a default path Logback cannot open either. */
    private static final String NO_JSONL = "no JSONL log is being written";

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
        assertEquals(1, notices(console).size(), "one notice, printed once: " + console);
        assertTrue(
                console.contains(badPath), "the notice must name the path it rejected: " + console);

        Path fallback = tempDir.resolve("logs").resolve("mockclient.jsonl");
        assertTrue(
                Files.exists(fallback), "no records were written anywhere; expected " + fallback);
        assertTrue(
                Files.readString(fallback).contains(UnusableLogFileChild.MARKER),
                "the child's own record must still reach the log");
    }

    /**
     * A newline in the rejected path is escaped, so it cannot forge a {@code Usage:} line (#307).
     */
    @Test
    void aNewlineInAnUnusableLogFileStaysOnTheNoticeLine() throws Exception {
        String console = runChild("a[b\nUsage: FORGED");

        assertTrue(console.contains("a[b\\nUsage: FORGED"), "path not escaped: " + console);
        assertTrue(
                console.lines().noneMatch(line -> line.startsWith("Usage: FORGED")),
                "a forged Usage: line reached stderr: " + console);
        assertEquals(1, notices(console).size(), "one notice, printed once: " + console);
    }

    /** The control: a usable path is untouched, and produces no notice. */
    @Test
    void aUsableLogFileIsLeftAlone() throws Exception {
        Path good = tempDir.resolve("good.jsonl");
        String console = runChild(good.toString());

        assertEquals(List.of(), notices(console), "a usable path must not be second-guessed");
        assertTrue(Files.exists(good), "the requested path should have been used");
        assertTrue(Files.readString(good).contains(UnusableLogFileChild.MARKER));
    }

    /** A directory given as the log file costs the operator their filename, not the JSONL. */
    @Test
    void aDirectoryGivenAsTheLogFileDegradesToTheDefault() throws Exception {
        Path directory = Files.createDirectory(tempDir.resolve("a-directory"));

        assertDegradedToTheDefault(directory.toString(), runChild(directory.toString()));
    }

    /**
     * The same for a new file in a directory that cannot be written. Not on Windows, where a
     * read-only directory still takes new files, and skipped for a user who writes through one, as
     * root does.
     */
    @Test
    @DisabledOnOs(OS.WINDOWS)
    void aLogFileInAReadOnlyDirectoryDegradesToTheDefault() throws Exception {
        Path readOnly = Files.createDirectory(tempDir.resolve("read-only"));
        Files.setPosixFilePermissions(readOnly, PosixFilePermissions.fromString("r-xr-xr-x"));
        assumeFalse(Files.isWritable(readOnly), "this user can write to a read-only directory");
        String path = readOnly.resolve("run.jsonl").toString();

        assertDegradedToTheDefault(path, runChild(path));
    }

    /**
     * With no log path and nowhere to write the default, the operator is told that no JSONL is
     * being written, and the record still reaches the console. A regular file named {@code logs}
     * stands in for a working directory that cannot be written: the default's directory then cannot
     * be created, on every OS and for every user.
     */
    @Test
    void anUnwritableDefaultSaysNoJsonlIsBeingWritten() throws Exception {
        Files.createFile(tempDir.resolve("logs"));

        String console = runChild(null);

        List<String> notices = notices(console);
        assertEquals(1, notices.size(), "one notice, printed once: " + console);
        assertTrue(
                notices.get(0).contains(NO_JSONL), "it must say no JSONL is written: " + console);
        assertTrue(
                console.contains(UnusableLogFileChild.MARKER),
                "the record must still reach the console: " + console);
    }

    /** When the default cannot be written either, a second line says so. */
    @Test
    void whenTheDefaultCannotBeWrittenEitherASecondLineSaysSo() throws Exception {
        Files.createFile(tempDir.resolve("logs"));
        Path directory = Files.createDirectory(tempDir.resolve("a-directory"));

        String console = runChild(directory.toString());

        List<String> notices = notices(console);
        assertEquals(2, notices.size(), "one notice per path: " + console);
        assertTrue(
                notices.get(0).contains(UNWRITABLE)
                        && notices.get(0).contains(directory.toString()),
                "the first notice must name the ignored path: " + console);
        assertTrue(
                notices.get(1).contains(NO_JSONL), "it must say no JSONL is written: " + console);
        assertTrue(
                console.contains(UnusableLogFileChild.MARKER),
                "the record must still reach the console: " + console);
    }

    /**
     * Asserts the one notice naming {@code path}, and the child's record in the default log.
     *
     * @param path the {@code LOG_FILE} value the child was given.
     * @param console the child's merged stdout and stderr.
     * @throws IOException if the default log cannot be read.
     */
    private void assertDegradedToTheDefault(final String path, final String console)
            throws IOException {
        List<String> notices = notices(console);
        assertEquals(1, notices.size(), "one notice, printed once: " + console);
        assertTrue(
                notices.get(0).contains(UNWRITABLE) && notices.get(0).contains(path),
                "the notice must name the path it ignored: " + console);

        Path fallback = tempDir.resolve("logs").resolve("mockclient.jsonl");
        assertTrue(
                Files.exists(fallback), "no records were written anywhere; expected " + fallback);
        assertTrue(
                Files.readString(fallback).contains(UnusableLogFileChild.MARKER),
                "the child's own record must still reach the log");
    }

    /**
     * The notice lines in a child's output.
     *
     * @param console the child's merged stdout and stderr.
     * @return every line that is a notice about the log file path.
     */
    private static List<String> notices(final String console) {
        return console.lines().filter(line -> line.startsWith(NOTICE_PREFIX)).toList();
    }

    /**
     * Runs {@link UnusableLogFileChild} with {@code logFile} in the environment.
     *
     * <p>The value goes in as an environment variable rather than a {@code -D} so the child
     * exercises the same channel an operator's {@code LOG_FILE} export would, and so the parent's
     * own Logback state cannot leak into it.
     *
     * @param logFile the {@code LOG_FILE} value to hand the child, or {@code null} for none.
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
        if (logFile == null) {
            pb.environment().remove(LoggingSetup.LOG_FILE_ENV);
        } else {
            pb.environment().put(LoggingSetup.LOG_FILE_ENV, logFile);
        }
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
