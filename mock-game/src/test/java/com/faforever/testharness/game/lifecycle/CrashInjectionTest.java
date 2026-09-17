package com.faforever.testharness.game.lifecycle;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.faforever.testharness.game.TestPorts;
import com.faforever.testharness.game.config.ExitCodes;
import com.faforever.testharness.game.config.MockGameConfig;
import com.faforever.testharness.game.gpgnet.GpgNetConnection;
import com.faforever.testharness.game.gpgnet.GpgNetFrame;
import com.faforever.testharness.game.gpgnet.ScriptedGpgNetServer;
import java.io.IOException;
import java.net.DatagramSocket;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Game crash injection (WBS-5.2): {@code --crash-after-seconds} arms a timer that halts the JVM
 * without an orderly shutdown.
 *
 * <p>Every test here drives the real FSM over a real socket from {@link ScriptedGpgNetServer}, and
 * substitutes only the halt itself. That substitution is the whole reason this file can exist:
 * {@link Runtime#halt(int)} would take the Gradle test worker with it, which surfaces as a process
 * vanishing rather than as a failing test.
 *
 * <p><b>What the stand-in cannot reproduce.</b> A recording halt <em>returns</em>; the real one
 * does not. So after it fires the lifecycle here carries on: the match timer still runs, {@code
 * gameEnds} would still send its closing frames. Every assertion below is therefore either made
 * synchronously inside the halt action (the exit code, the state the game died in) or is about
 * something that must <em>not</em> have happened by then. Proving that a halted process emits no
 * closing frames at all needs a real process, and lives in {@link
 * com.faforever.testharness.game.CrashInjectionProcessTest}.
 */
final class CrashInjectionTest {

    /** Budget for a frame to cross the loopback socket and drive the FSM to its target state. */
    private static final long STATE_TIMEOUT_SECONDS = 5;

    /** How long to wait before concluding a crash that should never fire has not fired. */
    private static final long QUIET_WINDOW_SECONDS = 2;

    /** A crash delay long enough that a test can cancel it before it fires. */
    private static final int CANCELLABLE_CRASH_SECONDS = 2;

    /**
     * Crash delay for the cases that sample the FSM state at the moment of the halt. One second is
     * far longer than the few instructions between a transition action returning and the state
     * being published, so the sample is never taken mid-transition.
     */
    private static final int SETTLED_CRASH_SECONDS = 1;

    /** PlayerOption frames the host emits per player it configures: Army through Color. */
    private static final int PLAYER_OPTION_FRAMES = 5;

    /** Scripted stand-in for the adapter's GPGNet server. */
    private ScriptedGpgNetServer gpgnet;

    /**
     * Stands in for a peer's relay socket inside the ICE adapter. A real bound socket, because
     * since WBS-4.3.2 the game sends to whatever a peer frame names and an unroutable destination
     * would log a send failure on every round.
     */
    private DatagramSocket peer;

    /** Every lifecycle a test built, torn down after it so no socket outlives the test. */
    private final List<MockGameLifecycle> lifecycles = new ArrayList<>();

    /** Exit codes passed to the halt action, in order. */
    private final List<Integer> haltCodes = new ArrayList<>();

    /** The FSM state the game was in when the halt fired; {@code null} until it does. */
    private volatile GameState stateAtHalt;

    /** Released by the halt action so a test can wait for the crash rather than sleep for it. */
    private final CountDownLatch halted = new CountDownLatch(1);

    /** Counts every halt call, so "armed exactly once" is checkable. */
    private final AtomicInteger haltCalls = new AtomicInteger();

    @BeforeEach
    void setup() throws IOException {
        gpgnet = new ScriptedGpgNetServer();
        peer = new DatagramSocket(0);
    }

    @AfterEach
    void tearDown() {
        lifecycles.forEach(lifecycle -> lifecycle.shutdown().run());
        gpgnet.stop();
        peer.close();
    }

    /**
     * A lifecycle whose halt is recorded rather than performed.
     *
     * <p>The halt action reads the FSM state before recording anything, because that is the one
     * fact the stand-in destroys by returning: a moment later the match timer may have moved the
     * machine on.
     */
    private MockGameLifecycle lifecycleWith(
            final int crashAfterSeconds, final Duration launchDelay, final Duration matchDuration)
            throws IOException {
        MockGameConfig config =
                new MockGameConfig(
                        50000,
                        TestPorts.freeUdpPort(),
                        1,
                        "Rhiza",
                        9001,
                        Map.of(),
                        0,
                        -1,
                        0,
                        crashAfterSeconds);
        MockGameLifecycle lifecycle =
                new MockGameLifecycle(
                        config,
                        new GpgNetConnection(gpgnet.port()),
                        Duration.ofSeconds(STATE_TIMEOUT_SECONDS),
                        launchDelay,
                        matchDuration,
                        code -> {
                            stateAtHalt = lifecycles.get(0).getState();
                            haltCodes.add(code);
                            haltCalls.incrementAndGet();
                            halted.countDown();
                        });
        lifecycles.add(lifecycle);
        return lifecycle;
    }

    /** The stub peer's address in the form the adapter names it. */
    private String peerAddress() {
        return "127.0.0.1:" + peer.getLocalPort();
    }

    /** Drives a fresh lifecycle as far as LOBBY, which every case below needs first. */
    private void driveToLobby(final MockGameLifecycle lifecycle) throws Exception {
        lifecycle.start();
        gpgnet.start();
        gpgnet.awaitClient();
        // GameState Idle.
        gpgnet.pollReceived(STATE_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        gpgnet.sendFrame(new GpgNetFrame("CreateLobby", List.of(0, 6112, "Rhiza", 1, 1)));
        // GameState Lobby.
        gpgnet.pollReceived(STATE_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        lifecycle.stateReached(GameState.LOBBY).get(STATE_TIMEOUT_SECONDS, TimeUnit.SECONDS);
    }

    /**
     * The single-game case, and the one the card described: no peer ever connects, the game reaches
     * LIVE on its own launch timer, and the crash fires from there.
     *
     * <p>The crash delay is {@link #SETTLED_CRASH_SECONDS} rather than zero so {@link #stateAtHalt}
     * means what it claims (#357 review). {@code armCrash()} runs inside the transition action, and
     * {@code StateMachine.commitTransition} publishes the new state only after that action returns,
     * so a zero-delay crash can sample the state the game is leaving rather than the one it is
     * entering. A delay the FSM cannot lose gives the sample a defined answer; the arming rule
     * itself is pinned by the zero-delay cases below.
     */
    @Test
    void armsOnEntryToLive() throws Exception {
        MockGameLifecycle lifecycle = lifecycleWith(SETTLED_CRASH_SECONDS, Duration.ZERO, null);
        driveToLobby(lifecycle);

        gpgnet.sendFrame(new GpgNetFrame("HostGame", List.of("scm_007")));
        lifecycle.stateReached(GameState.LIVE).get(STATE_TIMEOUT_SECONDS, TimeUnit.SECONDS);

        assertTrue(
                halted.await(STATE_TIMEOUT_SECONDS, TimeUnit.SECONDS),
                "reaching LIVE must arm and fire the injected crash");
        assertEquals(List.of(ExitCodes.INJECTED_CRASH), haltCodes);
        assertEquals(GameState.LIVE, stateAtHalt, "the game must still be live when it dies");
    }

    /**
     * The case a LIVE-only anchor would have missed, and the reason the anchor is what it is.
     *
     * <p>A multi-peer session runs with auto-launch disabled, because faf-server refuses a {@code
     * game_join} once the host reports {@code GameState Launching}. With no launch delay nothing
     * ever posts {@code LaunchMatch}, since {@code launchMatch()} is called from no production
     * code, so the game never enters LIVE at all. Anchored there, the fault would have been
     * silently inert in exactly the configuration {@code TwoPeerSessionLiveTest} runs.
     *
     * <p>The crash delay is {@link #SETTLED_CRASH_SECONDS} rather than zero so {@link #stateAtHalt}
     * means what it claims (#357 review). {@code armCrash()} runs inside the transition action, and
     * {@code StateMachine.commitTransition} publishes the new state only after that action returns,
     * so a zero-delay crash can sample the state the game is leaving rather than the one it is
     * entering. A delay the FSM cannot lose gives the sample a defined answer; the arming rule
     * itself is pinned by the zero-delay cases below.
     */
    @Test
    void armsOnFirstPeerWhenAutoLaunchIsDisabled() throws Exception {
        MockGameLifecycle lifecycle = lifecycleWith(SETTLED_CRASH_SECONDS, null, null);
        driveToLobby(lifecycle);

        gpgnet.sendFrame(new GpgNetFrame("JoinGame", List.of(peerAddress(), "Smith", 2)));
        lifecycle.stateReached(GameState.JOINING).get(STATE_TIMEOUT_SECONDS, TimeUnit.SECONDS);

        assertTrue(
                halted.await(STATE_TIMEOUT_SECONDS, TimeUnit.SECONDS),
                "a peer connecting must arm the crash even though LIVE is unreachable");
        assertEquals(List.of(ExitCodes.INJECTED_CRASH), haltCodes);
        assertEquals(
                GameState.JOINING,
                stateAtHalt,
                "the crash must land in the phase where peers actually exchange traffic");
    }

    /**
     * The game is connected and in the lobby, but has nothing to lose yet. A zero-second delay is
     * used deliberately: if the timer were armed any earlier than the rule says, it would have
     * fired well inside the window below.
     */
    @Test
    void doesNotArmBeforeAPeerOrLive() throws Exception {
        MockGameLifecycle lifecycle = lifecycleWith(0, null, null);
        driveToLobby(lifecycle);

        assertFalse(
                halted.await(QUIET_WINDOW_SECONDS, TimeUnit.SECONDS),
                "reaching LOBBY alone must not arm the crash");
        assertEquals(0, haltCalls.get());
    }

    /**
     * The default, and the criterion that an unset flag leaves a run exactly as it was.
     *
     * <p>Both arming points are exercised, in the order they are reachable. An earlier version sent
     * the {@code ConnectToPeer} after LIVE, where no such transition is registered, so the frame
     * was dropped with "No matching transitions" and only the LIVE half of the claim in the
     * assertion message was ever true.
     */
    @Test
    void negativeNeverArms() throws Exception {
        MockGameLifecycle lifecycle = lifecycleWith(-1, null, null);
        driveToLobby(lifecycle);

        gpgnet.sendFrame(new GpgNetFrame("HostGame", List.of("scm_007")));
        lifecycle.stateReached(GameState.HOSTING).get(STATE_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        gpgnet.sendFrame(new GpgNetFrame("ConnectToPeer", List.of(peerAddress(), "Smith", 2)));
        awaitPeerCount(lifecycle, 1);
        lifecycle.launchMatch();
        lifecycle.stateReached(GameState.LIVE).get(STATE_TIMEOUT_SECONDS, TimeUnit.SECONDS);

        assertFalse(
                halted.await(QUIET_WINDOW_SECONDS, TimeUnit.SECONDS),
                "a negative delay must never crash, through a peer or LIVE");
        assertEquals(0, haltCalls.get());
    }

    /**
     * A host collects a peer per {@code ConnectToPeer} and can then reach LIVE, so the arming rule
     * is reached repeatedly. Only the first may schedule anything; the rest would fire into a JVM
     * that is already gone.
     */
    @Test
    void armsOnlyOnceAcrossManyPeersAndLive() throws Exception {
        // Auto-launch is off so the game stays in HOSTING while the peers arrive. With a launch
        // delay the FSM would reach LIVE first, the ConnectToPeer transitions (registered only on
        // HOSTING and JOINING) would find no match, and peerConnectionRequest would never run, so
        // the test would reach exactly one arming point and pass with the guard deleted.
        MockGameLifecycle lifecycle = lifecycleWith(1, null, null);
        driveToLobby(lifecycle);

        gpgnet.sendFrame(new GpgNetFrame("HostGame", List.of("scm_007")));
        lifecycle.stateReached(GameState.HOSTING).get(STATE_TIMEOUT_SECONDS, TimeUnit.SECONDS);

        // Two peers, then LIVE: three arming points, one timer.
        gpgnet.sendFrame(new GpgNetFrame("ConnectToPeer", List.of(peerAddress(), "Smith", 2)));
        awaitPeerCount(lifecycle, 1);
        gpgnet.sendFrame(new GpgNetFrame("ConnectToPeer", List.of(peerAddress(), "Jones", 3)));
        awaitPeerCount(lifecycle, 2);
        lifecycle.launchMatch();
        lifecycle.stateReached(GameState.LIVE).get(STATE_TIMEOUT_SECONDS, TimeUnit.SECONDS);

        assertTrue(halted.await(STATE_TIMEOUT_SECONDS, TimeUnit.SECONDS), "the crash must fire");
        // Well past the 1s delay, so any surplus timer armed by the later peer or by LIVE would
        // have fired inside this window too.
        Thread.sleep(Duration.ofSeconds(QUIET_WINDOW_SECONDS).toMillis());
        assertEquals(1, haltCalls.get(), "the crash must be armed exactly once");
    }

    /**
     * Waits until the host has configured {@code expected} peers, by counting the {@code
     * PlayerOption} frames it emits per peer.
     *
     * <p>Needed so each {@code ConnectToPeer} is known to have been handled before the next frame
     * is sent. Without it the test would race its own setup and could reach fewer arming points
     * than it means to, which is precisely the defect that made an earlier version of this test
     * pass with the guard under test deleted.
     */
    private void awaitPeerCount(final MockGameLifecycle lifecycle, final int expected)
            throws Exception {
        for (int i = 0; i < PLAYER_OPTION_FRAMES; i++) {
            gpgnet.pollReceived(STATE_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        }
        assertEquals(
                GameState.HOSTING,
                lifecycle.getState(),
                "the host must still be in HOSTING after configuring peer " + expected);
    }

    /**
     * A zero-delay crash armed by a peer connecting fires, and the peer's frames all arrive.
     *
     * <p>This is the only case that exercises a zero-second delay actually firing: the cases that
     * sample the FSM state need a settled delay, so without this one nothing would cover the
     * shortest fault the flag can express.
     *
     * <p><b>It does not prove the ordering it was written for, and should not be read as doing
     * so.</b> {@code peerConnectionRequest} arms after sending the peer's {@code PlayerOption}
     * frames rather than before (#357 review), so a halt cannot land midway through them. Moving
     * the call back above the sends leaves this test passing, which was checked: five writes to a
     * loopback socket finish long before the scheduler thread wakes. Proving that ordering would
     * need a delay injected into the send path, and the fix rests on the argument in {@code
     * peerConnectionRequest} instead, which is that arming last gives a zero-delay crash a defined
     * landing point and matches {@code joinGame}.
     */
    @Test
    void aZeroDelayCrashOnAHostStillDeliversThePeersFrames() throws Exception {
        MockGameLifecycle lifecycle = lifecycleWith(0, null, null);
        driveToLobby(lifecycle);

        gpgnet.sendFrame(new GpgNetFrame("HostGame", List.of("scm_007")));
        lifecycle.stateReached(GameState.HOSTING).get(STATE_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        gpgnet.sendFrame(new GpgNetFrame("ConnectToPeer", List.of(peerAddress(), "Smith", 2)));

        assertTrue(
                halted.await(STATE_TIMEOUT_SECONDS, TimeUnit.SECONDS),
                "a peer connecting must arm and fire the crash");
        // Every frame the peer is owed arrived, and arrived before the halt: the poll budget is
        // spent on frames already queued, and the halt has provably happened by now.
        for (int i = 0; i < PLAYER_OPTION_FRAMES; i++) {
            assertEquals(
                    "PlayerOption",
                    gpgnet.pollReceived(STATE_TIMEOUT_SECONDS, TimeUnit.SECONDS).command(),
                    "every frame the peer is owed must still arrive");
        }
    }

    /**
     * Teardown cancels a crash that has not fired. This is {@code GameShutdown}'s {@code
     * stopSchedules()} calling {@code shutdownNow()}, pinned: a harness that tore the game down
     * deliberately must not then be told the game crashed.
     */
    @Test
    void teardownCancelsAPendingCrash() throws Exception {
        MockGameLifecycle lifecycle = lifecycleWith(CANCELLABLE_CRASH_SECONDS, Duration.ZERO, null);
        driveToLobby(lifecycle);

        gpgnet.sendFrame(new GpgNetFrame("HostGame", List.of("scm_007")));
        lifecycle.stateReached(GameState.LIVE).get(STATE_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        lifecycle.shutdown().run();

        assertFalse(
                halted.await(CANCELLABLE_CRASH_SECONDS + QUIET_WINDOW_SECONDS, TimeUnit.SECONDS),
                "teardown must cancel a crash that had not fired yet");
        assertEquals(0, haltCalls.get());
    }
}
