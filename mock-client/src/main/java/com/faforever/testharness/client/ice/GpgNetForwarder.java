package com.faforever.testharness.client.ice;

import com.faforever.testharness.client.lobby.LobbyConnection;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Forwards the GPGNet frames the local {@code faf-ice-adapter} emits to the lobby (WBS-3.1.4.6).
 * The adapter relays every frame the game sends it as an {@code onGpgNetMessageReceived(header,
 * chunks)} notification (json-rpc-spec.md §5); each one is wrapped in the GPGNet-over-WebSocket
 * envelope {@code {command: <header>, target: "game", args: <chunks>}} (lobby-protocol-spec.md §6)
 * and sent on the lobby socket.
 *
 * <p>Forwarding is generic by design: every frame is wrapped identically, with no per-command
 * branching and no typed per-message models — {@code GameState}, {@code PlayerOption}, {@code
 * GameResult}, and whatever else the game emits all take the same path. Outbound only: inbound
 * {@code target:"game"} commands from the lobby ({@code HostGame}, {@code JoinGame}, {@code
 * ConnectToPeer}) are routed by the lifecycle FSM (WBS 3.1.3), not here.
 *
 * <p>Malformed notifications are logged at WARN and dropped, per the codebase's log-and-drop
 * convention. Sends are fire-and-forget: a failure is logged and otherwise ignored — a dead
 * connection is surfaced through the connections' own disconnect listeners. The handler runs on the
 * adapter's reader thread and only does non-blocking work (envelope build + async send).
 */
public final class GpgNetForwarder {

    /** SLF4J logger; GPGNet frames carry no credentials. */
    private static final Logger LOG = LoggerFactory.getLogger(GpgNetForwarder.class);

    /** Jackson mapper for building the outbound envelope. */
    private final ObjectMapper mapper = new ObjectMapper();

    /** Lobby connection the wrapped envelopes are sent on. */
    private final LobbyConnection lobby;

    /** Adapter connection the notifications arrive on. */
    private final IceAdapterConnection adapter;

    /** Guards against {@link #start()} being called more than once. */
    private final AtomicBoolean started = new AtomicBoolean(false);

    /**
     * Creates a forwarder from {@code adapter} to {@code lobby}. Nothing is registered until {@link
     * #start()} — construction is side-effect free.
     *
     * @param lobby the lobby connection; must not be {@code null}
     * @param adapter the adapter connection; must not be {@code null}
     */
    public GpgNetForwarder(final LobbyConnection lobby, final IceAdapterConnection adapter) {
        this.lobby = Objects.requireNonNull(lobby, "lobby");
        this.adapter = Objects.requireNonNull(adapter, "adapter");
    }

    /**
     * Registers the {@code onGpgNetMessageReceived} notification handler. Call once, after both
     * connections exist (they need not be connected yet). A second call would register a duplicate
     * handler and forward every frame twice, so it throws instead.
     *
     * @throws IllegalStateException if called more than once
     */
    public void start() {
        if (!started.compareAndSet(false, true)) {
            throw new IllegalStateException("start() may only be called once");
        }
        adapter.registerNotification("onGpgNetMessageReceived", this::forwardToLobby);
    }

    /**
     * Wraps one {@code onGpgNetMessageReceived(header, chunks)} notification in the {@code
     * target:"game"} envelope and sends it to the lobby.
     *
     * @param notification the full JSON-RPC notification node from the adapter
     */
    private void forwardToLobby(final JsonNode notification) {
        JsonNode params = notification.get("params");
        if (params == null
                || !params.isArray()
                || params.size() < 2
                || !params.get(0).isTextual()
                || !params.get(1).isArray()) {
            LOG.warn(
                    "dropping malformed onGpgNetMessageReceived notification from adapter: {}",
                    notification);
            return;
        }
        String header = params.get(0).asText();
        ObjectNode envelope = mapper.createObjectNode();
        envelope.put("command", header);
        envelope.put("target", "game");
        envelope.set("args", params.get(1));
        lobby.send(envelope)
                .whenComplete(
                        (ok, error) -> {
                            if (error != null) {
                                LOG.warn(
                                        "failed to forward GPGNet '{}' frame to lobby: {}",
                                        header,
                                        error.getMessage());
                            }
                        });
    }
}
