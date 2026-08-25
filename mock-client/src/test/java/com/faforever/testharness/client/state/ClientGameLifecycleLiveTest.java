package com.faforever.testharness.client.state;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import com.faforever.testharness.client.config.ConfigLoader;
import com.faforever.testharness.client.config.MockClientConfig;
import com.faforever.testharness.client.ice.IceAdapterConnection;
import com.faforever.testharness.client.lobby.GameConfig;
import com.faforever.testharness.client.lobby.LobbyConnection;
import com.faforever.testharness.client.lobby.LobbySession;
import com.faforever.testharness.client.lobby.ScriptedWebSocketServer;
import com.faforever.testharness.client.process.IceAdapterLauncher;
import com.faforever.testharness.client.process.MockGameLauncher;
import com.faforever.testharness.client.process.SessionTeardown;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.net.DatagramSocket;
import java.net.ServerSocket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledIf;

/**
 * The sprint's integration milestone (WBS-3.1.2.7): the Mock Client launches the <em>real</em>
 * {@code faf-ice-adapter} and the <em>real</em> mock-game as subprocesses, the game completes its
 * GPGNet handshake with the adapter, the FSM drives the session through its phases on real signals,
 * the game plays out and self-exits, and teardown leaves nothing running.
 *
 * <p>Tagged {@code integration} so it runs under {@code ./gradlew :mock-client:integrationTest},
 * never under the default {@code test}. Everything below is one session, checked at ordered
 * checkpoints; it is deliberately not a scenario framework. Matrices, parameterised runs, and
 * multi-peer variants are Phase 4/5.
 *
 * <p><b>Lobby-independent, on purpose.</b> Client-lobby integration is already proven (3.1.1.x, and
 * the R71/live-lobby smoke tests). This card isolates the unproven seam — client to adapter to game
 * — so instead of waiting on the live lobby's {@code game_launch}, it posts the three lobby-side
 * trigger events itself: {@link WelcomeReceived} with a fixed fabricated identity, then {@link
 * LaunchGame}, then {@link HostGame}. Those events are the only fakes in the run. The lobby-driven
 * full path belongs to the two-peer milestone (4.3.1).
 *
 * <p>The fabricated identity is load-bearing rather than incidental: WBS-3.1.2.9 propagates the
 * identity the <em>lobby</em> assigned into both subprocess argvs, in preference to the config
 * defaults the launchers would otherwise fall back on. {@link #assertLaunchedUnderSessionIdentity}
 * checks that on the live processes, which is why the session config deliberately carries a login
 * and a player id that must never appear.
 *
 * <p><b>What stands in for the lobby.</b> {@link MockClientLifecycle} is built around a {@link
 * LobbySession}, so one has to exist. This test gives it a real {@link LobbyConnection} pointed at
 * an in-process {@link ScriptedWebSocketServer} that is never scripted to send anything (the {@code
 * GameProcessTest} pattern). The connection is real, so the client's end-of-session {@code
 * GameState Ended} send succeeds and teardown's lobby close is a real close; the only fiction is
 * that no lobby frames ever arrive, which is exactly the isolation this card wants.
 *
 * <p><b>Host path only.</b> The join path needs a second peer to host, which is 4.3.1. Crash paths
 * are 3.1.2.6/3.1.2.8, which supply the {@code GameExited}/{@code AdapterExited} edges this happy
 * path relies on to fail fast rather than hang.
 *
 * <p><b>Gating.</b> An {@link EnabledIf} probe self-skips (never fails) unless both binaries
 * resolve, matching the convention of {@code IceAdapterConnectionLiveSmokeTest} (R71) and {@code
 * GpgNetConnectionLiveSmokeTest} (3.2.2.4). The adapter jar comes from {@code FAF_ICE_ADAPTER_JAR}
 * or the repo-root default; see {@code documentation/operations/ice-adapter-setup.md} (R74). The
 * mock-game binary comes from {@code FAF_MOCK_GAME_BINARY} or the {@code application} plugin's
 * install layout, which {@code :mock-client:integrationTest} builds for us.
 *
 * <p><b>Every wait is bounded and named.</b> There is no unbounded {@code get()} anywhere below: a
 * missed checkpoint fails with the constant that ran out and what had been observed by then, so a
 * regression names itself. That is the whole hang-proofing — the class-level {@link Timeout} is a
 * total-runtime backstop and, under JUnit's default same-thread mode, could not interrupt a blocked
 * wait even if one existed.
 *
 * <p><b>Running it locally</b> is documented in {@code documentation/demos/README.md}; this test
 * doubles as the sprint demo script.
 */
