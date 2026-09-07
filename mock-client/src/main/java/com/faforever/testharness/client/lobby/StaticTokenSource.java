package com.faforever.testharness.client.lobby;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;

/**
 * A {@link TokenSource} that hands out a token someone else signed (WBS-3.1.6.4, #325).
 *
 * <p>No network exchange, no rotation, no file rewriting. It reads the token once at construction
 * and returns it for the life of the process.
 *
 * <p><b>Why this exists.</b> Every lobby-facing run currently needs {@code
 * --oauth-refresh-token-file} pointing at a real, rotating secret that only the developer who
 * created it holds. That is the difference between a live test one person can run and one CI can: a
 * statically signed JWT has no rotation to lose, no browser bootstrap to repeat, and no
 * per-developer state.
 *
 * <p><b>It cannot renew, and that is the failure mode operators will hit.</b> A refresh-token
 * source notices an expired token and exchanges for another; this one has nothing to exchange. So
 * an expired static token is not caught here — it is sent, and the lobby rejects it. That is
 * deliberate: the lobby's own rejection says the token is bad far more precisely than a local
 * expiry guess could, since only the issuer knows what it signed. What this class must not do is
 * turn that into a generic failure, so it does nothing but hand the token over.
 *
 * <p>The token is read from a file rather than taken as a flag value so it stays out of the process
 * table and out of CI logs — {@code ps} and a build log both show a command line.
 */
public final class StaticTokenSource implements TokenSource {

    /**
     * Expiry reported for the token. Zero, meaning "unknown to this process": the value is only
     * ever used to decide whether to renew, and this source cannot, so claiming an expiry would be
     * inventing one. See the class javadoc on why the lobby is the right thing to learn it from.
     */
    private static final long UNKNOWN_EXPIRY = 0L;

    /** The token read at construction; returned unchanged for the life of this source. */
    private final AccessToken token;

    /**
     * Reads the pre-signed token from {@code file}.
     *
     * @param file path to a file whose contents are the access token
     * @throws AuthenticationException if the file cannot be read or holds nothing usable
     */
    public StaticTokenSource(final Path file) {
        final String contents;
        try {
            contents = Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new AuthenticationException("could not read OAuth access-token file: " + file, e);
        }
        // Trimmed because the usual way to produce this file is `echo "$TOKEN" > token.jwt` or a
        // CI secret written to disk, and both leave a trailing newline that the lobby would reject
        // as part of the bearer value.
        String trimmed = contents.strip();
        if (trimmed.isEmpty()) {
            throw new AuthenticationException(
                    "OAuth access-token file is empty: "
                            + file
                            + ". It must contain the access token and nothing else.");
        }
        this.token = new AccessToken(trimmed, UNKNOWN_EXPIRY);
    }

    /**
     * Returns the configured token, always. Completes immediately and never touches the network.
     *
     * @return an already-completed future holding the pre-signed token
     */
    @Override
    public CompletableFuture<AccessToken> obtain() {
        return CompletableFuture.completedFuture(token);
    }
}
