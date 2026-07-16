package com.faforever.testharness.game.gpgnet;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * In-process raw-TCP GPGNet server for {@link GpgNetConnection} tests — the GPGNet equivalent of
 * the mock client's {@code ScriptedJsonRpcServer}. Binds an OS-chosen port on {@code 127.0.0.1},
 * accepts a single client (the mock game), decodes each frame the client sends onto a {@link
 * BlockingQueue} the test can poll, and lets the test push frames — or arbitrary raw bytes — back.
 *
 * <p>Lifecycle: the constructor binds immediately (so {@link #port()} is valid before any client
 * connects); {@link #start()} begins accepting and reading; always {@link #stop()} in
 * {@code @AfterEach}.
 *
 * <p>{@link #sendRaw(byte[])} writes its argument verbatim — the test controls framing, so it can
 * script a malformed or truncated frame to exercise the client's read loop. {@link
 * #sendFrame(GpgNetFrame)} is the well-formed convenience.
 */
final class ScriptedGpgNetServer {

    private final ServerSocket serverSocket;
    private final CountDownLatch clientConnected = new CountDownLatch(1);
    private final BlockingQueue<GpgNetFrame> received = new LinkedBlockingQueue<>();
    private volatile Socket client;
    private volatile OutputStream clientOut;

    ScriptedGpgNetServer() throws IOException {
        serverSocket = new ServerSocket();
        serverSocket.setReuseAddress(true);
        serverSocket.bind(new InetSocketAddress("127.0.0.1", 0));
    }

    /** Port the client should connect to. Valid as soon as the constructor returns. */
    int port() {
        return serverSocket.getLocalPort();
    }

    /** Begin accepting one client and decoding its frames on a background thread. */
    void start() {
        Thread thread = new Thread(this::run, "scripted-gpgnet-server");
        thread.setDaemon(true);
        thread.start();
    }

    private void run() {
        try {
            Socket accepted = serverSocket.accept();
            client = accepted;
            clientOut = accepted.getOutputStream();
            clientConnected.countDown();
            InputStream in = new BufferedInputStream(accepted.getInputStream());
            while (true) {
                received.add(GpgNetCodec.readFrame(in));
            }
        } catch (IOException ignored) {
            // Server stopped or client gone — end the read loop.
        }
    }

    /** Block until the client connects, or fail after 5s. */
    void awaitClient() throws InterruptedException {
        if (!clientConnected.await(5, TimeUnit.SECONDS)) {
            throw new AssertionError("no client connected within 5s");
        }
    }

    /** Next frame the client sent, or fail after {@code timeout}. */
    GpgNetFrame pollReceived(final long timeout, final TimeUnit unit) throws InterruptedException {
        GpgNetFrame frame = received.poll(timeout, unit);
        if (frame == null) {
            throw new AssertionError("no frame received within " + timeout + " " + unit);
        }
        return frame;
    }

    /** Send a well-formed frame to the client. */
    void sendFrame(final GpgNetFrame frame) throws IOException {
        sendRaw(GpgNetCodec.encode(frame));
    }

    /** Send {@code raw} to the client verbatim (the test owns framing). */
    void sendRaw(final byte[] raw) throws IOException {
        OutputStream out = clientOut;
        if (out == null) {
            throw new IllegalStateException("no client connected yet");
        }
        out.write(raw);
        out.flush();
    }

    /** Abruptly drop the client socket — simulates a peer reset / read error on the client side. */
    void dropClient() throws IOException {
        Socket current = client;
        if (current != null) {
            current.close();
        }
    }

    /** Close the client (if any) and the listening socket. */
    void stop() {
        try {
            if (client != null) {
                client.close();
            }
        } catch (IOException ignored) {
            // best effort
        }
        try {
            serverSocket.close();
        } catch (IOException ignored) {
            // best effort
        }
    }
}
