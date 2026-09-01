package com.faforever.testharness.client.process;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;

/**
 * A stand-in for {@code faf-ice-adapter}, run as a real subprocess by {@link
 * IceReachabilityCheckTest} (see {@code FakeAdapterStub} there for how it is launched).
 *
 * <p>It is a separate process on purpose. The check's first phase binds the adapter's two ports to
 * prove nothing else owns them, so a fake that listened in the test JVM would be indistinguishable
 * from the stale adapter that phase exists to catch. Spawning the fake through the same launcher
 * the real adapter goes through keeps that phase honest and exercises the launch path as well.
 *
 * <p>It speaks only the slice of the protocol the check uses: newline-delimited JSON-RPC on the RPC
 * port, answering any request carrying an {@code id} with a null result, and pushing {@code
 * onConnectionStateChanged("Connected")} to the RPC client when something connects to the GPGNet
 * port — which is what the real adapter does on accept (verified against 3.3.14).
 *
 * <p>{@link Mode} selects which of those behaviours to omit, so each failure verdict has a fake
 * that produces it deterministically, with no sleeps or port races. Unknown arguments are ignored:
 * the launcher passes the real adapter's full argument list, and only the ports matter here.
 */
public final class FakeIceAdapter {

    /** Which parts of a healthy adapter this instance imitates. */
    public enum Mode {
        /** Serves RPC and GPGNet, and announces the GPGNet client. A reachable adapter. */
        FULL,
        /** Serves RPC, but binds no GPGNet port. */
        RPC_ONLY,
        /** Accepts an RPC connection but never answers a request on it. */
        DEAF_RPC,
        /** Serves both ports, but answers every request with a JSON-RPC error response. */
        ERRORING_RPC,
        /** Serves both ports but never announces the GPGNet client over RPC. */
        SILENT_GPGNET,
        /** Binds nothing and stays alive, so nothing is ever reachable. */
        NO_LISTEN,
        /** Exits immediately, as a misconfigured adapter does. */
        EXIT_IMMEDIATELY
    }

    /** Exit status used when the fake is asked to leave straight away. */
    private static final int EARLY_EXIT_STATUS = 3;

    /** The connected RPC client's output stream, shared with the GPGNet accept thread. */
    private final AtomicReference<OutputStream> rpcOut = new AtomicReference<>();

    /** Behaviour this instance imitates. */
    private final Mode mode;

    private FakeIceAdapter(final Mode mode) {
        this.mode = mode;
    }

    /**
     * Entry point invoked by the generated stub script.
     *
     * @param args {@code --mode <MODE>} followed by the real adapter argument list; only {@code
     *     --rpc-port} and {@code --gpgnet-port} are read from the latter
     * @throws IOException if a listening socket cannot be bound
     */
    public static void main(final String[] args) throws IOException {
        Mode mode = Mode.valueOf(argValue(args, "--mode", Mode.FULL.name()));
        if (mode == Mode.EXIT_IMMEDIATELY) {
            System.out.println("[fake-adapter] exiting immediately");
            System.exit(EARLY_EXIT_STATUS);
        }
        int rpcPort = Integer.parseInt(argValue(args, "--rpc-port", "0"));
        int gpgNetPort = Integer.parseInt(argValue(args, "--gpgnet-port", "0"));
        new FakeIceAdapter(mode).run(rpcPort, gpgNetPort);
    }