@Tag("integration")
// A backstop, and deliberately BELOW the ~505 s pathological sum of the named budgets below, so a
// cascade of slow-but-not-failed checkpoints still ends the run instead of grinding on. It is not
// what makes this test hang-proof: with no junit-platform.properties in the repo the default
// timeout thread mode is SAME_THREAD, which reports a breach after the method returns rather than
// interrupting it, so a genuinely blocked wait would never be cut short by this. The named budgets
// on every individual wait are the real mechanism; this only bounds the total.
@Timeout(value = 300, unit = TimeUnit.SECONDS)
final class ClientGameLifecycleLiveTest {

    /** Environment override for the adapter jar, consistent with R74's documented setup. */
    private static final String ADAPTER_JAR_ENV = "FAF_ICE_ADAPTER_JAR";

    /** Environment override for the installed mock-game binary. */
    private static final String MOCK_GAME_ENV = "FAF_MOCK_GAME_BINARY";

    /**
     * Budget for the posted welcome to move the FSM into IDLE. Effectively instant — {@code
     * receiveEvent} is synchronous — so this is a guard, not a measurement.
     *
     * <p>There is deliberately no constant for the {@link LaunchGame} step that follows. That
     * transition runs synchronously inside {@code post} and is already bounded from the inside: the
     * adapter connect by {@code IceAdapterConnection}'s own retry window (~20 s), and each setup
     * RPC by its call timeout. A failure lands the FSM in TERMINATED rather than throwing, so the
     * checkpoint there is the state itself, which cannot hang.
     */
    private static final Duration WELCOME_TIMEOUT = Duration.ofSeconds(30);

    /**
     * Budget for the GPGNet handshake to complete once the game process exists: the game's JVM
     * boots, connects to the adapter's GPGNet port, settles (mock-game waits 500 ms before its
     * first frame — see 3.2.2.4's finding on the adapter's {@code currentClient} race), sends
     * {@code GameState Idle}, and answers the adapter's {@code CreateLobby} with {@code GameState
     * Lobby}.
     */
    private static final Duration HANDSHAKE_TIMEOUT = Duration.ofSeconds(45);

    /** Budget for the {@code hostGame} RPC to be issued and the FSM to settle in HOSTING. */
    private static final Duration HOSTING_TIMEOUT = Duration.ofSeconds(15);

    /**
     * Budget for {@code GameState Launching}. mock-game sits in the lobby for its own launch delay
     * (5 s at the time of writing, {@code Main.LAUNCH_DELAY}) before starting the match; this is
     * generous headroom over that, not a measurement of it.
     */
    private static final Duration LAUNCHING_TIMEOUT = Duration.ofSeconds(30);

    /**
     * Budget for the match to play out and the result frames to arrive. mock-game's simulated match
     * runs for {@code Main.MATCH_DURATION} (30 s at the time of writing) with no flag to shorten
     * it, which is the single largest contributor to this test's wall-clock cost.
     */
    private static final Duration MATCH_TIMEOUT = Duration.ofSeconds(90);

    /** Budget for the game process to exit under its own power after {@code GameEnded}. */
    private static final Duration GAME_EXIT_TIMEOUT = Duration.ofSeconds(30);

    /** Budget for the exit signal to drive the FSM to TERMINATED and for teardown to complete. */
    private static final Duration TEARDOWN_TIMEOUT = Duration.ofSeconds(30);

    /**
     * Budget for both subprocesses to disappear from this JVM's descendants after teardown. Not
     * zero: {@code SessionTeardown} returns once the processes have been reaped, but the handles
     * can linger a moment in the OS process table, so this is polled rather than sampled once.
     */
    private static final Duration NO_ORPHANS_TIMEOUT = Duration.ofSeconds(15);

    /**
     * Budget for a just-spawned subprocess to become visible as a descendant of this JVM with a
     * readable command line. Generous: it covers a start script that has not yet {@code exec}'d.
     */
    private static final Duration SUBPROCESS_VISIBLE_TIMEOUT = Duration.ofSeconds(15);

