package com.faforever.testharness.client.lobby;

import java.net.InetSocketAddress;
import java.net.URI;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.java_websocket.WebSocket;
import org.java_websocket.handshake.ClientHandshake;
import org.java_websocket.server.WebSocketServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * In-process WebSocket server used by the {@link LobbyConnection} tests. Binds to an OS-chosen free
 * port, captures every incoming text frame on a {@link BlockingQueue} the test can poll, and
 * exposes hooks for sending canned frames, closing cleanly, or terminating abruptly.
 *
 * <p>Lifecycle: {@code new ScriptedWebSocketServer().startAndAwait()} blocks until the server is
 * listening; the {@link #uri()} method returns the {@code ws://127.0.0.1:&lt;port&gt;} URL to point
 * a client at. Always close via {@link #stop()} in {@code @AfterEach}.
 *
 * <p>Every frame this server sends and receives is logged with a timestamp (#261, #268). Three
 * lobby tests have timed out waiting for a frame after the previous legs had succeeded, each re-run
 * green with nothing captured, and the question none of those runs could answer was whether the
 * frame was late or never sent at all. These lines and {@code LobbyConnection}'s own inbound-frame
 * log — enabled for the test task, see {@code mock-client/build.gradle} — put both ends of every
 * exchange in the Gradle test report a failing run uploads. The budgets are deliberately unchanged:
 * a longer timeout would hide the case worth knowing about.
 */
public final class ScriptedWebSocketServer extends WebSocketServer {

    /** Logger instance for this class. */
    private static final Logger LOG = LoggerFactory.getLogger(ScriptedWebSocketServer.class);

    private final CountDownLatch started = new CountDownLatch(1);
    private final CountDownLatch firstClientConnected = new CountDownLatch(1);
    private final BlockingQueue<String> received = new LinkedBlockingQueue<>();
    private final List<WebSocket> connections = new CopyOnWriteArrayList<>();

    public ScriptedWebSocketServer() {
        super(new InetSocketAddress("127.0.0.1", 0));
        setReuseAddr(true);
    }

    /** Start the server and block until it's listening. */
    public void startAndAwait() throws InterruptedException {
        start();
        if (!started.await(5, TimeUnit.SECONDS)) {
            throw new AssertionError("scripted WebSocket server failed to start within 5s");
        }
    }

    /** Block until the first client opens a connection. */
    public void awaitFirstClient() throws InterruptedException {
        if (!firstClientConnected.await(5, TimeUnit.SECONDS)) {
            throw new AssertionError("no client connected within 5s");
        }
    }

    /** URI a client should connect to. */
    public URI uri() {
        return URI.create("ws://127.0.0.1:" + getPort());
    }

    /** Poll the next text frame the server received, or fail after {@code timeout}. */
    public String pollReceived(final long timeout, final TimeUnit unit)
            throws InterruptedException {
        String msg = received.poll(timeout, unit);
        if (msg == null) {
            throw new AssertionError("no message received within " + timeout + " " + unit);
        }
        return msg;
    }

    /** Send a text frame to every connected client. */
    public void broadcastText(final String text) {
        // The connection count is the first line, before any send: #261 lost a `welcome` the
        // server believed it had broadcast, and a broadcast to zero connections is silent today.
        // The per-connection line after the send then says the write itself returned, so a frame
        // that was written but never arrived is distinguishable from one that was never written.
        LOG.debug("scripted server broadcasting to {} connection(s): {}", connections.size(), text);
        for (WebSocket c : connections) {
            c.send(text);
            LOG.debug("scripted server sent to {}: {}", c.getRemoteSocketAddress(), text);
        }
    }

    /** Send a clean WebSocket close to every connected client. */
    public void closeAllClean(final int code, final String reason) {
        for (WebSocket c : connections) {
            c.close(code, reason);
        }
    }

    /** Slam the underlying TCP socket without a close frame — simulates a network drop. */
    public void abruptlyTerminate() {
        for (WebSocket c : connections) {
            c.closeConnection(1006, "abrupt"); // 1006 = CLOSE_ABNORMAL, no close frame sent
        }
    }

    @Override
    public void onStart() {
        started.countDown();
    }

    @Override
    public void onOpen(final WebSocket conn, final ClientHandshake handshake) {
        connections.add(conn);
        firstClientConnected.countDown();
    }

    @Override
    public void onClose(
            final WebSocket conn, final int code, final String reason, final boolean remote) {
        connections.remove(conn);
    }

    @Override
    public void onMessage(final WebSocket conn, final String message) {
        // The receive side of the same question: #268 timed out in pollReceived waiting for the
        // client's `auth`, and only a line here says whether that frame ever reached the server.
        LOG.debug("scripted server received from {}: {}", conn.getRemoteSocketAddress(), message);
        received.add(message);
    }

    @Override
    public void onError(final WebSocket conn, final Exception ex) {
        // Tests that need to assert on errors do so via the client side; nothing to do here.
    }
}
