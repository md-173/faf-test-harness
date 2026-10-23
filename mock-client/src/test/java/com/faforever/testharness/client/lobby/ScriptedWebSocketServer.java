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
import org.java_websocket.drafts.Draft;
import org.java_websocket.drafts.Draft_6455;
import org.java_websocket.enums.Opcode;
import org.java_websocket.exceptions.InvalidDataException;
import org.java_websocket.framing.CloseFrame;
import org.java_websocket.framing.Framedata;
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
 *
 * <p>The server can also hold its reply to a client's close, so a test can see a client wait for
 * that reply (#510); see {@link #holdCloseReplies()}.
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

    /** The hold every connection's draft shares; see {@link #holdCloseReplies()}. */
    private final CloseHold closeHold;

    public ScriptedWebSocketServer() {
        this(new CloseHold());
    }

    /**
     * Binds to a free loopback port, with a draft that can hold each client's close.
     *
     * @param closeHold the hold every connection's draft shares
     */
    private ScriptedWebSocketServer(final CloseHold closeHold) {
        super(new InetSocketAddress("127.0.0.1", 0), List.of(new HoldingDraft(closeHold)));
        this.closeHold = closeHold;
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

    /**
     * From now on, hold this server's reply to each client's close until {@link
     * #releaseCloseReplies()} (#510). The close is read and the reply waits, so a test can see a
     * client that waits for it, and can still send that client frames meanwhile.
     *
     * <p>A held close keeps its connection's worker, and with it the connection's only read buffer,
     * so a client that sent anything more would stall every write until the release. The JDK client
     * sends nothing after its close.
     */
    public void holdCloseReplies() {
        closeHold.hold();
    }

    /** Wait until a client's close has been read and held, and return its close code. */
    public int awaitHeldClose(final long timeout, final TimeUnit unit) throws InterruptedException {
        Integer code = closeHold.awaitHeld(timeout, unit);
        if (code == null) {
            throw new AssertionError("no client close held within " + timeout + " " + unit);
        }
        return code;
    }

    /** Answer every held close, and hold no more. */
    public void releaseCloseReplies() {
        closeHold.release();
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
    public void onClosing(
            final WebSocket conn, final int code, final String reason, final boolean remote) {
        if (!remote) {
            return; // this server's own close, which closeAllClean waits for itself
        }
        // The reply to a client's close is queued like any other send, so #415's race can strand
        // it, and a client's teardown waits for it (#510). Waited for off this worker thread: the
        // worker keeps the connection's only read buffer until its decode ends, and the selector
        // needs that buffer to read anything more from the client, such as its FIN, before it
        // writes again.
        Thread guard =
                new Thread(() -> awaitCloseReplyWritten(conn, code), "scripted-server-close-reply");
        guard.setDaemon(true);
        guard.start();
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

    /**
     * {@link #awaitWritten} for the reply to a client's close, on a thread of its own, where a
     * failure has no test thread to reach and is logged instead.
     *
     * @param conn the connection answering a client's close
     * @param code the close code it answers with
     */
    private static void awaitCloseReplyWritten(final WebSocket conn, final int code) {
        try {
            awaitWritten(conn, "close reply (code=" + code + ")");
        } catch (AssertionError e) {
            LOG.warn("{}", e.getMessage());
        }
    }

    /**
     * Whether client closes are being held, and the code of each one that was. Shared by the server
     * and every connection's {@link HoldingDraft}, since Java-WebSocket copies the draft for each
     * connection.
     */
    private static final class CloseHold {

        /** Closed while closes are held, {@code null} while they are not. */
        private volatile CountDownLatch gate;

        /** The close code of each client close held so far, in order. */
        private final BlockingQueue<Integer> held = new LinkedBlockingQueue<>();

        synchronized void hold() {
            if (gate == null) {
                gate = new CountDownLatch(1);
            }
        }

        synchronized void release() {
            CountDownLatch open = gate;
            gate = null;
            if (open != null) {
                open.countDown();
            }
        }

        Integer awaitHeld(final long timeout, final TimeUnit unit) throws InterruptedException {
            return held.poll(timeout, unit);
        }
    }

    /**
     * Java-WebSocket's own RFC 6455 draft, except that while its {@link CloseHold} holds, a
     * client's close waits on this connection's worker before the draft answers it. The draft is
     * what answers a client's close, from inside its frame processing, so this is the one place a
     * reply can be held.
     */
    private static final class HoldingDraft extends Draft_6455 {

        /** The hold this draft shares with the server and every other connection's copy. */
        private final CloseHold closeHold;

        HoldingDraft(final CloseHold closeHold) {
            this.closeHold = closeHold;
        }

        @Override
        public void processFrame(final WebSocketImpl conn, final Framedata frame)
                throws InvalidDataException {
            CountDownLatch gate = closeHold.gate;
            if (gate != null && frame.getOpcode() == Opcode.CLOSING) {
                closeHold.held.add(
                        frame instanceof CloseFrame close
                                ? close.getCloseCode()
                                : CloseFrame.NOCODE);
                LOG.debug(
                        "scripted server holding its reply to {}'s close",
                        conn.getRemoteSocketAddress());
                try {
                    gate.await();
                } catch (InterruptedException e) {
                    // stop() interrupts the workers; the flag is what lets this one exit.
                    Thread.currentThread().interrupt();
                    return;
                }
            }
            super.processFrame(conn, frame);
        }

        @Override
        public Draft copyInstance() {
            return new HoldingDraft(closeHold);
        }
    }
}
