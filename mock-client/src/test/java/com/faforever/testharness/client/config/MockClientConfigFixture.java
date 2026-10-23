package com.faforever.testharness.client.config;

import java.net.URI;
import java.nio.file.Path;
import java.util.Optional;
import java.util.OptionalInt;

/**
 * The minimal valid {@link MockClientConfig} for tests that need a config but not a particular one,
 * so that only this class calls the record's constructor (#374). Each test class used to spell the
 * components out, and a branch that added a test class compiled on its own yet broke {@code
 * compileTestJava} once it met a branch that changed a component (#424 and #461).
 *
 * <p>The values only have to pass validation: no test reaches the endpoints or runs the binaries
 * through this config, and the default refresh-token file does not exist. The player login differs
 * from {@code SessionFixture}'s on purpose, so a launch that falls back to the config instead of
 * the session is caught (WBS-3.1.2.9). This is not the minimal configuration in {@link
 * TestFixtures}, which is the parse-level one and carries the CLI defaults.
 */
public final class MockClientConfigFixture {

    /** Refresh-token credentials, a fixed UID, and no game to host, join or queue for. */
    public static final MockClientConfig MINIMAL_CONFIG = builder().build();

    private MockClientConfigFixture() {}

    /**
     * Starts a config from {@link #MINIMAL_CONFIG}'s values.
     *
     * @return a new builder
     */
    public static Builder builder() {
        return new Builder();
    }

    /** {@link #MINIMAL_CONFIG} with the components tests vary; add a setter when one needs more. */
    public static final class Builder {

        private Path oauthRefreshTokenFile = Path.of("/nonexistent/test-refresh-token");
        private Optional<Path> oauthAccessTokenFile = Optional.empty();
        private Optional<GameHostConfig> hostConfig = Optional.empty();
        private Optional<GameJoinConfig> joinConfig = Optional.empty();
        private Optional<GameQueueConfig> queueConfig = Optional.empty();

        private Builder() {}

        /** May be {@code null}, for a config whose only credential is the access-token file. */
        public Builder oauthRefreshTokenFile(final Path file) {
            this.oauthRefreshTokenFile = file;
            return this;
        }

        public Builder oauthAccessTokenFile(final Optional<Path> file) {
            this.oauthAccessTokenFile = file;
            return this;
        }

        public Builder hostConfig(final Optional<GameHostConfig> config) {
            this.hostConfig = config;
            return this;
        }

        public Builder joinConfig(final Optional<GameJoinConfig> config) {
            this.joinConfig = config;
            return this;
        }

        public Builder queueConfig(final Optional<GameQueueConfig> config) {
            this.queueConfig = config;
            return this;
        }

        /**
         * Builds the config through the record's own validation.
         *
         * @return the config
         * @throws IllegalArgumentException if the overrides make an invalid config
         */
        public MockClientConfig build() {
            return new MockClientConfig(
                    URI.create("wss://ws.faforever.xyz"),
                    URI.create("https://hydra.faforever.xyz/oauth2/token"),
                    URI.create("https://hydra.faforever.xyz/oauth2/auth"),
                    URI.create("http://127.0.0.1"),
                    "openid offline lobby",
                    "95ecec08-29c1-4c48-ae0a-b000ff349cb8",
                    oauthRefreshTokenFile,
                    oauthAccessTokenFile,
                    Optional.of("00000000-0000-0000-0000-000000000000"),
                    "0.0.0-mock",
                    "faf-test-harness",
                    Optional.empty(),
                    Path.of("/bin/faf-ice-adapter"),
                    Path.of("/bin/mock-game"),
                    0,
                    0,
                    0,
                    0,
                    5,
                    "WARN",
                    Optional.empty(),
                    OptionalInt.empty(),
                    "Rhiza",
                    hostConfig,
                    joinConfig,
                    queueConfig,
                    0,
                    0,
                    -1);
        }
    }
}
