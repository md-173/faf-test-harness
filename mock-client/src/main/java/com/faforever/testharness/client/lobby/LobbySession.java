package com.faforever.testharness.client.lobby;

import java.nio.file.Path;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Orchestrates a single lobby session end to end: open the transport, run the authentication
 * handshake, hydrate the welcome state, then sit idle until the connection drops. This is the thin
 * glue over the three lobby pillars — {@link LobbyConnection} (transport, WBS-3.1.1.1), {@link
 * LobbyHandshake} (auth, WBS-3.1.1.2), and {@link WelcomeStateSync} (welcome, WBS-3.1.1.3) — that
 * the {@code run} command drives (WBS-3.1.1.4).
 *
 * <p>It adds no protocol logic of its own. In particular the idle heartbeat is free: {@link
 * LobbyConnection} already auto-replies {@code pong} to every server {@code ping}, so "stay idle
 * and keep the connection alive" is simply {@link #awaitDisconnect()} blocking on the disconnect
 * latch.
 *
 * <p>The session installs one of the connection's {@link
 * LobbyConnection#onDisconnect(java.util.function.Consumer) disconnect listeners} (they are
 * additive); other components — e.g. the lifecycle FSM — may register their own via {@link
 * #connection()}.
 *
 * <p><b>Usage:</b>
 *
 * <pre>{@code
 * LobbyConnection connection = new LobbyConnection(config.lobbyWebSocketUrl());
 * LobbySession session =
 *     new LobbySession(connection, config.uniqueId(), config.clientVersion(), config.userAgent());
 * SessionState me =
 *     session.connectAndAuthenticate(tokens, Duration.ofSeconds(15), Duration.ofSeconds(30));
 * session.awaitDisconnect(); // blocks idle; transport auto-pongs lobby pings
 * }</pre>
 *
 * @author md-173
 * @see LobbyConnection
 * @see LobbyHandshake
 * @see WelcomeStateSync
 */
public final class LobbySession {

    /** Underlying transport, bound to the lobby endpoint and owned by this session. */
    private final LobbyConnection connection;

    /** The auth handshake bound to {@link #connection}; performed once by this session. */
    private final LobbyHandshake handshake;

    /** Welcome → {@link SessionState} hydration, chained off the handshake's completion future. */
    private final WelcomeStateSync stateSync = new WelcomeStateSync();

    /** Released exactly once when the connection disconnects, for any reason. */
    private final CountDownLatch disconnected = new CountDownLatch(1);

    /** The disconnect event, populated the moment {@link #disconnected} is released. */
    private volatile LobbyConnection.DisconnectEvent disconnectEvent;

    /**
     * Bind a session to a not-yet-connected transport. Installs the connection's disconnect
     * listener and constructs the handshake; no I/O happens until {@link #connectAndAuthenticate}.
     *
     * @param connection a {@link LobbyConnection} that has not yet been {@link
     *     LobbyConnection#connect() connected}
     * @param uniqueId hardware identifier hash sent in the {@code auth} payload
     * @param clientVersion {@code version} field sent in {@code ask_session}
     * @param userAgent {@code user_agent} field sent in {@code ask_session}
     */
    public LobbySession(
            final LobbyConnection connection,
            final String uniqueId,
            final String clientVersion,
            final String userAgent) {
        this(connection, uniqueId, clientVersion, userAgent, Optional.empty());
    }

    /**
     * Bind a session that derives its {@code unique_id} from the {@code faf-uid} binary.
     *
     * @param connection a {@link LobbyConnection} that has not yet been {@link
     *     LobbyConnection#connect() connected}
     * @param uniqueId fallback hardware identifier used when {@code uidBinaryPath} is empty
     * @param clientVersion {@code version} field sent in {@code ask_session}
     * @param userAgent {@code user_agent} field sent in {@code ask_session}
     * @param uidBinaryPath optional path to the {@code faf-uid} binary (see {@link LobbyHandshake})
     */
    public LobbySession(
            final LobbyConnection connection,
            final String uniqueId,
            final String clientVersion,
            final String userAgent,
            final Optional<Path> uidBinaryPath) {
        this.connection = connection;
        this.handshake =
                new LobbyHandshake(connection, uniqueId, clientVersion, userAgent, uidBinaryPath);
        connection.onDisconnect(
                event -> {
                    this.disconnectEvent = event;
                    disconnected.countDown();
                });
    }

    /**
     * The transport this session is bound to. Exposed so collaborators that need lobby traffic
     * beyond the handshake — e.g. {@code MockClientLifecycle} registering {@code game_launch} /
     * disconnect listeners — can reach the server through the session instead of holding a separate
     * reference. The session remains the owner of connect ({@link #start}/{@link
     * #connectAndAuthenticate}) and {@link #close()}.
     *
     * @return the underlying {@link LobbyConnection}
     */
    public LobbyConnection connection() {
        return connection;
    }

    /**
     * Open the transport and run the full handshake asynchronously: {@code connect → ask_session →
     * session → auth → welcome}, hydrating the welcome into a {@link SessionState}. Non-blocking
     * counterpart of {@link #connectAndAuthenticate}; at most one of the two may be called, since
     * the underlying handshake performs only once.
     *
     * @param tokens source of the JWT access token for the {@code auth} step
     * @return future completing with the hydrated session identity, or exceptionally with the
     *     connect/handshake failure (e.g. {@link AuthenticationException})
     */
    public CompletableFuture<SessionState> start(final TokenSource tokens) {
        return connection.connect().thenCompose(v -> stateSync.hydrate(handshake.perform(tokens)));
    }

    /**
     * Open the transport and run the full handshake: {@code connect → ask_session → session → auth
     * → welcome}, then hydrate the welcome into a {@link SessionState}. Runs synchronously,
     * blocking the calling thread until the welcome arrives or a bound is exceeded.
     *
     * @param tokens source of the JWT access token for the {@code auth} step
     * @param connectTimeout bound on the WebSocket open
     * @param handshakeTimeout bound on the {@code ask_session → welcome} exchange
     * @return the hydrated session identity from the {@code welcome} payload
     * @throws TimeoutException if the connect or the handshake exceeds its bound
     * @throws ExecutionException if the connect, handshake, or welcome decode fails; the cause is
     *     the underlying failure (e.g. {@link AuthenticationException})
     * @throws InterruptedException if the calling thread is interrupted while waiting
     */
    public SessionState connectAndAuthenticate(
            final TokenSource tokens,
            final Duration connectTimeout,
            final Duration handshakeTimeout)
            throws TimeoutException, ExecutionException, InterruptedException {
        connection.connect().get(connectTimeout.toMillis(), TimeUnit.MILLISECONDS);
        return stateSync
                .hydrate(handshake.perform(tokens))
                .get(handshakeTimeout.toMillis(), TimeUnit.MILLISECONDS);
    }

    /**
     * Block until the connection disconnects — a server-initiated close, a network drop, or a local
     * {@link #close()}. While blocked the session is idle; the transport keeps the connection alive
     * by auto-replying {@code pong} to server {@code ping}s.
     *
     * @return the disconnect event that ended the session
     * @throws InterruptedException if the calling thread is interrupted while waiting
     */
    public LobbyConnection.DisconnectEvent awaitDisconnect() throws InterruptedException {
        disconnected.await();
        return disconnectEvent;
    }

    /**
     * Whether the session's connection has already disconnected.
     *
     * @return {@code true} once a disconnect (of any kind) has fired
     */
    public boolean isDisconnected() {
        return disconnected.getCount() == 0;
    }

    /**
     * The disconnect event, if the session has ended.
     *
     * @return the disconnect event, or empty if the connection is still live
     */
    public Optional<LobbyConnection.DisconnectEvent> disconnectEvent() {
        return Optional.ofNullable(disconnectEvent);
    }

    /**
     * Initiate a clean close of the underlying connection (status 1000). Idempotent: a no-op once
     * the connection has already gone. The disconnect listener fires with {@link
     * LobbyConnection.DisconnectReason#LOCAL_CLOSE}, releasing {@link #awaitDisconnect()}.
     *
     * @return future that completes when the close frame has been sent
     */
    public CompletableFuture<Void> close() {
        return connection.close();
    }

    /**
     * The hydrated session identity, if the welcome has been received. Convenience pass-through to
     * the underlying {@link WelcomeStateSync}.
     *
     * @return the {@link SessionState}, or empty if no welcome has completed yet
     */
    public Optional<SessionState> sessionState() {
        return stateSync.sessionState();
    }
}
