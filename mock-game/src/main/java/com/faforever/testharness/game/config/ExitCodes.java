package com.faforever.testharness.game.config;

/**
 * Process exit codes returned by the mock game (WBS-3.2.1.2).
 *
 * <p>The values are stable and documented in this one place so the Mock Client can rely on them:
 * the game exit code is the only failure signal that reaches the client (source-verified: the ICE
 * adapter waits indefinitely for a game that never connects, with no socket timeout), so the
 * client's crash detection (R41) and the lifecycle test (WBS-3.1.2.7) assert against these.
 *
 * <p>Mirrors the mock client's {@code ExitCodes} convention: {@code 0} is reserved for a clean
 * exit, and a usage error is distinct from a runtime error. {@link #USAGE} matches picocli's
 * default ({@link picocli.CommandLine.ExitCode#USAGE}) so a bad-argument failure needs no remap.
 */
public final class ExitCodes {

    /** Clean exit: valid launch arguments, normal shutdown. */
    public static final int OK = 0;

    /** Bad invocation: a missing, unknown, malformed, or out-of-range launch argument. */
    public static final int USAGE = 2;

    /**
     * Runtime failure after a valid start — e.g. the boot sequence could not reach the adapter.
     * Owned by the bootstrap (WBS-3.2.5.1); defined here so the code scheme lives in one place.
     */
    public static final int RUNTIME = 70;

    private ExitCodes() {}
}
