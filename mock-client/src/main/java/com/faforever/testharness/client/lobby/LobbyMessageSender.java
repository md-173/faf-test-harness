package com.faforever.testharness.client.lobby;

import com.faforever.testharness.client.lobby.message.OutboundMessage;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.net.http.WebSocket;
import java.util.concurrent.CompletableFuture;

/**
 * Typed outbound encoder that sits on top of {@link LobbyConnection}. Takes an {@link
 * OutboundMessage} record, JSON-encodes it with the wire {@code command} field spliced in, and
 * pushes the frame through the connection's raw transport.
 *
 * <p>The sender does not own a socket — it borrows the {@link LobbyConnection} passed in. Multiple
 * senders can share the same connection harmlessly; {@link LobbyConnection#send} already serialises
 * concurrent frames.
 */
public final class LobbyMessageSender {

    /** Raw-frame transport this sender writes through. */
    private final LobbyConnection connection;

    /** Jackson mapper for record → {@code ObjectNode} encode. */
    private final ObjectMapper mapper;

    /**
     * Construct a sender bound to an already-constructed (but not necessarily connected) {@link
     * LobbyConnection}.
     *
     * @param connection raw-frame transport from 3.1.1.1
     * @param mapper Jackson mapper used to encode records
     */
    public LobbyMessageSender(final LobbyConnection connection, final ObjectMapper mapper) {
        this.connection = connection;
        this.mapper = mapper;
    }

    /**
     * Convenience constructor that builds a default {@link ObjectMapper}.
     *
     * @param connection raw-frame transport from 3.1.1.1
     */
    public LobbyMessageSender(final LobbyConnection connection) {
        this(connection, new ObjectMapper());
    }

    /**
     * Encode a typed outbound message and send it through the underlying transport. The wire {@code
     * command} field is taken from the record class's {@link LobbyCommand} annotation.
     *
     * <p>Defensive schema check: the annotation must be present (catches a missing annotation on a
     * freshly added record), and Jackson serialisation must yield a JSON object (catches a
     * mis-typed record — primitives, arrays, etc. would fail the wire-shape contract).
     *
     * @param message typed outbound message
     * @return future that completes when the frame has been handed to the OS socket
     * @throws IllegalStateException if {@link LobbyConnection#connect()} has not completed, or the
     *     record class is missing {@link LobbyCommand}, or the serialised payload is not a JSON
     *     object
     */
    public CompletableFuture<WebSocket> send(final OutboundMessage message) {
        String command = LobbyMessageDispatcher.commandOf(message.getClass());
        ObjectNode envelope;
        Object serialised = mapper.valueToTree(message);
        if (!(serialised instanceof ObjectNode)) {
            throw new IllegalStateException(
                    "outbound payload "
                            + message.getClass().getSimpleName()
                            + " serialised to non-object JSON ("
                            + (serialised == null ? "null" : serialised.getClass().getSimpleName())
                            + "); every lobby frame must be a JSON object");
        }
        envelope = (ObjectNode) serialised;
        // Splice command in at the front. Jackson preserves insertion order on ObjectNode, but
        // the spec doesn't pin field order — we set command after for simplicity.
        envelope.put("command", command);
        return connection.send(envelope);
    }
}
