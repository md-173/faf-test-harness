package com.faforever.testharness.client.config;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

final class TestFixtures {

    static final String LOBBY_URL = "ws://localhost/ws";
    static final String OAUTH_TOKEN_URL = "http://localhost:4444/oauth2/token";
    static final String OAUTH_CLIENT_ID = "faf-client";
    static final String OAUTH_ACCESS_TOKEN = "test-token";
    static final String UNIQUE_ID = "00000000-0000-0000-0000-000000000000";
    static final String ICE_ADAPTER_BIN = "/bin/faf-ice-adapter";
    static final String MOCK_GAME_BIN = "/bin/mock-game";

    private TestFixtures() {}

    /** Minimal CLI args containing every required field plus a token credential. */
    static String[] minimalRequiredCli() {
        return new String[] {
            "--lobby-websocket-url=" + LOBBY_URL,
            "--oauth-token-url=" + OAUTH_TOKEN_URL,
            "--oauth-client-id=" + OAUTH_CLIENT_ID,
            "--oauth-access-token=" + OAUTH_ACCESS_TOKEN,
            "--unique-id=" + UNIQUE_ID,
            "--ice-adapter-binary-path=" + ICE_ADAPTER_BIN,
            "--mock-game-binary-path=" + MOCK_GAME_BIN,
        };
    }

    /** Minimal env map containing every required field plus a token credential. */
    static Map<String, String> minimalRequiredEnv() {
        Map<String, String> env = new LinkedHashMap<>();
        env.put("FAF_MOCK_CLIENT_LOBBY_WEBSOCKET_URL", LOBBY_URL);
        env.put("FAF_MOCK_CLIENT_OAUTH_TOKEN_URL", OAUTH_TOKEN_URL);
        env.put("FAF_MOCK_CLIENT_OAUTH_CLIENT_ID", OAUTH_CLIENT_ID);
        env.put("FAF_MOCK_CLIENT_OAUTH_ACCESS_TOKEN", OAUTH_ACCESS_TOKEN);
        env.put("FAF_MOCK_CLIENT_UNIQUE_ID", UNIQUE_ID);
        env.put("FAF_MOCK_CLIENT_ICE_ADAPTER_BINARY_PATH", ICE_ADAPTER_BIN);
        env.put("FAF_MOCK_CLIENT_MOCK_GAME_BINARY_PATH", MOCK_GAME_BIN);
        return env;
    }

    /** Minimal JSON containing every required field plus a token credential. */
    static String minimalRequiredJson() {
        return """
                {
                  "lobbyWebSocketUrl":     "%s",
                  "oauthTokenUrl":         "%s",
                  "oauthClientId":         "%s",
                  "oauthAccessToken":      "%s",
                  "uniqueId":              "%s",
                  "iceAdapterBinaryPath":  "%s",
                  "mockGameBinaryPath":    "%s"
                }
                """
                .formatted(
                        LOBBY_URL,
                        OAUTH_TOKEN_URL,
                        OAUTH_CLIENT_ID,
                        OAUTH_ACCESS_TOKEN,
                        UNIQUE_ID,
                        ICE_ADAPTER_BIN,
                        MOCK_GAME_BIN);
    }

    /** Write {@code json} to {@code dir/mock-client.json} and return the path. */
    static Path writeJson(final Path dir, final String json) throws Exception {
        Path file = dir.resolve("mock-client.json");
        Files.writeString(file, json);
        return file;
    }
}