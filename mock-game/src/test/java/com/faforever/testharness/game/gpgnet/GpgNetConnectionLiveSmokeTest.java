package com.faforever.testharness.game.gpgnet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import com.faforever.testharness.shared.process.SubprocessManager;
import java.io.IOException;
import java.net.DatagramSocket;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledIf;
import org.junit.jupiter.api.io.TempDir;

/**
 * Live test proving the mock game's GPGNet stack against the <em>real</em> {@code faf-ice-adapter}
 * binary (WBS 3.2.2.4) — the game-side mirror of the client's R71. Tagged {@code integration} so it
 * runs under {@code ./gradlew :mock-game:integrationTest}, not the default {@code test}.
 *
 * <p>Everything the codec ({@link GpgNetCodec}) and transport ({@link GpgNetConnection}) have been
 * proven against so far is {@link ScriptedGpgNetServer}, which was written from the same spec
 * document as the codec itself. A shared misreading of that spec would pass every unit test and
 * fail on first contact with the adapter. This is that first contact.
 *
 * <p>Scope is the connect handshake only: {@code GameState "Idle"} → {@code CreateLobby} → {@code
 * GameState "Lobby"}. Verified against java-ice-adapter 3.3.14:
 *
 * <ul>
 *   <li>{@code GPGNetServer} binds its {@code ServerSocket} during adapter init and starts
 *       accepting immediately, so no JSON-RPC call is needed to bring the GPGNet port up.
 *   <li>{@code CreateLobby} is sent straight from the {@code GameState "Idle"} handler with args
 *       {@code (lobbyInitMode.getId(), lobbyPort, login, id, 1)} taken from the adapter's own
 *       command-line state, so the handshake completes with no {@code hostGame}/{@code joinGame}.
 *       {@code lobbyInitMode} defaults to {@code NORMAL}, whose id is {@code 0}.
 *   <li>{@code GameState "Lobby"} only completes the adapter's internal {@code lobbyFuture} (which
 *       is what later gates queued RPC work); nothing is sent back, so the settle period below
 *       expects silence, not a reply.
 * </ul>
 *
 * <p><b>Findings, not failures.</b> Frames beyond {@code CreateLobby} are logged rather than
 * asserted on — cataloguing what the real adapter does that the scripted fixture does not is the
 * point of this card, and 3.2.4.1 / 3.1.2.7 build on the answer. The one exception is {@code
 * CreateLobby}'s own shape, which is the thing under test.
 *
 * <p><b>Finding: two preconditions the scripted fixture never imposed.</b> Both were found by the
 * first runs of this test, and either one alone kills the session on the very first frame with the
 * same signature: the adapter logs {@code IllegalStateException: gameState must not change to null}
 * and the game side sees the socket drop. Both trace to the adapter reaching a client through the
 * shared {@code GPGNetServer.currentClient} field, which is assigned only after {@code
 * GPGNetClient}'s constructor returns.
 *
 * <ol>
 *   <li><b>A JSON-RPC peer must be connected.</b> That constructor calls {@code
 *       rpcService.onConnectionStateChanged("Connected")}, which reaches {@code
 *       RPCService.getPeerOrWait()} — {@code tcpServer.getFirstPeer().get()}, an unbounded wait for
 *       the first JSON-RPC client. With no peer the constructor never returns and {@code
 *       currentClient} is never assigned at all. {@code "GPGNetClient has connected"} missing from
 *       the adapter log is the tell.
 *   <li><b>The first {@code GameState} must not be sent the instant the socket opens.</b> Even with
 *       a peer connected, the constructor tail can trail our {@code connect()}, leaving the same
 *       window: the listener thread is live while {@code currentClient} is still null.
 * </ol>
 *
 * <p>Inside that window the listener reads {@code GameState "Idle"} and sends {@code CreateLobby}
 * (that path uses {@code this}, so the handshake half-completes), then calls {@code
 * debug().gameStateChanged()}. Telemetry resolves {@code GPGNetServer.getGameState()}, which maps
 * over the null {@code currentClient}, so its {@code orElseThrow} fires. Only {@code IOException}
 * is caught around the read loop, so the exception closes the input stream and kills the listener
 * thread; {@code onGpgnetConnectionLost()} never runs.
 *
 * <p>Turning telemetry off would not rescue the first case: {@code processGpgnetMessage} ends with
 * {@code rpcService.onGpgNetMessageReceived(...)}, which calls {@code getPeerOrWait()} again, so
 * the listener would block there on every message instead. 3.3.14 has no working telemetry off
 * switch anyway (see ice-adapter-setup.md).
 *
 * <p>This test therefore holds a plain TCP socket open on the RPC port and pauses {@link
 * #PRE_HANDSHAKE_SETTLE} before its first frame. The socket is held until after {@code
 * terminate()}, so the observed exit code comes from SIGTERM and not from the adapter's own
 * first-peer-loss shutdown: dropping the RPC peer while at {@code GameState "Lobby"} makes the
 * adapter call {@code close(0)}, which reaches {@code System.exit(0)} roughly half a second later.
 * The card's "no JSON-RPC" constraint is kept at the protocol level — not one JSON-RPC byte is
 * sent, and the handshake still needs no {@code hostGame}/{@code joinGame} — but it cannot hold at
 * the connection level, because the adapter couples its GPGNet path to an RPC peer existing. <b>For
 * 3.2.4.1 and 3.1.2.7 this is an ordering constraint across components:</b> the mock game cannot
 * hold a GPGNet session against a real adapter until the mock client's JSON-RPC connection is up,
 * so the client must connect its adapter transport before the game is told to connect its own.
 *
 * <p><b>Gating.</b> Mirrors the client's {@code IceAdapterConnectionLiveSmokeTest}: an {@link
 * EnabledIf} probe self-skips (does not fail) when no adapter jar is resolvable, from {@code
 * FAF_ICE_ADAPTER_JAR} if set, otherwise {@code faf-ice-adapter.jar} in the subproject directory or
 * the repo root. For how to provision it see {@code documentation/operations/ice-adapter-setup.md}
 * (R74). Fresh ports are allocated per run so concurrent harness runs do not collide, and the
 * adapter is always torn down via {@link SubprocessManager#terminate()} in a {@code finally}.
 *
 * <p>The launch is duplicated here in miniature rather than reused: {@code IceAdapterLauncher}
 * lives in mock-client, which mock-game does not depend on. Two details are copied deliberately —
 * {@code --game-id} is required by 3.3.x (without it the adapter prints usage and exits before
 * binding), and the {@code -nojfx} jar's bundled logback config wires in a JavaFX appender that
 * crashes a JavaFX-less JRE on the first log line, so a console-only config is written and
 * injected.
 */
