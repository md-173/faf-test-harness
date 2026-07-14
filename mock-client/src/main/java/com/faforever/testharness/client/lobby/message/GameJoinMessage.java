package com.faforever.testharness.client.lobby.message;

import com.fasterxml.jackson.annotation.JsonGetter;
import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Outbound {@code game_join} request (lobby-protocol-spec.md §4.2, §10.2) — sent by the Mock Client
 * from {@code IDLE} to join an existing custom game by ID.
 *
 * <p>{@code command} is always {@code "game_join"} on the wire, so it is not a field on this record
 * — {@link #command()} reports the constant directly. {@code password} is omitted from the wire
 * frame when {@code null} rather than serialised as JSON {@code null}.
 *
 * <p>Encoded via Jackson directly by the sender: {@code mapper.valueToTree(message)}.
 *
 * @param uid ID of the game to join
 * @param password required only when the target game is password-protected; {@code null} otherwise
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record GameJoinMessage(int uid, String password) {

    /**
     * Always {@code "game_join"} on the wire.
     *
     * @return the literal {@code "game_join"}
     */
    @JsonGetter("command")
    public String command() {
        return "game_join";
    }
}
