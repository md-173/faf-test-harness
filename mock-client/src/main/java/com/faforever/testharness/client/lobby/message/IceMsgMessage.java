package com.faforever.testharness.client.lobby.message;

import com.fasterxml.jackson.annotation.JsonGetter;
import com.fasterxml.jackson.annotation.JsonIgnore;
import java.util.List;

/**
 * Outbound {@code IceMsg} envelope (json-rpc-spec.md §7 step 2) — wraps a local adapter {@code
 * onIceMsg} payload so the lobby server can relay it to the remote peer's Mock Client.
 *
 * <p>{@code command} and {@code target} are constant on the wire, so they are {@link JsonGetter}
 * constants rather than record components — the same idiom as {@link GameJoinMessage#command()}.
 * The lobby wire shape is {@code args:[remoteId, "<msg as JSON string>"]}: a positional array, so
 * the named components are exposed through {@link #args()} instead of serialising individually.
 *
 * <p>{@code msg} is the adapter's ICE payload already serialised to a JSON <em>string</em> — the
 * lobby relays it opaquely and the receiving side parses it back to an object (spec §7 step 4).
 * Stringifying is the sender's job; this record just carries the result.
 *
 * @param remoteId the receiving player's id from the sender's perspective ({@code args[0]}; the
 *     lobby server swaps it to the sender id in transit — spec §7 step 3)
 * @param msg the ICE candidate / SDP payload as a JSON string ({@code args[1]})
 */
public record IceMsgMessage(@JsonIgnore int remoteId, @JsonIgnore String msg) {

    /**
     * Always {@code "IceMsg"} on the wire.
     *
     * @return the literal {@code "IceMsg"}
     */
    @JsonGetter("command")
    public String command() {
        return "IceMsg";
    }

    /**
     * Always {@code "game"} on the wire — routes the frame to the lobby's game-message relay.
     *
     * @return the literal {@code "game"}
     */
    @JsonGetter("target")
    public String target() {
        return "game";
    }

    /**
     * The positional {@code args} array: {@code [remoteId, msg]}.
     *
     * @return the wire-shape argument list
     */
    @JsonGetter("args")
    public List<Object> args() {
        return List.of(remoteId, msg);
    }
}
