package com.faforever.testharness.shared.process;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/** Exercises {@link LineWaiter} directly, without a subprocess. */
@Timeout(value = 10, unit = TimeUnit.SECONDS)
class LineWaiterTest {

    private static final Duration SHORT_TIMEOUT = Duration.ofMillis(200);
    private static final Duration LONG_TIMEOUT = Duration.ofSeconds(5);

    @Test
    void returnsTheFirstLineMatchingThePredicate() throws Exception {
        LineWaiter waiter = new LineWaiter();
        waiter.accept("connecting...");
        waiter.accept("READY: adapter up");
        waiter.accept("more noise");

        String matched = waiter.awaitLine(line -> line.startsWith("READY"), LONG_TIMEOUT);

        assertEquals("READY: adapter up", matched);
    }

    @Test
    void nonMatchingLinesAreConsumedAndNotReplayed() throws Exception {
        LineWaiter waiter = new LineWaiter();
        waiter.accept("noise-1");
        waiter.accept("noise-2");
        waiter.accept("marker");

        waiter.awaitLine(line -> line.equals("marker"), LONG_TIMEOUT);

        // The lines before "marker" were drained looking for the first match, so a later wait on
        // one of them (rather than a subsequent, not-yet-seen line) must time out.
        assertThrows(
                TimeoutException.class,
                () -> waiter.awaitLine(line -> line.equals("noise-1"), SHORT_TIMEOUT));
    }

    @Test
    void timesOutWhenNoLineEverMatches() {
        LineWaiter waiter = new LineWaiter();
        waiter.accept("unrelated");

        long start = System.nanoTime();
        assertThrows(
                TimeoutException.class,
                () -> waiter.awaitLine(line -> line.equals("never"), SHORT_TIMEOUT));
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;

        assertTrue(elapsedMs >= SHORT_TIMEOUT.toMillis(), "returned before the timeout elapsed");
    }

    @Test
    void matchesALineThatArrivesWhileAlreadyWaiting() throws Exception {
        LineWaiter waiter = new LineWaiter();
        Thread producer =
                new Thread(
                        () -> {
                            try {
                                Thread.sleep(50);
                            } catch (InterruptedException e) {
                                Thread.currentThread().interrupt();
                                return;
                            }
                            waiter.accept("late marker");
                        });
        producer.start();
        try {
            String matched = waiter.awaitLine(line -> line.equals("late marker"), LONG_TIMEOUT);
            assertEquals("late marker", matched);
        } finally {
            producer.join();
        }
    }
}
