package com.faforever.testharness.client.session;

import com.faforever.testharness.client.config.MockClientConfig;
import com.faforever.testharness.client.ice.IceAdapterConnection;
import com.faforever.testharness.client.lobby.LobbyConnection;
import com.faforever.testharness.client.lobby.LobbySession;
import com.faforever.testharness.client.lobby.SessionState;
import com.faforever.testharness.client.process.SessionTeardown;
import com.faforever.testharness.client.state.MockClientLifecycle;
import com.faforever.testharness.shared.logging.LoggingSetup;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.MDC;

/**
 * One client in a {@link MultiPeerSession}: its config, its lifecycle and teardown, and what its
 * lobby and adapter reported. The read accessors are public so a test can check the session's
 * outcome; everything that drives the session stays package-private.
 */
public final class SessionPeer {

    /** {@code onConnected}'s parameter count: local id, remote id, connected. */
    private static final int VERDICT_PARAMS = 3;

    /** Name used in failure messages, e.g. {@code B(joiner)}, so a red run says which side. */
    private final String name;

    /**
     * This peer's instance label (WBS-4.3.3), put on the building thread while the peer is built,
     * started and shut down. Its components capture it at construction and carry it onto their own
     * threads, and its adapter and game output inherit it through capture.
     */
    private final String label;

    /** This peer's validated config, with its own ports and its host or join intent. */
    private final MockClientConfig config;

    /** The peer's client lifecycle. */
    private final MockClientLifecycle lifecycle;

    /** The peer's teardown, run again after shutdown as a once-guarded backstop. */
    private final SessionTeardown teardown;

    /** Peer verdicts in arrival order, filled from the adapter's reader thread. */
    private final BlockingQueue<PeerVerdict> verdicts = new LinkedBlockingQueue<>();

    /** Verdicts already taken off the queue, kept so a failure can report what was seen. */
    private final List<PeerVerdict> observed = new ArrayList<>();

    /**
     * The latest verdict per remote player id. Latest rather than first: a link reported up and
     * then down is not part of a mesh.
     */
    private final Map<Long, Boolean> latestByRemote = new HashMap<>();

    /**
     * A {@code game_join_failed} frame, if the server sent one. Recorded purely for the failure
     * message: the alternative is a bare timeout waiting for {@code game_launch} that never says
     * the server refused the join, and {@code game_not_ready} versus {@code bad_password} are very
     * different bugs.
     *
     * <p>Source-verified, because this command is missing from lobby-protocol-spec.md's §10.6
     * lookup table: faf-server's {@code lobbyconnection.command_game_join} sends {@code {"command":
     * "game_join_failed", "reason": …, "uid": …}} for three refusals: {@code host_left_game},
     * {@code game_not_ready} and {@code bad_password}. A foe of the host or a game in the wrong
     * init mode is refused with a {@code ClientError} instead, which records nothing here.
     */
    private final AtomicReference<String> joinRefusal = new AtomicReference<>();

    /**
     * Every {@code ConnectToPeer} this peer's lobby sent, as (remote id, offer). Filled on the
     * lobby listener thread.
     */
    private final Set<Offer> offers = ConcurrentHashMap.newKeySet();

    /** This peer's lobby-assigned identity, from its {@code welcome}; set once it arrives. */
    private volatile SessionState identity;

    /**
     * Builds the peer's components under its label. The {@code game_join_failed} and {@code
     * ConnectToPeer} recorders are registered before the lifecycle's own handlers, so each sees a
     * frame before the adapter does.
     *
     * @param label the instance label, e.g. {@code A}
     * @param role {@code host} or {@code joiner}
     * @param config the peer's validated config
     */
    SessionPeer(final String label, final String role, final MockClientConfig config) {
        this.label = label;
        this.name = label + "(" + role + ")";
        this.config = config;
        try (MDC.MDCCloseable ignored = labelled()) {
            LobbyConnection lobby = new LobbyConnection(config.lobbyWebSocketUrl());
            lobby.registerHandler("game_join_failed", frame -> joinRefusal.set(frame.toString()));
            lobby.registerHandler("ConnectToPeer", this::recordOffer);
            IceAdapterConnection adapter = new IceAdapterConnection(config.iceAdapterRpcPort());
            adapter.registerNotification("onConnected", this::recordVerdict);
            this.teardown = new SessionTeardown(lobby);
            this.lifecycle =
                    new MockClientLifecycle(
                            config,
                            new LobbySession(
                                    lobby,
                                    config.uniqueId(),
                                    config.clientVersion(),
                                    config.userAgent(),
                                    config.uidBinaryPath()),
                            adapter,
                            teardown);
        }
    }

    /**
     * One {@code ConnectToPeer} as the lobby sent it.
     *
     * @param remoteId the peer to connect to, or -1 for a malformed frame
     * @param offer whether this side makes the ICE offer
     */
    public record Offer(long remoteId, boolean offer) {}

