package com.faforever.testharness.client.config;

import com.faforever.testharness.client.cli.ExitCodes;
import com.faforever.testharness.client.cli.IceSmokeCommand;
import com.faforever.testharness.client.cli.LaunchGameCommand;
import com.faforever.testharness.client.cli.LaunchIceCommand;
import com.faforever.testharness.client.cli.RunCommand;
import com.faforever.testharness.shared.logging.LoggingSetup;
import java.net.URI;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.concurrent.Callable;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.ScopeType;
import picocli.CommandLine.Spec;

/**
 * Picocli root command for the Mock Client. Holds every {@link MockClientConfig} field as a
 * {@code @Option} and registers the four subcommands ({@code run}, {@code launch-ice}, {@code
 * launch-game}, {@code ice-smoke}). Picocli populates the fields by merging (in priority order):
 * CLI flags, environment variables (via {@link LayeredDefaultProvider}), the JSON config file (also
 * via the provider), and built-in {@code defaultValue} attributes.
 *
 * <p>Every {@code @Option} declared here uses {@link ScopeType#INHERIT}, so the same flags are
 * accepted by every subcommand and may appear before <em>or</em> after the subcommand name on the
 * command line. The parsed value is always stored on this root instance, regardless of which
 * command observed the flag — subcommands then read the populated config via
 * {@code @ParentCommand}.
 *
 * <p>Subcommands produce a validated {@link MockClientConfig} by calling {@link
 * #toValidatedConfig(CommandSpec)}. That method translates {@link IllegalArgumentException} from
 * the record's compact constructor into a {@link CommandLine.ParameterException} scoped to the
 * calling subcommand, so picocli renders a clean usage-style error rather than a stack trace.
 *
 * <p>Mandatory fields are <em>not</em> marked {@code required = true} on these options. picocli
 * enforces {@code required} on {@code INHERIT}-scoped options at the subcommand level
 * <em>before</em> consulting the default-value provider, which would make env-var and config-file
 * values unreachable for every subcommand (only explicit CLI flags would satisfy them). Instead,
 * presence is validated by the {@link MockClientConfig} compact constructor, so the env/file layers
 * populate the inherited options first and a genuinely missing value still surfaces as a clean
 * usage error.
 *
 * <p>When the root is invoked with no subcommand, {@link #call()} prints usage and exits with
 * {@link ExitCodes#USAGE}.
 */
@Command(
        name = "mock-client",
        mixinStandardHelpOptions = true,
        version = "mock-client 1.0-SNAPSHOT",
        description = "Headless FAF lobby client used by the integration test harness.",
        subcommands = {
            RunCommand.class,
            LaunchIceCommand.class,
            LaunchGameCommand.class,
            IceSmokeCommand.class,
        })
public final class MockClientCli implements Callable<Integer> {

    /** Component label written to log records by every Mock Client subcommand. */
    public static final String COMPONENT_NAME = "MockClient";

    /** Lowest port number the adapter's three listeners may be assigned. */
    private static final int MIN_PORT = 1;

    /** Highest port number the adapter's three listeners may be assigned. */
    private static final int MAX_PORT = 65535;

    /**
     * Picocli auto-injects the active {@link CommandSpec}; used by {@link #call()} to print usage.
     */
    @Spec private CommandSpec spec;

    /**
     * Path to a JSON config file. Values from the file are overridden by environment variables and
     * CLI flags.
     */
    @Option(
            names = ConfigLoader.CONFIG_FLAG,
            scope = ScopeType.INHERIT,
            description =
                    "Path to a JSON config file. Values from the file are overridden by "
                            + "environment variables and CLI flags.")
    private Path configFile;

    /** WebSocket endpoint of the FAF lobby server. */
    @Option(
            names = "--lobby-websocket-url",
            scope = ScopeType.INHERIT,
            description = "WebSocket endpoint of the FAF lobby server.")
    private URI lobbyWebSocketUrl;

