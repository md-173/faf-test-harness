package com.faforever.testharness.game.gpgnet;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * GPGNet transport over the loopback TCP socket to the faf-ice-adapter's GPGNet server (the adapter
 * listens, the mock game connects as client — json-rpc-spec §8). The GPGNet counterpart of the mock
 * client's {@code IceAdapterConnection}: it opens the socket with bounded retry (the adapter
 * subprocess may still be binding), runs a blocking read loop that decodes frames via {@link
 * GpgNetCodec} and hands each to a registered consumer, exposes a {@link #send(GpgNetFrame)}
 * primitive, and surfaces disconnects. It carries <em>no</em> message semantics — which frames to
 * send and how to react to inbound ones live in the dispatcher (3.2.2.2), sender (3.2.2.3), and
 * lifecycle controller.
 *
 * <p>Threading: one reader thread (started by {@link #connect()}) does connect-with-retry then runs
 * the blocking read loop; its lifetime is the connection's. Outbound {@link #send} writes happen on
 * caller threads, serialised by an internal lock. The frame consumer and disconnect listener run on
 * the reader thread, so they must not block — hand off to another executor if needed.
 *
 * <p>Frame boundaries are recovered structurally (§2.1); a malformed or truncated frame ends the
 * read loop and closes the connection with no attempt to resync (§5.3), and a clean socket close
 * likewise surfaces as a disconnect.
 */
public final class GpgNetConnection implements GpgNetFrameSink {

    /** SLF4J logger — see logback.xml for the {@code component=MockGame} MDC. */
    private static final Logger LOG = LoggerFactory.getLogger(GpgNetConnection.class);

    /** The adapter binds its GPGNet server on loopback only. */
    private static final String LOOPBACK = "127.0.0.1";

    /** Default max connect attempts while the adapter is still binding. */
    private static final int DEFAULT_CONNECT_ATTEMPTS = 20;

    /** Default delay between connect attempts. */
    private static final Duration DEFAULT_RETRY_DELAY = Duration.ofMillis(100);

    /** No-op consumer installed before {@link #onFrame(Consumer)} replaces it. */
    private static final Consumer<GpgNetFrame> NOOP_CONSUMER = ignored -> {};

    /** No-op listener installed before {@link #onDisconnect(Consumer)} replaces it. */
    private static final Consumer<DisconnectEvent> NOOP_LISTENER = ignored -> {};

    /** Coarse cause for the connection going down, reported to {@link #onDisconnect}. */
    public enum DisconnectReason {
        /** Never connected — every connect attempt failed. */
        CONNECT_FAILED,
        /** Adapter closed the socket, a read/parse error occurred, or the peer reset. */
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

    /** Adapter GPGNet port on {@link #LOOPBACK}. */
    private final int port;

    /** Max connect attempts before {@link #connect()} fails. */
    private final int connectAttempts;

    /** Delay between connect attempts. */
    private final Duration retryDelay;

    /** Serialises outbound writes so concurrent {@link #send}s don't interleave bytes. */
    private final Object writeLock = new Object();

    /** Guards against {@link #connect()} being called more than once. */
    private final AtomicBoolean started = new AtomicBoolean(false);

    /** True once {@link #close()} was called — lets the reader label the disconnect LOCAL_CLOSE. */
    private final AtomicBoolean closeRequested = new AtomicBoolean(false);

    /** Latch ensuring the disconnect listener fires at most once. */
    private final AtomicBoolean disconnectFired = new AtomicBoolean(false);

    /** Consumer for each decoded inbound frame; volatile so the reader thread sees updates. */
    private volatile Consumer<GpgNetFrame> frameConsumer = NOOP_CONSUMER;

    /** Currently-installed disconnect listener; volatile so the reader thread sees updates. */
    private volatile Consumer<DisconnectEvent> disconnectListener = NOOP_LISTENER;

    /** Live socket after a successful connect; {@code null} before. */
    private volatile Socket socket;

    /** Output stream of {@link #socket}; written under {@link #writeLock}. */
    private volatile OutputStream out;

    /**
     * Construct a connection to {@code 127.0.0.1:port} with default retry settings. The socket is
     * not opened until {@link #connect()} is called.
     *
     * @param port the adapter's GPGNet port (its {@code --gpgnet-port})
     */
    public GpgNetConnection(final int port) {
        this(port, DEFAULT_CONNECT_ATTEMPTS, DEFAULT_RETRY_DELAY);
    }

    /**
     * Full-control constructor — used by tests to tune retry for fast, deterministic runs.
     *
     * @param port the adapter's GPGNet port
     * @param connectAttempts max connect attempts before failing
     * @param retryDelay delay between connect attempts
     */
    public GpgNetConnection(final int port, final int connectAttempts, final Duration retryDelay) {
        this.port = port;
        this.connectAttempts = connectAttempts;
        this.retryDelay = retryDelay;
    }

    /**
     * Register the consumer for decoded inbound frames. Invoked on the reader thread for each
     * frame, in the order frames arrive; must not block. Replacing it before or during the
     * connection is allowed. A single consumer is installed — the dispatcher (3.2.2.2) registers
     * itself here.
     *
     * @param consumer receives each decoded frame; {@code null} installs a no-op
     */
    public void onFrame(final Consumer<GpgNetFrame> consumer) {
        this.frameConsumer = consumer == null ? NOOP_CONSUMER : consumer;
    }

    /**
     * Install the disconnect listener. Fires exactly once — on connect failure, remote close/read
     * error, or {@link #close()} — regardless of cause. Replacing it before disconnect is allowed.
     *
     * @param listener receives the disconnect event; {@code null} installs a no-op
     */
    public void onDisconnect(final Consumer<DisconnectEvent> listener) {
        this.disconnectListener = listener == null ? NOOP_LISTENER : listener;
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
        Thread reader = new Thread(() -> runConnection(connected), "gpgnet-conn");
        reader.setDaemon(true);
        reader.start();
        return connected;
    }

    /**
     * Send a frame. Encodes it via {@link GpgNetCodec} and writes it to the socket under the write
     * lock. This is a transport primitive — it has no opinion about <em>which</em> frames are valid
     * to send; the sender (3.2.2.3) builds them.
     *
     * @param frame the frame to send
     * @throws IOException if the socket is not connected or the write fails
     * @throws IllegalArgumentException if the frame exceeds the codec's chunk cap
     */
    @Override
    public void send(final GpgNetFrame frame) throws IOException {
        byte[] bytes = GpgNetCodec.encode(frame);
        OutputStream stream = out;
        if (stream == null) {
            throw new IOException("GPGNet socket not connected");
        }
        synchronized (writeLock) {
            stream.write(bytes);
            stream.flush();
        }
        LOG.debug("GPGNet sent frame: {}", frame);
    }

    /**
     * Close the socket from this side. The reader thread observes the close and fires the
     * disconnect listener with {@link DisconnectReason#LOCAL_CLOSE}.
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
            LOG.warn("error closing GPGNet socket: {}", e.getMessage());
        }
    }

    private void runConnection(final CompletableFuture<Void> connected) {
        final Socket opened;
        try {
            opened = connectWithRetry();
            this.out = opened.getOutputStream();
        } catch (IOException e) {
            LOG.warn(
                    "could not connect to GPGNet server at {}:{}: {}",
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
        LOG.info("connected to GPGNet server at {}:{}", LOOPBACK, port);
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
                        throw new IOException("interrupted while connecting to GPGNet server", ie);
                    }
                }
            }
        }
        throw new IOException(
                "GPGNet server not reachable at "
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
            InputStream in = new BufferedInputStream(connectedSocket.getInputStream());
            while (true) {
                dispatchFrame(GpgNetCodec.readFrame(in));
            }
        } catch (IOException e) {
            // Covers a clean close (EOF at a frame boundary) and a malformed/truncated frame alike:
            // there is no resync for GPGNet (§5.3), so any read/parse error ends the connection.
            error = e;
        } finally {
            // The socket must not outlive the read loop: a parse error alone leaves it
            // established, so send() would keep succeeding while the adapter's writes back up
            // unread. Socket.close() is idempotent, so the local-close path is unaffected.
            try {
                connectedSocket.close();
            } catch (IOException e) {
                LOG.warn("error closing GPGNet socket: {}", e.getMessage());
            }
            DisconnectReason reason =
                    closeRequested.get()
                            ? DisconnectReason.LOCAL_CLOSE
                            : DisconnectReason.REMOTE_CLOSE;
            fireDisconnect(new DisconnectEvent(reason, error));
        }
    }

    private void dispatchFrame(final GpgNetFrame frame) {
        LOG.debug("GPGNet received frame: {}", frame);
        try {
            frameConsumer.accept(frame);
        } catch (RuntimeException e) {
            // A throwing consumer must not kill the reader thread.
            LOG.warn(
                    "GPGNet frame consumer threw {}: {}",
                    e.getClass().getSimpleName(),
                    e.getMessage());
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
}
