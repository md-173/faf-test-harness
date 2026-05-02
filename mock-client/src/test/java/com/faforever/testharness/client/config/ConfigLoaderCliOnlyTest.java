package com.faforever.testharness.client.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * Confirms the CLI-flag layer alone produces a valid {@link MockClientConfig}. No env
 * vars, no config file, just {@code --kebab-case} flags.
 */
final class ConfigLoaderCliOnlyTest {

    @Test
    void cliSuppliesAllRequiredFieldsAndDefaultsApply() {
        MockClientConfig config =
                ConfigLoader.load(TestFixtures.minimalRequiredCli(), Map.of())
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

    @Test
    void cliFlagsCanOverrideADefaultPort() {
        String[] args =
                Stream.concat(
                                Arrays.stream(TestFixtures.minimalRequiredCli()),
                                Stream.of("--ice-adapter-rpc-port=9000"))
                        .toArray(String[]::new);

        MockClientConfig config = ConfigLoader.load(args, Map.of()).orElseThrow();

        assertEquals(9000, config.iceAdapterRpcPort());
    }
}