    /** OAuth2 token endpoint used to acquire lobby access tokens. */
    @Option(
            names = "--oauth-token-url",
            scope = ScopeType.INHERIT,
            description = "OAuth2 token endpoint used to acquire lobby access tokens.")
    private URI oauthTokenUrl;

    /** OAuth2 authorization endpoint used by the one-time refresh-token bootstrap. */
    @Option(
            names = "--oauth-auth-endpoint",
            scope = ScopeType.INHERIT,
            description =
                    "OAuth2 authorization endpoint used by the one-time refresh-token bootstrap.")
    private URI oauthAuthEndpoint;

    /** Redirect URI registered on the OAuth client. */
    @Option(
            names = "--oauth-redirect-uri",
            scope = ScopeType.INHERIT,
            description = "Redirect URI registered on the OAuth client.")
    private URI oauthRedirectUri;

    /** Space-separated OAuth2 scopes (e.g. "openid offline lobby"). */
    @Option(
            names = "--oauth-scopes",
            scope = ScopeType.INHERIT,
            description = "Space-separated OAuth2 scopes (e.g. \"openid offline lobby\").")
    private String oauthScopes;

    /** OAuth2 public client identifier. */
    @Option(
            names = "--oauth-client-id",
            scope = ScopeType.INHERIT,
            description = "OAuth2 public client identifier.")
    private String oauthClientId;

    /** Path to a file holding the refresh token; rewritten atomically on each rotation. */
    @Option(
            names = "--oauth-refresh-token-file",
            scope = ScopeType.INHERIT,
            description =
                    "Path to a file holding the refresh token; rewritten atomically on each "
                            + "rotation.")
    private Path oauthRefreshTokenFile;

    /** Stable hardware identifier sent in the lobby auth message. */
    @Option(
            names = "--unique-id",
            scope = ScopeType.INHERIT,
            description = "Stable hardware identifier sent in the lobby auth message.")
    private String uniqueId;

    /** Client version string sent in the lobby {@code ask_session} message. */
    @Option(
            names = "--client-version",
            scope = ScopeType.INHERIT,
            defaultValue = "0.0.0-mock",
            description =
                    "Client version string sent in the lobby ask_session message "
                            + "(default: ${DEFAULT-VALUE}).")
    private String clientVersion;

    /** Client identifier string sent in the lobby {@code ask_session} message. */
    @Option(
            names = "--user-agent",
            scope = ScopeType.INHERIT,
            defaultValue = "faf-test-harness",
            description =
                    "Client identifier string sent in the lobby ask_session message "
                            + "(default: ${DEFAULT-VALUE}).")
    private String userAgent;

    /**
     * Optional path to the {@code faf-uid} binary used to generate a real lobby {@code unique_id}.
     */
    @Option(
            names = "--uid-binary-path",
            scope = ScopeType.INHERIT,
            description =
                    "Optional path to the FAF faf-uid binary. When set, the auth handshake runs "
                            + "'<path> <session>' and sends its output as unique_id (the lobby's "
                            + "policy server requires a real RSA-encrypted UID). When unset, the "
                            + "static --unique-id is sent.")
    private Path uidBinaryPath;

    /** Path to the faf-ice-adapter binary; defaults to {@code faf-ice-adapter.jar} in the CWD. */
    @Option(
            names = "--ice-adapter-binary-path",
            scope = ScopeType.INHERIT,
            defaultValue = "faf-ice-adapter.jar",
            description =
                    "Path to the faf-ice-adapter binary (default: ${DEFAULT-VALUE}, resolved "
                            + "against the working directory). A .jar is launched via "
                            + "'java -jar'; any other file is executed directly.")
    private Path iceAdapterBinaryPath;

