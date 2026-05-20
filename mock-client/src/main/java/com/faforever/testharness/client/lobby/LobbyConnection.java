package com.faforever.testharness.client.lobby;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.time.Duration;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Single-connection WebSocket transport for the FAF lobby protocol. Encodes outgoing messages as
 * newline-terminated JSON (per the {@code ws_bridge_rs} compatibility convention documented in
 * {@code lobby-protocol-spec.md} §1), decodes incoming text frames as JSON tolerating trailing
 * whitespace, replies to {@code {"command":"ping"}} with {@code {"command":"pong"}} automatically,
 * and routes every other message to a handler registered against its {@code command} field.
 *
 * <p>This class is intentionally protocol-mechanism only: no auth handshake, no welcome state sync,
 * no reconnect-with-backoff. Higher layers register handlers for {@code session}, {@code welcome},
 * {@code authentication_failed}, {@code game_launch}, etc. ("No business logic in this class" per
 * the spec.)
 *
 * <p>Disconnects — server-initiated close, abrupt transport error, or a failed initial connect —
 * are surfaced exactly once via the {@link #onDisconnect(Consumer)} callback so a higher-level
 * reconnect FSM can react. The reconnect FSM itself is out of scope for this class.
 *
 * <p>Thread-safety:
 *
 * <ul>
 *   <li>{@link #send(JsonNode)} may be called from any thread. {@link java.net.http.WebSocket}
 *       requires the previous {@code sendText} {@code CompletionStage} to complete before the next
 *       {@code sendText} starts; the implementation serialises outgoing sends behind an internal
 *       chain so callers do not have to.
 *   <li>{@link #registerHandler(String, LobbyMessageHandler)} and {@link #onDisconnect(Consumer)}
 *       may be called before or after {@link #connect()}; handler lookups are concurrent-safe.
 *   <li>Handlers themselves are invoked serially on the listener thread — see {@link
 *       LobbyMessageHandler}.
 * </ul>
 */
public final class LobbyConnection {

    /** Default WebSocket open timeout. Tuned for the FAF test env over the public internet. */
    public static final Duration DEFAULT_CONNECT_TIMEOUT = Duration.ofSeconds(10);

    /**
     * SLF4J logger for the transport — see logback.xml for the {@code component=MockClient} MDC.
     */
    private static final Logger LOG = LoggerFactory.getLogger(LobbyConnection.class);

    /** Reason buckets reported to the disconnect callback. */
    public enum DisconnectReason {
        /** Failed before the WebSocket handshake completed. */
        CONNECT_FAILED,
        /** Peer sent a Close frame (clean close) — see {@link DisconnectEvent#statusCode}. */
        CLEAN_CLOSE,
        /**
         * Transport error (network drop, TLS failure, peer reset) — see {@link
         * DisconnectEvent#error}.
         */
        ABRUPT_CLOSE,
        /** {@link #close()} was called by this side. */
        LOCAL_CLOSE
    }

    /**
     * Single disconnect notification. The fields are populated according to the {@link #reason};
     * unrelated fields are left null/zero.
     *
     * @param reason coarse cause bucket
     * @param statusCode WebSocket close status code (only set for {@link
     *     DisconnectReason#CLEAN_CLOSE} and {@link DisconnectReason#LOCAL_CLOSE})
     * @param closeMessage WebSocket close reason text (only for the same two reasons)
     * @param error the throwable surfaced by the WebSocket listener (only for {@link
     *     DisconnectReason#CONNECT_FAILED} and {@link DisconnectReason#ABRUPT_CLOSE})
     */
    public record DisconnectEvent(
            DisconnectReason reason, int statusCode, String closeMessage, Throwable error) {}

    /** Default listener installed before {@link #onDisconnect(Consumer)} replaces it. */
    private static final Consumer<DisconnectEvent> NOOP_LISTENER = ignored -> {};

    /** Lobby endpoint this connection is bound to. */
    private final URI endpoint;

    /** HTTP client used to perform the WebSocket upgrade handshake. */
    private final HttpClient httpClient;

    /** Jackson mapper for outgoing-frame encode and incoming-frame decode. */
    private final ObjectMapper mapper;

    /** Initial handshake timeout. */
    private final Duration connectTimeout;

    /** Command-to-handler registry; lookups are concurrent-safe. */
    private final Map<String, LobbyMessageHandler> handlers = new ConcurrentHashMap<>();

    /** Commands we've already warned about; suppresses unhandled-command log spam. */
    private final Set<String> warnedUnknownCommands = ConcurrentHashMap.newKeySet();

    /** Latch ensuring the disconnect listener fires at most once. */
    private final AtomicBoolean disconnectFired = new AtomicBoolean(false);

    /** True iff {@link #close()} was called by this side; used to label the disconnect bucket. */
    private final AtomicBoolean closeRequested = new AtomicBoolean(false);

    /** Currently-installed disconnect listener; volatile so updates are seen by I/O threads. */
    private volatile Consumer<DisconnectEvent> disconnectListener = NOOP_LISTENER;

    /** Live WebSocket after {@link #connect()} succeeds; {@code null} before that. */
    private volatile WebSocket webSocket;

    /** Serialises {@code sendText} calls — see class-level thread-safety note. */
    private final Object sendLock = new Object();

    /** Tail of the serialised send chain; protected by {@link #sendLock}. */
    private CompletableFuture<?> sendChain = CompletableFuture.completedFuture(null);

    /**
     * Construct a connection bound to {@code endpoint}. The connection is not opened until {@link
     * #connect()} is called.
     *
     * @param endpoint WebSocket URI (e.g. {@code wss://lobby.faforever.xyz})
     */
    public LobbyConnection(final URI endpoint) {
        this(endpoint, HttpClient.newHttpClient(), new ObjectMapper(), DEFAULT_CONNECT_TIMEOUT);
    }

    /**
     * Full-control constructor for tests.
     *
     * @param endpoint WebSocket URI
     * @param httpClient HTTP client to use for the upgrade
     * @param mapper Jackson mapper for encode/decode
     * @param connectTimeout timeout for the initial WebSocket handshake
     */
    public LobbyConnection(
            final URI endpoint,
            final HttpClient httpClient,
            final ObjectMapper mapper,
            final Duration connectTimeout) {
        this.endpoint = endpoint;
        this.httpClient = httpClient;
        this.mapper = mapper;
        this.connectTimeout = connectTimeout;
    }

    /**
     * Register a handler for messages with the given {@code command} value. Replaces any handler
     * previously registered under the same command.
     *
     * <p>Reserved commands: {@code ping} cannot be overridden — the connection always replies with
     * {@code pong} on its own. Attempting to register a {@code ping} handler throws.
     *
     * @param command value of the JSON {@code command} field this handler should receive
     * @param handler callback invoked for matching messages
     * @throws IllegalArgumentException if {@code command} is reserved
     */
    public void registerHandler(final String command, final LobbyMessageHandler handler) {
        if ("ping".equals(command)) {
            throw new IllegalArgumentException(
                    "'ping' is handled internally — register for 'pong' if you need the "
                            + "round-trip echo, not 'ping'");
        }
        handlers.put(command, handler);
    }

    /**
     * Install a disconnect listener. The listener fires exactly once per {@link LobbyConnection}
     * instance, regardless of whether the cause is a clean close, an abrupt error, or a {@link
     * #close()} call. Calling this more than once replaces the previous listener.
     *
     * @param listener receives the disconnect event; {@code null} is equivalent to a no-op
     */
    public void onDisconnect(final Consumer<DisconnectEvent> listener) {
        this.disconnectListener = listener == null ? NOOP_LISTENER : listener;
    }

    /**
     * Open the WebSocket. The returned future completes when the WebSocket handshake is done.
     *
     * <p>If the handshake fails, the future completes exceptionally <em>and</em> the disconnect
     * listener fires with {@link DisconnectReason#CONNECT_FAILED}, so callers can choose either
     * style.
     *
     * @return future that completes once connected
     */
    public CompletableFuture<Void> connect() {
        return httpClient
                .newWebSocketBuilder()
                .connectTimeout(connectTimeout)
                .buildAsync(endpoint, new Listener())
                .handle(this::onHandshakeComplete);
    }

    private Void onHandshakeComplete(final WebSocket socket, final Throwable error) {
        if (error != null) {
            Throwable cause = error instanceof CompletionException ? error.getCause() : error;
            LOG.warn(
                    "lobby WebSocket connect failed: {}: {}",
                    cause.getClass().getSimpleName(),
                    cause.getMessage());
            fireDisconnect(new DisconnectEvent(DisconnectReason.CONNECT_FAILED, 0, null, cause));
            throw new CompletionException(cause);
        }
        this.webSocket = socket;
        LOG.info("lobby WebSocket connected: {}", endpoint);
        return null;
    }

    /**
     * Send a JSON message. Encodes to a single newline-terminated text frame. Safe to call from any
     * thread; concurrent calls are serialised internally.
     *
     * @param message a JSON object — typically built with {@link ObjectMapper#createObjectNode()}
     * @return future that completes when the frame has been handed to the OS socket
     * @throws IllegalStateException if {@link #connect()} has not completed
     */
    public CompletableFuture<WebSocket> send(final JsonNode message) {
        WebSocket socket = webSocket;
        if (socket == null) {
            throw new IllegalStateException(
                    "send() called before connect() completed — connect() must finish first");
        }
        String payload;
        try {
            payload = mapper.writeValueAsString(message) + "\n";
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            CompletableFuture<WebSocket> failed = new CompletableFuture<>();
            failed.completeExceptionally(e);
            return failed;
        }

        synchronized (sendLock) {
            CompletableFuture<WebSocket> next =
                    sendChain
                            .thenCompose(ignored -> socket.sendText(payload, true))
                            .toCompletableFuture();
            // Reset the chain to a non-failed stage so one failed send doesn't poison every
            // subsequent send; callers see failures on their own returned future.
            sendChain = next.exceptionally(e -> null);
            return next;
        }
    }

    /**
     * Initiate a clean WebSocket close from this side. The disconnect listener fires with {@link
     * DisconnectReason#LOCAL_CLOSE} once the close handshake completes (or immediately if the
     * connection is already gone).
     *
     * @param statusCode WebSocket close status code (1000 = normal)
     * @param reason human-readable close reason (empty string allowed)
     * @return future that completes when the close frame has been sent
     */
    public CompletableFuture<Void> close(final int statusCode, final String reason) {
        closeRequested.set(true);
        WebSocket socket = webSocket;
        if (socket == null) {
            fireDisconnect(
                    new DisconnectEvent(DisconnectReason.LOCAL_CLOSE, statusCode, reason, null));
            return CompletableFuture.completedFuture(null);
        }
        return socket.sendClose(statusCode, reason).thenAccept(ignored -> {});
    }

    /**
     * Convenience: normal-close shortcut (status 1000, no reason text).
     *
     * @return future that completes when the close frame has been sent
     */
    public CompletableFuture<Void> close() {
        return close(WebSocket.NORMAL_CLOSURE, "");
    }

    private void fireDisconnect(final DisconnectEvent event) {
        if (disconnectFired.compareAndSet(false, true)) {
            try {
                disconnectListener.accept(event);
            } catch (RuntimeException e) {
                LOG.warn(
                        "disconnect listener threw {}: {}",
                        e.getClass().getSimpleName(),
                        e.getMessage());
            }
        }
    }

    private void dispatch(final String text) {
        // The bridge may pass through a trailing \n; trim defensively before parsing.
        String trimmed = text.strip();
        if (trimmed.isEmpty()) {
            return;
        }
        JsonNode node;
        try {
            node = mapper.readTree(trimmed);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            LOG.warn("dropping malformed lobby frame: {}", e.getOriginalMessage());
            return;
        }
        LOG.debug("lobby received frame: {}", trimmed);
        JsonNode commandNode = node.get("command");
        if (commandNode == null || !commandNode.isTextual()) {
            LOG.warn("dropping lobby frame without textual 'command' field");
            return;
        }
        String command = commandNode.asText();

        if ("ping".equals(command)) {
            replyPong();
            return;
        }

        LobbyMessageHandler handler = handlers.get(command);
        if (handler == null) {
            if (warnedUnknownCommands.add(command)) {
                LOG.warn(
                        "unhandled lobby command '{}' (will be silent for subsequent occurrences)",
                        command);
            }
            return;
        }
        try {
            handler.onMessage(node);
        } catch (RuntimeException e) {
            LOG.warn(
                    "handler for '{}' threw {}: {}",
                    command,
                    e.getClass().getSimpleName(),
                    e.getMessage());
        }
    }

    private void replyPong() {
        ObjectNode pong = mapper.createObjectNode();
        pong.put("command", "pong");
        send(pong);
    }

    /** Adapter from {@link WebSocket.Listener} into {@link #dispatch(String)} + disconnect bus. */
    private final class Listener implements WebSocket.Listener {

        /** Accumulates partial text frames; the WebSocket API may split a JSON message. */
        private final StringBuilder partial = new StringBuilder();

        @Override
        public void onOpen(final WebSocket socket) {
            socket.request(1);
        }

        @Override
        public CompletionStage<?> onText(
                final WebSocket socket, final CharSequence data, final boolean last) {
            partial.append(data);
            if (last) {
                String full = partial.toString();
                partial.setLength(0);
                try {
                    dispatch(full);
                } catch (RuntimeException e) {
                    LOG.warn("lobby dispatch threw: {}", e.toString());
                }
            }
            socket.request(1);
            return null;
        }

        @Override
        public CompletionStage<?> onClose(
                final WebSocket socket, final int statusCode, final String reasonText) {
            DisconnectReason bucket =
                    closeRequested.get()
                            ? DisconnectReason.LOCAL_CLOSE
                            : DisconnectReason.CLEAN_CLOSE;
            LOG.info(
                    "lobby WebSocket closed: code={} reason='{}' bucket={}",
                    statusCode,
                    reasonText,
                    bucket);
            fireDisconnect(new DisconnectEvent(bucket, statusCode, reasonText, null));
            return null;
        }

        @Override
        public void onError(final WebSocket socket, final Throwable error) {
            LOG.warn(
                    "lobby WebSocket error: {}: {}",
                    error.getClass().getSimpleName(),
                    error.getMessage());
            fireDisconnect(new DisconnectEvent(DisconnectReason.ABRUPT_CLOSE, 0, null, error));
        }
    }
}
