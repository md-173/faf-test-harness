package com.faforever.testharness.client.lobby.message;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.Map;

/**
 * Inbound {@code welcome} payload (lobby-protocol-spec.md §3, §10.1) — the terminal positive
 * response to {@link AuthMessage}. Includes the authenticated player's identity and ratings, which
 * are duplicated at the top level (legacy of the AsyncAPI shape — both forms are present in
 * production traffic).
 *
 * <p>The post-welcome world-state messages ({@code player_info}, {@code game_info}, {@code social},
 * {@code matchmaker_info}) are deliberately not modelled here — they will land in 3.1.1.3 when the
 * welcome state-sync consumer is implemented.
 *
 * @param me nested identity block; same id/login as the top-level fields
 * @param currentTime server time in ISO 8601 (string, not parsed here)
 * @param id authenticated player ID (also present as {@code me.id})
 * @param login authenticated player login (also present as {@code me.login})
 */
@LobbyCommand("welcome")
@JsonIgnoreProperties(ignoreUnknown = true)
public record WelcomeMessage(
        Me me, @JsonProperty("current_time") String currentTime, int id, String login)
        implements InboundMessage {

    /**
     * Nested identity block from a {@code welcome} payload. Ratings are intentionally left as raw
     * {@link JsonNode}s — the rating value shape is non-trivial ({@code [mean, deviation]} array
     * plus {@code number_of_games}) and 3.1.1.3 is the natural place to refine that model.
     *
     * @param id player ID
     * @param login player login
     * @param clan clan tag (may be {@code null} or empty)
     * @param country ISO country code (may be {@code null} or empty)
     * @param ratings map of rating-type name → opaque rating value
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Me(
            int id, String login, String clan, String country, Map<String, JsonNode> ratings) {}
}
