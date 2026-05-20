package com.faforever.testharness.client.lobby;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * Callback invoked by {@link LobbyConnection} when a JSON message arrives whose {@code command}
 * field matches a registered handler.
 *
 * <p>Handlers run on the WebSocket listener thread, so the dispatcher cannot make progress until
 * {@link #onMessage(JsonNode)} returns. Implementations must not block on long I/O — hand work off
 * to another executor if it cannot complete in a few milliseconds. Exceptions thrown out of a
 * handler are caught by the connection, logged at WARN, and do not tear down the socket.
 */
@FunctionalInterface
public interface LobbyMessageHandler {

    /**
     * Process one decoded lobby message.
     *
     * @param message the full JSON object received; the {@code command} field is guaranteed to
     *     equal the value this handler was registered under
     */
    void onMessage(JsonNode message);
}