@Tag("integration")
// Above the sum of the internal budgets (30s connect + 20s CreateLobby + 15s exit + sleeps), so a
// failure surfaces as the specific assertion that timed out, not a blanket JUnit timeout.
@Timeout(value = 90, unit = TimeUnit.SECONDS)
final class GpgNetConnectionLiveSmokeTest {

    /** Environment override for the adapter jar, consistent with R74's documented setup. */
    private static final String ADAPTER_JAR_ENV = "FAF_ICE_ADAPTER_JAR";

    /**
     * Launch identity, echoed back to us inside {@code CreateLobby}. Deliberately not the adapter's
     * or the harness's defaults, so the assertions prove the values travelled rather than
     * coincided.
     */
    private static final int PLAYER_ID = 4242;

    private static final String PLAYER_LOGIN = "MockGameLive";

    /**
     * Required by faf-ice-adapter 3.3.x; a placeholder here, sourced from the lobby in a session.
     */
    private static final int GAME_ID = 12345;

    /** {@code LobbyInitMode.NORMAL.getId()} upstream — the adapter's default init mode. */
    private static final int INIT_MODE_NORMAL = 0;

    /** Console-only logback config for the adapter child JVM; see the class javadoc. */
    private static final String HEADLESS_LOGBACK_XML =
            """
            <?xml version="1.0" encoding="UTF-8"?>
            <configuration>
              <appender name="STDOUT" class="ch.qos.logback.core.ConsoleAppender">
                <encoder>
                  <pattern>%d{HH:mm:ss.SSS} [%thread] %-5level %logger{24} - %msg%n</pattern>
                </encoder>
              </appender>
              <root level="${LOG_LEVEL:-INFO}">
                <appender-ref ref="STDOUT"/>
              </root>
            </configuration>
            """;