    /**
     * One peer verdict as the adapter reported it.
     *
     * @param localId the reporting adapter's own player id
     * @param remoteId the peer the verdict is about
     * @param connected whether that peer is reachable
     */
    record PeerVerdict(long localId, long remoteId, boolean connected) {
        @Override
        public String toString() {
            return "onConnected(" + localId + ", " + remoteId + ", " + connected + ")";
        }
    }

    /**
     * The name used in failure messages.
     *
     * @return the label and role, e.g. {@code B(joiner)}
     */
    public String name() {
        return name;
    }

    /**
     * The instance label every line from this peer carries.
     *
     * @return the label, e.g. {@code B}
     */
    public String label() {
        return label;
    }

    /**
     * This peer's validated config.
     *
     * @return the config, including its ports and its host or join intent
     */
    public MockClientConfig config() {
        return config;
    }

    /**
     * This peer's lobby identity.
     *
     * @return the identity from its {@code welcome}, or {@code null} before it arrived
     */
    public SessionState identity() {
        return identity;
    }

    /**
     * Every {@code ConnectToPeer} this peer's lobby sent.
     *
     * @return an unmodifiable view, filled as frames arrive
     */
    public Set<Offer> offers() {
        return Collections.unmodifiableSet(offers);
    }

    /**
     * Records this peer's identity once its {@code welcome} arrived.
     *
     * @param welcome the identity
     */
    void identity(final SessionState welcome) {
        this.identity = welcome;
    }

    /**
     * The peer's client lifecycle.
     *
     * @return the lifecycle
     */
    MockClientLifecycle lifecycle() {
        return lifecycle;
    }

    /**
     * The peer's teardown.
     *
     * @return the teardown
     */
    SessionTeardown teardown() {
        return teardown;
    }

    /**
     * Puts this peer's label on the calling thread until the returned scope is closed.
     *
     * @return the scope that removes the label again
     */
    MDC.MDCCloseable labelled() {
        return MDC.putCloseable(LoggingSetup.INSTANCE_MDC_KEY, label);
    }

    /**
     * Moves every queued verdict into this peer's record, checking each is about this adapter.
     *
     * @throws CheckpointFailure if the adapter reports another player as itself
     */
    void drainVerdicts() {
        PeerVerdict verdict;
        while ((verdict = verdicts.poll()) != null) {
            observed.add(verdict);
            if (verdict.localId() != identity.id()) {
                throw new CheckpointFailure(
                        name,
                        "full mesh",
                        "adapter reported "
                                + verdict
                                + " but its lobby-assigned id is "
                                + identity.id());
            }
            latestByRemote.put(verdict.remoteId(), verdict.connected());
        }
    }

    /**
     * Whether this peer's adapter currently reports {@code other} connected.
     *
     * @param other another peer in the session
     * @return {@code true} if its latest verdict about {@code other} is connected
     */
    boolean reportsConnected(final SessionPeer other) {
        return latestByRemote.getOrDefault((long) other.identity.id(), false);
    }

    /**
     * The verdicts drained so far, for a failure message.
     *
     * @return the verdicts in arrival order
     */
    List<PeerVerdict> observed() {
        return observed;
    }

    /**
     * Appended to a failed checkpoint's message when the server refused this peer's join.
     *
     * @return the refusal frame in parentheses, or an empty string
     */
    String refusalHint() {
        String refusal = joinRefusal.get();
        return refusal == null ? "" : " (the lobby refused the join: " + refusal + ")";
    }

    /**
     * Records one {@code onConnected} notification off the R36 fan-out. Every parameter is checked,
     * not just the boolean: {@code asLong} answers 0 for a non-numeric node, which would record a
     * verdict from player 0 and fail the mesh checkpoint by blaming this peer's id rather than the
     * malformed frame. Malformed ones are dropped rather than failing here: this runs on the
     * adapter's reader thread, where an exception would be swallowed, so a missing verdict surfaces
     * as the checkpoint that timed out instead.
     *
     * @param notification the raw JSON-RPC notification
     */
    private void recordVerdict(final JsonNode notification) {
        JsonNode params = notification.path("params");
        if (!params.isArray()
                || params.size() < VERDICT_PARAMS
                || !params.get(0).canConvertToLong()
                || !params.get(1).canConvertToLong()
                || !params.get(2).isBoolean()) {
            return;
        }
        verdicts.add(
                new PeerVerdict(
                        params.get(0).asLong(), params.get(1).asLong(), params.get(2).asBoolean()));
    }

    /**
     * Records one {@code ConnectToPeer} frame, {@code args: [login, id, offer]} (faf-server {@code
     * GpgNetServerProtocol.send_ConnectToPeer}). A malformed frame is recorded with id -1, so it
     * fails an offer check by name instead of vanishing on the listener thread.
     *
     * @param frame the lobby frame
     */
    private void recordOffer(final JsonNode frame) {
        JsonNode args = frame.path("args");
        boolean wellFormed = args.path(1).canConvertToLong() && args.path(2).isBoolean();
        offers.add(
                new Offer(
                        wellFormed ? args.path(1).asLong() : -1,
                        wellFormed && args.path(2).asBoolean()));
    }
}
