package com.faforever.testharness.client.config;

import com.faforever.testharness.client.cli.ExitCodes;
import com.faforever.testharness.client.cli.IceSmokeCommand;
import com.faforever.testharness.client.cli.LaunchGameCommand;
import com.faforever.testharness.client.cli.LaunchIceCommand;
import com.faforever.testharness.client.cli.RunCommand;
import com.faforever.testharness.client.cli.SessionCommand;
import com.faforever.testharness.shared.logging.LoggingSetup;
import java.net.URI;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.concurrent.Callable;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.ScopeType;
import picocli.CommandLine.Spec;

/**
 * Picocli root command for the Mock Client. Declares a {@code @Option} for every {@link
 * MockClientConfig} setting, here or in one of its option mixins ({@link OAuthOptions}, {@link
 * SubprocessOptions}, {@link FaultOptions}, {@link LoggingOptions}, {@link GameIntentOptions}), and
 * registers the five subcommands ({@code run}, {@code launch-ice}, {@code launch-game}, {@code
 * ice-smoke}, {@code session}). Picocli populates the fields by merging (in priority order): CLI
 * flags, environment variables (via {@link LayeredDefaultProvider}), the JSON config file (also via
 * the provider), and built-in {@code defaultValue} attributes.
 *
 * <p>Every {@code @Option} declared here or in a mixin uses {@link ScopeType#INHERIT}, so the same
 * flags are accepted by every subcommand and may appear before <em>or</em> after the subcommand
 * name on the command line. The parsed value is always stored on this root instance or one of its
 * mixins, regardless of which command observed the flag, and subcommands then read the populated
 * config via {@code @ParentCommand}.
 *
 * <p>Picocli registers the options in the order their fields are declared, a mixin's where its
 * field is, and that order decides which malformed environment or config-file value is reported
 * when there are several, and the order of a mistyped flag's suggested solutions. Keep it when
 * moving an option.
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
 *
 * <p>Every {@code @Command} in this tree — this root and every subcommand — sets {@code
 * exitCodeOnExecutionException} to {@link ExitCodes#RUNTIME}. An exception escaping a subcommand's
 * {@code call()} is normally handled by {@link
 * com.faforever.testharness.client.cli.ExecutionExceptionHandler}, which never returns picocli's
 * default of {@code 1}; the annotation is the backstop for the routes that bypass that handler and
 * land in picocli's {@code handleUnhandled}. It must be set on the leaf commands, not just here —
 * see that class for why.
 */
@Command(
        name = "mock-client",
        mixinStandardHelpOptions = true,
        versionProvider = VersionProvider.class,
        description = "Headless FAF lobby client used by the integration test harness.",
        exitCodeOnExecutionException = ExitCodes.RUNTIME,
        subcommands = {
            RunCommand.class,
            LaunchIceCommand.class,
            LaunchGameCommand.class,
            IceSmokeCommand.class,
            SessionCommand.class,
        })
public final class MockClientCli implements Callable<Integer> {

    /** Component label written to log records by every Mock Client subcommand. */
    public static final String COMPONENT_NAME = "MockClient";

    /** Lowest port number the adapter's three listeners may be assigned. */
    private static final int MIN_PORT = 1;

    /** Highest port number the adapter's three listeners may be assigned. */
    private static final int MAX_PORT = 65535;

    /** Upper bound for {@code --mock-game-udp-drop-percent}; mirrors mock-game's own check. */
    private static final int MAX_DROP_PERCENT = 100;

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

    /** The OAuth endpoints, public client and credential files. */
    @Mixin private OAuthOptions oauth = new OAuthOptions();

    /**
     * Optional stable hardware identifier sent in the lobby auth message. If {@code
     * --uid-binary-path} is set, at an equal or higher layer this option is ignored.
     */
    @Option(
            names = "--unique-id",
            scope = ScopeType.INHERIT,
            description =
                    "Optional stable hardware identifier sent in the lobby auth message. "
                            + "Ignored if --uid-binary-path is set.")
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
     * Required for a live lobby connection (since the lobby rejects a static id). Used instead of
     * {@code --unique-id} when both are set at the same layer.
     */
    @Option(
            names = "--uid-binary-path",
            scope = ScopeType.INHERIT,
            description =
                    "Path to the FAF faf-uid binary. When set, the auth handshake runs "
                            + "'<path> <session>' and sends its output as unique_id (the lobby's "
                            + "policy server requires a real RSA-encrypted UID). When unset, the "
                            + "static --unique-id is sent.")
    private Path uidBinaryPath;