    /**
     * The real adapter JVM is far slower to bind than the in-process fixture: 100 × 200 ms ≈ 20 s.
     */
    private static final int CONNECT_ATTEMPTS = 100;

    private static final Duration RETRY_DELAY = Duration.ofMillis(200);

    /** The adapter binds all its listeners on loopback. */
    private static final String LOOPBACK = "127.0.0.1";

    /**
     * Pause between the socket opening and the first {@code GameState}, covering the second half of
     * the finding in the class javadoc. The window is a few statements wide, so this is orders of
     * magnitude more than it needs to be.
     *
     * <p><b>Best-effort heuristic, not a guarantee.</b> Nothing asserts that the window has closed,
     * so a long enough adapter-side stall (GC, a loaded host) reopens it silently. Raising this
     * constant is not the fix. The deterministic replacement is the adapter's own {@code
     * "GPGNetClient has connected"} log line: it is the last statement of the client constructor,
     * so it proves the blocking {@code getPeerOrWait()} above it has returned, and the only thing
     * left uncovered is the {@code currentClient} write, which completes long before the line
     * travels the pipe into this JVM. Waiting on it needs a per-line hook on subprocess output,
     * which {@code ProcessOutputLogger} does not have today; tracked as #225 (WBS 3.1.2.10).
     */
    private static final Duration PRE_HANDSHAKE_SETTLE = Duration.ofMillis(500);

    /** Budget for {@code CreateLobby} to arrive after {@code GameState "Idle"} goes out. */
    private static final Duration CREATE_LOBBY_TIMEOUT = Duration.ofSeconds(20);

    /** Poll slice while waiting for {@code CreateLobby}. */
    private static final Duration POLL_SLICE = Duration.ofMillis(500);

    /** Window held open after {@code GameState "Lobby"} to prove the connection stays up. */
    private static final Duration SETTLE = Duration.ofSeconds(2);

    /** Grace between SIGTERM and SIGKILL, matching {@code IceAdapterLauncher}. */
    private static final Duration TERMINATE_GRACE = Duration.ofSeconds(5);

    /**
     * A SIGTERM-ed JVM exits with 128 + SIGTERM(15); 137 (SIGKILL) would mean the grace was blown.
     * POSIX semantics, appropriate for this Linux/WSL-targeted harness.
     */
    private static final int EXIT_SIGTERM = 128 + 15;

    /** Every frame the adapter sends, in arrival order. */
    private final BlockingQueue<GpgNetFrame> inbound = new LinkedBlockingQueue<>();

