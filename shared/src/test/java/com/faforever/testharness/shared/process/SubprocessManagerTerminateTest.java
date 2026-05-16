package com.faforever.testharness.shared.process;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.OptionalInt;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

/**
 * Exercises {@link SubprocessManager#terminate()} and {@link
 * SubprocessManager#terminate(Duration)}.
 */
class SubprocessManagerTerminateTest {

    private static final Duration DEFAULT_GRACE = Duration.ofSeconds(2);
    private static final Duration SHORT_GRACE = Duration.ofMillis(500);
    private static final String TAG = "TestChild";
    private static final int AWAIT_SECONDS = 10;
    private static final long TERMINATE_BUDGET_MS = 3_000;

    @Test
    void terminateKillsRunningProcessWithinBudget() throws Exception {
        SubprocessManager m =
                SubprocessManager.start(TestSupport.testChild("sleep", "60000"), TAG, SHORT_GRACE);
        assertTrue(m.isAlive());
        long start = System.nanoTime();
        m.terminate();
        m.onExit().get(AWAIT_SECONDS, TimeUnit.SECONDS);
        long elapsedMs = (System.nanoTime() - start) / 1_000_000L;
        assertFalse(m.isAlive());
        assertTrue(elapsedMs < TERMINATE_BUDGET_MS, "terminate took too long: " + elapsedMs + "ms");
    }

    @Test
    void terminateAfterNaturalExitIsNoop() throws Exception {
        SubprocessManager m =
                SubprocessManager.start(TestSupport.testChild("exit", "0"), TAG, DEFAULT_GRACE);
        m.onExit().get(AWAIT_SECONDS, TimeUnit.SECONDS);
        m.terminate();
        m.terminate(Duration.ofMillis(100));
        assertFalse(m.isAlive());
        assertEquals(OptionalInt.of(0), m.exitCode());
    }
}
