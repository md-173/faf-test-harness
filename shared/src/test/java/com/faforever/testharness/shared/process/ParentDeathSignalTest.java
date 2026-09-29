package com.faforever.testharness.shared.process;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.function.Executable;
import org.junit.jupiter.api.io.TempDir;

/**
 * Exercises {@link ParentDeathSignal} and the thread {@link SubprocessManager#start} starts
 * children on (#378). The SIGKILL case, which forks a parent JVM, lives in {@link
 * SubprocessManagerShutdownTest}. The class timeout also bounds the control's {@code readLine},
 * which has none of its own.
 */
@Timeout(value = 60, unit = TimeUnit.SECONDS)
class ParentDeathSignalTest {

    private static final String READY = "ready";
    private static final Duration READY_WAIT = Duration.ofSeconds(30);
    private static final long SIGNAL_WAIT_SECONDS = 10;
    private static final Duration GRACE = Duration.ofSeconds(2);

    @TempDir private Path tempDir;

    /**
     * The kernel signals a child when the thread that started it exits, and the lifecycle starts
     * its children on lobby threads that come and go. A child started through {@link
     * SubprocessManager#start} on such a thread must outlive it.
     *
     * <p>The control is the same command started by a plain {@link ProcessBuilder#start()} on a
     * thread that also ends, and it must take the signal. That proves the prefix works here, and
     * that the exit of a thread that ended earlier has been delivered by the time the managed child
     * is checked: {@link Thread#join()} can return before the kernel thread is gone, so without the
     * control the check could run ahead of a signal that was still coming. Each child is reported
     * only once it runs, so setpriv has set the signal before its thread ends.
     */
    @Test
    @EnabledOnOs(OS.LINUX)
    void aChildOutlivesTheThreadThatStartedIt() throws Throwable {
        List<String> command = new ArrayList<>(ParentDeathSignal.prefix());
        assertFalse(command.isEmpty(), "needs setpriv --pdeathsig on PATH (util-linux 2.33+)");
        command.addAll(TestSupport.testChild("hook", READY, "bye").command());
        AtomicReference<SubprocessManager> managed = new AtomicReference<>();
        AtomicReference<Process> control = new AtomicReference<>();
        try {
            onAThreadThatEnds(
                    "short-lived-launcher",
                    () -> {
                        LineWaiter ready = new LineWaiter();
                        managed.set(
                                SubprocessManager.start(
                                        new ProcessBuilder(command), "Managed", GRACE, ready));
                        ready.awaitLine(READY::equals, READY_WAIT);
                    });
            onAThreadThatEnds(
                    "short-lived-control",
                    () -> {
                        control.set(new ProcessBuilder(command).start());
                        new BufferedReader(
                                        new InputStreamReader(
                                                control.get().getInputStream(),
                                                StandardCharsets.UTF_8))
                                .readLine();
                    });

            assertTrue(
                    control.get().waitFor(SIGNAL_WAIT_SECONDS, TimeUnit.SECONDS),
                    "the control outlived the thread that started it, so no signal came");
            assertEquals(143, control.get().exitValue(), "the control should end on SIGTERM");
            assertTrue(
                    managed.get().isAlive(),
                    "the child started through SubprocessManager died with its caller's thread");
        } finally {
            if (managed.get() != null) {
                managed.get().terminate();
            }
            if (control.get() != null) {
                control.get().destroyForcibly();
            }
        }
    }

    @Test
    void aMissingSetprivIsNotUsable() {
        String missing = tempDir.resolve("setpriv").toString();

        assertFalse(ParentDeathSignal.works(List.of(missing, "--pdeathsig", "TERM", "--")));
    }

    /** util-linux before 2.33, and BusyBox, reject {@code --pdeathsig} and exit 1. */
    @Test
    @EnabledOnOs(
            value = {OS.LINUX, OS.MAC},
            disabledReason =
                    "POSIX-only: spawns a shell script or POSIX utility (CONTRIBUTING.md § 3)")
    void aSetprivWithoutTheOptionIsNotUsable() throws Exception {
        Path stub = tempDir.resolve("setpriv");
        Files.writeString(stub, "#!/bin/sh\nexit 1\n");
        Files.setPosixFilePermissions(stub, PosixFilePermissions.fromString("rwx------"));

        assertFalse(ParentDeathSignal.works(List.of(stub.toString(), "--pdeathsig", "TERM", "--")));
    }

    /**
     * Runs {@code action} on a thread of its own and returns once that thread has ended.
     *
     * @param name the thread's name
     * @param action what the thread does
     * @throws Throwable whatever {@code action} threw
     */
    private static void onAThreadThatEnds(final String name, final Executable action)
            throws Throwable {
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread thread =
                new Thread(
                        () -> {
                            try {
                                action.execute();
                            } catch (Throwable t) {
                                failure.set(t);
                            }
                        },
                        name);
        thread.start();
        thread.join();
        if (failure.get() != null) {
            throw failure.get();
        }
    }
}
