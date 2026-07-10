package com.faforever.testharness.client.config;

/**
 * Host-a-custom-game settings (lobby-protocol-spec.md §4.1 / §10.2). Kept out of {@link
 * MockClientConfig} so hosting settings stay grouped, and so the whole group can be absent when the
 * mock client is configured to join a game rather than host one — see {@link
 * MockClientConfig#hostConfig()}.
 *
 * @param title ASCII-only game title advertised to the lobby
 * @param map map folder name
 * @param mod featured-mod technical name (e.g. {@code "faf"})
 * @param visibility {@code "public"} or {@code "friends"}
 */
public record GameHostConfig(String title, String map, String mod, String visibility) {

    /**
     * Validates that every field is present — this record only exists once the operator has opted
     * into hosting, at which point all four fields are required by the {@code game_host} request.
     *
     * @throws IllegalArgumentException if any field is {@code null} or blank
     */
    public GameHostConfig {
        if (title == null || title.isBlank()) {
            throw new IllegalArgumentException(
                    "--host-title must not be blank when hosting a game");
        }
        if (map == null || map.isBlank()) {
            throw new IllegalArgumentException("--host-map must not be blank when hosting a game");
        }
        if (mod == null || mod.isBlank()) {
            throw new IllegalArgumentException("--host-mod must not be blank when hosting a game");
        }
        if (visibility == null || visibility.isBlank()) {
            throw new IllegalArgumentException(
                    "--host-visibility must not be blank when hosting a game");
        }
    }
}
