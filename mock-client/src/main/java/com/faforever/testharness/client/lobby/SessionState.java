package com.faforever.testharness.client.lobby;

import com.faforever.testharness.client.lobby.message.WelcomeMessage;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.Map;

/**
 * Immutable app-facing session identity — "who am I" for the rest of the Mock Client. Built from a
 * decoded {@link WelcomeMessage} (lobby-protocol-spec.md §3, §10.1) after a successful auth
 * handshake, it exposes the subset of the welcome payload that downstream components need and is
 * deliberately decoupled from the wire shape so it can evolve independently.
 *
 * <p>This is <em>not</em> a {@link WelcomeMessage}: the welcome record models the inbound frame
 * (including the legacy top-level {@code id}/{@code login} duplicates), whereas {@code
 * SessionState} is the distilled application state. The ICE adapter launcher reads {@link #id()} /
 * {@link #login()} from here (json-rpc-spec.md §8.1); R30 consumes it when wiring the FSM
 * transition to IDLE.
 *
 * <p>There is no session id or server-config blob in {@code welcome} — the handshake {@code
 * session} number lives on {@link LobbyHandshake}, not here. Ratings are kept opaque ({@link
 * JsonNode} values) until a consumer needs the {@code [mean, deviation]} / {@code number_of_games}
 * shape modelled.
 *
 * @param id authenticated player ID (from {@code welcome.me.id})
 * @param login authenticated player login (from {@code welcome.me.login})
 * @param clan clan tag; may be {@code null} or empty when the server omits it
 * @param country ISO country code; may be {@code null} or empty when the server omits it
 * @param ratings rating-type name → opaque rating value; never {@code null} (empty when omitted)
 * @param currentTime server time in ISO 8601, as supplied (not parsed)
 */
public record SessionState(
        int id,
        String login,
        String clan,
        String country,
        Map<String, JsonNode> ratings,
        String currentTime) {

    /**
     * Compact canonical constructor — normalises {@code ratings} to a non-null, immutable copy so
     * consumers never null-check and cannot mutate the shared identity.
     */
    public SessionState {
        ratings = ratings == null ? Map.of() : Map.copyOf(ratings);
    }

    /**
     * Distil a {@code SessionState} from a decoded welcome payload. The welcome's nested {@code me}
     * block is the canonical identity source (its {@code id}/{@code login} are presence-validated
     * by {@link WelcomeMessage.Me}); the optional {@code clan}/{@code country}/{@code ratings} pass
     * through as-is.
     *
     * @param welcome the decoded, presence-validated welcome payload
     * @return the immutable session identity for the rest of the client to read
     */
    public static SessionState from(final WelcomeMessage welcome) {
        final WelcomeMessage.Me me = welcome.me();
        return new SessionState(
                me.id(), me.login(), me.clan(), me.country(), me.ratings(), welcome.currentTime());
    }
}
