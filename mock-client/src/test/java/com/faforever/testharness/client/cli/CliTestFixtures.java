package com.faforever.testharness.client.cli;

import java.util.stream.Stream;

/**
 * Minimal CLI argument fixtures shared across the {@code cli/} test classes. Mirrors the
 * package-private {@code TestFixtures} in {@code client.config} but lives here so the {@code cli/}
 * tests don't depend on a sibling package's package-private types.
 */
final class CliTestFixtures {

    static final String LOBBY_URL = "wss://lobby.faforever.xyz";
    static final String OAUTH_TOKEN_URL = "https://hydra.faforever.xyz/oauth2/token";
    static final String OAUTH_AUTH_ENDPOINT = "https://hydra.faforever.xyz/oauth2/auth";
    static final String OAUTH_REDIRECT_URI = "http://127.0.0.1";
    static final String OAUTH_SCOPES = "openid offline lobby";
    static final String OAUTH_CLIENT_ID = "95ecec08-29c1-4c48-ae0a-b000ff349cb8";
    static final String OAUTH_REFRESH_TOKEN = "test-refresh-token";
    static final String UNIQUE_ID = "00000000-0000-0000-0000-000000000000";
    static final String ICE_ADAPTER_BIN = "/bin/faf-ice-adapter";
    static final String MOCK_GAME_BIN = "/bin/mock-game";

    private CliTestFixtures() {}

    /** Required flags that satisfy {@link MockClientCli}'s required-check plus the auth-channel. */
    static String[] minimalRequiredFlags() {
        return new String[] {
            "--lobby-websocket-url=" + LOBBY_URL,
            "--oauth-token-url=" + OAUTH_TOKEN_URL,
            "--oauth-auth-endpoint=" + OAUTH_AUTH_ENDPOINT,
            "--oauth-redirect-uri=" + OAUTH_REDIRECT_URI,
            "--oauth-scopes=" + OAUTH_SCOPES,
            "--oauth-client-id=" + OAUTH_CLIENT_ID,
            "--oauth-refresh-token=" + OAUTH_REFRESH_TOKEN,
            "--unique-id=" + UNIQUE_ID,
            "--ice-adapter-binary-path=" + ICE_ADAPTER_BIN,
            "--mock-game-binary-path=" + MOCK_GAME_BIN,
        };
    }

    /**
     * Returns {@code [subcommand, ...minimalRequiredFlags()]}, suitable for driving {@code
     * CommandLine.execute(...)} where the leaf command is {@code subcommand} and the root's
     * required flags are all satisfied.
     */
    static String[] withSubcommand(final String subcommand) {
        return Stream.concat(Stream.of(subcommand), Stream.of(minimalRequiredFlags()))
                .toArray(String[]::new);
    }

    /**
     * Like {@link #withSubcommand(String)} but replaces {@code --ice-adapter-binary-path} with
     * {@code iceBinaryPath}. Tests that exercise {@code launch-ice}'s "binary not found" path pass
     * a guaranteed-absent path (e.g. one under a JUnit {@code @TempDir}) so the assertion does not
     * silently depend on {@link #ICE_ADAPTER_BIN} being absent on the host filesystem.
     */
    static String[] withSubcommand(final String subcommand, final String iceBinaryPath) {
        return Stream.concat(Stream.of(subcommand), Stream.of(minimalRequiredFlags()))
                .map(
                        arg ->
                                arg.startsWith("--ice-adapter-binary-path=")
                                        ? "--ice-adapter-binary-path=" + iceBinaryPath
                                        : arg)
                .toArray(String[]::new);
    }
}
