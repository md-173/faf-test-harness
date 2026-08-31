package com.faforever.testharness.game.config;

import java.io.PrintStream;
import java.util.HashMap;
import java.util.Map;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.ParameterException;

/**
 * Strict parser for the mock game's launch arguments (WBS-3.2.1.1), producing a {@link
 * MockGameConfig}. The caller is a machine — the Mock Client's {@code MockGameLauncher}
 * (WBS-3.1.2.3) — so the contract is strict: no environment-variable or config-file fallbacks, and
 * unknown arguments fail the parse. Every argument that states a <em>session fact</em> (the ports,
 * the identity, the game uid) is required and never defaulted, because a guessed port or player id
 * produces a session that looks alive and is wrong.
 *
 * <p><b>{@code --launch-delay-seconds} is the one defaulted argument</b> (WBS-4.3.1), and
 * deliberately so: it is a behavioural knob, not a session fact, and its default is the behaviour
 * mock-game had before the flag existed. Nothing silently depends on that default in an
 * orchestrated run — {@code MockGameLauncher} always emits the flag explicitly, from the client's
 * own config — so it only applies to a hand-run binary, and {@code Main} logs the effective policy
 * at startup so even a hand-run says which one it took.
 *
 * <p>Accepted argument list (subprocess-orchestration-spec.md §2.8). Extend both ends together if
 * orchestration ever adds the remaining {@code game_launch}-derived flags.
 *
 * <pre>{@code
 * --gpgnet-port <port> --lobby-port <port> --player-id <id> --player-login <login>
 * --game-uid <uid> [--launch-delay-seconds <seconds>]
 * }</pre>
 *
 * <p>Failures throw picocli's {@link ParameterException}. {@link #parseOrReport(String[],
 * PrintStream)} (WBS-3.2.1.2) catches it, prints the error and usage text to stderr, and returns a
 * stable exit code from {@link ExitCodes}.
 */
@Command(name = "mock-game")
public final class MockGameCli {

    /** Highest valid TCP/UDP port number. */
    private static final int MAX_PORT = 65535;

    /**
     * Default for {@code --launch-delay-seconds}: the 5 s {@code Main} used to hardcode. Long
     * enough that a peer's {@code ConnectToPeer} lands first in a single-peer run, short enough not
     * to pad every harness run.
     */
    private static final String DEFAULT_LAUNCH_DELAY_SECONDS = "5";

    /** TCP port of the adapter's GPGNet server; validated to a real port range. */
    @Option(names = "--gpgnet-port", required = true, description = "adapter GPGNet TCP port")
    private int gpgNetPort;

    /** UDP lobby port shared with the adapter; validated to a real port range. */
    @Option(names = "--lobby-port", required = true, description = "lobby UDP port")
    private int lobbyPort;

    /** FAF player id of the owning session; must be positive. */
    @Option(names = "--player-id", required = true, description = "FAF player id")
    private int playerId;

    /** FAF player login of the owning session; must not be blank. */
    @Option(names = "--player-login", required = true, description = "FAF player login")
    private String playerLogin;

    /** Id of the game being played; zero means no orchestrated session. */
    @Option(
            names = "--game-uid",
            required = true,
            description = "lobby game uid, 0 when launched without a session")
    private int gameUid;

    /**
     * A list of game options that can be set and, if this game is a host, will be sent to the
     * GpgNet server.
     */
    @Option(
            names = "--game-option",
            arity = "0..*",
            description = "Additional game options for the host to send")
    private Map<String, String> gameOptions = new HashMap<>();

    /**
     * How long the game sits in the lobby before starting the match on its own; negative disables
     * auto-launch entirely. The default is the behaviour that was hardcoded in {@code Main} before
     * this flag existed.
     */
    @Option(
            names = "--launch-delay-seconds",
            defaultValue = DEFAULT_LAUNCH_DELAY_SECONDS,
            description =
                    "Seconds to sit in the lobby before launching the match on our own "
                            + "(default: ${DEFAULT-VALUE}). Negative never auto-launches, which is "
                            + "what a multi-peer session needs: a host that launches makes its own "
                            + "game unjoinable.")
    private int launchDelaySeconds;