    @Test
    @EnabledIf("adapterJarAvailable")
    void idleYieldsCreateLobbyMatchingLaunchArgsAndLobbyKeepsConnectionUp(
            @TempDir final Path tempDir) throws Exception {
        Path binary = resolveAdapterBinary();
        AdapterPorts ports = freeAdapterPorts();

        SubprocessManager adapter = launchAdapter(binary, ports, tempDir);
        GpgNetConnection conn = new GpgNetConnection(ports.gpgnet(), CONNECT_ATTEMPTS, RETRY_DELAY);
        AtomicReference<GpgNetConnection.DisconnectEvent> disconnect = new AtomicReference<>();
        conn.onDisconnect(disconnect::set);
        conn.onFrame(
                frame -> {
                    System.out.println("[live smoke] adapter -> game: " + frame);
                    inbound.add(frame);
                });
        // Held open past terminate() below, never written to. Two reasons, both in the class
        // javadoc: without it the adapter's GPGNet listener dies on our first frame, and losing it
        // makes the adapter start its own close(0) shutdown that reaches System.exit(0) ~500ms
        // later, which would race the exit-143 SIGTERM assertion.
        Socket rpcPeer = null;
        try {
            rpcPeer = openRpcPeerSocket(ports.rpc());
            conn.connect().get(30, TimeUnit.SECONDS);
            // The adapter assigns currentClient only after its client constructor returns, which
            // can trail our connect(); sending inside that window kills its listener thread.
            Thread.sleep(PRE_HANDSHAKE_SETTLE.toMillis());

            GpgNetSender sender = new GpgNetSender(conn);
            sender.gameState("Idle");

            GpgNetFrame createLobby = awaitCreateLobby();
            // The 5th arg is hardcoded to 1 upstream (natTraversalProvider), so it is logged with
            // the frame rather than asserted; the other four are ours and must round-trip.
            assertEquals(5, createLobby.argCount(), "CreateLobby arg count");
            assertEquals(INIT_MODE_NORMAL, createLobby.intArg(0), "CreateLobby init mode");
            assertEquals(ports.lobby(), createLobby.intArg(1), "CreateLobby lobby port");
            assertEquals(PLAYER_LOGIN, createLobby.stringArg(2), "CreateLobby login");
            assertEquals(PLAYER_ID, createLobby.intArg(3), "CreateLobby player id");

            sender.gameState("Lobby");
            Thread.sleep(SETTLE.toMillis());

            GpgNetConnection.DisconnectEvent event = disconnect.get();
            assertNull(event, "connection dropped during the settle period: " + event);
            assertTrue(adapter.isAlive(), "adapter exited during the settle period");
            logRemainingFrames();
        } finally {
            // Always runs, so a failed assertion above still leaves no adapter behind. The RPC
            // peer is closed only after terminate(), so the observed exit code comes from SIGTERM
            // and not from the adapter's own first-peer-loss shutdown.
            conn.close();
            adapter.terminate();
            if (rpcPeer != null) {
                try {
                    rpcPeer.close();
                } catch (IOException ignored) {
                    // best effort
                }
            }
        }

        int exitCode = adapter.onExit().get(15, TimeUnit.SECONDS);
        assertFalse(adapter.isAlive(), "adapter should be dead after terminate()");
        assertEquals(
                EXIT_SIGTERM, exitCode, "adapter should exit cleanly on SIGTERM within the grace");
    }

    /**
     * Wait for {@code CreateLobby}, logging and skipping anything that arrives before it. Frames
     * the scripted fixture never sends are findings for the card, not failures.
     */
    private GpgNetFrame awaitCreateLobby() throws InterruptedException {
        long deadline = System.nanoTime() + CREATE_LOBBY_TIMEOUT.toNanos();
        List<GpgNetFrame> preceding = new ArrayList<>();
        while (System.nanoTime() < deadline) {
            GpgNetFrame frame = inbound.poll(POLL_SLICE.toMillis(), TimeUnit.MILLISECONDS);
            if (frame == null) {
                continue;
            }
            if ("CreateLobby".equals(frame.command())) {
                if (!preceding.isEmpty()) {
                    System.out.println(
                            "[live smoke] FINDING: frames before CreateLobby: " + preceding);
                }
                return frame;
            }
            preceding.add(frame);
        }
        return fail(
                "no CreateLobby within "
                        + CREATE_LOBBY_TIMEOUT
                        + " of GameState \"Idle\"; frames seen: "
                        + preceding);
    }

    /**
     * Open a plain TCP connection to the adapter's JSON-RPC port, retrying while it binds. Not one
     * JSON-RPC byte is ever written; the adapter only requires that a peer exist, because {@code
     * getPeerOrWait()} blocks until one connects. See the class javadoc.
     */
    private static Socket openRpcPeerSocket(final int port) throws IOException {
        IOException last = null;
        for (int attempt = 1; attempt <= CONNECT_ATTEMPTS; attempt++) {
            try {
                return new Socket(LOOPBACK, port);
            } catch (IOException e) {
                last = e;
                try {
                    Thread.sleep(RETRY_DELAY.toMillis());
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw new IOException("interrupted waiting for the adapter RPC port", ie);
                }
            }
        }
        throw new IOException("adapter RPC port " + port + " never accepted a connection", last);
    }

    /** Records whatever else the adapter sent, which the scripted fixture would never produce. */
    private void logRemainingFrames() {
        List<GpgNetFrame> extra = new ArrayList<>();
        inbound.drainTo(extra);
        if (extra.isEmpty()) {
            System.out.println("[live smoke] no frames after CreateLobby");
        } else {
            System.out.println("[live smoke] FINDING: frames after CreateLobby: " + extra);
        }
    }