    /**
     * Budget for the client's own clean-end flag to catch up with the {@code GameEnded} frame.
     *
     * <p>Polled rather than sampled, because the two are set by <em>different handlers on the same
     * fan-out dispatch</em>. This test's recorder is registered before the lifecycle's own, and
     * {@code IceAdapterConnection} runs handlers sequentially in registration order on its reader
     * thread — so enqueuing the frame here can wake the main thread before the lifecycle's handler
     * has run. Sampling the flag instead would go red on a perfectly healthy session.
     */
    private static final Duration CLEAN_END_FLAG_TIMEOUT = Duration.ofSeconds(10);

    /** Budget for the stand-in lobby transport to come up. */
    private static final Duration LOBBY_CONNECT_TIMEOUT = Duration.ofSeconds(10);

    /** Budget for the stand-in lobby server to shut down in the teardown block. */
    private static final Duration LOBBY_SERVER_STOP_TIMEOUT = Duration.ofSeconds(2);

    /** Poll slice for every bounded wait built on a queue or a repeated probe. */
    private static final Duration POLL_SLICE = Duration.ofMillis(250);

    /** The map name posted in the {@code HostGame} trigger and handed to the adapter. */
    private static final String HOST_MAP = "scmp_007";

    /** Game uid for the fabricated session; propagated into both argvs by WBS-3.1.2.9. */
    private static final int GAME_UID = 424242;

    /**
     * Config identity values that must never reach a subprocess. The lifecycle is required to
     * launch under {@link SessionFixture#SESSION} — the identity the lobby assigned — so seeing
     * either of these in a live argv means WBS-3.1.2.9 has regressed to the config fallback.
     */
    private static final String WRONG_LOGIN = "config-login-must-not-be-used";

    private static final int WRONG_PLAYER_ID = 7777;

    /** Deserialises the {@code HostGame} trigger; the client parses it as a lobby frame would. */
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** Every GPGNet frame the adapter forwarded to us, in arrival order. */
    private final BlockingQueue<Frame> frames = new LinkedBlockingQueue<>();

    /** Frames already taken off {@link #frames}, kept so a failure can report what was seen. */
    private final List<Frame> observed = new CopyOnWriteArrayList<>();

    /**
     * One GPGNet frame as it reaches the client over the R36 fan-out: {@code
     * onGpgNetMessageReceived(command, args)}.
     *
     * @param command the GPGNet command name, e.g. {@code GameState}
     * @param args its arguments, rendered as text so a checkpoint can match on them
     */
    private record Frame(String command, List<String> args) {
        @Override
        public String toString() {
            return command + args;
        }
    }

