package com.faforever.testharness.client.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/**
 * Confirms the environment-variable layer alone produces a valid {@link MockClientConfig}
 * via {@link LayeredDefaultProvider}'s {@code FAF_MOCK_CLIENT_*} convention. No file or
 * CLI input is supplied.
 */
final class ConfigLoaderEnvOnlyTest {

    @Test
    void envSuppliesAllRequiredFieldsAndDefaultsApply() {
        MockClientConfig config =
                ConfigLoader.load(new String[] {}, TestFixtures.minimalRequiredEnv())
                        .orElseThrow();

        assertEquals(URI.create(TestFixtures.LOBBY_URL), config.lobbyWebSocketUrl());
        assertEquals(URI.create(TestFixtures.OAUTH_TOKEN_URL), config.oauthTokenUrl());
        assertEquals(TestFixtures.OAUTH_CLIENT_ID, config.oauthClientId());
        assertEquals(TestFixtures.OAUTH_ACCESS_TOKEN, config.oauthAccessToken());
        assertEquals(TestFixtures.UNIQUE_ID, config.uniqueId());
        assertEquals(Path.of(TestFixtures.ICE_ADAPTER_BIN), config.iceAdapterBinaryPath());
        assertEquals(Path.of(TestFixtures.MOCK_GAME_BIN), config.mockGameBinaryPath());
        assertEquals(7236, config.iceAdapterRpcPort());
        assertEquals(7237, config.iceAdapterGpgNetPort());
        assertEquals("INFO", config.logLevel());
        assertTrue(config.logFile().isEmpty());
    }
}