package com.faforever.testharness.shared.process;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

/**
 * Verifies that a forked parent JVM's children end with it: through the JVM shutdown hook installed
 * by {@link SubprocessRegistry} when the parent receives SIGTERM, and through layer 2 when it is
 * killed, which runs no hook: {@link ParentDeathSignal}'s prefix on Linux, {@link OrphanReaper}
 * elsewhere. Slower than the surrounding unit tests (~5 s each) but kept in the same source set
 * rather than split into a separate integration-test task. The SIGTERM and setpriv cases are
 * Linux-only: they rely on POSIX signal semantics for {@code Process.destroy()}, which on Windows
 * is TerminateProcess and bypasses Java shutdown hooks entirely, and on {@code setpriv}. They run
 * under WSL on Windows dev boxes. The reaper case runs everywhere.
 */
class SubprocessManagerShutdownTest {

    private static final String PID_PREFIX = "GRANDCHILD_PID=";
    // The PID follows two JVM starts, the parent's and the grandchild's.
    private static final long PID_WAIT_MS = 30_000;
    private static final long PARENT_EXIT_TIMEOUT_S = 10;
    private static final long REAP_BUDGET_MS = 10_000;
    // The reaper is a JVM of its own, which may still be starting when the parent is killed.
    private static final long REAPER_BUDGET_MS = 30_000;
    private static final long POLL_INTERVAL_MS = 100;

    @Test
    @EnabledOnOs(OS.LINUX)
    @Timeout(value = 60, unit = TimeUnit.SECONDS)
    void parentSigtermReapsGrandchild() throws Exception {
        ProcessBuilder pb = TestSupport.forMain(HarnessChild.class);
        pb.redirectErrorStream(true);
        Process parent = pb.start();
        long grandchildPid = -1;
        try {
            grandchildPid = awaitGrandchildPid(parent);
            assertTrue(isAlive(grandchildPid), "grandchild should be alive before SIGTERM");

            parent.destroy();
            boolean parentExited = parent.waitFor(PARENT_EXIT_TIMEOUT_S, TimeUnit.SECONDS);
            assertTrue(parentExited, "parent JVM did not exit on SIGTERM within budget");

            long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(REAP_BUDGET_MS);
            while (System.nanoTime() < deadline) {
                if (!isAlive(grandchildPid)) {
                    return;
                }
                Thread.sleep(POLL_INTERVAL_MS);
            }
            fail("grandchild pid=" + grandchildPid + " was not reaped within budget");
        } finally {
            if (parent.isAlive()) {
                parent.destroyForcibly();
            }
            if (grandchildPid > 0) {
                ProcessHandle.of(grandchildPid).ifPresent(ProcessHandle::destroyForcibly);
            }
        }
    }

    /**
     * A SIGKILL of the parent runs no shutdown hook (#378), so here the kernel ends the grandchild:
     * it was launched behind {@link ParentDeathSignal}'s prefix, which asks for SIGTERM when its
     * parent dies.
     */
    @Test
    @EnabledOnOs(OS.LINUX)
    @Timeout(value = 60, unit = TimeUnit.SECONDS)
    void parentSigkillSignalsGrandchild() throws Exception {
        assertFalse(
                ParentDeathSignal.prefix().isEmpty(),
                "needs setpriv --pdeathsig on PATH (util-linux 2.33 or later)");
        killParentAndAwaitGrandchild(
                TestSupport.forMain(HarnessChild.class, "pdeathsig"), REAP_BUDGET_MS);
    }

    /**
     * Where the prefix is empty, as on macOS and Windows, {@link OrphanReaper} ends the grandchild
     * of a killed parent instead (#508): SIGKILL there, TerminateProcess on Windows, as {@code
     * taskkill /F} does. On Linux the parent's {@code PATH} is an empty directory, so its setpriv
     * probe fails and it takes the same route; it starts {@code java} by absolute path, so nothing
     * else needs {@code PATH}.
     */
    @Test
    @Timeout(value = 90, unit = TimeUnit.SECONDS)
    void parentHardKillEndsGrandchildThroughTheReaper(@TempDir Path emptyDir) throws Exception {
        ProcessBuilder pb = TestSupport.forMain(HarnessChild.class, "reaper");
        if (OS.LINUX.isCurrentOs()) {
            pb.environment().put("PATH", emptyDir.toString());
        }
        killParentAndAwaitGrandchild(pb, REAPER_BUDGET_MS);
    }

