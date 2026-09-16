package com.faforever.testharness.client.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
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
 *   <li>A credential channel must be supplied. There are two: a refresh-token file, whose rotated
 *       token is persisted back on every use, and a pre-signed access-token file used verbatim
 *       (WBS-3.1.6.4). Supplying neither is an error; supplying both at the <em>same</em> layer is
 *       too, because they renew differently and there is no precedence to separate them.
 *   <li>Across layers the documented precedence applies — config file, then {@code
 *       FAF_MOCK_CLIENT_*}, then CLI flags — so a higher layer overrides the other channel rather
 *       than colliding with it.
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

    /** Writes a throwaway credential file and returns its path. */
    private Path credentialFile(final Path dir, final String name) throws Exception {
        return Files.writeString(dir.resolve(name), "not-a-real-credential");
    }

    /** A config file supplying only {@code oauthRefreshTokenFile}, as the shipped example does. */
    private Path configWithRefreshToken(final Path dir, final Path refreshToken) throws Exception {
        return Files.writeString(
                dir.resolve("cfg.json"),
                "{\"oauthRefreshTokenFile\": \""
                        + refreshToken.toString().replace("\\", "\\\\")
                        + "\"}");
    }

    @Test
    void accessTokenOnTheCliOverridesARefreshTokenFromTheConfigFile(@TempDir Path dir)
            throws Exception {
        // The shape the repo ships: mock-client.example.json carries oauthRefreshTokenFile and the
        // runbook says to copy it, so supplying an access token by flag has to override it rather
        // than collide. This failed with "two OAuth credential channels configured" before the
        // precedence fix.
        Path refresh = credentialFile(dir, "refresh.txt");
        Path access = credentialFile(dir, "access.jwt");
        String[] args =
                concat(
                        REQUIRED_NO_CREDS,
                        new String[] {
                            "--config=" + configWithRefreshToken(dir, refresh),
                            "--oauth-access-token-file=" + access
                        });

        MockClientConfig config = ConfigLoader.load(args, Map.of()).orElseThrow();

        assertEquals(access, config.oauthAccessTokenFile().orElse(null));
        assertNull(config.oauthRefreshTokenFile(), "the CLI flag must win over the config file");
    }

    @Test
    void accessTokenFromTheEnvironmentOverridesARefreshTokenFromTheConfigFile(@TempDir Path dir)
            throws Exception {
        // The CI shape, and this card's stated motivation.
        Path refresh = credentialFile(dir, "refresh.txt");
        Path access = credentialFile(dir, "access.jwt");
        String[] args =
                concat(
                        REQUIRED_NO_CREDS,
                        new String[] {"--config=" + configWithRefreshToken(dir, refresh)});

        MockClientConfig config =
                ConfigLoader.load(
                                args,
                                Map.of(
                                        "FAF_MOCK_CLIENT_OAUTH_ACCESS_TOKEN_FILE",
                                        access.toString()))
                        .orElseThrow();

        assertEquals(access, config.oauthAccessTokenFile().orElse(null));
        assertNull(config.oauthRefreshTokenFile(), "the env var must win over the config file");
    }

    @Test
    void refreshTokenOnTheCliOverridesAnAccessTokenFromTheEnvironment(@TempDir Path dir)
            throws Exception {
        // Precedence runs both ways, or it is not precedence.
        Path refresh = credentialFile(dir, "refresh.txt");
        Path access = credentialFile(dir, "access.jwt");
        String[] args =
                concat(REQUIRED_NO_CREDS, new String[] {"--oauth-refresh-token-file=" + refresh});

        MockClientConfig config =
                ConfigLoader.load(
                                args,
                                Map.of(
                                        "FAF_MOCK_CLIENT_OAUTH_ACCESS_TOKEN_FILE",
                                        access.toString()))
                        .orElseThrow();

        assertEquals(refresh, config.oauthRefreshTokenFile());
        assertTrue(
                config.oauthAccessTokenFile().isEmpty(), "the CLI flag must win over the env var");
    }

    @Test
    void bothChannelsAtTheSameLayerStillThrows(@TempDir Path dir) throws Exception {
        // The case the rejection exists for: no precedence to apply, and the two channels renew
        // differently, so picking either would choose a failure mode the operator did not.
        Path refresh = credentialFile(dir, "refresh.txt");
        Path access = credentialFile(dir, "access.jwt");
        String[] args =
                concat(
                        REQUIRED_NO_CREDS,
                        new String[] {
                            "--oauth-refresh-token-file=" + refresh,
                            "--oauth-access-token-file=" + access
                        });

        CommandLine.ParameterException ex =
                assertThrows(
                        CommandLine.ParameterException.class,
                        () -> ConfigLoader.load(args, Map.of()));

        assertTrue(
                ex.getMessage().contains("two OAuth credential channels configured"),
                "the tie must still be rejected, and say so. Got: " + ex.getMessage());
    }

    @Test
    void theAccessTokenChannelNeedsNothingFromTheRefreshBootstrap(@TempDir Path dir)
            throws Exception {
        // The shape an operator can actually use, and the one the release needs: nothing here
        // supplies any of the five refresh-bootstrap settings. Before this card the same argv
        // exited 2 demanding --oauth-auth-endpoint, --oauth-redirect-uri and --oauth-scopes.
        // The live run against the test lobby carried placeholders for those three, so it
        // showed they are not read, not that this exact argv has been run end to end.
        Path access = credentialFile(dir, "access.jwt");
        String[] args =
                new String[] {
                    "--lobby-websocket-url=" + TestFixtures.LOBBY_URL,
                    "--unique-id=" + TestFixtures.UNIQUE_ID,
                    "--ice-adapter-binary-path=" + TestFixtures.ICE_ADAPTER_BIN,
                    "--mock-game-binary-path=" + TestFixtures.MOCK_GAME_BIN,
                    "--oauth-access-token-file=" + access
                };

        MockClientConfig config = ConfigLoader.load(args, Map.of()).orElseThrow();

        assertEquals(access, config.oauthAccessTokenFile().orElse(null));
    }

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
