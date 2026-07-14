package com.faforever.testharness.client.lobby;

import com.faforever.testharness.client.config.GameJoinConfig;
import com.faforever.testharness.client.lobby.message.GameJoinMessage;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.http.WebSocket;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;

/**
 * Sends the {@code game_join} request (lobby-protocol-spec.md §4.2, §10.2) that joins an existing
 * custom game by ID (WBS-3.1.1.8).
 *
 * <p>This class is deliberately a leaf: it builds one outbound frame from a {@link GameJoinConfig}
 * and hands it to {@link LobbyConnection#send(com.fasterxml.jackson.databind.JsonNode)}. It does
 * not decide <em>when</em> to join — that is {@link
 * com.faforever.testharness.client.state.MockClientLifecycle}'s {@code IDLE} entry hook, {@code
 * sendGameJoinIfConfigured()}, which calls {@link #sendGameJoin(GameJoinConfig)} only when the mock
 * client was configured to join.
 */
public final class GameJoinSender {

    /** Jackson mapper used to encode {@link GameJoinMessage} to a wire frame. */
    private final ObjectMapper mapper = new ObjectMapper();

    /** Connection the {@code game_join} request is sent on. */
    private final LobbyConnection lobby;

    /**
     * Creates a sender bound to {@code lobby}.
     *
     * @param lobby the connection to send {@code game_join} requests on; must not be {@code null}
     */
    public GameJoinSender(final LobbyConnection lobby) {
        this.lobby = Objects.requireNonNull(lobby, "lobby");
    }

    /**
     * Builds and sends a {@code game_join} request from {@code config}.
     *
     * @param config join settings to send; must not be {@code null}
     * @return future that completes when the frame has been handed to the OS socket
     * @throws IllegalStateException if the underlying {@link LobbyConnection} has not connected
     */
    public CompletableFuture<WebSocket> sendGameJoin(final GameJoinConfig config) {
        Objects.requireNonNull(config, "config");
        GameJoinMessage message =
                new GameJoinMessage(config.targetGameId(), config.password().orElse(null));
        return lobby.send(mapper.valueToTree(message));
    }
}
