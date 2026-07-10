package com.faforever.testharness.client.config;

import java.util.Objects;
import java.util.Optional;

/**
 * Join-an-existing-game settings (lobby-protocol-spec.md §4.2 / §10.2). Kept out of {@link
 * MockClientConfig} so join settings stay grouped, and so the whole group can be absent when the
 * mock client is configured to host a game rather than join one — see {@link
 * MockClientConfig#joinConfig()}.
 *
 * @param targetGameId ID of the existing game to join
 * @param password optional password sent alongside {@code game_join}; empty is encoded as a {@code
 *     null} password field, matching the protocol
 */
public record GameJoinConfig(int targetGameId, Optional<String> password) {

    /**
     * Guards against a {@code null} {@link Optional} slipping in via reflection or a future caller
     * — {@link Optional#empty()} is the correct way to express "no password".
     */
    public GameJoinConfig {
        Objects.requireNonNull(password, "password");
    }
}
