package com.faforever.testharness.client.lobby;

import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.channels.CancelledKeyException;
import java.nio.channels.SelectionKey;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;
import org.java_websocket.WebSocket;
import org.java_websocket.WebSocketImpl;
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
 *
 * <p>#415's capture answered it: the frame was queued but not written until the next write on its
 * connection, stranded by a race in Java-WebSocket's selector. So every send here returns only once
 * its frame is on the socket; see {@link #awaitWritten}.
 */
public final class ScriptedWebSocketServer extends WebSocketServer {

    /** Logger instance for this class. */
    private static final Logger LOG = LoggerFactory.getLogger(ScriptedWebSocketServer.class);

    /** How long {@link #awaitWritten} gives a queued frame to reach the socket. */
    private static final long WRITE_TIMEOUT_SECONDS = 5;

    private final CountDownLatch started = new CountDownLatch(1);
    private final CountDownLatch firstClientConnected = new CountDownLatch(1);
    private final BlockingQueue<String> received = new LinkedBlockingQueue<>();
    private final List<WebSocket> connections = new CopyOnWriteArrayList<>();

    /** The close code of each connection that closed, in order; see {@link #awaitClose}. */
    private final BlockingQueue<Integer> closes = new LinkedBlockingQueue<>();

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

    /**
     * Wait for the next connection to close and return its close code: {@code 1000} for a client
     * that sent a normal close frame, {@code 1006} for one that went away without one. Read it
     * before {@link #stop}, which closes whatever is still open and so records codes of its own.
     */
    public int awaitClose(final long timeout, final TimeUnit unit) throws InterruptedException {
        Integer code = closes.poll(timeout, unit);
        if (code == null) {
            throw new AssertionError("no connection closed within " + timeout + " " + unit);
        }
        return code;
    }

    /** Send a text frame to every connected client. */
    public void broadcastText(final String text) {
        // The connection count is the first line, before any send: #261 lost a `welcome` the
        // server believed it had broadcast, and a broadcast to zero connections is silent today.
        LOG.debug("scripted server broadcasting to {} connection(s): {}", connections.size(), text);
        for (WebSocket c : connections) {
            // send() only queues the frame, and the selector can strand it there (#415). Waiting
            // for the write means the test's next step starts with the frame on the socket.
            c.send(text);
            awaitWritten(c, text);
        }
    }

    /** Send a clean WebSocket close to every connected client. */
    public void closeAllClean(final int code, final String reason) {
        for (WebSocket c : connections) {
            LOG.debug(
                    "scripted server closing {} cleanly (code={}, reason={})",
                    c.getRemoteSocketAddress(),
                    code,
                    reason);
            c.close(code, reason);
            // The close frame goes through the same queue, so it can be stranded the same way. The
            // server drops the connection once the frame is out, so the "wrote" line can come
            // after onClose's "lost" line, or not at all.
            awaitWritten(c, "close frame (code=" + code + ")");
        }
    }

    /** Slam the underlying TCP socket without a close frame — simulates a network drop. */
    public void abruptlyTerminate() {
        for (WebSocket c : connections) {
            LOG.debug("scripted server abruptly terminating {}", c.getRemoteSocketAddress());
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
        LOG.debug("scripted server accepted {}", conn.getRemoteSocketAddress());
        firstClientConnected.countDown();
    }

    @Override
    public void onClose(
            final WebSocket conn, final int code, final String reason, final boolean remote) {
        connections.remove(conn);
        closes.add(code);
        // Dates the departure, so a later "broadcasting to 0 connection(s)" can be read against
        // the disconnect that caused it — #261 lost a welcome to an empty connection list.
        LOG.debug(
                "scripted server lost {} (code={}, reason={}, remote={})",
                conn.getRemoteSocketAddress(),
                code,
                reason,
                remote);
    }

    @Override
    public void onMessage(final WebSocket conn, final String message) {
        // The receive side of the same question: #268 timed out in pollReceived waiting for the
        // client's `auth`, and only a line here says whether that frame ever reached the server.
        //
        // Queued before logged, deliberately. Logback is synchronous here and writes both a
        // console line and a JSON file record, so logging first would put that I/O in front of
        // the message becoming visible to pollReceived — widening the very window this line was
        // added to measure, under the same contention all three flakes occurred under.
        received.add(message);
        LOG.debug("scripted server received from {}: {}", conn.getRemoteSocketAddress(), message);
    }

    @Override
    public void onError(final WebSocket conn, final Exception ex) {
        // Tests assert on errors via the client side, but a server-side throw was swallowed
        // entirely before this — and "the server threw while sending" is a live explanation for
        // both #261 and #268, so it must not be the one thing the report cannot show.
        LOG.warn(
                "scripted server error on {}: {}",
                conn == null ? "<no connection>" : conn.getRemoteSocketAddress(),
                ex.toString());
    }

    /**
     * Blocks until {@code conn} has written everything it queued, re-arming its write interest when
     * Java-WebSocket's selector has dropped it (#415). Package-private for {@code
     * ScriptedWebSocketServerTest}.
     *
     * <p>{@code WebSocketImpl.send} queues a frame, then sets the key's interest to {@code OP_READ
     * | OP_WRITE} from the sending thread. {@code WebSocketServer.doWrite} drains the queue on the
     * selector thread and, once it has seen it empty, sets the interest back to {@code OP_READ}.
     * When the sender's update lands between those two steps it is overwritten. The window is
     * narrow, but a scripted exchange aims at it: after a write-only selection the key stays in the
     * selected set with a stale write-ready bit, so every selector pass runs a no-op {@code
     * doWrite} on it until its next read. That read is what wakes the test to reply, and it also
     * takes the key out of the selected set, so a frame stranded in that pass stays queued until
     * the next {@code OP_WRITE} on the key. The code is the same in Java-WebSocket 1.5.7, 1.6.0 and
     * master.
     *
     * <p>The "wrote" line is logged once the queue is seen empty, about a millisecond after the
     * write itself and longer under load, so it can follow the client's own receive line.
     *
     * @param conn a connection of this server
     * @param what the frame, for the log and the failure message
     */
    static void awaitWritten(final WebSocket conn, final String what) {
        WebSocketImpl impl = (WebSocketImpl) conn;
        SelectionKey key = impl.getSelectionKey();
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(WRITE_TIMEOUT_SECONDS);
        while (!impl.outQueue.isEmpty() && key.isValid()) {
            // Give up at once, like the fixture's other waits: parkNanos returns straight away
            // while the flag is set, so waiting on would spin.
            if (Thread.currentThread().isInterrupted()) {
                throw new AssertionError(
                        "scripted server was interrupted waiting to write to "
                                + conn.getRemoteSocketAddress()
                                + ": "
                                + what);
            }
            try {
                // Checked twice: a write that finished after the loop test also leaves OP_READ.
                if ((key.interestOps() & SelectionKey.OP_WRITE) == 0 && !impl.outQueue.isEmpty()) {
                    LOG.debug(
                            "scripted server re-arming a write the selector dropped, to {}: {}",
                            conn.getRemoteSocketAddress(),
                            what);
                    key.interestOps(SelectionKey.OP_READ | SelectionKey.OP_WRITE);
                    key.selector().wakeup();
                }
            } catch (CancelledKeyException e) {
                break; // the connection closed under us; reported below
            }
            if (System.nanoTime() - deadline > 0) {
                throw new AssertionError(
                        "scripted server could not write to "
                                + conn.getRemoteSocketAddress()
                                + " within "
                                + WRITE_TIMEOUT_SECONDS
                                + "s: "
                                + what);
            }
            LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(1));
        }
        // "wrote" only while the key is valid: a send to an already-cancelled key empties the
        // queue without writing (WebSocketServer.onWriteDemand), and a close cancels the key.
        if (key.isValid()) {
            LOG.debug("scripted server wrote to {}: {}", conn.getRemoteSocketAddress(), what);
        } else if (!impl.outQueue.isEmpty()) {
            LOG.debug(
                    "scripted server lost {} before it could write: {}",
                    conn.getRemoteSocketAddress(),
                    what);
        }
    }
}
