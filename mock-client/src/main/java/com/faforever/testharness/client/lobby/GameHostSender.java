package com.faforever.testharness.client.lobby;

import com.faforever.testharness.client.config.MockClientConfig;
import com.faforever.testharness.client.lobby.message.GameHostMessage;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.http.WebSocket;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;

/**
 * Sends the {@code game_host} request (lobby-protocol-spec.md §4.1, §10.2) that starts the
 * custom-game host flow (WBS-3.1.1.7). The server's {@code game_launch} reply is handled elsewhere
 * — decoding it into a {@link com.faforever.testharness.client.lobby.message.GameLaunchMessage} is
 * already covered by 3.1.1.5/R24, and consuming it at runtime is the FSM's job (R30/R59a), not this
 * class's.
 *
 * <p>This class is deliberately a leaf: it builds one outbound frame from {@link MockClientConfig}
 * and hands it to {@link LobbyConnection#send(com.fasterxml.jackson.databind.JsonNode)}. It does
 * not decide <em>when</em> to host — that is the FSM's {@code IDLE} state, which is expected to
 * call {@link #sendGameHost(MockClientConfig)} on entry rather than have a direct call path wired
 * in from the CLI (soft dep on R30; see WBS-3.1.1.7 notes).
 */
public final class GameHostSender {

    /** Jackson mapper used to encode {@link GameHostMessage} to a wire frame. */
    private final ObjectMapper mapper = new ObjectMapper();

    /** Connection the {@code game_host} request is sent on. */
    private final LobbyConnection lobby;

    /**
     * Creates a sender bound to {@code lobby}.
     *
     * @param lobby the connection to send {@code game_host} requests on; must not be {@code null}
     */
    public GameHostSender(final LobbyConnection lobby) {
        this.lobby = Objects.requireNonNull(lobby, "lobby");
    }

    /**
     * Builds and sends a {@code game_host} request from {@code config}'s host settings ({@code
     * hostTitle}, {@code hostMap}, {@code hostMod}, {@code hostVisibility}) — nothing is hardcoded
     * here. {@code password} is not sent; the Mock Client does not host password-protected games.
     *
     * @param config validated configuration to read host settings from; must not be {@code null}
     * @return future that completes when the frame has been handed to the OS socket
     * @throws IllegalStateException if the underlying {@link LobbyConnection} has not connected
     */
    public CompletableFuture<WebSocket> sendGameHost(final MockClientConfig config) {
        Objects.requireNonNull(config, "config");
        GameHostMessage message =
                new GameHostMessage(
                        null,
                        config.hostTitle(),
                        config.hostVisibility(),
                        config.hostMod(),
                        config.hostMap(),
                        null);
        return lobby.send(mapper.valueToTree(message));
    }
}