    /**
     * Starts the parent {@code pb} describes, kills it once its grandchild runs, and fails unless
     * the grandchild exits within {@code budgetMs}.
     */
    private static void killParentAndAwaitGrandchild(ProcessBuilder pb, long budgetMs)
            throws Exception {
        pb.redirectErrorStream(true);
        Process parent = pb.start();
        Optional<ProcessHandle> grandchild = Optional.empty();
        try {
            grandchild = ProcessHandle.of(awaitGrandchildPid(parent));
            assertTrue(
                    grandchild.isPresent() && grandchild.get().isAlive(),
                    "grandchild should be alive before its parent is killed");

            parent.destroyForcibly();
            assertTrue(
                    parent.waitFor(PARENT_EXIT_TIMEOUT_S, TimeUnit.SECONDS),
                    "parent JVM did not die within budget");

            long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(budgetMs);
            while (System.nanoTime() < deadline) {
                if (hasExited(grandchild.get())) {
                    return;
                }
                Thread.sleep(POLL_INTERVAL_MS);
            }
            fail(
                    "grandchild pid="
                            + grandchild.get().pid()
                            + " outlived its killed parent by "
                            + budgetMs
                            + " ms");
        } finally {
            if (parent.isAlive()) {
                parent.destroyForcibly();
            }
            // Through the handle, which will not signal a later process that reused the PID.
            grandchild.ifPresent(ProcessHandle::destroyForcibly);
        }
    }

    private static boolean isAlive(long pid) {
        Optional<ProcessHandle> h = ProcessHandle.of(pid);
        return h.isPresent() && h.get().isAlive();
    }

    /**
     * Whether an orphaned {@code process} has exited. On Linux init or a subreaper reaps it, not
     * this JVM, and {@link ProcessHandle#isAlive()} reports an exited but unreaped process (state
     * {@code Z}) as alive, so that state counts as exited. Elsewhere there is no {@code /proc} to
     * ask, and {@code isAlive()} alone decides. The handle also ignores a later process that reused
     * the PID.
     */
    private static boolean hasExited(ProcessHandle process) {
        if (!process.isAlive()) {
            return true;
        }
        if (!OS.LINUX.isCurrentOs()) {
            return false;
        }
        try {
            String stat = Files.readString(Path.of("/proc/" + process.pid() + "/stat"));
            return stat.charAt(stat.lastIndexOf(')') + 2) == 'Z';
        } catch (IOException e) {
            return true;
        }
    }

    private static long awaitGrandchildPid(Process harness) throws InterruptedException {
        AtomicLong pidRef = new AtomicLong(-1);
        CountDownLatch latch = new CountDownLatch(1);
        Queue<String> output = new ConcurrentLinkedQueue<>();
        Thread reader =
                new Thread(() -> drainPid(harness, pidRef, latch, output), "harness-stdout-drain");
        reader.setDaemon(true);
        reader.start();
        if (!latch.await(PID_WAIT_MS, TimeUnit.MILLISECONDS) || pidRef.get() < 0) {
            throw new IllegalStateException(
                    "never saw " + PID_PREFIX + " line from harness; it printed " + output);
        }
        return pidRef.get();
    }

    private static void drainPid(
            Process harness, AtomicLong pidRef, CountDownLatch latch, Queue<String> output) {
        try (BufferedReader r =
                new BufferedReader(
                        new InputStreamReader(harness.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) {
                output.add(line);
                if (line.startsWith(PID_PREFIX)) {
                    pidRef.set(Long.parseLong(line.substring(PID_PREFIX.length())));
                    latch.countDown();
                }
            }
        } catch (IOException ignored) {
            // pipe closed when parent exits — expected
        } finally {
            // A harness that dies before its PID line fails the wait now, not at its bound.
            latch.countDown();
        }
    }
}