    /**
     * Path to the {@code mock-game} binary; defaults to the {@code application} plugin's install
     * layout ({@code mock-game/build/install/mock-game/bin/mock-game}) so the harness "just works"
     * when run from the repo root after {@code ./gradlew :mock-game:installDist}.
     */
    @Option(
            names = "--mock-game-binary-path",
            scope = ScopeType.INHERIT,
            defaultValue = "mock-game/build/install/mock-game/bin/mock-game",
            description =
                    "Path to the mock-game binary (default: ${DEFAULT-VALUE}, the Gradle "
                            + "'application' plugin install layout, resolved against the working "
                            + "directory). A .jar is launched via 'java -jar'; any other file is "
                            + "executed directly.")
    private Path mockGameBinaryPath;

    /** Local JSON-RPC port exposed by faf-ice-adapter. */
    @Option(
            names = "--ice-adapter-rpc-port",
            scope = ScopeType.INHERIT,
            defaultValue = "7236",
            description = "Local JSON-RPC port exposed by faf-ice-adapter.")
    private int iceAdapterRpcPort;

    /** Local GPGNet port exposed by faf-ice-adapter. */
    @Option(
            names = "--ice-adapter-gpg-net-port",
            scope = ScopeType.INHERIT,
            defaultValue = "7237",
            description = "Local GPGNet port exposed by faf-ice-adapter.")
    private int iceAdapterGpgNetPort;

    /** Local UDP lobby port passed to faf-ice-adapter as {@code --lobby-port}. */
    @Option(
            names = "--ice-adapter-lobby-port",
            scope = ScopeType.INHERIT,
            defaultValue = "7238",
            description =
                    "Local UDP lobby port the game lobby uses for game traffic; passed to "
                            + "faf-ice-adapter as --lobby-port.")
    private int iceAdapterLobbyPort;

    /** Game ID passed to faf-ice-adapter as {@code --game-id} (required by the adapter). */
    @Option(
            names = "--ice-adapter-game-id",
            scope = ScopeType.INHERIT,
            defaultValue = "0",
            description =
                    "Game ID passed to faf-ice-adapter as --game-id (required by the adapter). "
                            + "Used by the launch-ice / ice-smoke diagnostics; a full 'run' "
                            + "session sets it from the lobby game_launch.uid.")
    private int iceAdapterGameId;

    /**
     * Seconds mock-game waits in the lobby before starting the match on its own; negative disables
     * auto-launch (WBS-4.3.1). This is the single default for the knob — mock-game's own default
     * only ever applies to a hand-run binary, since {@code MockGameLauncher} always emits the flag.
     */
    @Option(
            names = "--mock-game-launch-delay-seconds",
            scope = ScopeType.INHERIT,
            defaultValue = "5",
            description =
                    "Seconds mock-game sits in the lobby before launching the match on its own "
                            + "(default: ${DEFAULT-VALUE}). Use a negative value for a multi-peer "
                            + "session: the lobby server refuses a game_join once the host has "
                            + "launched, so an auto-launching host is unjoinable.")
    private int mockGameLaunchDelaySeconds;

    /** Minimum log level (TRACE, DEBUG, INFO, WARN, ERROR). */
    @Option(
            names = "--log-level",
            scope = ScopeType.INHERIT,
            defaultValue = "INFO",
            description = "Minimum log level (TRACE, DEBUG, INFO, WARN, ERROR).")
    private String logLevel;

    /** Optional JSONL log file path. */
    @Option(
            names = "--log-file",
            scope = ScopeType.INHERIT,
            description = "Optional JSONL log file path.")
    private Path logFile;

    /** Optional player ID override for deterministic local testing. */
    @Option(
            names = "--player-id-override",
            scope = ScopeType.INHERIT,
            description =
                    "Optional player ID override for deterministic local testing. Used by the "
                            + "launch-ice / launch-game / ice-smoke diagnostics; a full 'run' "
                            + "session uses the lobby welcome identity instead.")
    private Integer playerIdOverride;

    /** Local player login passed to faf-ice-adapter as {@code --login}. */
    @Option(
            names = "--player-login",
            scope = ScopeType.INHERIT,
            defaultValue = "mock-client",
            description =
                    "Local player login passed to faf-ice-adapter as --login and to mock-game as "
                            + "--player-login. Used by the launch-ice / launch-game / ice-smoke "
                            + "diagnostics; a full 'run' session uses the lobby welcome identity "
                            + "instead.")
    private String playerLogin;

