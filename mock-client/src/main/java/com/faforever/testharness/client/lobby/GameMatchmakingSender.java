package com.faforever.testharness.client.lobby;

import com.faforever.testharness.client.config.GameQueueConfig;
import com.faforever.testharness.client.lobby.message.GameMatchmakingMessage;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.http.WebSocket;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;

/**
 * Sends the {@code game_matchmaking} request (lobby-protocol-spec.md §4.3, §10.2) that starts or
 * stops a matchmaker queue search (WBS-3.1.1.9). The server's {@code search_info}, {@code
 * match_found}, {@code match_cancelled}, and {@code search_timeout} replies are handled elsewhere —
 * consuming them at runtime is the FSM's job, not this class's.
 *
 * <p>This class is deliberately a leaf: it builds one outbound frame from a {@link GameQueueConfig}
 * and hands it to {@link LobbyConnection#send(com.fasterxml.jackson.databind.JsonNode)}. It does not
 * decide <em>when</em> to queue — that is {@link
 * com.faforever.testharness.client.state.MockClientLifecycle}'s {@code IDLE} entry hook, {@code
 * sendGameMatchmakingIfConfigured()}, which calls {@link #sendStart(GameQueueConfig)} only when the
 * mock client was configured to queue.
 */
public final class GameMatchmakingSender {

    /** Jackson mapper used to encode {@link GameMatchmakingMessage} to a wire frame. */
    private final ObjectMapper mapper = new ObjectMapper();

    /** Connection the {@code game_matchmaking} request is sent on. */
    private final LobbyConnection lobby;

    /**
     * Creates a sender bound to {@code lobby}.
     *
     * @param lobby the connection to send {@code game_matchmaking} requests on; must not be {@code
     *     null}
     */
    public GameMatchmakingSender(final LobbyConnection lobby) {
        this.lobby = Objects.requireNonNull(lobby, "lobby");
    }

    /**
     * Builds and sends a {@code game_matchmaking} state {@code "start"} request from {@code config},
     * including the configured faction, if any.
     *
     * @param config queue settings to send; must not be {@code null}
     * @return future that completes when the frame has been handed to the OS socket
     * @throws IllegalStateException if the underlying {@link LobbyConnection} has not connected
     */
    public CompletableFuture<WebSocket> sendStart(final GameQueueConfig config) {
        Objects.requireNonNull(config, "config");
        GameMatchmakingMessage message =
                new GameMatchmakingMessage(
                        config.queueName(), "start", config.faction().orElse(null));
        return lobby.send(mapper.valueToTree(message));
    }

    /**
     * Builds and sends a {@code game_matchmaking} state {@code "stop"} request for {@code config}'s
     * queue. {@code faction} is never sent on a stop request, matching the protocol.
     *
     * @param config queue settings to send; must not be {@code null}
     * @return future that completes when the frame has been handed to the OS socket
     * @throws IllegalStateException if the underlying {@link LobbyConnection} has not connected
     */
    public CompletableFuture<WebSocket> sendStop(final GameQueueConfig config) {
        Objects.requireNonNull(config, "config");
        GameMatchmakingMessage message = new GameMatchmakingMessage(config.queueName(), "stop", null);
        return lobby.send(mapper.valueToTree(message));
    }
}
