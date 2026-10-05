package com.faforever.testharness.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

/**
 * What a signal does to {@code mock-client}'s exit code (WBS-3.1.3.2-fix, #296).
 *
 * <p>{@code run} installs a JVM shutdown hook. On {@code SIGTERM} the hook runs the coordinated
 * teardown, the lobby close drives the FSM to TERMINATED, {@code RunCommand.call} unblocks and
 * returns a code, picocli hands it back, and {@code Main} calls {@link System#exit(int)} — from a
 * thread, with the shutdown sequence already running. {@link Runtime#exit(int)} does not return in
 * that state, so the computed code is discarded and the JVM exits with the signal's own code.
 *
 * <p>That outcome is documented as intended in {@code mock-client/README.md} — 130 for {@code
 * SIGINT}, 143 for {@code SIGTERM} — and the code accepts it rather than reaching for {@link
 * Runtime#halt(int)}, which would skip {@code SubprocessRegistry}'s hook and could orphan an
 * adapter or a game. This pins the behaviour {@code Main}'s and {@code RunCommand}'s javadoc now
 * describe, so a JDK whose {@code Runtime.exit} behaved differently fails here rather than quietly
 * making those comments wrong.
 *
 * <p>A child JVM, because the subject is a real signal and a real process exit code. Windows has no
 * {@code SIGTERM} in this sense and {@code Process.destroy} there is a hard kill, so the question
 * of which code wins has no meaning; the harness targets Linux and macOS.
 */
@Timeout(60)
@DisabledOnOs(OS.WINDOWS)
final class SignalExitCodeEndToEndTest {

    /** POSIX exit code for a process terminated by {@code SIGTERM}: 128 + 15. */
    private static final int SIGTERM_EXIT_CODE = 143;

    /** Budget for the child to park, take the signal and die. */
    private static final int CHILD_TIMEOUT_SECONDS = 30;

    /**
     * The signal's code wins, and the code the main thread computed never reaches the process.
     *
     * <p>These two are one assertion, not two: 143 and "not 70" cannot disagree, so the second
     * cannot fail independently of the first. It is kept for the message it carries — naming what
     * to fix if the computed code ever does reach the process — rather than as separate evidence.
     * Neither detects a missing teardown; the output assertions in the sibling test are what cover
     * that.
     */
    @Test
    void aSigtermSupersedesTheCodeTheMainThreadComputed(@TempDir final Path dir) throws Exception {
        Path console = dir.resolve("child.out");
        Process child = startChild(console);
        try {
            awaitLine(console, SignalExitChild.READY);

            child.destroy(); // SIGTERM on POSIX

            assertTrue(
                    child.waitFor(CHILD_TIMEOUT_SECONDS, TimeUnit.SECONDS),
                    "the child should have died on the signal");
            assertEquals(
                    SIGTERM_EXIT_CODE,
                    child.exitValue(),
                    "the signal's own code is what the process reports, and what the README"
                            + " documents");
            assertNotEquals(
                    SignalExitChild.COMPUTED_EXIT_CODE,
                    child.exitValue(),
                    "the computed code must not reach the process on this path; if it now does,"
                            + " Main's javadoc and RunCommand's @return are what to fix");
        } finally {
            child.destroyForcibly();
        }
    }

    /**
     * The main thread does reach {@code System.exit} — it is not blocked short of it — and {@code
     * System.exit} never returns. Together these are why the computed value is dead code rather
     * than merely overridden, which is the part of #296 a reader of {@code Main} needs.
     */
    @Test
    void mainReachesSystemExitAndNeverComesBack(@TempDir final Path dir) throws Exception {
        Path console = dir.resolve("child.out");
        Process child = startChild(console);
        try {
            awaitLine(console, SignalExitChild.READY);

            child.destroy();

            assertTrue(child.waitFor(CHILD_TIMEOUT_SECONDS, TimeUnit.SECONDS), "child hung");
        } finally {
            child.destroyForcibly();
        }

        String output = Files.readString(console);
        assertTrue(
                output.contains(SignalExitChild.HOOK_FINISHED),
                "the hook body must have run to completion: " + output);
        assertTrue(
                output.contains(SignalExitChild.MAIN_EXITING),
                "the hook must release the main thread, which must then reach System.exit: "
                        + output);
        assertFalse(
                output.contains(SignalExitChild.EXIT_RETURNED),
                "System.exit must not return during an in-progress shutdown: " + output);
    }

    /**
     * Starts the child with this JVM's classpath, both streams redirected to {@code console}.
     *
     * <p>A file rather than a pipe, because both tests stop the child with {@code child.destroy()}.
     * On Linux and macOS that sends SIGTERM and then at once closes this side's ends of the child's
     * pipes, so nothing the child prints after the signal could be read from them. The lines {@link
     * #mainReachesSystemExitAndNeverComesBack} reads are all printed after it: {@link
     * SignalExitChild#HOOK_FINISHED} by the shutdown hook the signal starts, {@link
     * SignalExitChild#MAIN_EXITING} by the main thread the hook releases. A redirect loses nothing:
     * the child writes, the file keeps it. This is the mechanism #361 fixed in {@code
     * SubprocessManager}. The JDK's process reaper is not a cause: when it sees the child exit it
     * drains what is left in a pipe before closing it.
     *
     * @param console the file to collect the child's output in
     * @return the started process
     * @throws IOException if the child cannot be started
     */
    private static Process startChild(final Path console) throws IOException {
        ProcessBuilder pb =
                new ProcessBuilder(
                        Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                        "-cp",
                        System.getProperty("java.class.path"),
                        SignalExitChild.class.getName());
        pb.redirectErrorStream(true);
        pb.redirectOutput(console.toFile());
        return pb.start();
    }

    /**
     * Waits until {@code console} contains {@code marker}, so the signal is sent once the child is
     * actually parked rather than at an arbitrary point in its start-up.
     *
     * @param console the child's redirected output
     * @param marker the substring to wait for
     * @throws IOException if the file cannot be read
     * @throws InterruptedException if the wait is interrupted
     */
    private static void awaitLine(final Path console, final String marker)
            throws IOException, InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(CHILD_TIMEOUT_SECONDS);
        while (System.nanoTime() < deadline) {
            if (Files.exists(console) && Files.readString(console).contains(marker)) {
                return;
            }
            Thread.sleep(20);
        }
        throw new AssertionError(
                "child never printed \""
                        + marker
                        + "\": "
                        + (Files.exists(console) ? Files.readString(console) : "<no output>"));
    }
}
