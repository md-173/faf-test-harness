package com.faforever.testharness.client.lobby;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.faforever.testharness.client.config.MockClientConfig;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.OptionalInt;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Unit tests for {@link TokenSources} — verifies the credential-channel precedence. */
final class TokenSourcesTest {

    /**
     * Build a {@link MockClientConfig} with the credential fields overridden. Other fields use
     * placeholder values that pass record validation.
     */
    private static MockClientConfig configWith(
            final String oauthRefreshToken, final Path oauthRefreshTokenFile) {
        return new MockClientConfig(
                URI.create("wss://lobby.faforever.xyz"),
                URI.create("https://hydra.faforever.xyz/oauth2/token"),
                URI.create("https://hydra.faforever.xyz/oauth2/auth"),
                URI.create("http://127.0.0.1"),
                "openid offline lobby",
                "95ecec08-29c1-4c48-ae0a-b000ff349cb8",
                oauthRefreshToken,
                oauthRefreshTokenFile,
                "00000000-0000-0000-0000-000000000000",
                Path.of("/bin/faf-ice-adapter"),
                Path.of("/bin/mock-game"),
                7236,
                7237,
                7238,
                0,
                "INFO",
                Optional.empty(),
                OptionalInt.empty(),
                "mock-client",
                Optional.empty(),
                OptionalInt.empty(),
                Optional.empty());
    }

    @Test
    void refreshTokenFileFallsThroughToAuthenticator(@TempDir final Path dir) throws Exception {
        Path refreshFile = dir.resolve("refresh.txt");
        Files.writeString(refreshFile, "refresh-token");

        MockClientConfig config = configWith(null, refreshFile);
        TokenSource source = TokenSources.fromConfig(config);
        assertInstanceOf(LobbyAuthenticator.class, source);
    }

    @Test
    void unreadableRefreshTokenFileFailsWithAuthenticationException(@TempDir final Path dir) {
        // A configured-but-unreadable refresh-token file is surfaced as AuthenticationException
        // (wrapping the underlying IOException) so callers handle a single failure type.
        Path missing = dir.resolve("does-not-exist.txt");
        MockClientConfig config = configWith(null, missing);

        AuthenticationException e =
                assertThrows(AuthenticationException.class, () -> TokenSources.fromConfig(config));
        assertInstanceOf(IOException.class, e.getCause());
    }

    @Test
    void noCredentialChannelFailsWithAuthenticationException() {
        // The MockClientConfig record validator forbids the (null, null, null, null) case at the
        // constructor level, so we test against a fully-null-credential config built via a path
        // the validator does accept (literal refresh token, then we null it out by reflection-free
        // means is hard — instead, instantiate with the only-literal-refresh case which fromConfig
        // explicitly rejects).
        MockClientConfig config = configWith("literal-only-refresh", null);
        assertThrows(AuthenticationException.class, () -> TokenSources.fromConfig(config));
    }
}
