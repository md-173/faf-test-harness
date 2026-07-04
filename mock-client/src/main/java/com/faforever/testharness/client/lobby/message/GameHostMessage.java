package com.faforever.testharness.client.lobby.message;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Outbound {@code game_host} request (lobby-protocol-spec.md §4.1, §10.2) — sent by the Mock Client
 * from {@code IDLE} to host a custom game, advertising it to the lobby.
 *
 * <p>{@code command} defaults to {@code "game_host"} when the canonical constructor receives {@code
 * null}, so callers building a request from config values don't have to repeat the literal. Every
 * other field is written as-is; a {@code null} field (e.g. {@code password} when the game isn't
 * password-protected) is omitted from the wire frame rather than serialised as JSON {@code null}.
 *
 * <p>Encoded via Jackson directly by the sender: {@code mapper.valueToTree(message)}.
 *
 * @param command always {@code "game_host"} on the wire; defaults when {@code null} is passed
 * @param title ASCII-only game title; required by the spec
 * @param visibility {@code "public"} or {@code "friends"}; required by the spec
 * @param mod featured-mod technical name (e.g. {@code "faf"}); required by the spec
 * @param mapname map folder name; required by the spec
 * @param password required only when {@code visibility} is password-protected; {@code null}
 *     otherwise
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record GameHostMessage(
        String command,
        String title,
        String visibility,
        String mod,
        String mapname,
        String password) {

    /** Compact canonical constructor — defaults {@code command} to {@code "game_host"}. */
    public GameHostMessage {
        command = command == null ? "game_host" : command;
    }
}
