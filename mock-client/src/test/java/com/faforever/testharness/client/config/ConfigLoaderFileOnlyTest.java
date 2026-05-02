package com.faforever.testharness.client.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Covers AC#2: a config file alone is sufficient to produce a valid {@link
 * MockClientConfig}, with built-in defaults still applying for fields the file omits.
 */
final class ConfigLoaderFileOnlyTest {

    @Test
    void fileSuppliesAllRequiredFieldsAndDefaultsApply(@TempDir final Path tempDir)
            throws Exception {
        Path file = TestFixtures.writeJson(tempDir, TestFixtures.minimalRequiredJson());

        MockClientConfig config =
                ConfigLoader.load(new String[] {"--config", file.toString()}, Map.of())
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
        assertTrue(config.playerIdOverride().isEmpty());
    }

    @Test
    void fileWithEqualsSyntaxAlsoWorks(@TempDir final Path tempDir) throws Exception {
        Path file = TestFixtures.writeJson(tempDir, TestFixtures.minimalRequiredJson());

        MockClientConfig config =
                ConfigLoader.load(new String[] {"--config=" + file}, Map.of()).orElseThrow();

        assertEquals(URI.create(TestFixtures.LOBBY_URL), config.lobbyWebSocketUrl());
    }
}