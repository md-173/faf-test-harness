package com.faforever.testharness.client.ice;

import com.faforever.testharness.client.lobby.LobbyConnection;
import com.faforever.testharness.client.lobby.message.IceMsgMessage;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
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
 *       sent to the lobby. {@code msg} crosses the lobby as a string, and is stringified exactly
 *       once — see the payload-shape note below.
 *   <li><b>Lobby → adapter.</b> An inbound {@code IceMsg} with {@code args:[senderId,
 *       "<msg-string>"]} is pushed to the adapter as {@code iceMsg(senderId, msg-string)}, with
 *       {@code args[1]} parsed only to check it is a JSON object before forwarding.
 * </ul>
 *
 * <p><b>The payload is a string on both sides, not an object.</b> json-rpc-spec.md §5 documents
 * {@code msg: object} for both {@code onIceMsg} and {@code iceMsg}; the shipped adapter (3.3.14)
 * disagrees on both, and it is authoritative. Verified against the jar: {@code
 * RPCHandler.iceMsg(long, Object)} casts its second argument to {@code String} and hands it to
 * {@code ObjectMapper.readValue(String, CandidatesMessage.class)}, and {@code
 * RPCService.onIceMsg(CandidatesMessage)} serialises the message before sending, so the wire
 * carries {@code "params":[localId, remoteId, "{\"srcId\":…}"]}.
 *
 * <p>Treating either end as an object breaks the relay in a way no single-client test can see, and
 * both halves were wrong until the 4.3.1 two-peer run: stringifying the already-stringified payload
 * double-encoded it, and the receiving client then dropped its peer's candidates as "not a JSON
 * object". The outbound direction still stringifies a genuine object payload, so a future adapter
 * that matches the spec keeps working.
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

    /** Guards against {@link #start()} being called more than once. */
    private final AtomicBoolean started = new AtomicBoolean(false);

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
     * not be connected yet — registration is independent of connection state). A second call would
     * register duplicate handlers and relay every candidate twice, so it throws instead.
     *
     * @throws IllegalStateException if called more than once
     */
    public void start() {
        if (!started.compareAndSet(false, true)) {
            throw new IllegalStateException("start() may only be called once");
        }
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
        JsonNode payload = params.get(ON_ICE_MSG_PAYLOAD);
        final String msgString;
        if (payload.isTextual()) {
            // What the shipped adapter actually sends (see class javadoc): the payload is already
            // a JSON string, so it is forwarded verbatim. Stringifying it again would double-encode
            // it, and the receiving client would drop it as "not a JSON object" — which is exactly
            // how this was found, in the 4.3.1 two-peer run.
            msgString = payload.asText();
        } else {
            try {
                msgString = mapper.writeValueAsString(payload);
            } catch (JsonProcessingException e) {
                LOG.warn("dropping onIceMsg for remoteId={}: could not stringify msg", remoteId, e);
                return;
            }
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
        String msgString = args.get(1).asText();
        final JsonNode msg;
        try {
            msg = mapper.readTree(msgString);
        } catch (JsonProcessingException e) {
            LOG.warn(
                    "dropping IceMsg from senderId={}: args[1] is not valid JSON: {}",
                    senderId,
                    e.getOriginalMessage());
            return;
        }
        // The parse is a validity check only — what goes to the adapter is the original string.
        // readTree accepts inputs a candidates payload never is: "" parses to a MissingNode and
        // scalars parse to themselves, and either would reach the adapter as an unparseable msg.
        if (!msg.isObject()) {
            LOG.warn(
                    "dropping IceMsg from senderId={}: args[1] is not a JSON object: {}",
                    senderId,
                    args.get(1));
            return;
        }
        adapter.call("iceMsg", senderId, msgString)
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