    /** ID of an existing game to join; when set the FSM sends {@code game_join} from IDLE. */
    @Option(
            names = "--target-game-id",
            scope = ScopeType.INHERIT,
            description =
                    "ID of an existing game to join. When set, the mock client sends game_join "
                            + "for this ID once it reaches the IDLE lobby state.")
    private Integer targetGameId;

    /** Optional password sent alongside {@code game_join} for password-protected games. */
    @Option(
            names = "--game-join-password",
            scope = ScopeType.INHERIT,
            description = "Optional password sent alongside game_join for a protected game.")
    private String gameJoinPassword;

    /**
     * Title advertised in the {@code game_host} request (lobby-protocol-spec §4.1 / §10.2). No
     * default: set together with {@code --host-map}, {@code --host-mod}, and {@code
     * --host-visibility} to host a game on IDLE; omit all four to not host.
     */
    @Option(
            names = "--host-title",
            scope = ScopeType.INHERIT,
            description =
                    "Hosted game title advertised to the lobby. Set together with --host-map, "
                            + "--host-mod, and --host-visibility to host a game on IDLE; omit "
                            + "all four to not host.")
    private String hostTitle;

    /**
     * Map folder name sent in the {@code game_host} request. No default; see {@code --host-title}.
     */
    @Option(
            names = "--host-map",
            scope = ScopeType.INHERIT,
            description = "Map folder name for the hosted game. See --host-title.")
    private String hostMap;

    /**
     * Featured-mod technical name sent in the {@code game_host} request. No default; see {@code
     * --host-title}.
     */
    @Option(
            names = "--host-mod",
            scope = ScopeType.INHERIT,
            description = "Featured-mod technical name for the hosted game. See --host-title.")
    private String hostMod;

    /**
     * Visibility ({@code public}/{@code friends}) sent in the {@code game_host} request. No
     * default; see {@code --host-title}.
     */
    @Option(
            names = "--host-visibility",
            scope = ScopeType.INHERIT,
            description =
                    "Visibility for the hosted game, \"public\" or \"friends\". See "
                            + "--host-title.")
    private String hostVisibility;

    /**
     * Minimum displayed rating for joining the hosted game. No default; see {@code --host-title}.
     */
    @Option(
            names = "--host-rating-min",
            scope = ScopeType.INHERIT,
            description = "Minimum displayed rating for joining the hosted game.")
    private Double hostRatingMin;

    /**
     * Maximum displayed rating for joining the hosted game. No default; see {@code --host-title}.
     */
    @Option(
            names = "--host-rating-max",
            scope = ScopeType.INHERIT,
            description = "Maximum displayed rating for joining the hosted game.")
    private Double hostRatingMax;

    /** Whether the server should enforce {@code --host-rating-min}/{@code --host-rating-max}. */
    @Option(
            names = "--host-enforce-rating-range",
            scope = ScopeType.INHERIT,
            defaultValue = "false",
            description =
                    "Whether to enforce --host-rating-min/--host-rating-max for the hosted game "
                            + "(default: ${DEFAULT-VALUE}).")
    private boolean hostEnforceRatingRange;

    /** A set of key-value pair game options to be sent by the host game. */
    @Option(
            names = "--host-game-option",
            arity = "0..*",
            scope = ScopeType.INHERIT,
            description = "A set of key-value pair game options to be sent by the host game.")
    private Map<String, String> hostGameOption = new HashMap<>();

    /**
     * Default action when the root is invoked with no subcommand: print usage to the configured
     * output stream and exit with {@link ExitCodes#USAGE}.
     *
     * @return {@link ExitCodes#USAGE}
     */
    @Override
    public Integer call() {
        spec.commandLine().usage(spec.commandLine().getOut());
        return ExitCodes.USAGE;
    }

