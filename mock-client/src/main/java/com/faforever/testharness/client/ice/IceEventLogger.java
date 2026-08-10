package com.faforever.testharness.client.ice;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Reports the adapter's connection-state notifications on the harness log contract (WBS-3.1.6.2).
 * Read-only. It observes the {@link IceAdapterConnection} fan-out, emits one stable INFO line per
 * notification, and sends nothing.
 *
 * <p>Three notifications are covered, and they are not interchangeable. {@code
 * onConnectionStateChanged} carries the local game to adapter GPGNet TCP link and is emitted by the
 * adapter's {@code GPGNetServer}. {@code onIceConnectionStateChanged} and {@code onConnected} carry
 * the peer link and are emitted by the adapter's {@code PeerIceModule}. Only the latter two move
 * during ICE negotiation, so they are what the Phase 5 delayed-negotiation tests measure. Verified
 * against the shipped {@code faf-ice-adapter} and json-rpc-spec.md §5.
 *
 * <p>The two peer notifications carry player ids as JSON-RPC {@code long} values, matching the
 * adapter's {@code RPCService} signatures. The spec table calls them ints, which is wrong.
 *
 * <p>Malformed notifications are logged at WARN and dropped, per the codebase's log-and-drop
 * convention. Handlers run on the adapter's reader thread and do nothing but log, matching what
 * that thread already does elsewhere.
 */
public final class IceEventLogger {

    /** SLF4J logger; adapter notifications carry no credentials. */
    private static final Logger LOG = LoggerFactory.getLogger(IceEventLogger.class);

    /** Index of {@code localPlayerId} in both peer notifications (spec §5). */
    private static final int PEER_LOCAL_ID = 0;

    /** Index of {@code remotePlayerId} in both peer notifications (spec §5). */
    private static final int PEER_REMOTE_ID = 1;

    /** Index of the trailing state or flag in both peer notifications (spec §5). */
    private static final int PEER_STATE = 2;

    /** Adapter connection the notifications arrive on. */
    private final IceAdapterConnection adapter;

    /** Guards against {@link #start()} being called more than once. */
    private final AtomicBoolean started = new AtomicBoolean(false);

    /**
     * Creates a logger for {@code adapter}. Nothing is registered until {@link #start()}, so
     * construction is side-effect free.
     *
     * @param adapter the adapter connection; must not be {@code null}
     */
    public IceEventLogger(final IceAdapterConnection adapter) {
        this.adapter = Objects.requireNonNull(adapter, "adapter");
    }

    /**
     * Registers the three notification handlers. Call once, after the connection exists. It need
     * not be connected yet. A second call would register duplicate handlers and log every
     * notification twice, so it throws instead.
     *
     * @throws IllegalStateException if called more than once
     */
    public void start() {
        if (!started.compareAndSet(false, true)) {
            throw new IllegalStateException("start() may only be called once");
        }
        adapter.registerNotification("onConnectionStateChanged", this::logGpgNetLink);
        adapter.registerNotification("onIceConnectionStateChanged", this::logPeerIceState);
        adapter.registerNotification("onConnected", this::logPeerConnected);
    }

    /**
     * Reports the local game to adapter GPGNet link changing state. This is not a peer signal.
     *
     * @param notification the full JSON-RPC notification node from the adapter
     */
    private void logGpgNetLink(final JsonNode notification) {
        JsonNode params = notification.get("params");
        if (params == null || !params.isArray() || params.isEmpty() || !params.get(0).isTextual()) {
            LOG.warn(
                    "dropping malformed onConnectionStateChanged notification from adapter: {}",
                    notification);
            return;
        }
        LOG.info("gpgnet link: state={}", params.get(0).asText());
    }

    /**
     * Reports one peer's ICE connection state changing. This mirrors {@code
     * RTCPeerConnection.iceConnectionState}, so it is the signal that moves while negotiation is
     * delayed.
     *
     * @param notification the full JSON-RPC notification node from the adapter
     */
    private void logPeerIceState(final JsonNode notification) {
        JsonNode params = notification.get("params");
        if (!hasPeerIds(params) || !params.get(PEER_STATE).isTextual()) {
            LOG.warn(
                    "dropping malformed onIceConnectionStateChanged notification from adapter: {}",
                    notification);
            return;
        }
        LOG.info(
                "peer ice: local={} remote={} state={}",
                params.get(PEER_LOCAL_ID).asLong(),
                params.get(PEER_REMOTE_ID).asLong(),
                params.get(PEER_STATE).asText());
    }

    /**
     * Reports the adapter's high-level verdict on whether a peer is reachable. This is the
     * definitive peer-established signal the two-peer session test asserts on.
     *
     * @param notification the full JSON-RPC notification node from the adapter
     */
    private void logPeerConnected(final JsonNode notification) {
        JsonNode params = notification.get("params");
        if (!hasPeerIds(params) || !params.get(PEER_STATE).isBoolean()) {
            LOG.warn("dropping malformed onConnected notification from adapter: {}", notification);
            return;
        }
        LOG.info(
                "peer connected: local={} remote={} connected={}",
                params.get(PEER_LOCAL_ID).asLong(),
                params.get(PEER_REMOTE_ID).asLong(),
                params.get(PEER_STATE).asBoolean());
    }

    /**
     * Checks that a params array carries the two player ids both peer notifications start with. The
     * ids are longs on the wire, so an int-only check would reject valid values. The range check is
     * deliberately all this does. The adapter only ever sends integral ids, and a stricter guard
     * would buy nothing.
     *
     * @param params the {@code params} node, possibly {@code null}
     * @return {@code true} if the two leading ids and a third element are present, with ids in long
     *     range
     */
    private static boolean hasPeerIds(final JsonNode params) {
        return params != null
                && params.isArray()
                && params.size() > PEER_STATE
                && params.get(PEER_LOCAL_ID).canConvertToLong()
                && params.get(PEER_REMOTE_ID).canConvertToLong();
    }
}
