package com.faforever.testharness.client.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
        assertTrue(
                message.contains("--ice-adapter-binary-path"),
                "Missing-parameter message should name --ice-adapter-binary-path. "
                        + "Got: "
                        + message);
        assertTrue(
                message.contains("--mock-game-binary-path"),
                "Missing-parameter message should name --mock-game-binary-path. "
                        + "Got: "
                        + message);
    }

    @Test
    void allRequiredFlagsSetPopulatesEveryBuiltInDefault() {
        MockClientConfig config =
                ConfigLoader.load(TestFixtures.minimalRequiredCli(), Map.of()).orElseThrow();

        assertEquals(7236, config.iceAdapterRpcPort(), "iceAdapterRpcPort default should be 7236");
        assertEquals(
                7237, config.iceAdapterGpgNetPort(), "iceAdapterGpgNetPort default should be 7237");
        assertEquals("INFO", config.logLevel(), "logLevel default should be INFO");
        assertTrue(config.logFile().isEmpty(), "logFile should default to empty Optional");
        assertTrue(
                config.playerIdOverride().isEmpty(),
                "playerIdOverride should default to empty OptionalInt");
    }
}
