package com.faforever.testharness.game;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.faforever.testharness.game.config.ExitCodes;
import com.faforever.testharness.game.gpgnet.GpgNetFrame;
import com.faforever.testharness.game.gpgnet.ScriptedGpgNetServer;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Integration-lite tests for the bootstrap (WBS-3.2.5.1): {@link Main#run} is driven in-JVM against
 * {@link ScriptedGpgNetServer} standing in for the adapter's GPGNet server, so the whole boot path
 * — parse, construct, connect, handshake, run, exit code — is exercised without exec'ing the
 * binary.
 *
 * <p>The argv is the one {@code MockGameLauncher} actually emits (subprocess-orchestration-spec
 * §2.8), {@code --game-uid} included, so a change to either end shows up here.
 */
final class MainTest {

    /** Short enough to keep the suite quick; the production values live in {@link Main}. */
    private static final Duration TEST_LAUNCH_DELAY = Duration.ofMillis(100);

    /** Short enough to keep the suite quick; the production values live in {@link Main}. */
    private static final Duration TEST_MATCH_DURATION = Duration.ofMillis(100);

    private ScriptedGpgNetServer adapter;

    @BeforeEach
    void setUp() throws IOException {
        adapter = new ScriptedGpgNetServer();
    }

    @AfterEach
    void tearDown() {
        adapter.stop();
    }

    @Test
    void completesHandshakeThenPlaysOutAndExitsClean() throws Exception {
        adapter.start();
        CompletableFuture<Integer> exit = boot(argv(adapter.port()));

        adapter.awaitClient();
        assertEquals("Idle", nextGameState(), "the game announces itself as Idle once connected");

        // The adapter answers GameState Idle with CreateLobby immediately (source-verified), so
        // the inbound handlers must already be registered by the time the first frame goes out —
        // a late registration would silently drop this reply and the handshake would stall here.
        adapter.sendFrame(new GpgNetFrame("CreateLobby", List.of(0, 6112, "Rhiza", 42, 1)));
        assertEquals("Lobby", nextGameState(), "CreateLobby is handled and answered");

        adapter.sendFrame(new GpgNetFrame("HostGame", List.of("scm_007")));
        assertEquals("Launching", nextGameState(), "the match starts after the launch delay");

        assertEquals("GameResult", nextCommand());
        assertEquals("JsonStats", nextCommand());
        assertEquals("GameEnded", nextCommand());
        assertEquals("Ended", nextGameState(), "the match ends after the match duration");

        assertEquals(
                ExitCodes.OK,
                exit.get(15, TimeUnit.SECONDS),
                "a session that played out exits cleanly");
    }

    @Test
    void badArgumentExitsWithUsageBeforeAnyConnectionAttempt() throws Exception {
        adapter.start();
        String[] args = argv(adapter.port());
        args[5] = "0"; // --player-id, rejected by MockGameCli's validation

        ByteArrayOutputStream captured = new ByteArrayOutputStream();
        PrintStream originalErr = System.err;
        int exitCode;
        try {
            System.setErr(new PrintStream(captured, true, StandardCharsets.UTF_8));
            // Runs on the test thread: a usage failure must return without waiting on anything.
            exitCode = Main.run(args, TEST_LAUNCH_DELAY, TEST_MATCH_DURATION);
        } finally {
            System.setErr(originalErr);
        }

        assertEquals(ExitCodes.USAGE, exitCode);
        assertTrue(
                captured.toString(StandardCharsets.UTF_8).contains("--player-id"),
                "the diagnostic must name the offending argument");
        assertFalse(
                adapter.awaitClient(1, TimeUnit.SECONDS),
                "a bad argument must fail before the game touches the network");
    }

    @Test
    void unreachableAdapterExitsWithRuntimeCodeInsideTheConnectWindow() throws Exception {
        CompletableFuture<Integer> exit = boot(argv(closedPort()));

        // Well inside the FSM's 30s not-established timeout: the bounded connect retry gives up
        // first, so this is the no-infinite-retry, no-hang assertion.
        assertEquals(ExitCodes.RUNTIME, exit.get(20, TimeUnit.SECONDS));
    }

    @Test
    void adapterLossExitsWithTheAdapterLostCode() throws Exception {
        adapter.start();
        CompletableFuture<Integer> exit = boot(argv(adapter.port()));

        adapter.awaitClient();
        assertEquals("Idle", nextGameState(), "connected before the adapter goes away");
        adapter.dropClient();

        assertEquals(
                ExitCodes.ADAPTER_LOST,
                exit.get(15, TimeUnit.SECONDS),
                "losing the adapter is distinct from both a clean exit and a boot failure");
    }

    /** The argv {@code MockGameLauncher} emits, pointed at {@code gpgNetPort}. */
    private static String[] argv(final int gpgNetPort) {
        return new String[] {
            "--gpgnet-port", Integer.toString(gpgNetPort),
            "--lobby-port", "6112",
            "--player-id", "42",
            "--player-login", "Rhiza",
            "--game-uid", "9001",
        };
    }

    /** Runs the bootstrap on a daemon thread, completing with the exit code it returns. */
    private static CompletableFuture<Integer> boot(final String[] args) {
        CompletableFuture<Integer> exit = new CompletableFuture<>();
        Thread thread =
                new Thread(
                        () -> exit.complete(Main.run(args, TEST_LAUNCH_DELAY, TEST_MATCH_DURATION)),
                        "mock-game-boot");
        thread.setDaemon(true);
        thread.start();
        return exit;
    }

    /** A port nothing is listening on: bound to claim it, then released. */
    private static int closedPort() throws IOException {
        try (ServerSocket socket = new ServerSocket()) {
            socket.bind(new InetSocketAddress("127.0.0.1", 0));
            return socket.getLocalPort();
        }
    }

    /** The command of the next frame the game sends. */
    private String nextCommand() throws InterruptedException {
        return adapter.pollReceived(10, TimeUnit.SECONDS).command();
    }

    /** The state named by the next frame, which must be a {@code GameState}. */
    private String nextGameState() throws InterruptedException {
        GpgNetFrame frame = adapter.pollReceived(10, TimeUnit.SECONDS);
        assertEquals("GameState", frame.command(), "expected a GameState frame, got " + frame);
        return (String) frame.args().get(0);
    }
}
