package com.faforever.testharness.client.config;

import com.faforever.testharness.client.cli.ExitCodes;
import com.faforever.testharness.client.cli.IceSmokeCommand;
import com.faforever.testharness.client.cli.LaunchGameCommand;
import com.faforever.testharness.client.cli.LaunchIceCommand;
import com.faforever.testharness.client.cli.RunCommand;
import com.faforever.testharness.shared.logging.LoggingSetup;
import java.net.URI;
import java.nio.file.Path;
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
            required = true,
            description = "WebSocket endpoint of the FAF lobby server.")
    private URI lobbyWebSocketUrl;

    /** OAuth2 token endpoint used to acquire lobby access tokens. */
    @Option(
            names = "--oauth-token-url",
            scope = ScopeType.INHERIT,
            required = true,
            description = "OAuth2 token endpoint used to acquire lobby access tokens.")
    private URI oauthTokenUrl;

    /** OAuth2 authorization endpoint used by the one-time refresh-token bootstrap. */
    @Option(
            names = "--oauth-auth-endpoint",
            scope = ScopeType.INHERIT,
            required = true,
            description =
                    "OAuth2 authorization endpoint used by the one-time refresh-token bootstrap.")
    private URI oauthAuthEndpoint;

    /** Redirect URI registered on the OAuth client. */
    @Option(
            names = "--oauth-redirect-uri",
            scope = ScopeType.INHERIT,
            required = true,
            description = "Redirect URI registered on the OAuth client.")
    private URI oauthRedirectUri;

    /** Space-separated OAuth2 scopes (e.g. "openid offline lobby"). */
    @Option(
            names = "--oauth-scopes",
            scope = ScopeType.INHERIT,
            required = true,
            description = "Space-separated OAuth2 scopes (e.g. \"openid offline lobby\").")
    private String oauthScopes;

    /** OAuth2 public client identifier. */
    @Option(
            names = "--oauth-client-id",
            scope = ScopeType.INHERIT,
            required = true,
            description = "OAuth2 public client identifier.")
    private String oauthClientId;

    /** Long-lived OAuth refresh token. Prefer env vars or CI secrets; rotated by Hydra on use. */
    @Option(
            names = "--oauth-refresh-token",
            scope = ScopeType.INHERIT,
            description =
                    "Long-lived OAuth refresh token. Prefer env vars or CI secrets; rotated on "
                            + "use.")
    private String oauthRefreshToken;

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
            required = true,
            description = "Stable hardware identifier sent in the lobby auth message.")
    private String uniqueId;

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
            description = "Optional player ID override for deterministic local testing.")
    private Integer playerIdOverride;

    /** Local player login passed to faf-ice-adapter as {@code --login}. */
    @Option(
            names = "--player-login",
            scope = ScopeType.INHERIT,
            defaultValue = "mock-client",
            description =
                    "Local player login passed to faf-ice-adapter as --login. Used by the "
                            + "launch-ice / ice-smoke diagnostics; a full 'run' session uses the "
                            + "lobby welcome identity instead.")
    private String playerLogin;

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
                oauthRefreshToken,
                oauthRefreshTokenFile,
                uniqueId,
                iceAdapterBinaryPath,
                mockGameBinaryPath,
                iceAdapterRpcPort,
                iceAdapterGpgNetPort,
                iceAdapterLobbyPort,
                logLevel,
                Optional.ofNullable(logFile),
                playerIdOverride == null ? OptionalInt.empty() : OptionalInt.of(playerIdOverride),
                playerLogin);
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
     * Bridges {@link MockClientConfig#logLevel()} and {@link MockClientConfig#logFile()} into the
     * system properties consumed by {@code logback.xml}. Subcommands call this from their {@code
     * Callable.call()} before {@link LoggingSetup#configure(String)} so Logback observes the
     * caller-supplied log level and JSONL path.
     *
     * @param config the validated configuration whose logging fields should be applied
     */
    public static void applyLoggingProperties(final MockClientConfig config) {
        System.setProperty(LoggingSetup.LOG_LEVEL_ENV, config.logLevel());
        config.logFile()
                .ifPresent(path -> System.setProperty(LoggingSetup.LOG_FILE_ENV, path.toString()));
    }
}
