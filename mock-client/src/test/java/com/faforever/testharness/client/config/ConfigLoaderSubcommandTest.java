package com.faforever.testharness.client.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.net.URI;
import java.nio.file.Path;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

/**
 * Regression coverage for the env/file layers reaching a <em>subcommand</em>. The config options
 * are {@code scope = INHERIT}; picocli enforces {@code required} on inherited options at the
 * subcommand level before consulting the default-value provider, so marking them {@code required =
 * true} made config-file and env-var values unreachable for every subcommand (only explicit CLI
 * flags satisfied them). Presence is now validated by {@link MockClientConfig} instead. These tests
 * parse <em>with</em> a leaf subcommand on the command line, the case the other {@code
 * ConfigLoader*Test} classes (root-only) never exercised.
 */
final class ConfigLoaderSubcommandTest {

    @Test
    void subcommandWithConfigFileSatisfiesRequired(@TempDir final Path dir) throws Exception {
        Path file = TestFixtures.writeJson(dir, TestFixtures.minimalRequiredJson());

        MockClientConfig config =
                ConfigLoader.load(new String[] {"run", "--config", file.toString()}, Map.of())
                        .orElseThrow();

        TestFixtures.assertMatchesMinimalRequired(config);
    }

    @Test
    void subcommandWithEnvSatisfiesRequired() {
        MockClientConfig config =
                ConfigLoader.load(new String[] {"run"}, TestFixtures.minimalRequiredEnv())
                        .orElseThrow();

        TestFixtures.assertMatchesMinimalRequired(config);
    }

    @Test
    void cliFlagOverridesConfigFileUnderSubcommand(@TempDir final Path dir) throws Exception {
        Path file = TestFixtures.writeJson(dir, TestFixtures.minimalRequiredJson());
        String override = "wss://override.example/lobby";

        String[] args =
                Stream.concat(
                                Stream.of("run", "--config", file.toString()),
                                Stream.of("--lobby-websocket-url=" + override))
                        .toArray(String[]::new);

        MockClientConfig config = ConfigLoader.load(args, Map.of()).orElseThrow();

        assertEquals(URI.create(override), config.lobbyWebSocketUrl());
        // a non-overridden field still comes from the file
        assertEquals(URI.create(TestFixtures.OAUTH_TOKEN_URL), config.oauthTokenUrl());
    }

    @Test
    void envOverridesConfigFileUnderSubcommand(@TempDir final Path dir) throws Exception {
        Path file = TestFixtures.writeJson(dir, TestFixtures.minimalRequiredJson());
        String override = "wss://env-override.example/lobby";

        MockClientConfig config =
                ConfigLoader.load(
                                new String[] {"run", "--config", file.toString()},
                                Map.of("FAF_MOCK_CLIENT_LOBBY_WEBSOCKET_URL", override))
                        .orElseThrow();

        assertEquals(URI.create(override), config.lobbyWebSocketUrl());
    }

    @Test
    void subcommandMissingRequiredSurfacesUsageError() {
        // No CLI flags, no env, no file: the record's presence check fires and toValidatedConfig
        // wraps it as a picocli ParameterException (rendered as a usage error, exit code USAGE).
        assertThrows(
                CommandLine.ParameterException.class,
                () -> ConfigLoader.load(new String[] {"run"}, Map.of()));
    }
}
