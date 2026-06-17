package com.faforever.testharness.client.ice;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.MappingIterator;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * JSON-RPC 2.0 transport over the loopback TCP socket to {@code faf-ice-adapter} (the adapter
 * listens, this connects). The local-IPC counterpart of {@code LobbyConnection}: it opens the
 * socket with bounded retry (the adapter subprocess may still be binding), frames messages,
 * correlates request→response by {@code id}, dispatches inbound notifications, and surfaces
 * disconnects. It implements <em>no</em> specific RPC method — callers issue them via {@link
 * #call}.
 *
 * <p>Threading: one reader thread (started by {@link #connect()}) does connect-with-retry then runs
 * the blocking read loop; its lifetime is the connection's. Outbound writes happen on caller
 * threads, serialised by an internal lock. Notification handlers and the disconnect listener run on
 * the reader thread, so they must not block — hand off to another executor if needed.
 *
 * <p>Wire framing follows {@code documentation/research/json-rpc-spec.md} §2: outbound frames are
 * compact UTF-8 JSON terminated by {@code \n}; inbound frames are read via Jackson's {@link
 * MappingIterator} over the socket stream, which (unlike the adapter's own brace-counter, §2.1)
 * does not desync on a string value containing {@code {} or {@code }}.
 */
public final class IceAdapterConnection {

    /** SLF4J logger; no credentials flow on this channel. */
    private static final Logger LOG = LoggerFactory.getLogger(IceAdapterConnection.class);

    /** The adapter binds its JSON-RPC server on loopback only. */
    private static final String LOOPBACK = "127.0.0.1";

    /** Default max connect attempts while the adapter is still binding. */
    private static final int DEFAULT_CONNECT_ATTEMPTS = 20;

    /** Default delay between connect attempts. */
    private static final Duration DEFAULT_RETRY_DELAY = Duration.ofMillis(100);

    /** Default time a {@link #call} waits for its response before failing. */
    private static final Duration DEFAULT_CALL_TIMEOUT = Duration.ofSeconds(5);

    /** Coarse cause for the connection going down, reported to {@link #onDisconnect}. */
    public enum DisconnectReason {
        /** Never connected — every connect attempt failed. */
        CONNECT_FAILED,
        /** Adapter closed the socket, a read error occurred, or the peer reset. */
        REMOTE_CLOSE,
        /** {@link #close()} was called by this side. */
        LOCAL_CLOSE
    }

    /**
     * One-shot disconnect notification.
     *
     * @param reason coarse cause
     * @param error the throwable that ended the connection, or {@code null} for a clean local close
     */
    public record DisconnectEvent(DisconnectReason reason, Throwable error) {}

    /** Adapter JSON-RPC port on {@link #LOOPBACK}. */
    private final int port;

    /** Max connect attempts before {@link #connect()} fails. */
    private final int connectAttempts;

    /** Delay between connect attempts. */
    private final Duration retryDelay;

    /** How long a {@link #call} waits for its response before completing exceptionally. */
    private final Duration callTimeout;

    /** Jackson mapper for encoding outbound and decoding inbound frames. */
    private final ObjectMapper mapper = new ObjectMapper();

    /**
     * In-flight requests by id; entries are removed on completion (response, timeout, or close).
     */
    private final Map<Long, CompletableFuture<JsonNode>> pending = new ConcurrentHashMap<>();

    /** Monotonic request id; starts at 1 (spec §3.1 reserves 0). */
    private final AtomicLong nextId = new AtomicLong(1);

    /**
     * Notification name → handlers; multiple may register against the same name, and each name's
     * list iterates lock-free on the reader thread.
     */
    private final Map<String, List<Consumer<JsonNode>>> notificationHandlers =
            new ConcurrentHashMap<>();

    /** Notification names already warned about, to suppress unhandled-notification log spam. */
    private final Set<String> warnedUnknown = ConcurrentHashMap.newKeySet();

    /** Serialises outbound writes so concurrent {@link #call}s don't interleave bytes. */
    private final Object writeLock = new Object();

    /** Guards against {@link #connect()} being called more than once. */
    private final AtomicBoolean started = new AtomicBoolean(false);

    /** True once {@link #close()} was called — lets the reader label the disconnect LOCAL_CLOSE. */
    private final AtomicBoolean closeRequested = new AtomicBoolean(false);

    /** Latch ensuring the disconnect listener fires at most once. */
    private final AtomicBoolean disconnectFired = new AtomicBoolean(false);

    /** Currently-installed disconnect listener; volatile so the reader thread sees updates. */
    private volatile Consumer<DisconnectEvent> disconnectListener = ignored -> {};

    /** Live socket after a successful connect; {@code null} before. */
    private volatile Socket socket;

    /** Output stream of {@link #socket}; written under {@link #writeLock}. */
    private volatile OutputStream out;

    /**
     * Construct a connection to {@code 127.0.0.1:port} with default retry and call-timeout
     * settings. The socket is not opened until {@link #connect()} is called.
     *
     * @param port the adapter's JSON-RPC port (its {@code --rpc-port})
     */
    public IceAdapterConnection(final int port) {
        this(port, DEFAULT_CONNECT_ATTEMPTS, DEFAULT_RETRY_DELAY, DEFAULT_CALL_TIMEOUT);
    }

    /**
     * Full-control constructor — used by tests to tune retry and timeout for fast, deterministic
     * runs.
     *
     * @param port the adapter's JSON-RPC port
     * @param connectAttempts max connect attempts before failing
     * @param retryDelay delay between connect attempts
     * @param callTimeout how long a {@link #call} waits for its response before failing
     */
    IceAdapterConnection(
            final int port,
            final int connectAttempts,
            final Duration retryDelay,
            final Duration callTimeout) {
        this.port = port;
        this.connectAttempts = connectAttempts;
        this.retryDelay = retryDelay;
        this.callTimeout = callTimeout;
    }

    /**
     * Open the socket (retrying while the adapter binds) and start the reader. The returned future
     * completes once the socket is open and the read loop is running; if every attempt fails it
     * completes exceptionally <em>and</em> the disconnect listener fires with {@link
     * DisconnectReason#CONNECT_FAILED}.
     *
     * @return future that completes once connected
     * @throws IllegalStateException if called more than once
     */
    public CompletableFuture<Void> connect() {
        if (!started.compareAndSet(false, true)) {
            throw new IllegalStateException("connect() may only be called once");
        }
        CompletableFuture<Void> connected = new CompletableFuture<>();
        Thread reader = new Thread(() -> runConnection(connected), "ice-adapter-conn");
        reader.setDaemon(true);
        reader.start();
        return connected;
    }

    private void runConnection(final CompletableFuture<Void> connected) {
        final Socket opened;
        try {
            opened = connectWithRetry();
            this.out = opened.getOutputStream();
        } catch (IOException e) {
            LOG.warn(
                    "could not connect to ICE adapter at {}:{}: {}",
                    LOOPBACK,
                    port,
                    e.getMessage());
            connected.completeExceptionally(e);
            fireDisconnect(new DisconnectEvent(DisconnectReason.CONNECT_FAILED, e));
            return;
        }
        this.socket = opened;
        if (closeRequested.get()) {
            // close() raced the connect while we were still retrying — honour it.
            try {
                opened.close();
            } catch (IOException ignored) {
                // best effort
            }
            connected.completeExceptionally(new IOException("connection closed during connect"));
            return;
        }
        LOG.info("connected to ICE adapter JSON-RPC at {}:{}", LOOPBACK, port);
        connected.complete(null);
        readLoop(opened);
    }

    private Socket connectWithRetry() throws IOException {
        IOException last = null;
        for (int attempt = 1; attempt <= connectAttempts; attempt++) {
            try {
                return new Socket(LOOPBACK, port);
            } catch (IOException e) {
                last = e;
                if (attempt < connectAttempts) {
                    try {
                        Thread.sleep(retryDelay.toMillis());
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        throw new IOException("interrupted while connecting to ICE adapter", ie);
                    }
                }
            }
        }
        throw new IOException(
                "ICE adapter not reachable at "
                        + LOOPBACK
                        + ":"
                        + port
                        + " after "
                        + connectAttempts
                        + " attempts",
                last);
    }

    private void readLoop(final Socket connectedSocket) {
        Throwable error = null;
        try {
            MappingIterator<JsonNode> frames =
                    mapper.readerFor(JsonNode.class).readValues(connectedSocket.getInputStream());
            while (frames.hasNextValue()) {
                route(frames.nextValue());
            }
        } catch (IOException e) {
            // Also catches Jackson parse errors from a desynced stream: a brace-framed JSON-RPC
            // reader can't reliably resync mid-stream, and spec §1 treats a dead socket as a dead
            // adapter — so end the connection rather than skip one frame (unlike LobbyConnection).
            error = e;
        } finally {
            DisconnectReason reason =
                    closeRequested.get()
                            ? DisconnectReason.LOCAL_CLOSE
                            : DisconnectReason.REMOTE_CLOSE;
            fireDisconnect(new DisconnectEvent(reason, error));
            failAllPending(reason, error);
        }
    }

    private void route(final JsonNode msg) {
        LOG.debug("ICE adapter received frame: {}", msg);
        JsonNode idNode = msg.get("id");
        boolean hasId = idNode != null && !idNode.isNull();
        boolean hasMethod = msg.hasNonNull("method");

        if (hasId && (msg.has("result") || msg.has("error"))) {
            completePending(idNode.asLong(), msg);
        } else if (hasMethod && !hasId) {
            dispatchNotification(msg.get("method").asText(), msg);
        } else if (hasMethod) {
            // method + id = an inbound request; the adapter never sends these (spec §3.4). Ignore.
            LOG.warn(
                    "ignoring unexpected inbound JSON-RPC request '{}' (id={})",
                    msg.get("method").asText(),
                    idNode.asLong());
        } else {
            LOG.warn("dropping unrecognised JSON-RPC frame: {}", msg);
        }
    }

    private void completePending(final long id, final JsonNode response) {
        CompletableFuture<JsonNode> future = pending.remove(id);
        if (future == null) {
            LOG.warn("ICE adapter response for unknown/expired id {}", id);
            return;
        }
        if (response.has("error")) {
            JsonNode err = response.get("error");
            future.completeExceptionally(
                    new IceRpcException(err.path("code").asInt(), err.path("message").asText("")));
        } else {
            future.complete(response.get("result"));
        }
    }

    private void dispatchNotification(final String method, final JsonNode msg) {
        List<Consumer<JsonNode>> handlers = notificationHandlers.get(method);
        if (handlers == null || handlers.isEmpty()) {
            if (warnedUnknown.add(method)) {
                LOG.warn(
                        "unhandled ICE adapter notification '{}': {} (repeats suppressed)",
                        method,
                        msg);
            }
            return;
        }
        // Each consumer in its own try/catch: a throwing one must not stop the others or kill the
        // reader thread.
        for (Consumer<JsonNode> handler : handlers) {
            try {
                handler.accept(msg);
            } catch (RuntimeException e) {
                LOG.warn(
                        "notification handler for '{}' threw {}: {}",
                        method,
                        e.getClass().getSimpleName(),
                        e.getMessage());
            }
        }
    }

    /**
     * Issue a JSON-RPC request and await its response. Assigns a fresh id, sends {@code {jsonrpc,
     * method, params, id}}, and returns a future that completes with the response {@code result}
     * (possibly a JSON null), or completes exceptionally with {@link IceRpcException} (error
     * response), a {@link java.util.concurrent.TimeoutException} (no response within the call
     * timeout), or an {@link IOException} (send failure / disconnect).
     *
     * <p>Each {@code params} argument becomes one element of the JSON-RPC {@code params} array, so
     * a method taking a single array parameter (e.g. {@code setIceServers}) must be passed that
     * array as one argument, not its elements spread across the varargs.
     *
     * @param method JSON-RPC method name
     * @param params positional parameters (each converted to JSON); may be empty
     * @return future completing with the response result
     */
    public CompletableFuture<JsonNode> call(final String method, final Object... params) {
        long id = nextId.getAndIncrement();
        CompletableFuture<JsonNode> future = new CompletableFuture<>();
        pending.put(id, future);

        ObjectNode request = mapper.createObjectNode();
        request.put("jsonrpc", "2.0");
        request.put("method", method);
        ArrayNode array = request.putArray("params");
        for (Object param : params) {
            array.add(mapper.valueToTree(param));
        }
        request.put("id", id);

        try {
            write(request);
        } catch (IOException e) {
            pending.remove(id);
            future.completeExceptionally(
                    new IOException("failed to send '" + method + "' to ICE adapter", e));
            return future;
        }

        future.orTimeout(callTimeout.toMillis(), TimeUnit.MILLISECONDS);
        future.whenComplete((result, error) -> pending.remove(id));
        return future;
    }

    /**
     * Register a handler for an inbound notification (e.g. {@code onIceMsg}). Multiple handlers may
     * register against the same name; each is invoked in registration order on the reader thread
     * when a matching notification arrives. Notifications with no registered handler are logged
     * once and dropped.
     *
     * @param name the notification {@code method} name
     * @param handler invoked with the full notification node on the reader thread (must not block)
     */
    public void registerNotification(final String name, final Consumer<JsonNode> handler) {
        notificationHandlers
                .computeIfAbsent(name, ignored -> new CopyOnWriteArrayList<>())
                .add(handler);
    }

    /**
     * Install the disconnect listener. Fires exactly once — on connect failure, remote close/read
     * error, or {@link #close()} — regardless of cause. Replacing it before disconnect is allowed.
     *
     * @param listener receives the disconnect event; {@code null} installs a no-op
     */
    public void onDisconnect(final Consumer<DisconnectEvent> listener) {
        this.disconnectListener = listener == null ? ignored -> {} : listener;
    }

    /**
     * Close the socket from this side. The reader thread observes the close, fires the disconnect
     * listener with {@link DisconnectReason#LOCAL_CLOSE}, and fails any in-flight {@link #call}
     * futures.
     */
    public void close() {
        closeRequested.set(true);
        Socket current = socket;
        if (current == null) {
            // connect() never succeeded (or wasn't called) — surface a local close directly.
            fireDisconnect(new DisconnectEvent(DisconnectReason.LOCAL_CLOSE, null));
            return;
        }
        try {
            current.close();
        } catch (IOException e) {
            LOG.warn("error closing ICE adapter socket: {}", e.getMessage());
        }
    }

    private void write(final JsonNode message) throws IOException {
        OutputStream stream = out;
        if (stream == null) {
            throw new IOException("ICE adapter socket not connected");
        }
        final byte[] bytes;
        try {
            bytes = (mapper.writeValueAsString(message) + "\n").getBytes(StandardCharsets.UTF_8);
        } catch (JsonProcessingException e) {
            throw new IOException("could not serialise JSON-RPC frame", e);
        }
        synchronized (writeLock) {
            stream.write(bytes);
            stream.flush();
        }
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

    private void failAllPending(final DisconnectReason reason, final Throwable error) {
        IOException cause =
                new IOException("ICE adapter connection closed (" + reason + ")", error);
        for (CompletableFuture<JsonNode> future : pending.values()) {
            future.completeExceptionally(cause);
        }
        pending.clear();
    }
}
