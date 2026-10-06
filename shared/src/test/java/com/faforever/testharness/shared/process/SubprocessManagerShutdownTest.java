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

/**
 * Verifies that a forked parent JVM's children end with it: through the JVM shutdown hook installed
 * by {@link SubprocessRegistry} when the parent receives SIGTERM, and through {@link
 * ParentDeathSignal}'s prefix when it receives SIGKILL, which runs no hook. Polls {@code /proc} for
 * the grandchild; slower than the surrounding unit tests (~5 s each) but kept in the same source
 * set rather than split into a separate integration-test task. Linux-only: relies on POSIX signal
 * semantics for {@code Process.destroy()}, and on {@code setpriv} for the SIGKILL case. On Windows
 * {@code destroy()} is TerminateProcess, which bypasses Java shutdown hooks entirely. Runs under
 * WSL on Windows dev boxes.
 */
@EnabledOnOs(OS.LINUX)
class SubprocessManagerShutdownTest {

    private static final String PID_PREFIX = "GRANDCHILD_PID=";
    // The PID follows two JVM starts, the parent's and the grandchild's.
    private static final long PID_WAIT_MS = 30_000;
    private static final long PARENT_EXIT_TIMEOUT_S = 10;
    private static final long REAP_BUDGET_MS = 10_000;
    private static final long POLL_INTERVAL_MS = 100;

    @Test
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
    @Timeout(value = 60, unit = TimeUnit.SECONDS)
    void parentSigkillSignalsGrandchild() throws Exception {
        assertFalse(
                ParentDeathSignal.prefix().isEmpty(),
                "needs setpriv --pdeathsig on PATH (util-linux 2.33 or later)");
        ProcessBuilder pb = TestSupport.forMain(HarnessChild.class, "pdeathsig");
        pb.redirectErrorStream(true);
        Process parent = pb.start();
        Optional<ProcessHandle> grandchild = Optional.empty();
        try {
            grandchild = ProcessHandle.of(awaitGrandchildPid(parent));
            assertTrue(
                    grandchild.isPresent() && grandchild.get().isAlive(),
                    "grandchild should be alive before SIGKILL");

            parent.destroyForcibly();
            assertTrue(
                    parent.waitFor(PARENT_EXIT_TIMEOUT_S, TimeUnit.SECONDS),
                    "parent JVM did not die on SIGKILL within budget");

            long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(REAP_BUDGET_MS);
            while (System.nanoTime() < deadline) {
                if (hasExited(grandchild.get())) {
                    return;
                }
                Thread.sleep(POLL_INTERVAL_MS);
            }
            fail(
                    "grandchild pid="
                            + grandchild.get().pid()
                            + " outlived its SIGKILLed parent by "
                            + REAP_BUDGET_MS
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
     * Whether an orphaned {@code process} has exited. Init or a subreaper reaps it, not this JVM,
     * and {@link ProcessHandle#isAlive()} reports an exited but unreaped process (state {@code Z})
     * as alive, so that state counts as exited. The handle also ignores a later process that reused
     * the PID.
     */
    private static boolean hasExited(ProcessHandle process) {
        if (!process.isAlive()) {
            return true;
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
