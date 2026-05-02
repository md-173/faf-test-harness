package com.faforever.testharness.client.config;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.net.URI;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Covers AC#2: a config file alone is sufficient to produce a valid {@link MockClientConfig}, with
 * built-in defaults still applying for fields the file omits.
 */
final class ConfigLoaderFileOnlyTest {

    @Test
    void fileSuppliesAllRequiredFieldsAndDefaultsApply(@TempDir final Path tempDir)
            throws Exception {
        Path file = TestFixtures.writeJson(tempDir, TestFixtures.minimalRequiredJson());

        MockClientConfig config =
                ConfigLoader.load(new String[] {"--config", file.toString()}, Map.of())
                        .orElseThrow();

        TestFixtures.assertMatchesMinimalRequired(config);
    }

    @Test
    void fileWithEqualsSyntaxAlsoWorks(@TempDir final Path tempDir) throws Exception {
        Path file = TestFixtures.writeJson(tempDir, TestFixtures.minimalRequiredJson());

        MockClientConfig config =
                ConfigLoader.load(new String[] {"--config=" + file}, Map.of()).orElseThrow();

        assertEquals(URI.create(TestFixtures.LOBBY_URL), config.lobbyWebSocketUrl());
    }
}
