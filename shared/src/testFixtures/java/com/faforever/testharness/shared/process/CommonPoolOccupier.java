package com.faforever.testharness.shared.process;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.TimeUnit;

/**
 * Test support: holds the common pool busy, for tests proving that a wait does not depend on it.
 * Kept in one place for every module's tests (#461 review), since its pool behaviour is subtle
 * enough to have needed a fix once.
 */
public final class CommonPoolOccupier {

    /** How long the pool gets to start the blockers. */
    private static final long OCCUPY_SECONDS = 10;

    private CommonPoolOccupier() {}

    /**
     * Blocks as many common-pool threads as the pool runs tasks on at once, its parallelism, until
     * {@code release} opens. Spare threads an earlier test's blocking {@code get()} left behind
     * stay idle while that many run, and the pool does not wake them for queued work, the work this
     * holds back included. One blocker per spare is queued too, for a spare still busy with earlier
     * work to take once it is free.
     *
     * @param release opened by the caller to let the pool go
     * @throws InterruptedException if interrupted while the pool fills
     * @throws AssertionError if the pool does not start that many blockers within ten seconds
     */
    public static void occupy(final CountDownLatch release) throws InterruptedException {
        ForkJoinPool pool = ForkJoinPool.commonPool();
        int parallelism = ForkJoinPool.getCommonPoolParallelism();
        int threads = Math.max(parallelism, pool.getPoolSize());
        CountDownLatch occupied = new CountDownLatch(parallelism);
        for (int i = 0; i < threads; i++) {
            pool.execute(
                    () -> {
                        occupied.countDown();
                        try {
                            release.await();
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                        }
                    });
        }
        if (!occupied.await(OCCUPY_SECONDS, TimeUnit.SECONDS)) {
            throw new AssertionError(
                    "could not occupy the common pool's " + parallelism + " threads");
        }
    }
}
