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
 *
 * <p>The complete scheme, owned by the bootstrap (WBS-3.2.5.1), which maps {@code
 * MockGameLifecycle.ExitStatus} onto it once the FSM reaches ENDED:
 *
 * <table border="1">
 *   <caption>mock-game exit codes</caption>
 *   <tr><th>Code</th><th>Meaning</th><th>Source</th></tr>
 *   <tr><td>{@link #OK}</td><td>match played out and ended normally</td>
 *       <td>{@code ExitStatus.OK}</td></tr>
 *   <tr><td>{@link #USAGE}</td><td>bad launch argument; exits before any connect attempt</td>
 *       <td>{@code MockGameCli.parseOrReport}</td></tr>
 *   <tr><td>{@link #ADAPTER_LOST}</td><td>GPGNet connection went down mid-session</td>
 *       <td>{@code ExitStatus.SERVER_CONNECTION_LOST}</td></tr>
 *   <tr><td>{@link #RUNTIME}</td><td>never reached the adapter, or any other failed run</td>
 *       <td>{@code ExitStatus.SERVER_NOT_CONNECTED}, {@code ExitStatus.FAILED}</td></tr>
 *   <tr><td>{@code 143}</td><td>{@code SIGTERM} — the JVM's own signal default, not set here</td>
 *       <td>client-initiated teardown</td></tr>
 * </table>
 */
public final class ExitCodes {

    /** Clean exit: valid launch arguments, normal shutdown. */
    public static final int OK = 0;

    /** Bad invocation: a missing, unknown, malformed, or out-of-range launch argument. */
    public static final int USAGE = 2;

    /**
     * The GPGNet connection went down mid-session, so the game ended without playing out its match.
     * Distinct from {@link #OK} because the session did not complete, and distinct from {@link
     * #RUNTIME} because the game booted and connected fine. {@code 69} is sysexits' {@code
     * EX_UNAVAILABLE} ("a service is unavailable"), the closest standard fit.
     *
     * <p>Deliberately worded as the connection going down rather than the adapter dropping it. The
     * transport cannot tell those apart: {@code GpgNetConnection}'s read loop labels every {@code
     * IOException} out of the codec {@code REMOTE_CLOSE}, so a truncated or malformed frame, or a
     * bug in our own decoder, arrives here identically to a peer that went away. The log line
     * carries the underlying error; this code only says the session lost its adapter link.
     */
    public static final int ADAPTER_LOST = 69;

    /**
     * Runtime failure after a valid start — the boot sequence could not reach the adapter within
     * the connect window, or the run failed for a reason with no more specific code. {@code 70} is
     * sysexits' {@code EX_SOFTWARE}, and matches the mock client's {@code ExitCodes.RUNTIME}.
     */
    public static final int RUNTIME = 70;

    private ExitCodes() {}
}
