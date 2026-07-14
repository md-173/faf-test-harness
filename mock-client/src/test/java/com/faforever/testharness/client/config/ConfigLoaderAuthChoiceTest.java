package com.faforever.testharness.client.config;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

/**
 * Verifies the cross-field auth-choice rule after the WBS-2.2.10 / spec §2 migration:
 *
 * <ul>
 *   <li>A refresh-token file must be supplied — it is the only credential channel, since the
 *       rotated token must be persisted back on every use.
 *   <li>Stale password-grant fields ({@code oauthUsername}, {@code oauthPassword}, {@code
 *       oauthClientSecret}) are rejected with a deprecation error pointing at the spec.
 * </ul>
 */
final class ConfigLoaderAuthChoiceTest {

    /** Required fields only, no credential channel. */
    private static final String[] REQUIRED_NO_CREDS =
            new String[] {
                "--lobby-websocket-url=" + TestFixtures.LOBBY_URL,
                "--oauth-token-url=" + TestFixtures.OAUTH_TOKEN_URL,
                "--oauth-auth-endpoint=" + TestFixtures.OAUTH_AUTH_ENDPOINT,
                "--oauth-redirect-uri=" + TestFixtures.OAUTH_REDIRECT_URI,
                "--oauth-scopes=" + TestFixtures.OAUTH_SCOPES,
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

        String msg = ex.getMessage();
        assertTrue(
                msg.contains("no OAuth credentials supplied"),
                "Auth-choice violation should be self-describing. Got: " + msg);
        assertTrue(
                msg.contains("WBS-2.2.10"),
                "Auth-choice error should cite WBS-2.2.10. Got: " + msg);
        assertTrue(msg.contains("§2"), "Auth-choice error should cite spec §2. Got: " + msg);
    }

    @Test
    void literalRefreshTokenFlagRejectedAsUnknownOption() {
        // The literal --oauth-refresh-token flag was removed: Hydra rotates the refresh token on
        // every use and the rotated value must be persisted back, which only the file channel can
        // do. A literal value would silently break on the next run.
        String[] args = withExtra(REQUIRED_NO_CREDS, "--oauth-refresh-token=rt-value");

        assertThrows(CommandLine.ParameterException.class, () -> ConfigLoader.load(args, Map.of()));
    }

    @Test
    void refreshTokenFileSatisfiesAuthChoice() {
        String[] args =
                withExtra(REQUIRED_NO_CREDS, "--oauth-refresh-token-file=/tmp/refresh-token");

        MockClientConfig config = ConfigLoader.load(args, Map.of()).orElseThrow();

        assertNotNull(config);
    }

    @Test
    void staleUsernameInJsonRejectedWithDeprecationError(@TempDir final Path tempDir)
            throws Exception {
        String json =
                """
                {
                  "lobbyWebSocketUrl":     "%s",
                  "oauthTokenUrl":         "%s",
                  "oauthAuthEndpoint":     "%s",
                  "oauthRedirectUri":      "%s",
                  "oauthScopes":           "%s",
                  "oauthClientId":         "%s",
                  "oauthUsername":         "alice",
                  "uniqueId":              "%s",
                  "iceAdapterBinaryPath":  "%s",
                  "mockGameBinaryPath":    "%s"
                }
                """
                        .formatted(
                                TestFixtures.LOBBY_URL,
                                TestFixtures.OAUTH_TOKEN_URL,
                                TestFixtures.OAUTH_AUTH_ENDPOINT,
                                TestFixtures.OAUTH_REDIRECT_URI,
                                TestFixtures.OAUTH_SCOPES,
                                TestFixtures.OAUTH_CLIENT_ID,
                                TestFixtures.UNIQUE_ID,
                                TestFixtures.ICE_ADAPTER_BIN,
                                TestFixtures.MOCK_GAME_BIN);
        Path file = tempDir.resolve("stale.json");
        Files.writeString(file, json);

        CommandLine.ParameterException ex =
                assertThrows(
                        CommandLine.ParameterException.class,
                        () ->
                                ConfigLoader.load(
                                        new String[] {"--config", file.toString()}, Map.of()));

        assertDeprecationMessage(ex.getMessage(), "oauthUsername");
    }

