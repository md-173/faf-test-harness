package com.faforever.testharness.client.ice;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * In-process JSON-RPC TCP server for {@link IceAdapterConnection} tests — the loopback equivalent
 * of {@code ScriptedWebSocketServer}. Binds an OS-chosen port on {@code 127.0.0.1}, accepts a
 * single client, captures each newline-delimited frame the client sends on a {@link BlockingQueue}
 * the test can poll, and lets the test push canned response/notification frames back.
 *
 * <p>Lifecycle: the constructor binds immediately (so {@link #port()} is valid before any client
 * connects); {@link #start()} begins accepting and reading; always {@link #stop()} in
 * {@code @AfterEach}.
 *
 * <p>{@link #send(String)} writes its argument verbatim — the test controls framing, so it can
 * script embedded {@code {}/{@code }} inside string values or two back-to-back frames to exercise
 * the client's reader. The client's own outbound frames are read here line-by-line (it terminates
 * each with {@code \n}), so {@link #pollReceived} returns one compact JSON object per call.
 */
final class ScriptedJsonRpcServer {

    private final ServerSocket serverSocket;
    private final CountDownLatch clientConnected = new CountDownLatch(1);
    private final BlockingQueue<String> received = new LinkedBlockingQueue<>();
    private volatile Socket client;
    private volatile OutputStream clientOut;

    ScriptedJsonRpcServer() throws IOException {
        serverSocket = new ServerSocket();
        serverSocket.setReuseAddress(true);
        serverSocket.bind(new InetSocketAddress("127.0.0.1", 0));
    }

    /** Port the client should connect to. Valid as soon as the constructor returns. */
    int port() {
        return serverSocket.getLocalPort();
    }

    /** Begin accepting one client and reading its frames on a background thread. */
    void start() {
        Thread thread = new Thread(this::run, "scripted-jsonrpc-server");
        thread.setDaemon(true);
        thread.start();
    }

    private void run() {
        try {
            Socket accepted = serverSocket.accept();
            client = accepted;
            clientOut = accepted.getOutputStream();
            clientConnected.countDown();
            BufferedReader in =
                    new BufferedReader(
                            new InputStreamReader(
                                    accepted.getInputStream(), StandardCharsets.UTF_8));
            String line;
            while ((line = in.readLine()) != null) {
                received.add(line);
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
    String pollReceived(final long timeout, final TimeUnit unit) throws InterruptedException {
        String frame = received.poll(timeout, unit);
        if (frame == null) {
            throw new AssertionError("no frame received within " + timeout + " " + unit);
        }
        return frame;
    }

    /**
     * Send {@code raw} to the client verbatim (the test owns framing — add {@code \n} if wanted).
     */
    void send(final String raw) throws IOException {
        OutputStream out = clientOut;
        if (out == null) {
            throw new IllegalStateException("no client connected yet");
        }
        out.write(raw.getBytes(StandardCharsets.UTF_8));
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
