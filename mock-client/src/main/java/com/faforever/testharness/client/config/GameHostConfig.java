package com.faforever.testharness.client.config;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;

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
 * @param ratingMin minimum displayed rating for joining the hosted game; empty means unset
 * @param ratingMax maximum displayed rating for joining the hosted game; empty means unset
 * @param enforceRatingRange whether the server should enforce {@code ratingMin}/{@code ratingMax};
 *     defaults to {@code false}, matching the server's own default
 * @param gameOptions a set of game options to be passed to the mock game host. Can be empty.
 */
public record GameHostConfig(
        String title,
        String map,
        String mod,
        String visibility,
        Optional<Double> ratingMin,
        Optional<Double> ratingMax,
        boolean enforceRatingRange,
        Map<String, String> gameOptions) {

    /**
     * Validates that {@code title}, {@code map}, {@code mod}, and {@code visibility} are present —
     * this record only exists once the operator has opted into hosting, at which point those four
     * fields are required by the {@code game_host} request. The rating fields are genuinely
     * optional (lobby-protocol-spec.md §10.2), so only their {@link Optional} wrapper is checked
     * for non-null.
     *
     * @throws IllegalArgumentException if {@code title}, {@code map}, {@code mod}, or {@code
     *     visibility} is {@code null} or blank
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
        Objects.requireNonNull(ratingMin, "ratingMin");
        Objects.requireNonNull(ratingMax, "ratingMax");
    }
}
