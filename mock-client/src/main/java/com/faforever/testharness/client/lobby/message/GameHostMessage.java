package com.faforever.testharness.client.lobby.message;

import com.fasterxml.jackson.annotation.JsonGetter;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Outbound {@code game_host} request (lobby-protocol-spec.md §4.1, §10.2) — sent by the Mock Client
 * from {@code IDLE} to host a custom game, advertising it to the lobby.
 *
 * <p>{@code command} is always {@code "game_host"} on the wire, so it is not a field on this record
 * — {@link #command()} reports the constant directly, which rules out a caller ever constructing a
 * message with the wrong command. Every other nullable field is omitted from the wire frame when
 * {@code null} rather than serialised as JSON {@code null} (e.g. {@code password} when the game
 * isn't password-protected).
 *
 * <p>Encoded via Jackson directly by the sender: {@code mapper.valueToTree(message)}.
 *
 * @param title ASCII-only game title; required by the spec
 * @param visibility {@code "public"} or {@code "friends"}; required by the spec
 * @param mod featured-mod technical name (e.g. {@code "faf"}); required by the spec
 * @param mapname map folder name; required by the spec
 * @param password required only when {@code visibility} is password-protected; {@code null}
 *     otherwise
 * @param ratingMin minimum displayed rating for joining; {@code null} means unset
 * @param ratingMax maximum displayed rating for joining; {@code null} means unset
 * @param enforceRatingRange whether the server should enforce {@code ratingMin}/{@code ratingMax}
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record GameHostMessage(
        String title,
        String visibility,
        String mod,
        String mapname,
        String password,
        @JsonProperty("rating_min") Double ratingMin,
        @JsonProperty("rating_max") Double ratingMax,
        @JsonProperty("enforce_rating_range") boolean enforceRatingRange) {

    /**
     * Validates the fields the spec marks required.
     *
     * @throws IllegalArgumentException if {@code title}, {@code visibility}, {@code mod}, or {@code
     *     mapname} is {@code null} or blank
     */
    public GameHostMessage {
        if (title == null || title.isBlank()) {
            throw new IllegalArgumentException("title must not be blank");
        }
        if (visibility == null || visibility.isBlank()) {
            throw new IllegalArgumentException("visibility must not be blank");
        }
        if (mod == null || mod.isBlank()) {
            throw new IllegalArgumentException("mod must not be blank");
        }
        if (mapname == null || mapname.isBlank()) {
            throw new IllegalArgumentException("mapname must not be blank");
        }
    }

    /**
     * Always {@code "game_host"} on the wire.
     *
     * @return the literal {@code "game_host"}
     */
    @JsonGetter("command")
    public String command() {
        return "game_host";
    }
}