    @Test
    void stalePasswordInJsonRejectedWithDeprecationError(@TempDir final Path tempDir)
            throws Exception {
        String json =
                """
                {
                  "oauthPassword": "hunter2"
                }
                """;
        Path file = tempDir.resolve("stale.json");
        Files.writeString(file, json);

        CommandLine.ParameterException ex =
                assertThrows(
                        CommandLine.ParameterException.class,
                        () ->
                                ConfigLoader.load(
                                        new String[] {"--config", file.toString()}, Map.of()));

        assertDeprecationMessage(ex.getMessage(), "oauthPassword");
    }

    @Test
    void staleClientSecretInJsonRejectedWithDeprecationError(@TempDir final Path tempDir)
            throws Exception {
        String json =
                """
                {
                  "oauthClientSecret": "topsecret"
                }
                """;
        Path file = tempDir.resolve("stale.json");
        Files.writeString(file, json);

        CommandLine.ParameterException ex =
                assertThrows(
                        CommandLine.ParameterException.class,
                        () ->
                                ConfigLoader.load(
                                        new String[] {"--config", file.toString()}, Map.of()));

        assertDeprecationMessage(ex.getMessage(), "oauthClientSecret");
    }

    @Test
    void staleUsernameInEnvRejectedWithDeprecationError() {
        Map<String, String> env = new LinkedHashMap<>(TestFixtures.minimalRequiredEnv());
        env.put("FAF_MOCK_CLIENT_OAUTH_USERNAME", "alice");

        CommandLine.ParameterException ex =
                assertThrows(
                        CommandLine.ParameterException.class,
                        () -> ConfigLoader.load(new String[] {}, env));

        assertDeprecationMessage(ex.getMessage(), "FAF_MOCK_CLIENT_OAUTH_USERNAME");
    }

    @Test
    void stalePasswordInEnvRejectedWithDeprecationError() {
        Map<String, String> env = new LinkedHashMap<>(TestFixtures.minimalRequiredEnv());
        env.put("FAF_MOCK_CLIENT_OAUTH_PASSWORD", "hunter2");

        CommandLine.ParameterException ex =
                assertThrows(
                        CommandLine.ParameterException.class,
                        () -> ConfigLoader.load(new String[] {}, env));

        assertDeprecationMessage(ex.getMessage(), "FAF_MOCK_CLIENT_OAUTH_PASSWORD");
    }

    @Test
    void staleClientSecretInEnvRejectedWithDeprecationError() {
        Map<String, String> env = new LinkedHashMap<>(TestFixtures.minimalRequiredEnv());
        env.put("FAF_MOCK_CLIENT_OAUTH_CLIENT_SECRET", "topsecret");

        CommandLine.ParameterException ex =
                assertThrows(
                        CommandLine.ParameterException.class,
                        () -> ConfigLoader.load(new String[] {}, env));

        assertDeprecationMessage(ex.getMessage(), "FAF_MOCK_CLIENT_OAUTH_CLIENT_SECRET");
    }

    @Test
    void staleUsernameFlagOnCliRejectedAsUnknownOption() {
        // CLI flags --oauth-username / --oauth-password / --oauth-client-secret were removed; the
        // user gets picocli's standard "unknown option" error, which is friendly enough for a CLI
        // flag (the README and --help no longer list them).
        String[] args = withExtra(REQUIRED_NO_CREDS, "--oauth-username=alice");

        assertThrows(CommandLine.ParameterException.class, () -> ConfigLoader.load(args, Map.of()));
    }

    private static void assertDeprecationMessage(final String message, final String token) {
        assertTrue(
                message.contains(token),
                "Deprecation error should name the offending key. Got: " + message);
        assertTrue(
                message.contains("WBS-2.2.10"),
                "Deprecation error should cite WBS-2.2.10. Got: " + message);
        assertTrue(
                message.contains("§2"), "Deprecation error should cite spec §2. Got: " + message);
    }

    private static String[] withExtra(final String[] base, final String extra) {
        return concat(base, new String[] {extra});
    }

    private static String[] concat(final String[] a, final String[] b) {
        return Stream.concat(Arrays.stream(a), Arrays.stream(b)).toArray(String[]::new);
    }
}
