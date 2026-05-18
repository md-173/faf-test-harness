package com.faforever.testharness.shared.process;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;

/**
 * Verifies that the JVM shutdown hook installed by {@link SubprocessRegistry} reaps active children
 * when the parent JVM receives SIGTERM. Forks a JVM and polls {@code /proc} for the grandchild;
 * slower than the surrounding unit tests (~5 s) but kept in the same source set rather than split
 * into a separate integration-test task. Linux-only — relies on POSIX signal semantics for {@code
 * Process.destroy()}. On Windows {@code destroy()} is TerminateProcess, which bypasses Java
 * shutdown hooks entirely. Runs under WSL on Windows dev boxes.
 */
@EnabledOnOs(OS.LINUX)
class SubprocessManagerShutdownTest {

    private static final String PID_PREFIX = "GRANDCHILD_PID=";
    private static final long PID_WAIT_MS = 10_000;
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

            long deadline = System.currentTimeMillis() + REAP_BUDGET_MS;
            while (System.currentTimeMillis() < deadline) {
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

    private static boolean isAlive(long pid) {
        Optional<ProcessHandle> h = ProcessHandle.of(pid);
        return h.isPresent() && h.get().isAlive();
    }

    private static long awaitGrandchildPid(Process harness) throws InterruptedException {
        AtomicLong pidRef = new AtomicLong(-1);
        CountDownLatch latch = new CountDownLatch(1);
        Thread reader = new Thread(() -> drainPid(harness, pidRef, latch), "harness-stdout-drain");
        reader.setDaemon(true);
        reader.start();
        if (!latch.await(PID_WAIT_MS, TimeUnit.MILLISECONDS)) {
            throw new IllegalStateException("never saw " + PID_PREFIX + " line from harness");
        }
        return pidRef.get();
    }

    private static void drainPid(Process harness, AtomicLong pidRef, CountDownLatch latch) {
        try (BufferedReader r =
                new BufferedReader(
                        new InputStreamReader(harness.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) {
                if (line.startsWith(PID_PREFIX)) {
                    pidRef.set(Long.parseLong(line.substring(PID_PREFIX.length())));
                    latch.countDown();
                }
            }
        } catch (IOException ignored) {
            // pipe closed when parent exits — expected
        }
    }
}
