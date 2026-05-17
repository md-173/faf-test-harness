package com.faforever.testharness.shared.process;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Tracks active {@link SubprocessManager} instances and terminates them on JVM shutdown.
 *
 * <p>This is layer 1 of spec §6.3 — it handles polite JVM exits ({@code System.exit}, SIGTERM,
 * SIGINT, last-non-daemon-thread). SIGKILL / OOM-kill / {@code Runtime.halt} do not run shutdown
 * hooks and are covered by the orphan-prevention layers (setpriv / setsid / tini) owned by the
 * launcher tickets.
 */
final class SubprocessRegistry {

    /** Diagnostic logger for shutdown-hook activity. */
    private static final Logger LOG = LoggerFactory.getLogger(SubprocessRegistry.class);

    /** Active managers; strong references so losing the launcher handle doesn't leak children. */
    private static final Set<SubprocessManager> ACTIVE = ConcurrentHashMap.newKeySet();

    /** True once the JVM shutdown hook has been installed. */
    private static final AtomicBoolean HOOK_INSTALLED = new AtomicBoolean();

    private SubprocessRegistry() {}

    /**
     * Adds {@code manager} to the active set and installs the JVM shutdown hook on first call.
     *
     * @param manager the manager to track
     * @throws IllegalStateException if the JVM is already shutting down
     */
    static void register(final SubprocessManager manager) {
        installHookOnce();
        ACTIVE.add(manager);
    }

    /**
     * Removes {@code manager} from the active set. Safe to call on a manager that was never
     * registered.
     *
     * @param manager the manager to drop
     */
    static void deregister(final SubprocessManager manager) {
        ACTIVE.remove(manager);
    }

    /**
     * Visible for testing — returns whether {@code manager} is currently tracked.
     *
     * @param manager the manager to look up
     * @return {@code true} if the manager is currently in the active set
     */
    static boolean contains(final SubprocessManager manager) {
        return ACTIVE.contains(manager);
    }

    private static void installHookOnce() {
        if (HOOK_INSTALLED.compareAndSet(false, true)) {
            try {
                Runtime.getRuntime()
                        .addShutdownHook(
                                new Thread(
                                        SubprocessRegistry::shutdownAll,
                                        "subprocess-shutdown-hook"));
            } catch (IllegalStateException e) {
                HOOK_INSTALLED.set(false);
                throw e;
            }
        }
    }

    private static void shutdownAll() {
        List<Thread> threads = new ArrayList<>(ACTIVE.size());
        for (SubprocessManager m : ACTIVE) {
            Thread t =
                    new Thread(
                            () -> {
                                try {
                                    m.terminate();
                                } catch (RuntimeException e) {
                                    LOG.warn("Error terminating child during JVM shutdown", e);
                                }
                            },
                            "subprocess-shutdown-" + m.pid());
            t.start();
            threads.add(t);
        }
        for (Thread t : threads) {
            try {
                t.join();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }
}
