package com.faforever.testharness.client.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import picocli.CommandLine;

/**
 * Covers AC#1: loading with nothing set fails with an error that names every missing required
 * field, and pairs with a positive case proving built-in {@code defaultValue} annotations populate
 * when the required fields are supplied.
 */
final class ConfigLoaderDefaultsOnlyTest {

    @Test
    void emptyArgsThrowsParameterExceptionListingEveryRequiredFlag() {
        // Required-ness is validated by the MockClientConfig record (not picocli's required=true),
        // so the env/file layers can reach subcommands; toValidatedConfig surfaces it as a
        // ParameterException whose message names every missing flag.
        CommandLine.ParameterException ex =
                assertThrows(
                        CommandLine.ParameterException.class,
                        () -> ConfigLoader.load(new String[] {}, Map.of()),
                        "Empty args + empty env should fail the required-field check.");

        String message = ex.getMessage();

        assertTrue(
                message.contains("--uid-binary-path"),
                "Missing-parameter message should name --uid-binary-path. Got: " + message);

        assertTrue(
                message.contains("--unique-id"),
                "Missing-parameter message should name --unique-id. Got: " + message);

        // These three default to the FAF test environment (#421), so an empty command line no
        // longer lacks them.
        List<String> defaultedFlags =
                List.of("--lobby-websocket-url", "--oauth-token-url", "--oauth-client-id");
        for (String defaulted : defaultedFlags) {
            assertFalse(
                    message.contains(defaulted),
                    "Missing-parameter message should not name " + defaulted + ". Got: " + message);
        }
    }

    @Test
    void aRefreshTokenFileAndFafUidAreAllTheRefreshChannelNeeds() {
        // #421: the one credential and the one binary only the operator can supply are enough;
        // the lobby URL, token URL and client id come from the built-in defaults.
        String[] args = {
            "--oauth-refresh-token-file=" + TestFixtures.OAUTH_REFRESH_TOKEN_FILE,
            "--uid-binary-path=./faf-uid",
        };

        MockClientConfig config = ConfigLoader.load(args, Map.of()).orElseThrow();

        assertEquals(URI.create(TestFixtures.DEFAULT_LOBBY_URL), config.lobbyWebSocketUrl());
        assertEquals(URI.create(TestFixtures.DEFAULT_OAUTH_TOKEN_URL), config.oauthTokenUrl());
        assertEquals(TestFixtures.DEFAULT_OAUTH_CLIENT_ID, config.oauthClientId());
    }

    @Test
    void anAccessTokenFileAndFafUidAreAllTheAccessTokenChannelNeeds() {
        // Before #421 this channel still needed --lobby-websocket-url, though no OAuth option.
        Path accessTokenFile = Path.of("/nonexistent/test-access-token");
        String[] args = {
            "--oauth-access-token-file=" + accessTokenFile, "--uid-binary-path=./faf-uid",
        };

        MockClientConfig config = ConfigLoader.load(args, Map.of()).orElseThrow();

        assertEquals(URI.create(TestFixtures.DEFAULT_LOBBY_URL), config.lobbyWebSocketUrl());
        assertEquals(Optional.of(accessTokenFile), config.oauthAccessTokenFile());
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
