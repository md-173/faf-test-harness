package com.faforever.testharness.client.lobby;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.faforever.testharness.client.config.MockClientConfig;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.OptionalInt;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Unit tests for {@link TokenSources}: which credential channel a configuration selects.
 *
 * <p>Two exist — a refresh-token file exchanged at Hydra, and a pre-signed access-token file used
 * verbatim (WBS-3.1.6.4, #325). They are mutually exclusive by config validation rather than by a
 * precedence rule here, which is the point of the last case below.
 */
final class TokenSourcesTest {

    /**
     * Build a {@link MockClientConfig} with the credential file overridden. Other fields use
     * placeholder values that pass record validation.
     */
    private static MockClientConfig configWith(final Path oauthRefreshTokenFile) {
        return configWith(oauthRefreshTokenFile, Optional.empty());
    }

    /** As above, with the pre-signed access-token channel chosen instead or as well. */
    private static MockClientConfig configWith(
            final Path oauthRefreshTokenFile, final Optional<Path> oauthAccessTokenFile) {
        return new MockClientConfig(
                URI.create("wss://lobby.faforever.xyz"),
                URI.create("https://hydra.faforever.xyz/oauth2/token"),
                URI.create("https://hydra.faforever.xyz/oauth2/auth"),
                URI.create("http://127.0.0.1"),
                "openid offline lobby",
                "95ecec08-29c1-4c48-ae0a-b000ff349cb8",
                oauthRefreshTokenFile,
                oauthAccessTokenFile,
                "00000000-0000-0000-0000-000000000000",
                "0.0.0-mock",
                "faf-test-harness",
                Optional.empty(),
                Path.of("/bin/faf-ice-adapter"),
                Path.of("/bin/mock-game"),
                7236,
                7237,
                7238,
                0,
                5,
                "INFO",
                Optional.empty(),
                OptionalInt.empty(),
                "mock-client",
                Optional.empty(),
                Optional.empty(),
                0);
    }

    @Test
    void refreshTokenFileFallsThroughToAuthenticator(@TempDir final Path dir) throws Exception {
        Path refreshFile = dir.resolve("refresh.txt");
        Files.writeString(refreshFile, "refresh-token");

        MockClientConfig config = configWith(refreshFile);
        TokenSource source = TokenSources.fromConfig(config);
        assertInstanceOf(LobbyAuthenticator.class, source);
    }

    /**
     * The WBS-3.1.6.4 channel: a pre-signed token is used as-is, with no exchange. This is what
     * lets a lobby-facing run happen without a rotating per-developer secret.
     */
    @Test
    void accessTokenFileGivesAStaticSource(@TempDir final Path dir) throws Exception {
        Path accessFile = dir.resolve("access.jwt");
        Files.writeString(accessFile, "pre-signed-token");

        TokenSource source = TokenSources.fromConfig(configWith(null, Optional.of(accessFile)));

        assertInstanceOf(StaticTokenSource.class, source);
        assertEquals("pre-signed-token", source.obtain().get().token());
    }

    /**
     * A trailing newline is what `echo "$TOKEN" > token.jwt` and most CI secret-to-file steps
     * produce, and the lobby would reject it as part of the bearer value.
     */
    @Test
    void aTrailingNewlineIsNotPartOfTheToken(@TempDir final Path dir) throws Exception {
        Path accessFile = dir.resolve("access.jwt");
        Files.writeString(accessFile, "pre-signed-token\n");

        TokenSource source = TokenSources.fromConfig(configWith(null, Optional.of(accessFile)));

        assertEquals("pre-signed-token", source.obtain().get().token());
    }

    /** An empty file is a misconfiguration, not an empty token to send and have rejected. */
    @Test
    void anEmptyAccessTokenFileFailsWithAuthenticationException(@TempDir final Path dir)
            throws Exception {
        Path accessFile = dir.resolve("access.jwt");
        Files.writeString(accessFile, "   \n");

        MockClientConfig config = configWith(null, Optional.of(accessFile));

        AuthenticationException e =
                assertThrows(AuthenticationException.class, () -> TokenSources.fromConfig(config));
        assertTrue(e.getMessage().contains("empty"), e.getMessage());
    }

    /** Same treatment as an unreadable refresh-token file: one failure type for callers. */
    @Test
    void anUnreadableAccessTokenFileFailsWithAuthenticationException(@TempDir final Path dir) {
        MockClientConfig config = configWith(null, Optional.of(dir.resolve("does-not-exist.jwt")));

        AuthenticationException e =
                assertThrows(AuthenticationException.class, () -> TokenSources.fromConfig(config));
        assertInstanceOf(IOException.class, e.getCause());
    }

    /**
     * Configuring both is rejected at config load, so this factory never has to choose. Asserted
     * here because the alternative — a precedence rule — is what this design deliberately avoids:
     * the two channels fail differently, and picking one silently would hand the operator a failure
     * mode they did not choose.
     */
    @Test
    void configuringBothChannelsIsRejectedBeforeThisFactoryIsReached(@TempDir final Path dir)
            throws Exception {
        Path refreshFile = dir.resolve("refresh.txt");
        Files.writeString(refreshFile, "refresh-token");
        Path accessFile = dir.resolve("access.jwt");
        Files.writeString(accessFile, "pre-signed-token");

        IllegalArgumentException e =
                assertThrows(
                        IllegalArgumentException.class,
                        () -> configWith(refreshFile, Optional.of(accessFile)));

        assertTrue(
                e.getMessage().contains("--oauth-refresh-token-file")
                        && e.getMessage().contains("--oauth-access-token-file"),
                "the error must name both channels: " + e.getMessage());
    }

    @Test
    void unreadableRefreshTokenFileFailsWithAuthenticationException(@TempDir final Path dir) {
        // A configured-but-unreadable refresh-token file is surfaced as AuthenticationException
        // (wrapping the underlying IOException) so callers handle a single failure type.
        Path missing = dir.resolve("does-not-exist.txt");
        MockClientConfig config = configWith(missing);

        AuthenticationException e =
                assertThrows(AuthenticationException.class, () -> TokenSources.fromConfig(config));
        assertInstanceOf(IOException.class, e.getCause());
    }
}
