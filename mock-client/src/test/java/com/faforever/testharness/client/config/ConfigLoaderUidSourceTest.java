package com.faforever.testharness.client.config;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.file.Path;
import java.util.Arrays;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * Verifies the rules around passing the {@code --unique-id} and {@code --uid-binary-path} options:
 *
 * <ul>
 *   <li>At least one must be passed.
 *   <li>When both are passed at the same layer, {@code --unique-id} is ignored.
 *   <li>When they are passed at different layers, the one passed at a higher layer wins and the
 *       other is ignored.
 * </ul>
 */
final class ConfigLoaderUidSourceTest {
    /** Required fields but with no {@code --unique-id} or {@code --uid-binary-path}. */
    private static final String[] REQUIRED_NO_SOURCE =
            new String[] {
                "--lobby-websocket-url=" + TestFixtures.LOBBY_URL,
                "--oauth-token-url=" + TestFixtures.OAUTH_TOKEN_URL,
                "--oauth-auth-endpoint=" + TestFixtures.OAUTH_AUTH_ENDPOINT,
                "--oauth-redirect-uri=" + TestFixtures.OAUTH_REDIRECT_URI,
                "--oauth-scopes=" + TestFixtures.OAUTH_SCOPES,
                "--oauth-client-id=" + TestFixtures.OAUTH_CLIENT_ID,
                "--oauth-refresh-token-file=" + TestFixtures.OAUTH_REFRESH_TOKEN_FILE,
                "--ice-adapter-binary-path=" + TestFixtures.ICE_ADAPTER_BIN,
                "--mock-game-binary-path=" + TestFixtures.MOCK_GAME_BIN,
            };

    @Test
    public void uidBinaryPathSufficient() {
        Path uidPath = Path.of("./faf-uid");
        String[] args = concat(REQUIRED_NO_SOURCE, "--uid-binary-path=" + uidPath);

        MockClientConfig config = ConfigLoader.load(args, Map.of()).orElseThrow();

        assertEquals(uidPath, config.uidBinaryPath().orElse(null));
    }

    @Test
    public void uidBinaryPathWinsOverUniqueId() {
        Path uidPath = Path.of("./faf-uid");
        String[] args =
                concat(
                        REQUIRED_NO_SOURCE,
                        "--uid-binary-path=" + uidPath,
                        "--unique-id=00000000-0000-0000-000000000000");

        MockClientConfig config = ConfigLoader.load(args, Map.of()).orElseThrow();

        assertEquals(uidPath, config.uidBinaryPath().orElse(null));
        assertEquals(Optional.empty(), config.uniqueId());
    }

    @Test
    public void higherLayerWins() {
        String[] args = concat(REQUIRED_NO_SOURCE, "--unique-id=00000000-0000-0000-000000000000");
        MockClientConfig config =
                ConfigLoader.load(args, Map.of("FAF_MOCK_CLIENT_UID_BINARY_PATH", "./faf-uid"))
                        .orElseThrow();

        assertEquals(Optional.empty(), config.uidBinaryPath());
        assertEquals("00000000-0000-0000-000000000000", config.uniqueId().orElse(null));
    }

    private static String[] concat(final String[] a, final String... b) {
        return Stream.concat(Arrays.stream(a), Arrays.stream(b)).toArray(String[]::new);
    }
}
