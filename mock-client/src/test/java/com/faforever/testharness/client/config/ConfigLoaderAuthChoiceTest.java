package com.faforever.testharness.client.config;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import picocli.CommandLine;

/**
 * Verifies the cross-field auth-choice rule: every successful load must supply either
 * an OAuth token (access-token literal or token-file path) or the full password-grant
 * trio (username + password + client secret). Missing credentials produce a clear
 * {@link CommandLine.ParameterException}.
 */
final class ConfigLoaderAuthChoiceTest {

    /** Required fields only, no OAuth credentials. */
    private static final String[] REQUIRED_NO_CREDS = new String[] {
        "--lobby-websocket-url=" + TestFixtures.LOBBY_URL,
        "--oauth-token-url=" + TestFixtures.OAUTH_TOKEN_URL,
        "--oauth-client-id=" + TestFixtures.OAUTH_CLIENT_ID,
        "--unique-id=" + TestFixtures.UNIQUE_ID,
        "--ice-adapter-binary-path=" + TestFixtures.ICE_ADAPTER_BIN,
        "--mock-game-binary-path=" + TestFixtures.MOCK_GAME_BIN,
    };

    @Test
    void noCredentialsThrowsParameterException() {
        CommandLine.ParameterException ex =
                assertThrows(
                        CommandLine.ParameterException.class,
                        () -> ConfigLoader.load(REQUIRED_NO_CREDS, Map.of()));

        assertTrue(
                ex.getMessage().contains("no OAuth credentials supplied"),
                "Auth-choice violation should be self-describing. Got: " + ex.getMessage());
    }

    @Test
    void accessTokenAloneSatisfiesAuthChoice() {
        String[] args = withExtra(REQUIRED_NO_CREDS, "--oauth-access-token=abc");

        MockClientConfig config = ConfigLoader.load(args, Map.of()).orElseThrow();

        assertNotNull(config);
    }

    @Test
    void tokenFileAloneSatisfiesAuthChoice() {
        String[] args = withExtra(REQUIRED_NO_CREDS, "--oauth-token-file=/tmp/token.jwt");

        MockClientConfig config = ConfigLoader.load(args, Map.of()).orElseThrow();

        assertNotNull(config);
    }

    @Test
    void fullPasswordTrioSatisfiesAuthChoice() {
        String[] args =
                concat(
                        REQUIRED_NO_CREDS,
                        new String[] {
                            "--oauth-username=alice",
                            "--oauth-password=hunter2",
                            "--oauth-client-secret=topsecret",
                        });

        MockClientConfig config = ConfigLoader.load(args, Map.of()).orElseThrow();

        assertNotNull(config);
    }

    @Test
    void usernameOnlyDoesNotSatisfyAuthChoice() {
        String[] args = withExtra(REQUIRED_NO_CREDS, "--oauth-username=alice");

        assertThrows(
                CommandLine.ParameterException.class,
                () -> ConfigLoader.load(args, Map.of()));
    }

    @Test
    void usernameAndPasswordWithoutClientSecretDoesNotSatisfyAuthChoice() {
        String[] args =
                concat(
                        REQUIRED_NO_CREDS,
                        new String[] {
                            "--oauth-username=alice",
                            "--oauth-password=hunter2",
                        });

        assertThrows(
                CommandLine.ParameterException.class,
                () -> ConfigLoader.load(args, Map.of()));
    }

    private static String[] withExtra(final String[] base, final String extra) {
        return concat(base, new String[] {extra});
    }

    private static String[] concat(final String[] a, final String[] b) {
        return Stream.concat(Arrays.stream(a), Arrays.stream(b)).toArray(String[]::new);
    }
}