    /** Instantiated only by {@link #parse(String[])}. */
    private MockGameCli() {}

    /**
     * Parses and validates the launch argv into an immutable config.
     *
     * @param args the raw argv as passed to {@code main}
     * @return the fully populated config; never partial
     * @throws ParameterException if any argument is missing, unknown, malformed, or out of range
     */
    public static MockGameConfig parse(final String[] args) {
        MockGameCli cli = new MockGameCli();
        CommandLine commandLine = new CommandLine(cli);
        commandLine.parseArgs(args);
        cli.validate(commandLine);
        return new MockGameConfig(
                cli.gpgNetPort,
                cli.lobbyPort,
                cli.playerId,
                cli.playerLogin,
                cli.gameUid,
                cli.gameOptions,
                cli.launchDelaySeconds);
    }

    /**
     * The outcome of {@link #parseOrReport(String[], PrintStream)}: an exit code, plus the parsed
     * config on success.
     *
     * @param exitCode {@link ExitCodes#OK} on success, {@link ExitCodes#USAGE} on a bad argument
     * @param config the parsed config on success, or {@code null} on a usage error
     */
    public record ParseOutcome(int exitCode, MockGameConfig config) {}

    /**
     * Parses the launch argv the way the machine caller expects, turning {@link #parse(String[])}'s
     * exception into a diagnostic and a stable exit code. On a bad argument it writes picocli's
     * error message and the generated usage text to {@code err}, then returns {@link
     * ExitCodes#USAGE} with a {@code null} config. On success it returns {@link ExitCodes#OK} with
     * the config and writes nothing — a valid set passes through silently.
     *
     * <p>It returns the code rather than calling {@link System#exit(int)} so it stays
     * unit-testable; the bootstrap (WBS-3.2.5.1) maps the code to the process exit status. The
     * error goes to {@code err} (normally {@code System.err}) because that is where the Mock Client
     * captures it, tagged {@code [MockGame]}, for a developer reading logs after a failed run.
     *
     * @param args the raw argv as passed to {@code main}
     * @param err the stream bad-argument diagnostics are written and flushed to
     * @return {@link ExitCodes#OK} with the config, or {@link ExitCodes#USAGE} with a {@code null}
     *     config
     */
    public static ParseOutcome parseOrReport(final String[] args, final PrintStream err) {
        try {
            return new ParseOutcome(ExitCodes.OK, parse(args));
        } catch (ParameterException e) {
            err.println(e.getMessage());
            e.getCommandLine().usage(err);
            err.flush();
            return new ParseOutcome(ExitCodes.USAGE, null);
        }
    }

    /**
     * Semantic checks past picocli's presence and type conversion, so a bad value fails at parse
     * time instead of surfacing later as an unexplained connect failure.
     *
     * @param commandLine the parse context, required by {@link ParameterException}
     */
    private void validate(final CommandLine commandLine) {
        requirePortRange(commandLine, "--gpgnet-port", gpgNetPort);
        requirePortRange(commandLine, "--lobby-port", lobbyPort);
        if (playerId < 1) {
            throw new ParameterException(commandLine, "--player-id must be positive: " + playerId);
        }
        if (playerLogin.isBlank()) {
            throw new ParameterException(commandLine, "--player-login must not be blank");
        }
        // Zero is the documented "no orchestrated session" value the launch-game diagnostic passes,
        // so it is allowed. Negative is never a game id.
        if (gameUid < 0) {
            throw new ParameterException(
                    commandLine, "--game-uid must not be negative: " + gameUid);
        }
    }

    /**
     * Requires {@code value} to be a usable port number.
     *
     * @param commandLine the parse context, required by {@link ParameterException}
     * @param name the option name for the failure message
     * @param value the parsed port value
     */
    private static void requirePortRange(
            final CommandLine commandLine, final String name, final int value) {
        if (value < 1 || value > MAX_PORT) {
            throw new ParameterException(
                    commandLine, name + " out of range (1-" + MAX_PORT + "): " + value);
        }
    }
}
