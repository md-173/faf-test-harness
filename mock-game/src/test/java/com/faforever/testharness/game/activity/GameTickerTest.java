package com.faforever.testharness.game.activity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link GameTicker} (WBS-3.2.3). Manual-mode tests are fully deterministic with no
 * wall-clock dependence; real-time tests assert only bounds that {@code scheduleWithFixedDelay}
 * guarantees (spacing lower bound, delivery continuing), never exact wall-clock counts.
 */
class GameTickerTest {

    /** Generous bound for awaiting scheduled ticks so CI load cannot flake the tests. */
    private static final long AWAIT_SECONDS = 5;

    // --- manual mode: deterministic, no clocks ---

    @Test
    void advanceDeliversExactCountSynchronouslyOnCallingThread() {
        AtomicInteger count = new AtomicInteger();
        AtomicReference<Thread> deliveryThread = new AtomicReference<>();
        GameTicker ticker =
                GameTicker.manual(
                        () -> {
                            count.incrementAndGet();
                            deliveryThread.set(Thread.currentThread());
                        });

        ticker.advance(5);
        assertEquals(5, count.get());
        ticker.advance(2);
        assertEquals(7, count.get());
        assertSame(Thread.currentThread(), deliveryThread.get());
    }

    @Test
    void advanceAfterStopDeliversNothing() {
        AtomicInteger count = new AtomicInteger();
        GameTicker ticker = GameTicker.manual(count::incrementAndGet);

        ticker.advance(2);
        ticker.stop();
        ticker.advance(3);
        assertEquals(2, count.get());
    }

    @Test
    void stopFromInsideCallbackHaltsRemainingTicks() {
        AtomicInteger count = new AtomicInteger();
        AtomicReference<GameTicker> self = new AtomicReference<>();
        GameTicker ticker =
                GameTicker.manual(
                        () -> {
                            if (count.incrementAndGet() == 2) {
                                self.get().stop();
                            }
                        });
        self.set(ticker);

        ticker.advance(10);
        assertEquals(2, count.get());
    }

    @Test
    void manualTickerRejectsStart() {
        GameTicker ticker = GameTicker.manual(() -> {});
        assertThrows(IllegalStateException.class, ticker::start);
    }

    // --- real-time mode ---

    @Test
    void realTimeDeliversTicksWithAtLeastFixedDelaySpacing() throws InterruptedException {
        Duration interval = Duration.ofMillis(20);
        CountDownLatch fourTicks = new CountDownLatch(4);
        GameTicker ticker = GameTicker.realTime(interval, fourTicks::countDown);

        long before = System.nanoTime();
        ticker.start();
        // Double-start must be a no-op: two in-phase schedules would deliver 4 ticks in about
        // 2 intervals, breaking the 3-interval lower bound asserted below.
        ticker.start();
        try {
            assertTrue(fourTicks.await(AWAIT_SECONDS, TimeUnit.SECONDS), "expected 4 ticks");
        } finally {
            ticker.stop();
        }
        long elapsedMillis = (System.nanoTime() - before) / 1_000_000;
        // First tick fires one interval after start, so 4 fixed-delay ticks take at least
        // 4 intervals; asserting 3 leaves a full interval of slack for timer grain.
        assertTrue(
                elapsedMillis >= 3 * interval.toMillis(),
                "4 fixed-delay ticks arrived in " + elapsedMillis + "ms");
    }

    @Test
    void stopHaltsScheduledDelivery() throws InterruptedException {
        AtomicInteger count = new AtomicInteger();
        CountDownLatch firstTick = new CountDownLatch(1);
        GameTicker ticker =
                GameTicker.realTime(
                        Duration.ofMillis(10),
                        () -> {
                            count.incrementAndGet();
                            firstTick.countDown();
                        });

        ticker.start();
        assertTrue(firstTick.await(AWAIT_SECONDS, TimeUnit.SECONDS), "expected a first tick");
        ticker.stop();
        // Let any in-flight tick finish before snapshotting, then hold a multi-interval window
        // open; the count must not move again.
        Thread.sleep(50);
        int afterStop = count.get();
        Thread.sleep(80);
        assertEquals(afterStop, count.get(), "ticks delivered after stop()");
    }

    @Test
    void stopIsSafeBeforeStartAndTwiceAndMakesStartNoOp() throws InterruptedException {
        AtomicInteger count = new AtomicInteger();
        GameTicker ticker = GameTicker.realTime(Duration.ofMillis(10), count::incrementAndGet);

        ticker.stop();
        ticker.stop();
        ticker.start();
        Thread.sleep(50);
        assertEquals(0, count.get(), "start() after stop() must not schedule");
    }

    @Test
    void callbackExceptionDoesNotCancelSchedule() throws InterruptedException {
        CountDownLatch twoTicks = new CountDownLatch(2);
        GameTicker ticker =
                GameTicker.realTime(
                        Duration.ofMillis(10),
                        () -> {
                            twoTicks.countDown();
                            throw new IllegalStateException("boom");
                        });

        ticker.start();
        try {
            // A ScheduledExecutorService cancels a periodic task whose body throws; a second tick
            // proves the ticker's catch kept the schedule alive.
            assertTrue(
                    twoTicks.await(AWAIT_SECONDS, TimeUnit.SECONDS),
                    "schedule died after callback threw");
        } finally {
            ticker.stop();
        }
    }

    @Test
    void realTimeTickerRejectsAdvance() {
        GameTicker ticker = GameTicker.realTime(Duration.ofMillis(10), () -> {});
        assertThrows(IllegalStateException.class, () -> ticker.advance(1));
    }

    @Test
    void realTimeRejectsNonPositiveInterval() {
        assertThrows(
                IllegalArgumentException.class, () -> GameTicker.realTime(Duration.ZERO, () -> {}));
        assertThrows(
                IllegalArgumentException.class,
                () -> GameTicker.realTime(Duration.ofMillis(-5), () -> {}));
    }
}
