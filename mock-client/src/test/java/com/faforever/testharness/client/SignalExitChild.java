package com.faforever.testharness.client;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Child JVM reproducing {@code mock-client}'s signal-terminated shape, for {@link
 * SignalExitCodeEndToEndTest} (WBS-3.1.3.2-fix, #296).
 *
 * <p>The shape is what matters, and it is exactly {@code run}'s: a shutdown hook that performs the
 * teardown and, by doing so, releases a main thread parked waiting for the FSM to terminate. The
 * main thread then computes an exit code and calls {@link System#exit(int)} — from a thread, with
 * the JVM's shutdown sequence already in progress.
 *
 * <p>Deliberately not the real {@code Main}: driving {@code run} to the same point needs a live
 * lobby, a real OAuth exchange and two subprocess binaries, none of which can run in CI. Everything
 * this test is about happens after all of that, in the interaction between the hook and {@code
 * System.exit}, so the stand-in reproduces that interaction and nothing else.
 *
 * <p>Prints one line per step so the test can assert what did and did not run, and flushes each,
 * since the JVM is about to die in a way that skips ordinary stream cleanup.
 */
public final class SignalExitChild {

    /** The code the main thread computes and tries to exit with. Never reaches the process. */
    public static final int COMPUTED_EXIT_CODE = 70;

    /** Printed once the hook body has finished; its absence is the point of the test. */
    public static final String HOOK_FINISHED = "hook: finished";

    /** Printed immediately before {@code System.exit}, so its presence proves main got there. */
    public static final String MAIN_EXITING = "main: about to exit";

    /** Printed if {@code System.exit} ever returns. It does not. */
    public static final String EXIT_RETURNED = "main: System.exit returned";

    /** Printed when the child is parked and ready to be signalled. */
    public static final String READY = "child: ready";

    private SignalExitChild() {}

    /**
     * Parks until signalled, then mimics {@code RunCommand}'s unblock-and-return.
     *
     * @param args ignored
     * @throws InterruptedException if the park is interrupted rather than signalled
     */
    public static void main(final String[] args) throws InterruptedException {
        CountDownLatch terminated = new CountDownLatch(1);

        Runtime.getRuntime()
                .addShutdownHook(
                        new Thread(
                                () -> {
                                    // Stands in for the coordinated teardown whose lobby close
                                    // drives the FSM to TERMINATED and releases the main thread.
                                    terminated.countDown();
                                    say(HOOK_FINISHED);
                                },
                                "mc-shutdown"));

        say(READY);
        // Stands in for stateReached(TERMINATED).get(). Bounded here only so a child that is never
        // signalled cannot outlive the test.
        terminated.await(60, TimeUnit.SECONDS);

        say(MAIN_EXITING);
        System.exit(COMPUTED_EXIT_CODE);
        say(EXIT_RETURNED);
    }

    private static void say(final String line) {
        System.out.println(line);
        System.out.flush();
    }
}
