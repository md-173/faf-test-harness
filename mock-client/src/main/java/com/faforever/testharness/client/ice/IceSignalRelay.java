package com.faforever.testharness.client.ice;

import com.faforever.testharness.client.lobby.LobbyConnection;
import com.faforever.testharness.client.lobby.message.IceMsgMessage;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
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
 * object". Both directions target the shipped adapter, not the spec: outbound tolerates an object
 * payload by stringifying it, but inbound always hands the adapter a string, so an adapter that
 * ever did take an object would need the inbound half changed too. Not worth pre-building a
 * shape-aware path for — the {@code (String)} cast is what the adapter actually ships.
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
 *
 * <p><b>Fault injection (WBS-5.1).</b> An optional forward delay holds every relayed candidate for
 * a fixed interval before it goes out, in both directions, simulating slow ICE negotiation without
 * {@code tc} or elevated privileges. It delays the <em>signalling</em>, not the adapter-to-adapter
 * connectivity checks, which happen inside {@code faf-ice-adapter} and are not ours to touch;
 * delaying candidate relay delays when negotiation can begin, which is the faithful reading.
 *
 * <p>Delay, never drop and never reorder. Validation and transcoding still happen inline on the
 * reader thread, so a malformed frame is still rejected immediately and the delay applies only to
 * the forward itself. The scheduler is single-threaded and every forward takes the same delay, so
 * tasks fire in submission order and candidates keep their relative sequence. At the default of
 * zero the scheduler is never created and each forward runs inline on the reader thread, exactly as
 * it did before the flag existed.
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

    /** How long each forward is held before it goes out; {@link Duration#ZERO} forwards inline. */
    private final Duration forwardDelay;

    /**
     * Schedules delayed forwards, or {@code null} when {@link #forwardDelay} is zero. Single-
     * threaded so equal delays fire in submission order, and daemon so it can never hold the JVM
     * open — this relay outlives no explicit teardown in the session path that builds it.
     */
    private final ScheduledExecutorService scheduler;

    /**
     * Creates a relay that forwards without delay. Equivalent to {@link #IceSignalRelay(
     * LobbyConnection, IceAdapterConnection, Duration)} with {@link Duration#ZERO}.
     *
     * @param lobby the lobby connection; must not be {@code null}
     * @param adapter the adapter connection; must not be {@code null}
     */
    public IceSignalRelay(final LobbyConnection lobby, final IceAdapterConnection adapter) {
        this(lobby, adapter, Duration.ZERO);
    }

    /**
     * Creates a relay between {@code lobby} and {@code adapter}, holding every forward for {@code
     * forwardDelay}. Nothing is registered until {@link #start()} — construction allocates the
     * scheduler when a delay is configured, and is otherwise side-effect free.
     *
     * @param lobby the lobby connection; must not be {@code null}
     * @param adapter the adapter connection; must not be {@code null}
     * @param forwardDelay how long to hold each relayed candidate, in both directions; {@link
     *     Duration#ZERO} (the default) forwards inline, and is the pre-WBS-5.1 behaviour
     * @throws IllegalArgumentException if {@code forwardDelay} is negative
     */
    public IceSignalRelay(
            final LobbyConnection lobby,
            final IceAdapterConnection adapter,
            final Duration forwardDelay) {
        this.lobby = Objects.requireNonNull(lobby, "lobby");
        this.adapter = Objects.requireNonNull(adapter, "adapter");
        this.forwardDelay = Objects.requireNonNull(forwardDelay, "forwardDelay");
        if (forwardDelay.isNegative()) {
            throw new IllegalArgumentException(
                    "forwardDelay must not be negative: " + forwardDelay);
        }
        this.scheduler =
                forwardDelay.isZero()
                        ? null
                        : Executors.newSingleThreadScheduledExecutor(
                                runnable -> {
                                    Thread thread = new Thread(runnable, "ice-relay-delay");
                                    thread.setDaemon(true);
                                    return thread;
                                });
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
        forward(
                () ->
                        lobby.send(mapper.valueToTree(new IceMsgMessage(remoteId, msgString)))
                                .whenComplete(
                                        (ok, error) -> {
                                            if (error != null) {
                                                LOG.warn(
                                                        "failed to relay IceMsg to lobby for"
                                                                + " remoteId={}: {}",
                                                        remoteId,
                                                        error.getMessage());
                                            }
                                        }));
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
        forward(
                () ->
                        adapter.call("iceMsg", senderId, msgString)
                                .whenComplete(
                                        (ok, error) -> {
                                            if (error != null) {
                                                LOG.warn(
                                                        "iceMsg call to adapter failed for"
                                                                + " senderId={}: {}",
                                                        senderId,
                                                        error.getMessage());
                                            }
                                        }));
    }

    /**
     * Runs one forward, immediately or after the configured delay.
     *
     * <p>Only the forward is deferred. The caller has already validated and transcoded the frame on
     * the reader thread, so a malformed candidate is still rejected at once and never occupies a
     * scheduler slot. Submission order is preserved because the scheduler is single-threaded and
     * every task takes the same delay, so their deadlines fall in the order they were queued.
     *
     * @param action the send or call to perform
     */
    private void forward(final Runnable action) {
        if (scheduler == null) {
            action.run();
            return;
        }
        scheduler.schedule(action, forwardDelay.toMillis(), TimeUnit.MILLISECONDS);
    }

    /**
     * Releases the delay scheduler, if one was created. Optional: the scheduler's thread is a
     * daemon and cannot hold the JVM open, so the session path that never tears the relay down is
     * not leaking anything that matters. Tests call it so a suite does not accumulate one idle
     * thread per relay. Idempotent; queued forwards are abandoned rather than run.
     */
    public void stop() {
        if (scheduler != null) {
            scheduler.shutdownNow();
        }
    }
}