    @Test
    @EnabledIf("binariesAvailable")
    void launchesAdapterAndGameHandshakesPlaysOutAndTearsDownClean() throws Exception {
        AdapterPorts ports = freeAdapterPorts();
        MockClientConfig config = configFor(resolveAdapterBinary(), resolveGameBinary(), ports);

        ScriptedWebSocketServer lobbyServer = new ScriptedWebSocketServer();
        lobbyServer.startAndAwait();
        LobbyConnection lobby = new LobbyConnection(lobbyServer.uri());
        lobby.connect().get(LOBBY_CONNECT_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
        lobbyServer.awaitFirstClient();

        // Real transport, real launchers: nothing between the client and the two binaries is
        // stubbed. The connection is injected only so this test can observe the same R36 fan-out
        // the lifecycle consumes; it is the same object the production constructor would build.
        IceAdapterConnection adapter = new IceAdapterConnection(ports.rpc());
        adapter.registerNotification("onGpgNetMessageReceived", this::recordFrame);

        SessionTeardown teardown = new SessionTeardown(lobby);
        MockClientLifecycle lifecycle =
                new MockClientLifecycle(
                        config,
                        new LobbySession(lobby, "uid-fixture", "1.0.0", "mock-client-3.1.2.7"),
                        adapter,
                        new MockGameLauncher(config),
                        new IceAdapterLauncher(config),
                        teardown);

        try {
            runSession(lifecycle, teardown, config);
        } finally {
            // Always runs, so a failed checkpoint above still leaves no adapter and no game behind.
            // Deliberately the session's own teardown rather than a second mechanism: it is
            // once-guarded, so on a successful run this is a no-op, and on a failed one it is the
            // exact sequence the assertions were checking.
            teardown.run();
            lobbyServer.stop((int) LOBBY_SERVER_STOP_TIMEOUT.toMillis());
        }

        assertNoSurvivingSubprocesses(config);
    }

    /**
     * The ordered checkpoints, from the first posted trigger to a torn-down session. Split out of
     * the test method so the {@code finally} above wraps every one of them.
     *
     * @param lifecycle the lifecycle under test, not yet driven
     * @param teardown the session's teardown, shared with the lifecycle
     * @param config the session config, used to identify the spawned subprocesses
     * @throws Exception if any bounded wait is interrupted
     */
    private void runSession(
            final MockClientLifecycle lifecycle,
            final SessionTeardown teardown,
            final MockClientConfig config)
            throws Exception {
        // Futures for the later states are taken before the events that can reach them, so a
        // transition cannot slip past between the post and the wait.
        // Every one of them, not just the later ones. StateMachine.stateReached short-circuits only
        // while the state is still current; ask for a state the FSM has already overshot and you
        // get a fresh future that can never complete. The realistic case is a failing hostGame RPC
        // — that lands the FSM in TERMINATED inside the post, so a stateReached(HOSTING) taken
        // afterwards would burn the whole HOSTING budget and then blame HOSTING, when the real
        // answer ("the session had already died") was available immediately.
        CompletableFuture<Void> idle = lifecycle.stateReached(ClientState.IDLE);
        CompletableFuture<Void> hosting = lifecycle.stateReached(ClientState.HOSTING);
        CompletableFuture<Void> playing = lifecycle.stateReached(ClientState.PLAYING);
        CompletableFuture<Void> terminated = lifecycle.stateReached(ClientState.TERMINATED);

        // Trigger 1 — the welcome the lobby would have sent. Establishes the session identity that
        // both subprocesses are then launched under.
        lifecycle.post(new WelcomeReceived(SessionFixture.SESSION));
        await(idle, WELCOME_TIMEOUT, "FSM reaches IDLE");

        // Trigger 2 — game_launch. This is the heavy one: it spawns the adapter, connects and
        // configures the JSON-RPC transport, and spawns the game, all synchronously inside the
        // transition. A failure anywhere in there lands the FSM in TERMINATED instead of throwing,
        // so the state itself is the checkpoint.
        lifecycle.post(new LaunchGame(gameConfig()));
        assertEquals(
                ClientState.STARTING_GAME,
                lifecycle.getState(),
                "LaunchGame must reach STARTING_GAME; TERMINATED means the adapter or the game "
                        + "failed to launch — see the captured [ICEAdapter] output above");

        assertLaunchedUnderSessionIdentity(config);

        // Checkpoint 1 — the GPGNet handshake, observed through the client rather than at either
        // socket. Idle is the game's first frame; Lobby is its answer to the adapter's CreateLobby,
        // so seeing Lobby proves the adapter accepted the game and replied (3.2.2.4 source-verified
        // that CreateLobby is sent straight from the Idle handler).
        awaitFrame("GameState", "Idle", HANDSHAKE_TIMEOUT);
        awaitFrame("GameState", "Lobby", HANDSHAKE_TIMEOUT);

        // Trigger 3 — HostGame. Posted only now: the adapter defers hostGame until the game reports
        // Lobby, so posting it earlier would prove nothing about the ordering the spec documents
        // (json-rpc-spec §9a). The map argument travels to the adapter's hostGame RPC.
        lifecycle.post(new HostGame(hostGameCommand()));
        await(hosting, HOSTING_TIMEOUT, "FSM reaches HOSTING and the hostGame RPC was issued");

        // Checkpoint 2 — the match goes live. Launching is the game's own signal, and 3.1.3.5 wires
        // it to StartMatch through the same fan-out, so PLAYING here is reached on a real frame and
        // not on anything this test posted.
        awaitFrame("GameState", "Launching", LAUNCHING_TIMEOUT);
        await(playing, LAUNCHING_TIMEOUT, "FSM reaches PLAYING on the real Launching frame");

        // Checkpoint 3 — the match plays out and reports. GameResult must arrive before GameEnded,
        // and awaitFrame consumes in arrival order, so reaching GameEnded here having already
        // matched GameResult is itself the ordering assertion.
        //
        // Source-verified against faf-server, because the requirement is real but nowhere
        // documented upstream — it falls out of the finish path. GameConnection.handle_game_ended
        // sets finished_sim and calls check_game_finish; once the last connection has done so,
        // process_game_results consumes self._results, and an empty _results marks the game
        // UNKNOWN_RESULT (unrated). There is no recovery: a late result still lands in _results,
        // but check_game_finish's state guard means on_game_finish never runs again, so the result
        // is silently lost. In a single-connection harness "the last connection" is the only one,
        // which makes the ordering strictly load-bearing here.
        //
        // JsonStats is deliberately NOT ordered against GameEnded. _process_pending_army_stats is
        // re-triggered from both add_result and report_army_stats and is not gated on game state,
        // so late stats still process; its only real constraint is that the player's result exists
        // first. Do not "fix" this by adding a JsonStats ordering assertion — it would pin
        // behaviour faf-server does not require.
        Frame result = awaitFrame("GameResult", null, MATCH_TIMEOUT);
        // The payload, not just the command name: an empty arg list here would mean the frame's
        // body was lost somewhere on the game → adapter → client hop, which a name-only match would
        // wave through. The values themselves are deliberately not pinned — mock-game still carries
        // a "TODO: Configurable values" on them, so asserting "victory 10" would break on a change
        // this test has no opinion about.
        assertFalse(
                result.args().isEmpty(),
                "GameResult must carry its result payload across the fan-out; got: " + result);
        awaitFrame("GameEnded", null, MATCH_TIMEOUT);
        awaitCleanEndFlag(lifecycle);

        // Checkpoint 4 — a clean self-exit. Strictly 0, not "0 or 143": 3.1.1.10 landed with
        // teardown gated on the game-exit signal (TERMINATED's entry hook runs it, and the game
        // path into TERMINATED is GameExited, posted from the process-exit continuation), so the
        // client's SIGTERM cannot race the game's own exit. A 143 here means that gating regressed.
        int exitCode =
                lifecycle.gameExit().get(GAME_EXIT_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
        assertEquals(
                0,
                exitCode,
                "mock-game must self-exit cleanly; 143 would mean teardown SIGTERM-ed it first");

        // Checkpoint 5 — the exit signal drives the FSM to TERMINATED, whose entry hook is the
        // teardown.
        await(terminated, TEARDOWN_TIMEOUT, "game exit drives the FSM to TERMINATED");
        assertTrue(teardown.hasRun(), "TERMINATED entry must have run the session teardown");
    }

    /**
     * Records one {@code onGpgNetMessageReceived} notification off the R36 fan-out. Malformed
     * notifications are dropped rather than failing here: this runs on the transport's reader
     * thread, where an assertion error would be swallowed, so a missing frame is left to surface as
     * the checkpoint that timed out waiting for it.
     *
     * @param notification the raw JSON-RPC notification
     */
    private void recordFrame(final JsonNode notification) {
        JsonNode params = notification.path("params");
        if (!params.isArray() || params.size() < 2 || !params.get(1).isArray()) {
            return;
        }
        List<String> args = new ArrayList<>();
        params.get(1).forEach(arg -> args.add(arg.asText()));
        frames.add(new Frame(params.get(0).asText(), List.copyOf(args)));
    }

    /**
     * Consume frames in arrival order until one matches, or fail with everything seen so far.
     *
     * <p>Consuming rather than scanning is what makes the checkpoints ordered: a later call can
     * only match a frame that arrived after the one the previous call matched, so "results before
     * {@code GameEnded}" needs no separate index comparison.
     *
     * @param command the GPGNet command to wait for
     * @param firstArg the required first argument, or {@code null} to match on the command alone
     * @param timeout the named budget for this checkpoint
     * @return the matched frame, so a caller can assert on its payload
     * @throws InterruptedException if the wait is interrupted
     */
    private Frame awaitFrame(final String command, final String firstArg, final Duration timeout)
            throws InterruptedException {
        long deadline = System.nanoTime() + timeout.toNanos();
        do {
            Frame frame = frames.poll(POLL_SLICE.toMillis(), TimeUnit.MILLISECONDS);
            if (frame == null) {
                continue;
            }
            observed.add(frame);
            boolean argMatches =
                    firstArg == null
                            || (!frame.args().isEmpty() && firstArg.equals(frame.args().get(0)));
            if (command.equals(frame.command()) && argMatches) {
                return frame;
            }
        } while (System.nanoTime() < deadline);
        return fail(
                "no "
                        + command
                        + (firstArg == null ? "" : " " + firstArg)
                        + " frame within "
                        + timeout
                        + "; frames observed so far: "
                        + observed);
    }

    /**
     * Wait for the client to record its own clean-end flag, which it sets from the same {@code
     * GameEnded} frame this test just matched.
     *
     * <p>This is a wait and not a sample on purpose; see {@link #CLEAN_END_FLAG_TIMEOUT}. The two
     * observers sit on one fan-out dispatch and this test's runs first, so the frame can reach the
     * queue — and wake this thread — a moment before the lifecycle's handler has run.
     *
     * @param lifecycle the lifecycle under test
     * @throws InterruptedException if the wait is interrupted
     */
    private void awaitCleanEndFlag(final MockClientLifecycle lifecycle)
            throws InterruptedException {
        long deadline = System.nanoTime() + CLEAN_END_FLAG_TIMEOUT.toNanos();
        do {
            if (lifecycle.isCleanEndSeen()) {
                return;
            }
            Thread.sleep(POLL_SLICE.toMillis());
        } while (System.nanoTime() < deadline);
        fail(
                "the client never recorded the clean-end flag from the GameEnded frame within "
                        + CLEAN_END_FLAG_TIMEOUT);
    }

    /**
     * Bounded wait on an FSM state future, failing with the checkpoint's own description.
     *
     * @param future the {@code stateReached} future
     * @param timeout the named budget for this checkpoint
     * @param what what reaching it proves, used verbatim in the failure message
     * @throws InterruptedException if the wait is interrupted
     */
    private void await(
            final CompletableFuture<Void> future, final Duration timeout, final String what)
            throws InterruptedException {
        try {
            future.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            fail("timed out after " + timeout + " waiting for: " + what + "; frames: " + observed);
        } catch (ExecutionException e) {
            fail("failed waiting for: " + what, e.getCause());
        }
    }

    /**
     * Proves WBS-3.1.2.9 on the live processes: both subprocesses must carry the identity the
     * (fabricated) welcome assigned, not the config fallback. Read from the OS rather than from the
     * launchers, so it measures what was actually spawned.
     *
     * @param config the session config, whose binary file names identify our own children
     * @throws InterruptedException if the wait for a subprocess to appear is interrupted
     */
    private void assertLaunchedUnderSessionIdentity(final MockClientConfig config)
            throws InterruptedException {
        String adapterArgv = awaitDescendant(adapterNeedle(config));
        String gameArgv = awaitDescendant(gameNeedle(config));

        assertTrue(
                adapterArgv.contains("--login " + SessionFixture.SESSION.login()),
                "the adapter must be launched under the session login, not the config default; "
                        + "argv was: "
                        + adapterArgv);
        assertTrue(
                adapterArgv.contains("--id " + SessionFixture.SESSION.id()),
                "the adapter must be launched under the session player id; argv was: "
                        + adapterArgv);
        assertTrue(
                gameArgv.contains("--player-login " + SessionFixture.SESSION.login()),
                "mock-game must be launched under the session login; argv was: " + gameArgv);
        assertTrue(
                gameArgv.contains("--player-id " + SessionFixture.SESSION.id()),
                "mock-game must be launched under the session player id; argv was: " + gameArgv);
        assertTrue(
                !adapterArgv.contains(WRONG_LOGIN) && !gameArgv.contains(WRONG_LOGIN),
                "no subprocess may carry the config-default login");
    }

    /**
     * The "pgrep-clean" checkpoint: once teardown has run, neither binary may still be running.
     * Polled, because {@code SessionTeardown} can return a moment before the OS drops the handles.
     *
     * @param config the session config, whose binary file names identify our own children
     * @throws InterruptedException if the wait is interrupted
     */
    private void assertNoSurvivingSubprocesses(final MockClientConfig config)
            throws InterruptedException {
        long deadline = System.nanoTime() + NO_ORPHANS_TIMEOUT.toNanos();
        List<String> survivors;
        do {
            survivors =
                    matching(descendantCommandLines(), adapterNeedle(config), gameNeedle(config));
            if (survivors.isEmpty()) {
                return;
            }
            Thread.sleep(POLL_SLICE.toMillis());
        } while (System.nanoTime() < deadline);
        fail("subprocesses survived teardown after " + NO_ORPHANS_TIMEOUT + ": " + survivors);
    }

    /**
     * Wait for a descendant process whose command line contains {@code needle}, and return it.
     *
     * <p>Bounded rather than sampled once, and matched on the binary's file name rather than on
     * anything further inside the argv, because mock-game is launched through the {@code
     * application} plugin's start script: for the first few milliseconds the child is {@code
     * /bin/sh …/bin/mock-game …}, and only once the script's trailing {@code exec} runs does it
     * become the JVM. The file name is present either way, so this matches the process across that
     * hand-off instead of racing it.
     *
     * @param needle the binary file name identifying the subprocess
     * @return that process's full command line
     * @throws InterruptedException if the wait is interrupted
     */
    private String awaitDescendant(final String needle) throws InterruptedException {
        long deadline = System.nanoTime() + SUBPROCESS_VISIBLE_TIMEOUT.toNanos();
        do {
            List<String> found = matching(descendantCommandLines(), needle);
            if (!found.isEmpty()) {
                return found.get(0);
            }
            Thread.sleep(POLL_SLICE.toMillis());
        } while (System.nanoTime() < deadline);
        return fail(
                "no running subprocess matching '"
                        + needle
                        + "' within "
                        + SUBPROCESS_VISIBLE_TIMEOUT
                        + "; descendants were: "
                        + descendantCommandLines());
    }

    /** Command lines of every process descended from this JVM, skipping any we cannot read. */
    private static List<String> descendantCommandLines() {
        List<String> lines = new ArrayList<>();
        ProcessHandle.current()
                .descendants()
                .forEach(handle -> handle.info().commandLine().ifPresent(lines::add));
        return lines;
    }

    /** Every command line containing at least one of {@code needles}, in discovery order. */
    private static List<String> matching(final List<String> commandLines, final String... needles) {
        List<String> hits = new ArrayList<>();
        for (String line : commandLines) {
            for (String needle : needles) {
                if (line.contains(needle)) {
                    hits.add(line);
                    break;
                }
            }
        }
        return hits;
    }

    /** File name identifying the adapter subprocess in a command line, e.g. the jar's name. */
    private static String adapterNeedle(final MockClientConfig config) {
        return config.iceAdapterBinaryPath().getFileName().toString();
    }

    /** File name identifying the mock-game subprocess, both as a start script and as the JVM. */
    private static String gameNeedle(final MockClientConfig config) {
        return config.mockGameBinaryPath().getFileName().toString();
    }

    /** The {@code game_launch}-derived config the lifecycle launches this session under. */
    private static GameConfig gameConfig() {
        return new GameConfig(
                GAME_UID,
                "faf",
                "3.1.2.7 lifecycle integration",
                0,
                "custom",
                "global",
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null);
    }

    /** The {@code HostGame} frame the lobby would have sent, carrying the map as its first arg. */
    private static JsonNode hostGameCommand() {
        ObjectNode command = MAPPER.createObjectNode();
        command.put("command", "HostGame");
        command.putArray("args").add(HOST_MAP);
        return command;
    }

    /**
     * Build the session config. The lobby URL and OAuth fields are required by config validation
     * but unused: this session never authenticates, and its transport is the scripted server built
     * in the test body. The identity fields are set to values that must never reach a subprocess —
     * see {@link #assertLaunchedUnderSessionIdentity}.
     */
    private static MockClientConfig configFor(
            final Path adapter, final Path game, final AdapterPorts ports) {
        List<String> args =
                List.of(
                        "--lobby-websocket-url=wss://lobby.faforever.xyz",
                        "--oauth-token-url=https://hydra.faforever.xyz/oauth2/token",
                        "--oauth-auth-endpoint=https://hydra.faforever.xyz/oauth2/auth",
                        "--oauth-redirect-uri=http://127.0.0.1",
                        "--oauth-scopes=openid offline lobby",
                        "--oauth-client-id=95ecec08-29c1-4c48-ae0a-b000ff349cb8",
                        "--oauth-refresh-token-file=/nonexistent/unused-by-this-test",
                        "--unique-id=00000000-0000-0000-0000-000000000000",
                        "--ice-adapter-binary-path=" + adapter.toAbsolutePath(),
                        "--mock-game-binary-path=" + game.toAbsolutePath(),
                        "--ice-adapter-rpc-port=" + ports.rpc(),
                        "--ice-adapter-gpg-net-port=" + ports.gpgnet(),
                        "--ice-adapter-lobby-port=" + ports.lobby(),
                        "--player-login=" + WRONG_LOGIN,
                        "--player-id-override=" + WRONG_PLAYER_ID);
        return ConfigLoader.load(args.toArray(new String[0]), Map.of()).orElseThrow();
    }

    /** {@code @EnabledIf} probe — skips cleanly (never fails) when either binary is missing. */
    @SuppressWarnings("unused")
    static boolean binariesAvailable() {
        Path adapter = findAdapterBinary();
        Path game = findGameBinary();
        if (adapter == null) {
            System.out.println(
                    "[3.1.2.7] skipping client-game lifecycle test: no faf-ice-adapter jar found "
                            + "(set "
                            + ADAPTER_JAR_ENV
                            + " or run ./gradlew downloadIceAdapter; see "
                            + "documentation/operations/ice-adapter-setup.md).");
        }
        if (game == null) {
            System.out.println(
                    "[3.1.2.7] skipping client-game lifecycle test: no mock-game binary found "
                            + "(set "
                            + MOCK_GAME_ENV
                            + " or run ./gradlew :mock-game:installDist).");
        }
        return adapter != null && game != null;
    }

    /** Non-null variant for the test body; guaranteed present once the gate passes. */
    private static Path resolveAdapterBinary() {
        Path binary = findAdapterBinary();
        if (binary == null) {
            throw new IllegalStateException("adapter jar vanished after the @EnabledIf gate");
        }
        return binary;
    }

    /** Non-null variant for the test body; guaranteed present once the gate passes. */
    private static Path resolveGameBinary() {
        Path binary = findGameBinary();
        if (binary == null) {
            throw new IllegalStateException("mock-game binary vanished after the @EnabledIf gate");
        }
        return binary;
    }

    /**
     * Resolve the adapter jar: the {@code FAF_ICE_ADAPTER_JAR} override first, then the default
     * {@code faf-ice-adapter.jar} relative to the subproject CWD and the repo root.
     */
    private static Path findAdapterBinary() {
        return resolve(ADAPTER_JAR_ENV, "faf-ice-adapter.jar", "../faf-ice-adapter.jar");
    }

    /**
     * Resolve the installed mock-game binary. The repo-root-relative candidate is the launcher's
     * own default; the {@code ../} one is what actually hits, since a Gradle {@code Test} task runs
     * with the subproject as its working directory.
     */
    private static Path findGameBinary() {
        return resolve(
                MOCK_GAME_ENV,
                "mock-game/build/install/mock-game/bin/mock-game",
                "../mock-game/build/install/mock-game/bin/mock-game");
    }

    /** First readable candidate, with {@code env} taking precedence; {@code null} when none is. */
    private static Path resolve(final String env, final String... candidates) {
        String override = System.getenv(env);
        if (override != null && !override.isBlank()) {
            Path p = Path.of(override);
            return Files.isRegularFile(p) ? p : null;
        }
        for (String candidate : candidates) {
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
     * @param rpc JSON-RPC port (TCP)
     * @param gpgnet GPGNet port (TCP), shared with mock-game per spec §2.8
     * @param lobby lobby game-traffic port (UDP), shared with mock-game per spec §2.8
     */
    private record AdapterPorts(int rpc, int gpgnet, int lobby) {}

    /**
     * Allocate three distinct free ports so concurrent harness runs don't collide. The TCP sockets
     * are held open simultaneously so the OS hands out distinct numbers; lobby is probed as UDP
     * since the adapter binds it for game traffic. All are closed before the adapter binds them — a
     * benign TOCTOU window, which surfaces as connect-retry exhaustion rather than a wrong answer.
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
