package com.faforever.testharness.client.config;

import java.net.URI;
import java.nio.file.Path;
import java.util.Optional;
import java.util.OptionalInt;
import picocli.CommandLine;
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

    @Option(
            names = ConfigLoader.CONFIG_FLAG,
            description =
                    "Path to a JSON config file. Values from the file are overridden by "
                            + "environment variables and CLI flags.")
    Path configFile;

    @Option(
            names = "--lobby-websocket-url",
            required = true,
            description = "WebSocket endpoint of the FAF lobby server.")
    URI lobbyWebSocketUrl;

    @Option(
            names = "--oauth-token-url",
            required = true,
            description = "OAuth2 token endpoint used to acquire lobby access tokens.")
    URI oauthTokenUrl;

    @Option(names = "--oauth-client-id", required = true, description = "OAuth2 client identifier.")
    String oauthClientId;

    @Option(
            names = "--oauth-client-secret",
            description = "OAuth2 client secret. Prefer environment variables or CI secrets.")
    String oauthClientSecret;

    @Option(names = "--oauth-username", description = "OAuth username for local/test environments.")
    String oauthUsername;

    @Option(
            names = "--oauth-password",
            description = "OAuth password for local/test environments. Prefer env or CI secrets.")
    String oauthPassword;

    @Option(
            names = "--oauth-access-token",
            description = "Pre-obtained OAuth access token. Prefer env or CI secrets.")
    String oauthAccessToken;

    @Option(
            names = "--oauth-token-file",
            description = "Path to a file containing a pre-obtained OAuth access token.")
    Path oauthTokenFile;

    @Option(
            names = "--unique-id",
            required = true,
            description = "Stable hardware identifier sent in the lobby auth message.")
    String uniqueId;

    @Option(
            names = "--ice-adapter-binary-path",
            required = true,
            description = "Path to the faf-ice-adapter executable.")
    Path iceAdapterBinaryPath;

    @Option(
            names = "--mock-game-binary-path",
            required = true,
            description = "Path to the mock-game executable.")
    Path mockGameBinaryPath;

    @Option(
            names = "--ice-adapter-rpc-port",
            defaultValue = "7236",
            description = "Local JSON-RPC port exposed by faf-ice-adapter.")
    int iceAdapterRpcPort;

    @Option(
            names = "--ice-adapter-gpg-net-port",
            defaultValue = "7237",
            description = "Local GPGNet port exposed by faf-ice-adapter.")
    int iceAdapterGpgNetPort;

    @Option(
            names = "--log-level",
            defaultValue = "INFO",
            description = "Minimum log level (TRACE, DEBUG, INFO, WARN, ERROR).")
    String logLevel;

    @Option(names = "--log-file", description = "Optional JSONL log file path.")
    Path logFile;

    @Option(
            names = "--player-id-override",
            description = "Optional player ID override for deterministic local testing.")
    Integer playerIdOverride;

    /**
     * Validate cross-field rules and produce the immutable {@link MockClientConfig}.
     *
     * @throws CommandLine.ParameterException if no OAuth credential channel is supplied
     */
    MockClientConfig toConfig() {
        boolean hasToken = oauthAccessToken != null || oauthTokenFile != null;
        boolean hasPasswordGrant =
                oauthUsername != null && oauthPassword != null && oauthClientSecret != null;
        if (!hasToken && !hasPasswordGrant) {
            throw new CommandLine.ParameterException(
                    new CommandLine(this),
                    "no OAuth credentials supplied: set --oauth-access-token / "
                            + "--oauth-token-file, or --oauth-username + --oauth-password "
                            + "+ --oauth-client-secret");
        }

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