    /**
     * Launch the adapter on {@code ports}, headless. Only the jar form is handled — R74 provisions
     * the pinned {@code -nojfx} jar, and the env override is named for it.
     */
    private static SubprocessManager launchAdapter(
            final Path binary, final AdapterPorts ports, final Path tempDir) throws IOException {
        Path logbackConfig = tempDir.resolve("logback-headless.xml");
        Files.writeString(logbackConfig, HEADLESS_LOGBACK_XML);
        List<String> argv =
                List.of(
                        javaBinary(),
                        "-Dlogback.configurationFile=" + logbackConfig.toAbsolutePath(),
                        "-jar",
                        binary.toAbsolutePath().toString(),
                        "--id",
                        Integer.toString(PLAYER_ID),
                        "--login",
                        PLAYER_LOGIN,
                        "--game-id",
                        Integer.toString(GAME_ID),
                        "--rpc-port",
                        Integer.toString(ports.rpc()),
                        "--gpgnet-port",
                        Integer.toString(ports.gpgnet()),
                        "--lobby-port",
                        Integer.toString(ports.lobby()));
        System.out.println("[live smoke] launching adapter: " + String.join(" ", argv));
        return SubprocessManager.start(new ProcessBuilder(argv), "ICEAdapter", TERMINATE_GRACE);
    }

    /** The JRE running this test, falling back to {@code java.home} when the OS withholds it. */
    private static String javaBinary() {
        return ProcessHandle.current()
                .info()
                .command()
                .orElse(System.getProperty("java.home") + "/bin/java");
    }

    /** {@code @EnabledIf} probe — skips cleanly (not fails) when no adapter jar is present. */
    @SuppressWarnings("unused")
    static boolean adapterJarAvailable() {
        Path binary = findAdapterBinary();
        if (binary == null) {
            System.out.println(
                    "[live smoke] skipping GPGNet live smoke test: no faf-ice-adapter jar found "
                            + "(set "
                            + ADAPTER_JAR_ENV
                            + " or run ./gradlew downloadIceAdapter; see "
                            + "documentation/operations/ice-adapter-setup.md).");
        }
        return binary != null;
    }

    /** Non-null variant for the test body; the jar is guaranteed present once the gate passes. */
    private static Path resolveAdapterBinary() {
        Path binary = findAdapterBinary();
        if (binary == null) {
            throw new IllegalStateException("adapter jar vanished after the @EnabledIf gate");
        }
        return binary;
    }

    /**
     * Resolve the adapter jar: the {@code FAF_ICE_ADAPTER_JAR} override first, then the default
     * {@code faf-ice-adapter.jar} relative to the subproject CWD and the repo root. Returns {@code
     * null} when none is a regular file.
     */
    private static Path findAdapterBinary() {
        String override = System.getenv(ADAPTER_JAR_ENV);
        if (override != null && !override.isBlank()) {
            Path p = Path.of(override);
            return Files.isRegularFile(p) ? p : null;
        }
        for (String candidate : new String[] {"faf-ice-adapter.jar", "../faf-ice-adapter.jar"}) {
            Path p = Path.of(candidate);
            if (Files.isRegularFile(p)) {
                return p;
            }
        }
        return null;
    }

    /**
     * The three adapter listener ports, allocated free per run.
     *
     * @param rpc JSON-RPC port (TCP), unused here but required to avoid colliding with the 7236
     *     default when another adapter is up
     * @param gpgnet GPGNet port (TCP) the connection targets
     * @param lobby lobby game-traffic port (UDP), echoed back inside CreateLobby
     */
    private record AdapterPorts(int rpc, int gpgnet, int lobby) {}

    /**
     * Allocate three distinct free ports. The TCP sockets are held open simultaneously so the OS
     * hands out distinct numbers; lobby is probed as UDP since the adapter binds it for game
     * traffic. All are closed before the adapter binds them, a benign TOCTOU window for a smoke
     * test.
     */
    private static AdapterPorts freeAdapterPorts() throws IOException {
        try (ServerSocket rpc = new ServerSocket(0);
                ServerSocket gpgnet = new ServerSocket(0);
                DatagramSocket lobby = new DatagramSocket(0)) {
            return new AdapterPorts(
                    rpc.getLocalPort(), gpgnet.getLocalPort(), lobby.getLocalPort());
        }
    }
}
