package com.faforever.testharness.client.config;

import java.net.URI;
import java.nio.file.Path;
import java.util.Optional;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.ScopeType;

/**
 * The OAuth options of {@link MockClientCli}: the token and authorization endpoints, the public
 * client, and the two credential files. Mixed into the root command, so like every root option they
 * are accepted by every subcommand; they build the {@link OAuthConfig} group of {@link
 * MockClientConfig}.
 *
 * <p>Each field is named for its option in camel case, because the config-file key is the field
 * name.
 */
final class OAuthOptions {

    /** OAuth2 token endpoint used to acquire lobby access tokens. */
    @Option(
            names = "--oauth-token-url",
            scope = ScopeType.INHERIT,
            description = "OAuth2 token endpoint used to acquire lobby access tokens.")
    private URI oauthTokenUrl;

    /** OAuth2 authorization endpoint used by the one-time refresh-token bootstrap. */
    @Option(
            names = "--oauth-auth-endpoint",
            scope = ScopeType.INHERIT,
            description =
                    "OAuth2 authorization endpoint used by the one-time refresh-token bootstrap.")
    private URI oauthAuthEndpoint;

    /** Redirect URI registered on the OAuth client. */
    @Option(
            names = "--oauth-redirect-uri",
            scope = ScopeType.INHERIT,
            description = "Redirect URI registered on the OAuth client.")
    private URI oauthRedirectUri;

    /** Space-separated OAuth2 scopes (e.g. "openid offline lobby"). */
    @Option(
            names = "--oauth-scopes",
            scope = ScopeType.INHERIT,
            description = "Space-separated OAuth2 scopes (e.g. \"openid offline lobby\").")
    private String oauthScopes;

    /** OAuth2 public client identifier. */
    @Option(
            names = "--oauth-client-id",
            scope = ScopeType.INHERIT,
            description = "OAuth2 public client identifier.")
    private String oauthClientId;

    /** Path to a file holding the refresh token; rewritten atomically on each rotation. */
    @Option(
            names = "--oauth-refresh-token-file",
            scope = ScopeType.INHERIT,
            description =
                    "Path to a file holding the refresh token; rewritten atomically on each "
                            + "rotation.")
    private Path oauthRefreshTokenFile;

    /**
     * Path to a file holding a pre-signed access token, used verbatim (WBS-3.1.6.4, #325).
     *
     * <p>The alternative to {@code --oauth-refresh-token-file}, and mutually exclusive with it. A
     * file rather than a bare value so the token stays out of the process table and out of CI logs.
     */
    @Option(
            names = "--oauth-access-token-file",
            scope = ScopeType.INHERIT,
            description =
                    "Path to a file holding a pre-signed OAuth access token, used as-is with no "
                            + "exchange and no renewal. Mutually exclusive with "
                            + "--oauth-refresh-token-file.")
    private Path oauthAccessTokenFile;

    /**
     * The OAuth group from these options, with their own two credential files.
     *
     * @return the group, unvalidated: {@link MockClientConfig} checks it
     */
    OAuthConfig toConfig() {
        return toConfig(oauthRefreshTokenFile, Optional.ofNullable(oauthAccessTokenFile));
    }

    /**
     * The OAuth group from these options, with a credential the caller supplies instead of the two
     * {@code --oauth-*-token-file} options: the {@code session} command builds one config per peer
     * this way, each with its own account.
     *
     * @param refreshTokenFile the refresh-token file the group authenticates with, or {@code null}
     *     on the access-token channel
     * @param accessTokenFile the access-token file the group authenticates with, or empty on the
     *     refresh-token channel
     * @return the group, unvalidated: {@link MockClientConfig} checks it
     */
    OAuthConfig toConfig(final Path refreshTokenFile, final Optional<Path> accessTokenFile) {
        return new OAuthConfig(
                oauthTokenUrl,
                oauthAuthEndpoint,
                oauthRedirectUri,
                oauthScopes,
                oauthClientId,
                refreshTokenFile,
                accessTokenFile);
    }

    /**
     * Applies the documented precedence to the two OAuth credential channels before validation, so
     * that a higher layer <em>overrides</em> a lower one instead of colliding with it.
     *
     * <p>Without this, {@link MockClientConfig}'s conflict check sees only that both fields are
     * populated and rejects the run. That is wrong for every shape the repo actually ships: {@code
     * mock-client.example.json} carries {@code oauthRefreshTokenFile} and the runbook tells
     * operators to copy it, so supplying an access token by flag, or by {@code
     * FAF_MOCK_CLIENT_OAUTH_ACCESS_TOKEN_FILE} (the CI shape this card exists for), failed with
     * "two OAuth credential channels configured" rather than doing what the precedence contract
     * promises. Both were reproduced against a build of this branch.
     *
     * <p>Only a genuine tie still throws. Two channels at the same layer is an operator writing
     * both into one config file or exporting both variables, where there is no precedence to apply
     * and picking either would silently choose a failure mode they did not: the channels renew
     * differently, which is the whole reason the rejection exists.
     *
     * @param callerSpec the spec whose command line carries the layered provider
     */
    void dropShadowedCredentialChannel(final CommandSpec callerSpec) {
        if (oauthRefreshTokenFile == null || oauthAccessTokenFile == null) {
            return;
        }
        Optional<String> winner =
                MockClientCli.higherLayer(
                        callerSpec, "--oauth-access-token-file", "--oauth-refresh-token-file");
        if (winner.isEmpty()) {
            return;
        }
        if (winner.get().equals("--oauth-access-token-file")) {
            oauthRefreshTokenFile = null;
        } else {
            oauthAccessTokenFile = null;
        }
    }
}
