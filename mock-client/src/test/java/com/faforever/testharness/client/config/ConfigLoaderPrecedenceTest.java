package com.faforever.testharness.client.config;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.net.URI;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Verifies the four-layer precedence:
 *
 * <pre>
 *   built-in defaults  &lt;  config file  &lt;  env vars  &lt;  CLI flags
 * </pre>
 *
 * <p>Each test seeds the same field ({@code lobbyWebSocketUrl} for URI fields, {@code
 * iceAdapterRpcPort} for the numeric default-bearing field) at multiple layers and asserts that the
 * highest-priority layer wins.
 */
final class ConfigLoaderPrecedenceTest {

    private static final URI URL_FROM_FILE = URI.create("ws://from-file/ws");
    private static final URI URL_FROM_ENV = URI.create("ws://from-env/ws");
    private static final URI URL_FROM_CLI = URI.create("ws://from-cli/ws");

    private static final int PORT_DEFAULT = 7236;
    private static final int PORT_FROM_FILE = 8000;
    private static final int PORT_FROM_ENV = 8100;
    private static final int PORT_FROM_CLI = 8200;

    @Test
    void builtInDefaultIsUsedWhenNoLayerSetsTheField() {
        MockClientConfig config =
                ConfigLoader.load(TestFixtures.minimalRequiredCli(), Map.of()).orElseThrow();

        assertEquals(PORT_DEFAULT, config.iceAdapterRpcPort());
    }

    @Test
    void fileBeatsBuiltInDefault(@TempDir final Path tempDir) throws Exception {
        Path file = TestFixtures.writeJson(tempDir, jsonWithPort(PORT_FROM_FILE));

        MockClientConfig config = loadWithFile(file).orElseThrow();

        assertEquals(PORT_FROM_FILE, config.iceAdapterRpcPort());
    }

    @Test
    void envBeatsFile(@TempDir final Path tempDir) throws Exception {
        Path file = TestFixtures.writeJson(tempDir, jsonWithPort(PORT_FROM_FILE));
        Map<String, String> env = new LinkedHashMap<>();
        env.put("FAF_MOCK_CLIENT_ICE_ADAPTER_RPC_PORT", String.valueOf(PORT_FROM_ENV));

        MockClientConfig config =
                ConfigLoader.load(new String[] {"--config", file.toString()}, env).orElseThrow();

        assertEquals(PORT_FROM_ENV, config.iceAdapterRpcPort());
    }

    @Test
    void cliBeatsEnv(@TempDir final Path tempDir) throws Exception {
        Path file = TestFixtures.writeJson(tempDir, jsonWithPort(PORT_FROM_FILE));
        Map<String, String> env = new LinkedHashMap<>();
        env.put("FAF_MOCK_CLIENT_ICE_ADAPTER_RPC_PORT", String.valueOf(PORT_FROM_ENV));

        String[] args =
                new String[] {
                    "--config", file.toString(), "--ice-adapter-rpc-port=" + PORT_FROM_CLI,
                };

        MockClientConfig config = ConfigLoader.load(args, env).orElseThrow();

        assertEquals(PORT_FROM_CLI, config.iceAdapterRpcPort());
    }

    @Test
    void cliBeatsAllOtherLayersForUriField(@TempDir final Path tempDir) throws Exception {
        String json = jsonWithLobbyUrl(URL_FROM_FILE.toString());
        Path file = TestFixtures.writeJson(tempDir, json);

        Map<String, String> env = new LinkedHashMap<>();
        env.put("FAF_MOCK_CLIENT_LOBBY_WEBSOCKET_URL", URL_FROM_ENV.toString());

        String[] args =
                new String[] {
                    "--config", file.toString(), "--lobby-websocket-url=" + URL_FROM_CLI,
                };

        MockClientConfig config = ConfigLoader.load(args, env).orElseThrow();

        assertEquals(URL_FROM_CLI, config.lobbyWebSocketUrl());
    }

    @Test
    void envBeatsFileForUriField(@TempDir final Path tempDir) throws Exception {
        String json = jsonWithLobbyUrl(URL_FROM_FILE.toString());
        Path file = TestFixtures.writeJson(tempDir, json);

        Map<String, String> env = new LinkedHashMap<>();
        env.put("FAF_MOCK_CLIENT_LOBBY_WEBSOCKET_URL", URL_FROM_ENV.toString());

        MockClientConfig config =
                ConfigLoader.load(new String[] {"--config", file.toString()}, env).orElseThrow();

        assertEquals(URL_FROM_ENV, config.lobbyWebSocketUrl());
    }

    /** Build a JSON config with every required field, varying only the RPC port. */
    private static String jsonWithPort(final int rpcPort) {
        return """
                {
                  "lobbyWebSocketUrl":     "%s",
                  "oauthTokenUrl":         "%s",
                  "oauthAuthEndpoint":     "%s",
                  "oauthRedirectUri":      "%s",
                  "oauthScopes":           "%s",
                  "oauthClientId":         "%s",
                  "oauthRefreshTokenFile": "%s",
                  "uniqueId":              "%s",
                  "iceAdapterBinaryPath":  "%s",
                  "mockGameBinaryPath":    "%s",
                  "iceAdapterRpcPort":     %d
                }
                """
                .formatted(
                        TestFixtures.LOBBY_URL,
                        TestFixtures.OAUTH_TOKEN_URL,
                        TestFixtures.OAUTH_AUTH_ENDPOINT,
                        TestFixtures.OAUTH_REDIRECT_URI,
                        TestFixtures.OAUTH_SCOPES,
                        TestFixtures.OAUTH_CLIENT_ID,
                        TestFixtures.OAUTH_REFRESH_TOKEN_FILE,
                        TestFixtures.UNIQUE_ID,
                        TestFixtures.ICE_ADAPTER_BIN,
                        TestFixtures.MOCK_GAME_BIN,
                        rpcPort);
    }

    /** Build a JSON config with every required field, varying only the lobby WebSocket URL. */
    private static String jsonWithLobbyUrl(final String lobbyUrl) {
        return """
                {
                  "lobbyWebSocketUrl":     "%s",
                  "oauthTokenUrl":         "%s",
                  "oauthAuthEndpoint":     "%s",
                  "oauthRedirectUri":      "%s",
                  "oauthScopes":           "%s",
                  "oauthClientId":         "%s",
                  "oauthRefreshTokenFile": "%s",
                  "uniqueId":              "%s",
                  "iceAdapterBinaryPath":  "%s",
                  "mockGameBinaryPath":    "%s"
                }
                """
                .formatted(
                        lobbyUrl,
                        TestFixtures.OAUTH_TOKEN_URL,
                        TestFixtures.OAUTH_AUTH_ENDPOINT,
                        TestFixtures.OAUTH_REDIRECT_URI,
                        TestFixtures.OAUTH_SCOPES,
                        TestFixtures.OAUTH_CLIENT_ID,
                        TestFixtures.OAUTH_REFRESH_TOKEN_FILE,
                        TestFixtures.UNIQUE_ID,
                        TestFixtures.ICE_ADAPTER_BIN,
                        TestFixtures.MOCK_GAME_BIN);
    }

    private static java.util.Optional<MockClientConfig> loadWithFile(final Path file) {
        return ConfigLoader.load(new String[] {"--config", file.toString()}, Map.of());
    }
}
