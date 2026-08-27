package com.faforever.testharness.game;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.faforever.testharness.game.config.ExitCodes;
import com.faforever.testharness.game.config.MockGameConfig;
import com.faforever.testharness.game.gpgnet.GpgNetConnection;
import com.faforever.testharness.game.gpgnet.GpgNetFrame;
import com.faforever.testharness.game.gpgnet.ScriptedGpgNetServer;
import com.faforever.testharness.game.lifecycle.GameState;
import com.faforever.testharness.game.lifecycle.MockGameLifecycle;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

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

    /**
     * A timer long enough that it never fires during a test — for cases that drive the FSM by hand
     * and need the lifecycle's own scheduler to stay out of the way.
     */
    private static final Duration NO_AUTO_ADVANCE = Duration.ofMinutes(5);

    /**
     * Cap on frames skipped while waiting for one, so a wrong sequence fails instead of hanging.
     */
    private static final int MAX_FRAMES_SKIPPED = 32;

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

        // The adapter answers GameState Idle with CreateLobby. This exercises the handshake, but
        // note what it does not pin: by the time we send, registration has long since happened, and
        // the lifecycle's 500ms pre-first-frame wait leaves so much slack that a late registration
        // would still pass here. That handlers precede the first outbound frame is structural — the
        // constructor registers them, and only then calls connect().
        adapter.sendFrame(new GpgNetFrame("CreateLobby", List.of(0, 6112, "Rhiza", 42, 1)));
        assertEquals("Lobby", nextGameState(), "CreateLobby is handled and answered");

        adapter.sendFrame(new GpgNetFrame("HostGame", List.of("scm_007")));
        assertEquals("Launching", nextGameState(), "the match starts after the launch delay");

        awaitCommand("GameResult");
        awaitCommand("JsonStats");
        awaitCommand("GameEnded");
        assertEquals("Ended", nextGameState(), "the match ends after the match duration");

        assertEquals(
                ExitCodes.OK,
                exit.get(15, TimeUnit.SECONDS),
                "a session that played out exits cleanly");
    }

    @Test
    void badArgumentExitsWithUsageBeforeAnyConnectionAttempt() throws Exception {
        adapter.start();
        // A player id of 0 is rejected by MockGameCli's validation.
        String[] args = argv(adapter.port(), 0);

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

    @Test
    void shutdownHookTearsDownBeforeStoppingLogging() throws Exception {
        adapter.start();
        MockGameLifecycle lifecycle = lifecycleOn(adapter.port());
        lifecycle.stateReached(GameState.IDLE).get(10, TimeUnit.SECONDS);

        List<String> order = new CopyOnWriteArrayList<>();
        lifecycle.shutdown().registerConnection(recordingConnection(order));
        Main.shutdownHook(lifecycle, () -> order.add("stop-logging")).run();

        assertEquals(
                List.of("close-connection", "stop-logging"),
                order,
                "logging must stop last, or the teardown's own log lines are swallowed");
    }

    /**
     * The three phases a client-initiated SIGTERM can land in. The hook body must complete at each
     * of them — pre-connect it has no socket to close at all, and mid-FSM it must not be blocked by
     * a transition in flight.
     */
    @ParameterizedTest(name = "SIGTERM during {0}")
    @ValueSource(strings = {"pre-connect", "connected", "in-fsm"})
    void shutdownHookCompletesAtEveryPhase(final String phase) throws Exception {
        MockGameLifecycle lifecycle;
        if ("pre-connect".equals(phase)) {
            // Nothing is listening, so the connection is still retrying: no socket exists yet.
            lifecycle = lifecycleOn(closedPort());
        } else {
            adapter.start();
            lifecycle = lifecycleOn(adapter.port());
            lifecycle.stateReached(GameState.IDLE).get(10, TimeUnit.SECONDS);
            if ("in-fsm".equals(phase)) {
                adapter.sendFrame(new GpgNetFrame("CreateLobby", List.of(0, 6112, "Rhiza", 42, 1)));
                lifecycle.stateReached(GameState.LOBBY).get(10, TimeUnit.SECONDS);
                // HOSTING is transient: entering it schedules LaunchMatch TEST_LAUNCH_DELAY later.
                // stateReached is edge triggered and cannot observe a state the FSM has already
                // left (#250), so the future has to be registered before the frame that causes it.
                CompletableFuture<Void> hosting = lifecycle.stateReached(GameState.HOSTING);
                adapter.sendFrame(new GpgNetFrame("HostGame", List.of("scm_007")));
                hosting.get(10, TimeUnit.SECONDS);
            }
        }

        CompletableFuture<Void> ran = new CompletableFuture<>();
        Thread hook =
                new Thread(
                        () -> {
                            Main.shutdownHook(lifecycle, () -> {}).run();
                            ran.complete(null);
                        },
                        "hook-" + phase);
        hook.setDaemon(true);
        hook.start();

        // Generous, but the point is bounded-vs-hung: the real hook runs inside the client's
        // SIGTERM->SIGKILL grace.
        ran.get(10, TimeUnit.SECONDS);
    }

    /**
     * The fourth SIGTERM phase, which the parameterized test above cannot reach: the signal lands
     * <em>while</em> the FSM is transitioning into ENDED. This is the interleaving the card calls
     * out, and the one the old {@code synchronized run()} deadlocked on — the FSM thread held the
     * StateMachine monitor and wanted GameShutdown's, the hook thread held GameShutdown's and
     * wanted the StateMachine's.
     *
     * <p>{@code endMatch()} posts its event on the calling thread, which is the seam this needs:
     * its racer thread becomes the FSM thread, entering the synchronized {@code receiveEvent} and
     * running ENDED's entry hook (the shutdown sequence) while holding the monitor. The barrier
     * releases the hook thread into that same window. Whichever wins the once-guard, the other must
     * not block behind it.
     *
     * <p>Repeated because the window is a genuine race rather than a pinned interleaving, and the
     * measurement is not academic: with the old guard restored, repetitions 1 and 3 still passed
     * while most of the rest timed out, so a single-shot version of this test would be flaky in
     * exactly the direction that matters. Both timers are set long enough that the lifecycle's own
     * scheduler is not a third racer — the only {@code GameEnded} here is the one posted below.
     */
    @RepeatedTest(20)
    void shutdownHookCompletesDuringTheEndedTransition() throws Exception {
        adapter.start();
        MockGameLifecycle lifecycle = lifecycleOn(adapter.port(), NO_AUTO_ADVANCE, NO_AUTO_ADVANCE);
        lifecycle.stateReached(GameState.IDLE).get(10, TimeUnit.SECONDS);

        CompletableFuture<Void> lobby = lifecycle.stateReached(GameState.LOBBY);
        adapter.sendFrame(new GpgNetFrame("CreateLobby", List.of(0, 6112, "Rhiza", 42, 1)));
        lobby.get(10, TimeUnit.SECONDS);

        CompletableFuture<Void> hosting = lifecycle.stateReached(GameState.HOSTING);
        adapter.sendFrame(new GpgNetFrame("HostGame", List.of("scm_007")));
        hosting.get(10, TimeUnit.SECONDS);

        CompletableFuture<Void> live = lifecycle.stateReached(GameState.LIVE);
        lifecycle.launchMatch();
        live.get(10, TimeUnit.SECONDS);

        // Both racers run on their own daemon threads and the test thread only waits. That matters:
        // a reintroduced lock inversion deadlocks whichever threads are racing, so if the test
        // thread were one of them it would hang the build instead of failing. Verified by putting
        // the synchronized guard back — this fails in seconds rather than hanging.
        CyclicBarrier bothReady = new CyclicBarrier(2);
        CompletableFuture<Void> hookReturned = new CompletableFuture<>();
        CompletableFuture<Void> endMatchReturned = new CompletableFuture<>();
        startRacer(
                bothReady,
                () -> Main.shutdownHook(lifecycle, () -> {}).run(),
                hookReturned,
                "hook-during-ended");
        startRacer(bothReady, lifecycle::endMatch, endMatchReturned, "end-match");

        hookReturned.get(10, TimeUnit.SECONDS);
        endMatchReturned.get(10, TimeUnit.SECONDS);
        // ENDED is reached either way: if the hook closed the socket first, gameEnds' sends fail
        // and the FailedTransitionException names ENDED as its failure state, which still fires
        // ENDED's entry and commits. So the exit status is deliberately not asserted — this test
        // is about not hanging, and which caller won the guard decides OK versus FAILED.
        assertEquals(GameState.ENDED, lifecycle.getState(), "the FSM must land in ENDED");
    }

    /**
     * Starts {@code body} on a named daemon thread that waits on {@code barrier} first, so every
     * racer is released into the contended window together. Completes {@code done} on return, or
     * exceptionally if the body threw.
     */
    private static void startRacer(
            final CyclicBarrier barrier,
            final Runnable body,
            final CompletableFuture<Void> done,
            final String name) {
        Thread thread =
                new Thread(
                        () -> {
                            try {
                                barrier.await(10, TimeUnit.SECONDS);
                                body.run();
                                done.complete(null);
                            } catch (Exception e) {
                                done.completeExceptionally(e);
                            }
                        },
                        name);
        thread.setDaemon(true);
        thread.start();
    }

    /** A lifecycle wired to {@code gpgNetPort}, using the test durations. */
    private static MockGameLifecycle lifecycleOn(final int gpgNetPort) {
        return lifecycleOn(gpgNetPort, TEST_LAUNCH_DELAY, TEST_MATCH_DURATION);
    }

    /** A lifecycle wired to {@code gpgNetPort}, with its two timers chosen by the caller. */
    private static MockGameLifecycle lifecycleOn(
            final int gpgNetPort, final Duration launchDelay, final Duration matchDuration) {
        return new MockGameLifecycle(
                new MockGameConfig(gpgNetPort, 6112, 42, "Rhiza", 9001, Map.of()),
                new GpgNetConnection(gpgNetPort),
                launchDelay,
                matchDuration);
    }

    /** A never-connected connection that records when the shutdown sequence closes it. */
    private static GpgNetConnection recordingConnection(final List<String> order) {
        GpgNetConnection connection = new GpgNetConnection(1);
        connection.onDisconnect(event -> order.add("close-connection"));
        return connection;
    }

    /** The argv {@code MockGameLauncher} emits, pointed at {@code gpgNetPort}. */
    private static String[] argv(final int gpgNetPort) {
        return argv(gpgNetPort, 42);
    }

    /** As {@link #argv(int)}, with the player id chosen — 0 is the invalid case. */
    private static String[] argv(final int gpgNetPort, final int playerId) {
        return new String[] {
            "--gpgnet-port", Integer.toString(gpgNetPort),
            "--lobby-port", "6112",
            "--player-id", Integer.toString(playerId),
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

    /**
     * The next frame carrying {@code command}, skipping anything sent before it.
     *
     * <p>The lifecycle interleaves frames this test has no opinion about: {@code PlayerOption} and
     * {@code GameOption} on entering HOSTING, and one {@code GameResult} per army at the end
     * (WBS-3.2.4.3, which owns asserting their content). This test is about the boot path and the
     * exit code, so it waits for the frames that mark a state change and ignores the rest, rather
     * than pinning a frame sequence that belongs to another card and breaks whenever it grows.
     */
    private GpgNetFrame awaitCommand(final String command) throws InterruptedException {
        for (int i = 0; i < MAX_FRAMES_SKIPPED; i++) {
            GpgNetFrame frame = adapter.pollReceived(10, TimeUnit.SECONDS);
            if (command.equals(frame.command())) {
                return frame;
            }
        }
        throw new AssertionError(
                "no " + command + " frame within " + MAX_FRAMES_SKIPPED + " frames");
    }

    /** The state named by the next {@code GameState} frame. */
    private String nextGameState() throws InterruptedException {
        return (String) awaitCommand("GameState").args().get(0);
    }
}