    /** The ICE adapter and mock-game binaries, the adapter's ports, game id and launch delay. */
    @Mixin private SubprocessOptions subprocesses = new SubprocessOptions();

    /** The fault-injection options (WBS-5.1, 5.2). */
    @Mixin private FaultOptions faults = new FaultOptions();

    /** The log level and JSONL log file options. */
    @Mixin private LoggingOptions logging = new LoggingOptions();

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

    /** The options that host, join or queue for a game on reaching IDLE. */
    @Mixin private GameIntentOptions intents = new GameIntentOptions();

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
        return toConfig(oauth.toConfig());
    }

    /**
     * {@link #toConfig()} with the OAuth group supplied by the caller, so that its credential can
     * come from elsewhere than the two {@code --oauth-*-token-file} options: the {@code session}
     * command builds one config per peer this way, each with its own account.
     *
     * @param credentials the OAuth group this config authenticates with
     * @return the validated configuration
     * @throws IllegalArgumentException if a mandatory field is missing (raised by the {@link
     *     MockClientConfig} compact constructor)
     */
    private MockClientConfig toConfig(final OAuthConfig credentials) {
        return new MockClientConfig(
                lobbyWebSocketUrl,
                credentials,
                Optional.ofNullable(uniqueId),
                clientVersion,
                userAgent,
                Optional.ofNullable(uidBinaryPath),
                subprocesses.toIceAdapterConfig(),
                subprocesses.toMockGameConfig(),
                logging.toConfig(),
                playerIdOverride == null ? OptionalInt.empty() : OptionalInt.of(playerIdOverride),
                playerLogin,
                intents.buildHostConfig(),
                intents.buildJoinConfig(),
                intents.buildQueueConfig(),
                faults.toConfig());
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
            oauth.dropShadowedCredentialChannel(callerSpec);
            dropShadowedUniqueIdSource(callerSpec);
            // Read after the precedence step, which may clear a shadowed refresh-token file.
            return toConfig();
        } catch (IllegalArgumentException e) {
            throw new CommandLine.ParameterException(callerSpec.commandLine(), e.getMessage(), e);
        }
    }

    /**
     * Same as {@link #toValidatedConfig(CommandSpec)} but authenticating with {@code
     * refreshTokenFile}, for one peer of the {@code session} command (WBS-4.2.1). The peer's file
     * is its whole credential: the two {@code --oauth-*-token-file} options are not used, so a root
     * access-token file cannot collide with it and there is no precedence to resolve.
     *
     * @param callerSpec the {@link CommandSpec} of the command requesting validation
     * @param refreshTokenFile the refresh-token file this config authenticates with
     * @return the validated configuration
     * @throws CommandLine.ParameterException if a mandatory field is missing
     */
    public MockClientConfig toValidatedConfig(
            final CommandSpec callerSpec, final Path refreshTokenFile) {
        try {
            dropShadowedUniqueIdSource(callerSpec);
            return toConfig(oauth.toConfig(refreshTokenFile, Optional.empty()));
        } catch (IllegalArgumentException e) {
            throw new CommandLine.ParameterException(callerSpec.commandLine(), e.getMessage(), e);
        }
    }

    /**
     * Same as {@link #toValidatedConfig(CommandSpec, Path)} but on the access-token channel: the
     * peer authenticates with a pre-signed token read from {@code accessTokenFile}, sent as-is and
     * never renewed (WBS-3.1.6.4). The two {@code --oauth-*-token-file} options are not used.
     *
     * @param callerSpec the {@link CommandSpec} of the command requesting validation
     * @param accessTokenFile the access-token file this config authenticates with
     * @return the validated configuration
     * @throws CommandLine.ParameterException if a mandatory field is missing
     */
    public MockClientConfig toValidatedConfigWithAccessToken(
            final CommandSpec callerSpec, final Path accessTokenFile) {
        try {
            dropShadowedUniqueIdSource(callerSpec);
            return toConfig(oauth.toConfig(null, Optional.of(accessTokenFile)));
        } catch (IllegalArgumentException e) {
            throw new CommandLine.ParameterException(callerSpec.commandLine(), e.getMessage(), e);
        }
    }

    /**
     * Which of two options was configured at the higher layer: the command line, then a {@code
     * FAF_MOCK_CLIENT_*} variable, then the config file (harness-runbook.md §3). Both options must
     * be configured. Only the environment and the config file are recorded, so an option with no
     * recorded layer is taken to be on the command line, which is only true when it is set at all.
     *
     * @param callerSpec the spec of the command asking, whose command line carries the provider
     * @param flagA one option's long name, e.g. {@code --peer-access-token-file}
     * @param flagB the other option's long name
     * @return the option set at the higher layer, or empty when both were set at the same layer
     */
    public static Optional<String> higherLayer(
            final CommandSpec callerSpec, final String flagA, final String flagB) {
        LayeredDefaultProvider.Layer a = layerOf(callerSpec, flagA);
        LayeredDefaultProvider.Layer b = layerOf(callerSpec, flagB);
        if (a.ordinal() > b.ordinal()) {
            return Optional.of(flagA);
        }
        if (b.ordinal() > a.ordinal()) {
            return Optional.of(flagB);
        }
        return Optional.empty();
    }

    /**
     * Names the layer a configured option came from, for an error message about a tie.
     *
     * @param callerSpec the spec of the command asking
     * @param flag the option's long name; it must be configured
     * @return {@code on the command line}, {@code in FAF_MOCK_CLIENT_* environment variables} or
     *     {@code in the config file}
     */
    public static String layerDescription(final CommandSpec callerSpec, final String flag) {
        return switch (layerOf(callerSpec, flag)) {
            case FILE -> "in the config file";
            case ENV -> "in FAF_MOCK_CLIENT_* environment variables";
            case CLI -> "on the command line";
        };
    }

    /**
     * The layer a configured option came from, defaulting to the command line when the layered
     * provider did not answer for it or is not attached.
     *
     * @param callerSpec the spec of the command asking
     * @param flag the option's long name
     * @return its layer
     */
    private static LayeredDefaultProvider.Layer layerOf(
            final CommandSpec callerSpec, final String flag) {
        return layeredProvider(callerSpec)
                .flatMap(provider -> provider.layerFor(flag))
                .orElse(LayeredDefaultProvider.Layer.CLI);
    }

    /**
     * Applies the documented precedence to the two sources for a unique ID: a static one and a
     * faf-uid binary path, so that a higher layer <em>overrides</em> a lower one instead of
     * colliding with it.
     *
     * <p>A genuine tie is resolved by dropping the static unique ID and keeping the faf-uid binary
     * path. Otherwise, whichever option is in a lower layer is dropped and the other one is kept.
     *
     * @param callerSpec the spec whose command line carries the layered provider
     */
    private void dropShadowedUniqueIdSource(final CommandSpec callerSpec) {
        if (uidBinaryPath == null || uniqueId == null) {
            return;
        }
        Optional<String> winner = higherLayer(callerSpec, "--uid-binary-path", "--unique-id");

        if (winner.isEmpty()) {
            // Same layer, so uidBinaryPath wins.
            uniqueId = null;
        } else {
            if (winner.get().equals("--uid-binary-path")) {
                uniqueId = null;
            } else {
                uidBinaryPath = null;
            }
        }
    }

    /**
     * Finds the layered provider for {@code callerSpec}, walking up to the root because a
     * subcommand's own spec does not carry it — {@code ConfigLoader} sets it on the root command
     * line, and both entry points reach this method through a subcommand on the {@code execute()}
     * path.
     *
     * @param callerSpec the spec of the command requesting validation
     * @return the provider, or empty when one is not attached (a hand-built {@code CommandLine})
     */
    private static Optional<LayeredDefaultProvider> layeredProvider(final CommandSpec callerSpec) {
        for (CommandLine current = callerSpec.commandLine();
                current != null;
                current = current.getParent()) {
            if (current.getCommandSpec().defaultValueProvider()
                    instanceof LayeredDefaultProvider layered) {
                return Optional.of(layered);
            }
        }
        return Optional.empty();
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
        IceAdapterConfig adapterConfig = subprocesses.toIceAdapterConfig();
        LoggingConfig logConfig = logging.toConfig();
        List<String> problems = new ArrayList<>();
        checkPort(problems, "--ice-adapter-rpc-port", adapterConfig.rpcPort());
        checkPort(problems, "--ice-adapter-gpg-net-port", adapterConfig.gpgNetPort());
        checkPort(problems, "--ice-adapter-lobby-port", adapterConfig.lobbyPort());
        // Both are TCP listeners in one process: equal values make the adapter fail to bind the
        // second one and exit, which would otherwise only show up as an unexplained "unreachable"
        // once the connect budget expired.
        if (adapterConfig.rpcPort() == adapterConfig.gpgNetPort()) {
            problems.add(
                    "--ice-adapter-rpc-port and --ice-adapter-gpg-net-port must differ; both are "
                            + adapterConfig.rpcPort());
        }
        // Checked here rather than on the record: the full-session path accepts a blank level
        // (MockClientConfig does not validate it), and this card must not change what launch-ice
        // and run accept. A diagnostic invocation gets the clean usage error instead.
        if (logConfig.level() == null || logConfig.level().isBlank()) {
            problems.add("--log-level must not be blank");
        }
        if (!problems.isEmpty()) {
            throw new CommandLine.ParameterException(
                    callerSpec.commandLine(), String.join("; ", problems));
        }
        try {
            return new IceAdapterSettings(
                    adapterConfig.binaryPath(),
                    adapterConfig.rpcPort(),
                    adapterConfig.gpgNetPort(),
                    adapterConfig.lobbyPort(),
                    adapterConfig.gameId(),
                    playerIdOverride == null
                            ? OptionalInt.empty()
                            : OptionalInt.of(playerIdOverride),
                    playerLogin,
                    logConfig.level(),
                    logConfig.file());
        } catch (IllegalArgumentException | NullPointerException e) {
            throw new CommandLine.ParameterException(callerSpec.commandLine(), e.getMessage(), e);
        }
    }

    /**
     * Validates the mock-game slice of the configuration, for the subcommands that launch {@code
     * mock-game} without a lobby session (WBS-3.1.5.2-fix, #308).
     *
     * <p>The sibling of {@link #toValidatedAdapterSettings(CommandSpec)}, and the reason is the
     * same: {@code launch-game} opens no lobby connection, so requiring the seven lobby/OAuth
     * fields plus {@code --unique-id} meant every one of them was a placeholder invented to get
     * past validation, which the runbook then had to explain. This validates the fields {@code
     * MockGameLauncher} actually reads, and nothing else.
     *
     * <p>Both ports are checked because a nonsense value reaches {@code mock-game} as a launch
     * argument and comes back as its usage error rather than as this one. They are <em>not</em>
     * required to differ: one is the adapter's TCP GPGNet port and the other the game's own UDP
     * lobby port, so an overlap is legal and the full-session path allows it.
     *
     * @param callerSpec the invoking subcommand's spec, so a failure is reported against it
     * @return the validated mock-game settings
     * @throws CommandLine.ParameterException if any value the launch reads is missing or invalid
     */
    public MockGameSettings toValidatedGameSettings(final CommandSpec callerSpec) {
        IceAdapterConfig adapterConfig = subprocesses.toIceAdapterConfig();
        MockGameConfig gameConfig = subprocesses.toMockGameConfig();
        FaultConfig faultConfig = faults.toConfig();
        LoggingConfig logConfig = logging.toConfig();
        List<String> problems = new ArrayList<>();
        checkPort(problems, "--ice-adapter-gpg-net-port", adapterConfig.gpgNetPort());
        checkPort(problems, "--ice-adapter-lobby-port", adapterConfig.lobbyPort());
        // The 0-100 range check has to be repeated here. It lives in MockClientConfig's compact
        // constructor, which launch-game no longer reaches now that this command builds the
        // narrowed record directly — so without this copy --mock-game-udp-drop-percent=150 would
        // stop exiting USAGE on that path. It also belongs here by this PR's own rule: range
        // checks on operator input are the CLI layer's business, next to the port checks above.
        int dropPercent = faultConfig.mockGameUdpDropPercent();
        if (dropPercent < 0 || dropPercent > MAX_DROP_PERCENT) {
            problems.add(
                    "--mock-game-udp-drop-percent must be between 0 and 100; got " + dropPercent);
        }
        // Checked here rather than on the record, matching toValidatedAdapterSettings: the
        // full-session path accepts a blank level, and this must not change what run accepts.
        if (logConfig.level() == null || logConfig.level().isBlank()) {
            problems.add("--log-level must not be blank");
        }
        if (!problems.isEmpty()) {
            throw new CommandLine.ParameterException(
                    callerSpec.commandLine(), String.join("; ", problems));
        }
        try {
            return new MockGameSettings(
                    gameConfig.binaryPath(),
                    adapterConfig.gpgNetPort(),
                    adapterConfig.lobbyPort(),
                    adapterConfig.gameId(),
                    playerIdOverride == null
                            ? OptionalInt.empty()
                            : OptionalInt.of(playerIdOverride),
                    playerLogin,
                    gameConfig.launchDelaySeconds(),
                    dropPercent,
                    faultConfig.mockGameCrashAfterSeconds(),
                    // Gated exactly as MockGameSettings.from(config) gates it. Forwarding the raw
                    // map here made launch-game send --game-option for a run with no host flags,
                    // while the lobby-driven path dropped it — contradicting MockGameSettings'
                    // own javadoc that both routes launch mock-game identically, and #308's
                    // "neither command's behaviour changes".
                    intents.buildHostConfig().map(GameHostConfig::gameOptions).orElseGet(Map::of),
                    logConfig.level(),
                    logConfig.file());
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
     * Bridges the configuration's {@link LoggingConfig#level()} and {@link LoggingConfig#file()}
     * into the system properties consumed by {@code logback.xml}. Subcommands call this from their
     * {@code Callable.call()} before {@link LoggingSetup#configure(String)} so Logback observes the
     * caller-supplied log level and JSONL path.
     *
     * @param config the validated configuration whose logging fields should be applied
     */
    public static void applyLoggingProperties(final MockClientConfig config) {
        applyLoggingProperties(config.logging().level(), config.logging().file());
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
     * As {@link #applyLoggingProperties(MockClientConfig)}, for the mock-game slice a no-lobby
     * {@code launch-game} runs on.
     *
     * @param settings the validated mock-game settings
     */
    public static void applyLoggingProperties(final MockGameSettings settings) {
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

    /**
     * Same bridge as {@link #applyLoggingProperties(MockClientConfig)}, but reading the populated
     * option fields directly instead of a validated {@link MockClientConfig}.
     *
     * <p>Exists because {@link ConfigLoader#newCommandLine} applies logging before any subcommand
     * runs, and at that point there is no {@link MockClientConfig} to hand the sibling method.
     * Building one would mean running the whole validation, which throws on any missing or
     * malformed field — taking the process down before it could explain why. Picocli has already
     * populated these fields by then, from every layer including the environment and the config
     * file, so they carry the operator's real intent.
     *
     * <p>Nothing here needs validating in any case. {@link MockClientConfig}'s compact constructor
     * checks the URLs, the OAuth fields and the identity strings, and says nothing whatever about
     * {@code logLevel} or {@code logFile}, so the two methods agree by construction — this one
     * simply reaches the same values a step earlier. An unrecognised level reaches Logback either
     * way, which treats it as {@code DEBUG}.
     */
    void applyLoggingPropertiesFromOptions() {
        LoggingConfig logConfig = logging.toConfig();
        if (logConfig.level() != null) {
            System.setProperty(LoggingSetup.LOG_LEVEL_ENV, logConfig.level());
        }
        logConfig
                .file()
                .ifPresent(path -> System.setProperty(LoggingSetup.LOG_FILE_ENV, path.toString()));
    }
}