    /**
     * Binds whichever listeners {@link #mode} calls for, then blocks until the process is killed.
     *
     * @param rpcPort JSON-RPC port to serve
     * @param gpgNetPort GPGNet port to serve
     * @throws IOException if a listening socket cannot be bound
     */
    private void run(final int rpcPort, final int gpgNetPort) throws IOException {
        if (mode != Mode.NO_LISTEN) {
            serve(rpcPort, "rpc", this::handleRpcClient);
            if (mode != Mode.RPC_ONLY) {
                serve(gpgNetPort, "gpgnet", this::handleGpgNetClient);
            }
        }
        System.out.println("[fake-adapter] ready mode=" + mode);
        // Nothing else to do: the check terminates this process when it is finished with it.
        try {
            new CountDownLatch(1).await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * Binds {@code port} and hands each accepted socket to {@code handler} on a daemon thread.
     *
     * @param port port to bind
     * @param name thread-name suffix, for readable stack dumps
     * @param handler consumer of each accepted socket
     * @throws IOException if the port cannot be bound
     */
    private static void serve(final int port, final String name, final SocketHandler handler)
            throws IOException {
        ServerSocket server = new ServerSocket(port);
        Thread thread =
                new Thread(
                        () -> {
                            while (!Thread.currentThread().isInterrupted()) {
                                try {
                                    handler.accept(server.accept());
                                } catch (IOException e) {
                                    return;
                                }
                            }
                        },
                        "fake-adapter-" + name);
        thread.setDaemon(true);
        thread.start();
    }

    /**
     * Reads newline-delimited JSON-RPC requests and answers each one that carries an {@code id},
     * unless this instance is playing deaf.
     *
     * @param client the accepted RPC socket
     * @throws IOException if the socket fails while being read or written
     */
    private void handleRpcClient(final Socket client) throws IOException {
        OutputStream out = client.getOutputStream();
        rpcOut.set(out);
        BufferedReader in =
                new BufferedReader(
                        new InputStreamReader(client.getInputStream(), StandardCharsets.UTF_8));
        String line;
        while ((line = in.readLine()) != null) {
            String id = extractId(line);
            if (id == null || mode == Mode.DEAF_RPC) {
                continue;
            }
            if (mode == Mode.ERRORING_RPC) {
                write(
                        out,
                        "{\"jsonrpc\":\"2.0\",\"error\":{\"code\":-32000,"
                                + "\"message\":\"refused by the fake\"},\"id\":"
                                + id
                                + "}\n");
                continue;
            }
            write(out, "{\"jsonrpc\":\"2.0\",\"result\":null,\"id\":" + id + "}\n");
        }
    }

    /**
     * Announces an accepted GPGNet client to the RPC peer, exactly as the real adapter does from
     * its {@code GPGNetClient} constructor.
     *
     * @param client the accepted GPGNet socket
     * @throws IOException if the notification cannot be written
     */
    private void handleGpgNetClient(final Socket client) throws IOException {
        System.out.println("[fake-adapter] gpgnet client connected");
        if (mode == Mode.SILENT_GPGNET) {
            return;
        }
        OutputStream out = rpcOut.get();
        if (out == null) {
            System.out.println("[fake-adapter] no rpc peer to notify");
            return;
        }
        write(
                out,
                "{\"jsonrpc\":\"2.0\",\"method\":\"onConnectionStateChanged\","
                        + "\"params\":[\"Connected\"]}\n");
    }

    /**
     * Writes one frame and flushes it.
     *
     * @param out stream to write to
     * @param frame the exact bytes to send
     * @throws IOException if the write fails
     */
    private static void write(final OutputStream out, final String frame) throws IOException {
        synchronized (out) {
            out.write(frame.getBytes(StandardCharsets.UTF_8));
            out.flush();
        }
    }

    /**
     * Pulls the {@code id} out of a request frame. Crude on purpose — the fake only ever sees the
     * compact frames {@code IceAdapterConnection} writes, where {@code "id"} is the last field.
     *
     * @param frame one JSON-RPC frame
     * @return the id as written, or {@code null} for a frame without one
     */
    private static String extractId(final String frame) {
        int marker = frame.lastIndexOf("\"id\":");
        if (marker < 0) {
            return null;
        }
        String tail = frame.substring(marker + "\"id\":".length());
        StringBuilder digits = new StringBuilder();
        for (int i = 0; i < tail.length() && Character.isDigit(tail.charAt(i)); i++) {
            digits.append(tail.charAt(i));
        }
        return digits.isEmpty() ? null : digits.toString();
    }

    /**
     * Reads {@code flag}'s value out of an argument list.
     *
     * @param args the full argument list
     * @param flag the flag to look for
     * @param fallback value to use when the flag is absent
     * @return the flag's value, or {@code fallback}
     */
    private static String argValue(final String[] args, final String flag, final String fallback) {
        for (int i = 0; i < args.length - 1; i++) {
            if (flag.equals(args[i])) {
                return args[i + 1];
            }
        }
        return fallback;
    }

    /** Accepts one connected socket; an {@link IOException} ends that listener's loop. */
    @FunctionalInterface
    private interface SocketHandler {

        /**
         * Handles one accepted connection.
         *
         * @param client the accepted socket
         * @throws IOException if the socket fails
         */
        void accept(Socket client) throws IOException;
    }
}
