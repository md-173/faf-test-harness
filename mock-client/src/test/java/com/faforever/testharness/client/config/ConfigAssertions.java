package com.faforever.testharness.client.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.nio.file.Path;

/** Assertions for the canonical minimal MockClientConfig fixture. */
public final class ConfigAssertions {

    private ConfigAssertions() {}

    /** Asserts {@code config} matches the minimal required fixture shape. */
    public static void assertMinimalRequired(final MockClientConfig config) {
        assertEquals(URI.create(TestFixtures.LOBBY_URL), config.lobbyWebSocketUrl());
        assertEquals(URI.create(TestFixtures.OAUTH_TOKEN_URL), config.oauthTokenUrl());
        assertEquals(URI.create(TestFixtures.OAUTH_AUTH_ENDPOINT), config.oauthAuthEndpoint());
        assertEquals(URI.create(TestFixtures.OAUTH_REDIRECT_URI), config.oauthRedirectUri());
        assertEquals(TestFixtures.OAUTH_SCOPES, config.oauthScopes());
        assertEquals(TestFixtures.OAUTH_CLIENT_ID, config.oauthClientId());
        assertEquals(
                Path.of(TestFixtures.OAUTH_REFRESH_TOKEN_FILE), config.oauthRefreshTokenFile());
        assertEquals(TestFixtures.UNIQUE_ID, config.uniqueId());
        assertEquals(Path.of(TestFixtures.ICE_ADAPTER_BIN), config.iceAdapterBinaryPath());
        assertEquals(Path.of(TestFixtures.MOCK_GAME_BIN), config.mockGameBinaryPath());
        assertEquals(7236, config.iceAdapterRpcPort());
        assertEquals(7237, config.iceAdapterGpgNetPort());
        assertEquals(7238, config.iceAdapterLobbyPort());
        assertEquals("INFO", config.logLevel());
        assertEquals("mock-client", config.playerLogin());
        assertTrue(config.logFile().isEmpty(), "logFile should default to empty");
        assertTrue(config.playerIdOverride().isEmpty(), "playerIdOverride should default to empty");
    }
}