    /**
     * Build a validated {@link MockClientConfig} from the populated fields.
     *
     * @return the validated configuration
     * @throws IllegalArgumentException if no OAuth credential channel is supplied (raised by the
     *     {@link MockClientConfig} compact constructor)
     */
    public MockClientConfig toConfig() {
        return new MockClientConfig(
                lobbyWebSocketUrl,
                oauthTokenUrl,
                oauthAuthEndpoint,
                oauthRedirectUri,
                oauthScopes,
                oauthClientId,
                oauthRefreshTokenFile,
                uniqueId,
                clientVersion,
                userAgent,
                Optional.ofNullable(uidBinaryPath),
                iceAdapterBinaryPath,
                mockGameBinaryPath,
                iceAdapterRpcPort,
                iceAdapterGpgNetPort,
                iceAdapterLobbyPort,
                iceAdapterGameId,
                mockGameLaunchDelaySeconds,
                logLevel,
                Optional.ofNullable(logFile),
                playerIdOverride == null ? OptionalInt.empty() : OptionalInt.of(playerIdOverride),
                playerLogin,
                buildHostConfig(),
                buildJoinConfig());
    }

    /**
     * Builds the join config from {@code --target-game-id} and {@code --game-join-password}, or
     * empty if no target game was set.
     *
     * @return the join config, or {@link Optional#empty()} if the operator did not request joining
     */
    private Optional<GameJoinConfig> buildJoinConfig() {
        if (targetGameId == null) {
            return Optional.empty();
        }
        return Optional.of(new GameJoinConfig(targetGameId, Optional.ofNullable(gameJoinPassword)));
    }

    /**
     * Builds the host config from the four {@code --host-*} options, or empty if none of them was
     * set. A partial set (e.g. only {@code --host-title}) is rejected by {@link GameHostConfig}'s
     * compact constructor, which names the specific missing option.
     *
     * @return the host config, or {@link Optional#empty()} if the operator did not request hosting
     */
    private Optional<GameHostConfig> buildHostConfig() {
        if (hostTitle == null && hostMap == null && hostMod == null && hostVisibility == null) {
            return Optional.empty();
        }
        return Optional.of(
                new GameHostConfig(
                        hostTitle,
                        hostMap,
                        hostMod,
                        hostVisibility,
                        Optional.ofNullable(hostRatingMin),
                        Optional.ofNullable(hostRatingMax),
                        hostEnforceRatingRange,
                        hostGameOption));
    }

    /**
     * Same as {@link #toConfig()} but translates {@link IllegalArgumentException} from the record's
     * compact constructor into a {@link CommandLine.ParameterException} scoped to {@code
     * callerSpec}'s {@link CommandLine}, so the resulting error message is rendered with the right
     * command's usage block. Subcommands call this from their {@code Callable.call()}.
     *
     * @param callerSpec the {@link CommandSpec} of the command requesting validation
     * @return the validated configuration
     * @throws CommandLine.ParameterException if no OAuth credential channel is supplied
     */
    public MockClientConfig toValidatedConfig(final CommandSpec callerSpec) {
        try {
            return toConfig();
        } catch (IllegalArgumentException e) {
            throw new CommandLine.ParameterException(callerSpec.commandLine(), e.getMessage(), e);
        }
    }

