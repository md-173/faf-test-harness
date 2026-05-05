package com.faforever.testharness.client.cli;

import java.util.stream.Stream;

/**
 * Minimal CLI argument fixtures shared across the {@code cli/} test classes. Mirrors the
 * package-private {@code TestFixtures} in {@code client.config} but lives here so the {@code cli/}
 * tests don't depend on a sibling package's package-private types.
 */
final class CliTestFixtures {

    static final String LOBBY_URL = "ws://localhost/ws";
    static final String OAUTH_TOKEN_URL = "http://localhost:4444/oauth2/token";
    static final String OAUTH_CLIENT_ID = "faf-client";
    static final String OAUTH_ACCESS_TOKEN = "test-token";
    static final String UNIQUE_ID = "00000000-0000-0000-0000-000000000000";
    static final String ICE_ADAPTER_BIN = "/bin/faf-ice-adapter";
    static final String MOCK_GAME_BIN = "/bin/mock-game";

    private CliTestFixtures() {}

    /** The seven required flags that satisfy {@link MockClientCli}'s required-check. */
    static String[] minimalRequiredFlags() {
        return new String[] {
            "--lobby-websocket-url=" + LOBBY_URL,
            "--oauth-token-url=" + OAUTH_TOKEN_URL,
            "--oauth-client-id=" + OAUTH_CLIENT_ID,
            "--oauth-access-token=" + OAUTH_ACCESS_TOKEN,
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
}
