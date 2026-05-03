package com.faforever.testharness.client.config;

import java.net.URI;
import java.nio.file.Path;
import java.util.Optional;
import java.util.OptionalInt;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

/**
 * Picocli command holder for the Mock Client. Picocli populates the fields by merging (in priority
 * order): CLI flags, environment variables (via {@link LayeredDefaultProvider}), the JSON config
 * file (also via the provider), and built-in {@code defaultValue} attributes.
 *
 * <p>{@link #toConfig()} performs cross-field validation and produces the immutable {@link
 * MockClientConfig} consumed by every other component.
 */
@Command(
        name = "mock-client",
        mixinStandardHelpOptions = true,
        description = "Headless FAF lobby client used by the integration test harness.")
public final class MockClientCli {

    /**
     * Path to a JSON config file. Values from the file are overridden by environment variables and
     * CLI flags.
     */
    @Option(
            names = ConfigLoader.CONFIG_FLAG,
            description =
                    "Path to a JSON config file. Values from the file are overridden by "
                            + "environment variables and CLI flags.")
    private Path configFile;

    /** WebSocket endpoint of the FAF lobby server. */
    @Option(
            names = "--lobby-websocket-url",
            required = true,
            description = "WebSocket endpoint of the FAF lobby server.")
    private URI lobbyWebSocketUrl;

    /** OAuth2 token endpoint used to acquire lobby access tokens. */
    @Option(
            names = "--oauth-token-url",
            required = true,
            description = "OAuth2 token endpoint used to acquire lobby access tokens.")
    private URI oauthTokenUrl;

    /** OAuth2 client identifier. */
    @Option(names = "--oauth-client-id", required = true, description = "OAuth2 client identifier.")
    private String oauthClientId;

    /** OAuth2 client secret. Prefer environment variables or CI secrets. */
    @Option(
            names = "--oauth-client-secret",
            description = "OAuth2 client secret. Prefer environment variables or CI secrets.")
    private String oauthClientSecret;

    /** OAuth username for local/test environments. Prefer environment variables or CI secrets. */
    @Option(names = "--oauth-username", description = "OAuth username for local/test environments.")
    private String oauthUsername;

    /** OAuth password for local/test environments. Prefer environment variables or CI secrets. */
    @Option(
            names = "--oauth-password",
            description = "OAuth password for local/test environments. Prefer env or CI secrets.")
    private String oauthPassword;

    /** Pre-obtained OAuth access token. Prefer environment variables or CI secrets. */
    @Option(
            names = "--oauth-access-token",
            description = "Pre-obtained OAuth access token. Prefer env or CI secrets.")
    private String oauthAccessToken;

    /** Path to a file containing a pre-obtained OAuth access token. */
    @Option(
            names = "--oauth-token-file",
            description = "Path to a file containing a pre-obtained OAuth access token.")
    private Path oauthTokenFile;

    /** Stable hardware identifier sent in the lobby auth message. */
    @Option(
            names = "--unique-id",
            required = true,
            description = "Stable hardware identifier sent in the lobby auth message.")
    private String uniqueId;

    /** Path to the faf-ice-adapter executable. */
    @Option(
            names = "--ice-adapter-binary-path",
            required = true,
            description = "Path to the faf-ice-adapter executable.")
    private Path iceAdapterBinaryPath;

    /** Path to the mock-game executable. */
    @Option(
            names = "--mock-game-binary-path",
            required = true,
            description = "Path to the mock-game executable.")
    private Path mockGameBinaryPath;

    /** Local JSON-RPC port exposed by faf-ice-adapter. */
    @Option(
            names = "--ice-adapter-rpc-port",
            defaultValue = "7236",
            description = "Local JSON-RPC port exposed by faf-ice-adapter.")
    private int iceAdapterRpcPort;

    /** Local GPGNet port exposed by faf-ice-adapter. */
    @Option(
            names = "--ice-adapter-gpg-net-port",
            defaultValue = "7237",
            description = "Local GPGNet port exposed by faf-ice-adapter.")
    private int iceAdapterGpgNetPort;

    /** Minimum log level (TRACE, DEBUG, INFO, WARN, ERROR). */
    @Option(
            names = "--log-level",
            defaultValue = "INFO",
            description = "Minimum log level (TRACE, DEBUG, INFO, WARN, ERROR).")
    private String logLevel;

    /** Optional JSONL log file path. */
    @Option(names = "--log-file", description = "Optional JSONL log file path.")
    private Path logFile;

    /** Optional player ID override for deterministic local testing. */
    @Option(
            names = "--player-id-override",
            description = "Optional player ID override for deterministic local testing.")
    private Integer playerIdOverride;

    /**
     * Validate cross-field rules and produce the immutable {@link MockClientConfig}.
     *
     * @return the validated configuration
     * @throws CommandLine.ParameterException if no OAuth credential channel is supplied
     */
    MockClientConfig toConfig() {
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
}