    /**
     * Builds the adapter-only view of the populated options, validating <em>only</em> what an
     * ICE-adapter diagnostic actually uses. Deliberately does not go through {@link #toConfig()}: a
     * localhost reachability check has no lobby leg, so requiring the lobby endpoint and the OAuth
     * credential channel would force a consumer with no FAF account to invent placeholder values
     * for fields nothing reads (WBS-3.1.4.3).
     *
     * <p>This is where operator input is range-checked, since it is the layer that receives it.
     * {@link IceAdapterSettings} itself validates only what would reach the adapter as a nonsense
     * argument, so it stays a faithful narrowing of an already-validated {@link MockClientConfig}.
     *
     * @param callerSpec the {@link CommandSpec} of the command requesting validation, so the error
     *     renders under that subcommand's usage block
     * @return the validated adapter settings
     * @throws CommandLine.ParameterException if a port is out of range, the JSON-RPC and GPGNet
     *     ports are equal, or an adapter-relevant value is blank
     */
    public IceAdapterSettings toValidatedAdapterSettings(final CommandSpec callerSpec) {
        List<String> problems = new ArrayList<>();
        checkPort(problems, "--ice-adapter-rpc-port", iceAdapterRpcPort);
        checkPort(problems, "--ice-adapter-gpg-net-port", iceAdapterGpgNetPort);
        checkPort(problems, "--ice-adapter-lobby-port", iceAdapterLobbyPort);
        // Both are TCP listeners in one process: equal values make the adapter fail to bind the
        // second one and exit, which would otherwise only show up as an unexplained "unreachable"
        // once the connect budget expired.
        if (iceAdapterRpcPort == iceAdapterGpgNetPort) {
            problems.add(
                    "--ice-adapter-rpc-port and --ice-adapter-gpg-net-port must differ; both are "
                            + iceAdapterRpcPort);
        }
        // Checked here rather than on the record: the full-session path accepts a blank level
        // (MockClientConfig does not validate it), and this card must not change what launch-ice
        // and run accept. A diagnostic invocation gets the clean usage error instead.
        if (logLevel == null || logLevel.isBlank()) {
            problems.add("--log-level must not be blank");
        }
        if (!problems.isEmpty()) {
            throw new CommandLine.ParameterException(
                    callerSpec.commandLine(), String.join("; ", problems));
        }
        try {
            return new IceAdapterSettings(
                    iceAdapterBinaryPath,
                    iceAdapterRpcPort,
                    iceAdapterGpgNetPort,
                    iceAdapterLobbyPort,
                    iceAdapterGameId,
                    playerIdOverride == null
                            ? OptionalInt.empty()
                            : OptionalInt.of(playerIdOverride),
                    playerLogin,
                    logLevel,
                    Optional.ofNullable(logFile));
        } catch (IllegalArgumentException | NullPointerException e) {
            throw new CommandLine.ParameterException(callerSpec.commandLine(), e.getMessage(), e);
        }
    }

    /**
     * Records a range problem for {@code port} under {@code flag}, if there is one.
     *
     * @param problems accumulator every check appends to, so one error lists them all
     * @param flag the CLI flag name to blame in the message
     * @param port the configured port value
     */
    private static void checkPort(final List<String> problems, final String flag, final int port) {
        if (port < MIN_PORT || port > MAX_PORT) {
            problems.add(
                    flag + " must be between " + MIN_PORT + " and " + MAX_PORT + "; got " + port);
        }
    }

    /**
     * Bridges {@link MockClientConfig#logLevel()} and {@link MockClientConfig#logFile()} into the
     * system properties consumed by {@code logback.xml}. Subcommands call this from their {@code
     * Callable.call()} before {@link LoggingSetup#configure(String)} so Logback observes the
     * caller-supplied log level and JSONL path.
     *
     * @param config the validated configuration whose logging fields should be applied
     */
    public static void applyLoggingProperties(final MockClientConfig config) {
        applyLoggingProperties(config.logLevel(), config.logFile());
    }

    /**
     * Overload for the adapter-only diagnostics, which never build a full {@link MockClientConfig}.
     *
     * @param settings the validated adapter settings whose logging fields should be applied
     */
    public static void applyLoggingProperties(final IceAdapterSettings settings) {
        applyLoggingProperties(settings.logLevel(), settings.logFile());
    }

    /**
     * Sets the two system properties {@code logback.xml} reads.
     *
     * @param level the minimum log level to apply
     * @param file optional JSONL log file path; left untouched when empty, so the logback default
     *     applies
     */
    private static void applyLoggingProperties(final String level, final Optional<Path> file) {
        System.setProperty(LoggingSetup.LOG_LEVEL_ENV, level);
        file.ifPresent(path -> System.setProperty(LoggingSetup.LOG_FILE_ENV, path.toString()));
    }
}
