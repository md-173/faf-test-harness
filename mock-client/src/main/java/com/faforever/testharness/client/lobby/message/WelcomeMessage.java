package com.faforever.testharness.client.lobby.message;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.Map;

/**
 * Inbound {@code welcome} payload (lobby-protocol-spec.md §3, §10.1) — the server's terminal
 * positive response to a successful {@code auth} request. Carries the authenticated player's
 * identity and ratings.
 *
 * <p>The identity is echoed twice on the wire: canonically inside {@code me}, and duplicated at the
 * top level ({@code id} / {@code login}) as a legacy of the AsyncAPI shape. The Mock Client treats
 * {@code me} as the source of truth — it is what feeds the ICE adapter's {@code --id} / {@code
 * --login} (json-rpc-spec.md §8.1) — so {@code me.id} / {@code me.login} are presence-checked while
 * the top-level duplicates are optional.
 *
 * <p>Decoded via Jackson directly by the consumer: {@code mapper.treeToValue(node,
 * WelcomeMessage.class)}. The post-welcome world-state messages ({@code player_info}, {@code
 * game_info}, {@code social}, {@code matchmaker_info}) are deliberately not modelled here — they
 * will land in 3.1.1.3 when the welcome state-sync consumer is implemented.
 *
 * @param me nested identity block (canonical id/login + ratings); required, and its id/login are
 *     presence-checked
 * @param currentTime server time in ISO 8601 (string, not parsed here); required
 * @param id top-level player ID — optional legacy duplicate of {@code me.id}
 * @param login top-level player login — optional legacy duplicate of {@code me.login}
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record WelcomeMessage(
        Me me, @JsonProperty("current_time") String currentTime, Integer id, String login) {

    /**
     * Compact canonical constructor — rejects a frame missing {@code me} or {@code current_time}.
     * The top-level {@code id} / {@code login} are optional legacy duplicates and are not checked;
     * the canonical identity lives in {@code me} (see {@link Me}).
     */
    public WelcomeMessage {
        if (me == null) {
            throw new IllegalArgumentException("welcome.me is required");
        }
        if (currentTime == null || currentTime.isBlank()) {
            throw new IllegalArgumentException("welcome.current_time is required");
        }
    }

    /**
     * Nested identity block from a {@code welcome} payload — the canonical source of the player's
     * id and login. Ratings are intentionally left as raw {@link JsonNode}s — the rating value
     * shape is non-trivial ({@code [mean, deviation]} array plus {@code number_of_games}) and
     * 3.1.1.3 is the natural place to refine that model.
     *
     * <p>{@code id} is a boxed {@link Integer} so an omitted field decodes to {@code null} and the
     * constructor throws rather than silently defaulting to {@code 0}.
     *
     * @param id player ID; required
     * @param login player login; required
     * @param clan clan tag (may be {@code null} or empty)
     * @param country ISO country code (may be {@code null} or empty)
     * @param ratings map of rating-type name → opaque rating value
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Me(
            Integer id, String login, String clan, String country, Map<String, JsonNode> ratings) {

        /** Compact canonical constructor — rejects a {@code me} block missing id or login. */
        public Me {
            if (id == null) {
                throw new IllegalArgumentException("welcome.me.id is required");
            }
            if (login == null || login.isBlank()) {
                throw new IllegalArgumentException("welcome.me.login is required");
            }
        }
    }
}
