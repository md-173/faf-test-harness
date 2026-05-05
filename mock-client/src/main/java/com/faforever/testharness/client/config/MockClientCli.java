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

    /** OAuth2 client identifier. */
    @Option(
            names = "--oauth-client-id",
            scope = ScopeType.INHERIT,
            required = true,
            description = "OAuth2 client identifier.")
    private String oauthClientId;

    /** OAuth2 client secret. Prefer environment variables or CI secrets. */
    @Option(
            names = "--oauth-client-secret",
            scope = ScopeType.INHERIT,
            description = "OAuth2 client secret. Prefer environment variables or CI secrets.")
    private String oauthClientSecret;

    /** OAuth username for local/test environments. Prefer environment variables or CI secrets. */
    @Option(
            names = "--oauth-username",
            scope = ScopeType.INHERIT,
            description = "OAuth username for local/test environments.")
    private String oauthUsername;

    /** OAuth password for local/test environments. Prefer environment variables or CI secrets. */
    @Option(
            names = "--oauth-password",
            scope = ScopeType.INHERIT,
            description = "OAuth password for local/test environments. Prefer env or CI secrets.")
    private String oauthPassword;

    /** Pre-obtained OAuth access token. Prefer environment variables or CI secrets. */
    @Option(
            names = "--oauth-access-token",
            scope = ScopeType.INHERIT,
            description = "Pre-obtained OAuth access token. Prefer env or CI secrets.")
    private String oauthAccessToken;

    /** Path to a file containing a pre-obtained OAuth access token. */
    @Option(
            names = "--oauth-token-file",
            scope = ScopeType.INHERIT,
            description = "Path to a file containing a pre-obtained OAuth access token.")
    private Path oauthTokenFile;

    /** Stable hardware identifier sent in the lobby auth message. */
    @Option(
            names = "--unique-id",
            scope = ScopeType.INHERIT,
            required = true,
            description = "Stable hardware identifier sent in the lobby auth message.")
    private String uniqueId;

    /** Path to the faf-ice-adapter executable. */
    @Option(
            names = "--ice-adapter-binary-path",
            scope = ScopeType.INHERIT,
            required = true,
            description = "Path to the faf-ice-adapter executable.")
    private Path iceAdapterBinaryPath;

    /** Path to the mock-game executable. */
    @Option(
            names = "--mock-game-binary-path",
            scope = ScopeType.INHERIT,
            required = true,
            description = "Path to the mock-game executable.")
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
                oauthClientId,
                oauthClientSecret,
                oauthUsername,
                oauthPassword,
                oauthAccessToken,
                oauthTokenFile,
                uniqueId,
                iceAdapterBinaryPath,
                mockGameBinaryPath,
                iceAdapterRpcPort,
                iceAdapterGpgNetPort,
                logLevel,
                Optional.ofNullable(logFile),
                playerIdOverride == null ? OptionalInt.empty() : OptionalInt.of(playerIdOverride));
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
