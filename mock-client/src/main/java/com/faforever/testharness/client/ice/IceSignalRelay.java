package com.faforever.testharness.client.ice;

import com.faforever.testharness.client.lobby.LobbyConnection;
import com.faforever.testharness.client.lobby.message.IceMsgMessage;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Bidirectional ICE-candidate relay between the local {@code faf-ice-adapter} (JSON-RPC) and the
 * lobby WebSocket — the loop described in json-rpc-spec.md §7. The Mock Client is the only
 * component touching both protocols, and this class is that seam:
 *
 * <ul>
 *   <li><b>Adapter → lobby.</b> An {@code onIceMsg(localId, remoteId, msg)} notification is wrapped
 *       as {@code {command:"IceMsg", target:"game", args:[remoteId, "<msg as JSON string>"]}} and
 *       sent to the lobby. {@code msg} crosses the lobby as a string, so it is stringified exactly
 *       once here.
 *   <li><b>Lobby → adapter.</b> An inbound {@code IceMsg} with {@code args:[senderId,
 *       "<msg-string>"]} has {@code args[1]} parsed back to a JSON object and is pushed to the
 *       adapter as {@code iceMsg(senderId, msg)}.
 * </ul>
 *
 * <p>The relay never swaps ids itself: it sends {@code remoteId} outbound and trusts {@code
 * args[0]} inbound — the lobby server performs the sender/receiver swap in transit (spec §7 step
 * 3). Malformed frames in either direction are logged at WARN and dropped, per the codebase's
 * log-and-drop convention; both connections additionally shield their reader threads from handler
 * exceptions, so a bad frame can never kill the pump.
 *
 * <p>Both directions are fire-and-forget: a failed {@code send}/{@code call} is logged and
 * otherwise ignored. A dead connection is surfaced through the connections' own disconnect
 * listeners and handled by the lifecycle FSM (WBS 3.1.3), not here. Handlers run on the respective
 * reader threads and only do non-blocking work (JSON transcode + async send).
 */
public final class IceSignalRelay {

    /** SLF4J logger; ICE payloads carry no credentials. */
    private static final Logger LOG = LoggerFactory.getLogger(IceSignalRelay.class);

    /** Index of {@code remotePlayerId} in the {@code onIceMsg} params (spec §5). */
    private static final int ON_ICE_MSG_REMOTE_ID = 1;

    /** Index of {@code msg} in the {@code onIceMsg} params (spec §5). */
    private static final int ON_ICE_MSG_PAYLOAD = 2;

    /** Jackson mapper for stringifying outbound and parsing inbound {@code msg} payloads. */
    private final ObjectMapper mapper = new ObjectMapper();

    /** Lobby side of the relay. */
    private final LobbyConnection lobby;

    /** Adapter side of the relay. */
    private final IceAdapterConnection adapter;

    /**
     * Creates a relay between {@code lobby} and {@code adapter}. Nothing is registered until {@link
     * #start()} — construction is side-effect free.
     *
     * @param lobby the lobby connection; must not be {@code null}
     * @param adapter the adapter connection; must not be {@code null}
     */
    public IceSignalRelay(final LobbyConnection lobby, final IceAdapterConnection adapter) {
        this.lobby = Objects.requireNonNull(lobby, "lobby");
        this.adapter = Objects.requireNonNull(adapter, "adapter");
    }

    /**
     * Registers both directions of the relay: the adapter's {@code onIceMsg} notification and the
     * lobby's {@code IceMsg} command handler. Call once, after both connections exist (they need
     * not be connected yet — registration is independent of connection state).
     */
    public void start() {
        adapter.registerNotification("onIceMsg", this::forwardToLobby);
        lobby.registerHandler("IceMsg", this::forwardToAdapter);
    }

    /**
     * Adapter → lobby: wrap an {@code onIceMsg} notification as a lobby {@code IceMsg} frame.
     *
     * @param notification the full JSON-RPC notification node from the adapter
     */
    private void forwardToLobby(final JsonNode notification) {
        JsonNode params = notification.get("params");
        if (params == null
                || !params.isArray()
                || params.size() <= ON_ICE_MSG_PAYLOAD
                || !params.get(ON_ICE_MSG_REMOTE_ID).canConvertToInt()) {
            LOG.warn("dropping malformed onIceMsg notification from adapter: {}", notification);
            return;
        }
        int remoteId = params.get(ON_ICE_MSG_REMOTE_ID).asInt();
        final String msgString;
        try {
            msgString = mapper.writeValueAsString(params.get(ON_ICE_MSG_PAYLOAD));
        } catch (JsonProcessingException e) {
            LOG.warn("dropping onIceMsg for remoteId={}: could not stringify msg", remoteId, e);
            return;
        }
        lobby.send(mapper.valueToTree(new IceMsgMessage(remoteId, msgString)))
                .whenComplete(
                        (ok, error) -> {
                            if (error != null) {
                                LOG.warn(
                                        "failed to relay IceMsg to lobby for remoteId={}: {}",
                                        remoteId,
                                        error.getMessage());
                            }
                        });
    }

    /**
     * Lobby → adapter: unwrap an {@code IceMsg} frame and push it as an {@code iceMsg} call.
     *
     * @param message the full lobby frame with {@code command == "IceMsg"}
     */
    private void forwardToAdapter(final JsonNode message) {
        JsonNode args = message.get("args");
        if (args == null
                || !args.isArray()
                || args.size() < 2
                || !args.get(0).canConvertToInt()
                || !args.get(1).isTextual()) {
            LOG.warn("dropping malformed IceMsg from lobby: {}", message);
            return;
        }
        int senderId = args.get(0).asInt();
        final JsonNode msg;
        try {
            msg = mapper.readTree(args.get(1).asText());
        } catch (JsonProcessingException e) {
            LOG.warn(
                    "dropping IceMsg from senderId={}: args[1] is not valid JSON: {}",
                    senderId,
                    e.getOriginalMessage());
            return;
        }
        adapter.call("iceMsg", senderId, msg)
                .whenComplete(
                        (ok, error) -> {
                            if (error != null) {
                                LOG.warn(
                                        "iceMsg call to adapter failed for senderId={}: {}",
                                        senderId,
                                        error.getMessage());
                            }
                        });
    }
}
