package com.faforever.testharness.client.lobby;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.fail;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.ExecutionException;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;

/**
 * Live integration test for {@link LobbyAuthenticator} against the public FAF Hydra ({@code
 * hydra.faforever.xyz}). Tagged {@code integration} and enabled only when a real refresh token is
 * present at {@code .secrets/refresh_token.txt} (repo root or subproject cwd).
 *
 * <p>Unlike {@link LobbyConnectionLiveSmokeTest}, which hand-rolls its own token exchange, this
 * exercises the production {@link LobbyAuthenticator} end to end. If Hydra rejects the token
 * ({@code invalid_grant} / expired), re-bootstrap {@code .secrets/refresh_token.txt} per {@link
 * LobbyConnectionLiveSmokeTest}'s javadoc.
 */
@Tag("integration")
final class LobbyAuthenticatorLiveTest {

    private static final URI HYDRA_TOKEN = URI.create("https://hydra.faforever.xyz/oauth2/token");

    /** Seeded "FAF Classic Client (Python)" public OAuth client (spec §2). */
    private static final String FAF_OAUTH_CLIENT_ID = "95ecec08-29c1-4c48-ae0a-b000ff349cb8";

    /**
     * Obtains two access tokens in succession from the real OAuth server via the production {@link
     * LobbyAuthenticator}, exercising refresh-token rotation end to end. Running this rotates and
     * rewrites {@code .secrets/refresh_token.txt} twice.
     */
    @Test
    @EnabledIf("hasRefreshToken")
    void liveConnection() {
        Path tokenFile = findRefreshTokenFile();
        try {
            LobbyAuthenticator authenticator =
                    new LobbyAuthenticator(tokenFile, HYDRA_TOKEN, FAF_OAUTH_CLIENT_ID);
            AccessToken token = authenticator.obtain().get();
            assertNotNull(token.token());
            token = authenticator.obtain().get();
            assertNotNull(token.token());
        } catch (IOException | AuthenticationException | ExecutionException e) {
            fail(
                    String.format(
                            "Failed by %s due to %s",
                            e.getClass().getSimpleName(), e.getMessage()));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            fail("Authenticator thread got interrupted");
        }
    }

    /** Locate {@code .secrets/refresh_token.txt} relative to either the subproject or repo root. */
    private static Path findRefreshTokenFile() {
        String[] candidates = {".secrets/refresh_token.txt", "../.secrets/refresh_token.txt"};
        for (String candidate : candidates) {
            Path p = Path.of(candidate);
            if (Files.isRegularFile(p)) {
                return p;
            }
        }
        return null;
    }

    /**
     * {@code @EnabledIf} probe — enables the test only when a readable refresh token is present.
     */
    @SuppressWarnings("unused")
    static boolean hasRefreshToken() {
        Path p = findRefreshTokenFile();
        try {
            return p != null && Files.size(p) > 0;
        } catch (IOException e) {
            return false;
        }
    }
}
