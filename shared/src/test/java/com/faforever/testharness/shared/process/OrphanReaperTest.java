package com.faforever.testharness.shared.process;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * Runs {@link OrphanReaper}'s {@link OrphanReaper#main} as the process it is in production, with
 * this JVM as the harness: closing its stdin is what the OS does when the harness JVM dies (#508).
 * Runs on every OS, so Linux CI covers the reaper although Linux itself uses setpriv. The waits
 * absorb the reaper JVM's start-up on a loaded machine.
 */
@Timeout(value = 90, unit = TimeUnit.SECONDS)
class OrphanReaperTest {

    /**
     * How far this JVM's and the reaper's views of one start time are made to differ, as two JVMs
     * on Linux do after the wall clock is stepped between their start-ups.
     */
    private static final long SKEW_MS = 5_000;

    private static final long EXIT_WAIT_SECONDS = 30;

    @Test
    void endsAWatchedChildAtEndOfFile() throws Exception {
        Process child = TestSupport.testChild("sleep", "60000").start();
        Process reaper = TestSupport.forMain(OrphanReaper.class).start();
        try {
            // Both shifted alike, so the reaper must use the header's offset to recognise the
            // child.
            sendAndClose(
                    reaper,
                    startOf(reaper) - SKEW_MS,
                    child.pid() + " " + (startOf(child) - SKEW_MS));

            assertTrue(
                    child.waitFor(EXIT_WAIT_SECONDS, TimeUnit.SECONDS),
                    "the reaper left its child running after end of file");
            assertTrue(
                    reaper.waitFor(EXIT_WAIT_SECONDS, TimeUnit.SECONDS),
                    "the reaper did not exit after end of file");
        } finally {
            child.destroyForcibly();
            reaper.destroyForcibly();
        }
    }

    /** The guard against a reused PID: a line whose start time is not the process's own. */
    @Test
    void leavesAProcessThatStartedAtAnotherTimeAlone() throws Exception {
        Process other = TestSupport.testChild("sleep", "60000").start();
        Process reaper = TestSupport.forMain(OrphanReaper.class).start();
        try {
            sendAndClose(reaper, startOf(reaper), other.pid() + " " + (startOf(other) + 1));

            assertTrue(
                    reaper.waitFor(EXIT_WAIT_SECONDS, TimeUnit.SECONDS),
                    "the reaper did not exit after end of file");
            assertTrue(other.isAlive(), "the reaper ended a process that started at another time");
        } finally {
            other.destroyForcibly();
            reaper.destroyForcibly();
        }
    }

    private static long startOf(Process process) {
        return process.toHandle().info().startInstant().orElseThrow().toEpochMilli();
    }

    /** Writes the header and one child line, then closes the pipe, as a dying harness JVM does. */
    private static void sendAndClose(Process reaper, long header, String line) throws IOException {
        try (OutputStream out = reaper.getOutputStream()) {
            out.write((header + "\n" + line + "\n").getBytes(StandardCharsets.US_ASCII));
        }
    }
}
