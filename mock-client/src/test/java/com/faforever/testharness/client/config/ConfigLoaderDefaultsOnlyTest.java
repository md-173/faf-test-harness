package com.faforever.testharness.client.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.Arrays;
import java.util.Map;
import org.junit.jupiter.api.Test;
import picocli.CommandLine;

/**
 * Covers AC#1: loading with nothing set fails with an error that names every missing required
 * field, and pairs with a positive case proving built-in {@code defaultValue} annotations populate
 * when the required fields are supplied.
 */
final class ConfigLoaderDefaultsOnlyTest {

    @Test
    void emptyArgsThrowsMissingParameterExceptionListingEveryRequiredFlag() {
        CommandLine.MissingParameterException ex =
                assertThrows(
                        CommandLine.MissingParameterException.class,
                        () -> ConfigLoader.load(new String[] {}, Map.of()),
                        "Empty args + empty env should fail picocli's required-options "
                                + "check.");

        String message = ex.getMessage();

        assertTrue(
                message.contains("--lobby-websocket-url"),
                "Missing-parameter message should name --lobby-websocket-url. "
                        + "Got: "
                        + message);
        assertTrue(
                message.contains("--oauth-token-url"),
                "Missing-parameter message should name --oauth-token-url. Got: " + message);
        assertTrue(
                message.contains("--oauth-auth-endpoint"),
                "Missing-parameter message should name --oauth-auth-endpoint. Got: " + message);
        assertTrue(
                message.contains("--oauth-redirect-uri"),
                "Missing-parameter message should name --oauth-redirect-uri. Got: " + message);
        assertTrue(
                message.contains("--oauth-scopes"),
                "Missing-parameter message should name --oauth-scopes. Got: " + message);
        assertTrue(
                message.contains("--oauth-client-id"),
                "Missing-parameter message should name --oauth-client-id. Got: " + message);
        assertTrue(
                message.contains("--unique-id"),
                "Missing-parameter message should name --unique-id. Got: " + message);
    }

    @Test
    void iceAdapterBinaryPathDefaultsToFafIceAdapterJarWhenUnset() {
        // --ice-adapter-binary-path is optional (subprocess-orchestration-spec §2.2): when unset
        // it resolves to faf-ice-adapter.jar in the working directory.
        String[] withoutIceBinary =
                Arrays.stream(TestFixtures.minimalRequiredCli())
                        .filter(arg -> !arg.startsWith("--ice-adapter-binary-path"))
                        .toArray(String[]::new);

        MockClientConfig config = ConfigLoader.load(withoutIceBinary, Map.of()).orElseThrow();

        assertEquals(Path.of("faf-ice-adapter.jar"), config.iceAdapterBinaryPath());
    }

    @Test
    void mockGameBinaryPathDefaultsToGradleInstallLayoutWhenUnset() {
        // --mock-game-binary-path is optional (WBS-3.1.2.3): when unset it resolves to the
        // application-plugin install layout, so the harness works from the repo root after
        // ./gradlew :mock-game:installDist.
        String[] withoutGameBinary =
                Arrays.stream(TestFixtures.minimalRequiredCli())
                        .filter(arg -> !arg.startsWith("--mock-game-binary-path"))
                        .toArray(String[]::new);

        MockClientConfig config = ConfigLoader.load(withoutGameBinary, Map.of()).orElseThrow();

        assertEquals(
                Path.of("mock-game/build/install/mock-game/bin/mock-game"),
                config.mockGameBinaryPath());
    }

    @Test
    void allRequiredFlagsSetPopulatesEveryBuiltInDefault() {
        MockClientConfig config =
                ConfigLoader.load(TestFixtures.minimalRequiredCli(), Map.of()).orElseThrow();

        assertEquals(7236, config.iceAdapterRpcPort(), "iceAdapterRpcPort default should be 7236");
        assertEquals(
                7237, config.iceAdapterGpgNetPort(), "iceAdapterGpgNetPort default should be 7237");
        assertEquals(
                7238, config.iceAdapterLobbyPort(), "iceAdapterLobbyPort default should be 7238");
        assertEquals(
                "mock-client", config.playerLogin(), "playerLogin default should be mock-client");
        assertEquals("INFO", config.logLevel(), "logLevel default should be INFO");
        assertTrue(config.logFile().isEmpty(), "logFile should default to empty Optional");
        assertTrue(
                config.playerIdOverride().isEmpty(),
                "playerIdOverride should default to empty OptionalInt");
    }
}
