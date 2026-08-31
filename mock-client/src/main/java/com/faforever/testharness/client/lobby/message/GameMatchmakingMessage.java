package com.faforever.testharness.client.lobby.message;

import com.fasterxml.jackson.annotation.JsonGetter;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Outbound {@code game_matchmaking} request (lobby-protocol-spec.md §4.3, §10.2) — sent by the Mock
 * Client from {@code IDLE} to start or stop searching a matchmaker queue.
 *
 * <p>{@code command} is always {@code "game_matchmaking"} on the wire, so it is not a field on this
 * record — {@link #command()} reports the constant directly. {@code faction} is omitted from the
 * wire frame when {@code null} rather than serialised as JSON {@code null}; it only applies on a
 * {@code "start"} request, matching faf-server's {@code lobbyconnection.command_game_matchmaking}.
 *
 * <p>Encoded via Jackson directly by the sender: {@code mapper.valueToTree(message)}.
 *
 * @param queueName matchmaker queue to search, e.g. {@code "ladder1v1"}
 * @param state {@code "start"} or {@code "stop"}
 * @param faction faction to search with; {@code null} unless {@code state} is {@code "start"}
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record GameMatchmakingMessage(
        @JsonProperty("queue_name") String queueName, String state, Integer faction) {

    /**
     * Validates the fields the spec marks required.
     *
     * @throws IllegalArgumentException if {@code queueName} or {@code state} is {@code null} or
     *     blank, or {@code state} is neither {@code "start"} nor {@code "stop"}
     */
    public GameMatchmakingMessage {
        if (queueName == null || queueName.isBlank()) {
            throw new IllegalArgumentException("queueName must not be blank");
        }
        if (!"start".equals(state) && !"stop".equals(state)) {
            throw new IllegalArgumentException("state must be \"start\" or \"stop\"");
        }
    }

    /**
     * Always {@code "game_matchmaking"} on the wire.
     *
     * @return the literal {@code "game_matchmaking"}
     */
    @JsonGetter("command")
    public String command() {
        return "game_matchmaking";
    }
}
