package com.faforever.testharness.shared.logging;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

/** Tests for {@link InstanceLabel}: capture on one thread, apply on another, restore after. */
final class InstanceLabelTest {

    @AfterEach
    void clearMdc() {
        MDC.remove(LoggingSetup.INSTANCE_MDC_KEY);
    }

    @Test
    void wrappedTaskRunsUnderTheCapturedLabelOnAnotherThread() throws Exception {
        MDC.put(LoggingSetup.INSTANCE_MDC_KEY, "B");
        InstanceLabel label = InstanceLabel.capture();
        MDC.remove(LoggingSetup.INSTANCE_MDC_KEY);

        CompletableFuture<String> seen = new CompletableFuture<>();
        Thread thread =
                new Thread(label.wrap(() -> seen.complete(MDC.get(LoggingSetup.INSTANCE_MDC_KEY))));
        thread.start();

        assertEquals("B", seen.get(5, TimeUnit.SECONDS));
    }

    @Test
    void wrappedExecutorRestoresTheWorkerThreadsLabelAfterEachTask() throws Exception {
        MDC.put(LoggingSetup.INSTANCE_MDC_KEY, "C");
        InstanceLabel label = InstanceLabel.capture();

        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            CompletableFuture<String> during =
                    CompletableFuture.supplyAsync(
                            () -> MDC.get(LoggingSetup.INSTANCE_MDC_KEY), label.wrap(pool));
            assertEquals("C", during.get(5, TimeUnit.SECONDS));

            // Same worker thread, unwrapped: the label must not have leaked onto it.
            String after =
                    CompletableFuture.supplyAsync(
                                    () -> MDC.get(LoggingSetup.INSTANCE_MDC_KEY), pool)
                            .get(5, TimeUnit.SECONDS);
            assertNull(after);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void applyRestoresAPreviousLabel() {
        MDC.put(LoggingSetup.INSTANCE_MDC_KEY, "D");
        InstanceLabel label = InstanceLabel.capture();
        MDC.put(LoggingSetup.INSTANCE_MDC_KEY, "A");

        try (InstanceLabel.Scope ignored = label.apply()) {
            assertEquals("D", MDC.get(LoggingSetup.INSTANCE_MDC_KEY));
        }

        assertEquals("A", MDC.get(LoggingSetup.INSTANCE_MDC_KEY));
    }

    @Test
    void nothingCapturedLeavesTasksAndThreadsUntouched() {
        InstanceLabel label = InstanceLabel.capture();
        Runnable task = () -> {};

        assertNull(label.value());
        assertSame(task, label.wrap(task));
        MDC.put(LoggingSetup.INSTANCE_MDC_KEY, "A");
        try (InstanceLabel.Scope ignored = label.apply()) {
            assertEquals("A", MDC.get(LoggingSetup.INSTANCE_MDC_KEY));
        }
        assertEquals("A", MDC.get(LoggingSetup.INSTANCE_MDC_KEY));
    }
}
