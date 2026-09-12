package com.faforever.testharness.client.state;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.fail;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.faforever.testharness.client.config.ConfigLoader;
import com.faforever.testharness.client.config.MockClientConfig;
import com.faforever.testharness.client.ice.IceAdapterConnection;
import com.faforever.testharness.client.lobby.GameConfig;
import com.faforever.testharness.client.lobby.LobbyConnection;
import com.faforever.testharness.client.lobby.LobbySession;
import com.faforever.testharness.client.lobby.SessionState;
import com.faforever.testharness.client.lobby.TokenSource;
import com.faforever.testharness.client.lobby.TokenSources;
import com.faforever.testharness.client.process.IceAdapterLauncher;
import com.faforever.testharness.client.process.MockGameLauncher;
import com.faforever.testharness.client.process.SessionTeardown;
import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import java.net.DatagramSocket;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledIf;
import org.slf4j.LoggerFactory;

/**
 * The two-peer milestone (WBS-4.3.1): two Mock Clients on one host, each with its own lobby
 * account, its own port set, and its own real {@code faf-ice-adapter} and mock-game, complete a
 * host/join through the <em>live</em> lobby, and both adapters report the peer link established.
 *
 * <p>The two clients never touch each other in-process. A's game uid reaches B through {@link
 * MockClientLifecycle#gameLaunched()} — the same value an operator reads off A's {@code game
 * launch:} log line — and every other exchange between them (the {@code game_host}/{@code
 * game_join} pair, {@code JoinGame}, {@code ConnectToPeer}, and every ICE candidate) crosses the
 * FAF test lobby, exactly as two separate machines would.
 *
 * <p><b>The signal.</b> The definitive one is the adapter's {@code onConnected(localId, remoteId,
 * connected)} notification — {@code RPCService.onConnected(long, long, boolean)}, json-rpc-spec.md
 * §5 — observed on the R36 fan-out of each client's own adapter connection. The card originally
 * proposed polling the {@code status} RPC; that method is deprecated for removal upstream, and
 * {@code onConnected} is the same verdict pushed rather than polled. {@code
 * onIceConnectionStateChanged} carries the intermediate states and is logged by {@code
 * IceEventLogger} (WBS-3.1.6.2) for debugging, but is deliberately not asserted on: {@code
 * completed} is unreachable in adapter 3.3.14, so a matcher waiting on the "final" state would
 * never fire.
 *
 * <p><b>Why the empty ICE server list is enough.</b> Both peers are on one machine, so they connect
 * over host candidates and never need STUN or TURN. The session's only network dependence is that
 * the lobby is reachable, which this test probes and self-skips on.
 *
 * <p><b>Auto-launch is off on both peers</b> ({@code --mock-game-launch-delay-seconds=-1},
 * WBS-4.3.1). faf-server accepts a {@code game_join} only while the game is in {@code
 * GameState.LOBBY} and leaves that state the moment the host reports {@code GameState Launching},
 * so a host on the default 5 s timer would make itself unjoinable while B is still booting two
 * JVMs. Nothing is lost here: the peer link is established during the lobby phase, and so is the
 * game traffic this test now also asserts. The one exception is WBS-4.3.4's post-launch run below,
 * which has to leave that state on purpose and pays for it with a launch delay long enough to let
 * the joiner in first.
 *
 * <p><b>Game traffic (WBS-4.3.2).</b> Each mock game binds its lobby port on {@code CreateLobby}
 * and starts sending to a peer as soon as the adapter names one, so datagrams cross the finished
 * ICE path during the lobby phase — as the real game's autolobby does, and without needing a launch
 * this session deliberately never performs. The evidence is each game's own progress line, captured
 * off its stdout by {@code ProcessOutputLogger}: a line naming a <em>receiving</em> and a
 * <em>sending</em> player id is one direction proven, and both lines together are the round trip.
 * Counts are asserted as "at least", never exactly: the adapter drops everything sent before ICE
 * completes ({@code PeerIceModule.sendViaIce} is guarded by {@code connected}), so a stream that
 * starts mid-sequence with gaps in it is the expected shape, not a defect.
 *
 * <p><b>One known cause of a slow pass.</b> If an adapter re-announces a peer at a
 * <em>different</em> relay port, that peer's send sequence restarts at zero (WBS-3.2.2.5 installs a
 * fresh counter per registration), while the receiving side only ever raises its highest-seen
 * sequence. The advancing check below then makes no progress until the restarted stream climbs past
 * the old high — bounded, about a second per ten datagrams already sent, but it can eat most of
 * {@link #TRAFFIC_TIMEOUT}. A rejoining peer would hit the same shape deliberately, which is why
 * rejoin is explicitly out of WBS-4.3.4's scope and left to a later card.
 *
 * <p><b>Prerequisites</b>, all probed by {@link #liveEnvironmentAvailable()} so an unequipped
 * machine skips rather than fails: the adapter jar ({@code ./gradlew downloadIceAdapter}), the
 * installed mock-game binary ({@code ./gradlew :mock-game:installDist}), the {@code faf-uid} binary
 * (the lobby's policy server rejects a placeholder {@code unique_id}), and <b>two</b> seeded
 * accounts' refresh tokens — {@code .secrets/refresh_token.txt} and {@code
 * .secrets/refresh_token_b.txt}, each overridable by environment variable. One account cannot host
 * and join its own game. Both files are rewritten in place on every run, because Hydra rotates the
 * refresh token on use. See {@code documentation/demos/README.md} for the bootstrap and run notes.
 *
 * <p><b>Mid-session peer departure (WBS-4.3.4).</b> Two further runs take the session this class
 * already builds and remove a peer from it, because the survivor learns about a departure two
 * different ways depending on when it happens.
 *
 * <ul>
 *   <li><b>In the lobby phase</b>, faf-server tells every remaining player: {@code
 *       GameConnection.abort} runs {@code disconnect_all_peers()} under a {@code GameState.LOBBY}
 *       guard, the client relays that to its adapter as {@code disconnectFromPeer}, and the frame
 *       the adapter forwards ends the survivor's own game. Provoked with {@code kill -9} on the
 *       joiner's game, which is the path that proves the client's crash-side {@code GameState
 *       Ended} fallback reaches the server at all.
 *   <li><b>After launch</b>, that guard closes and no targeted notice is sent. The survivor finds
 *       out from its own adapter, whose connectivity checker declares a silent peer lost after ten
 *       seconds and pushes {@code onConnected(local, remote, false)}. Nothing is built for this
 *       path; it is asserted.
 * </ul>
 *
 * <p>The second run is the only one that launches a match, and it launches exactly one side. The
 * server's game state is what decides which of the two paths a departure takes, and {@code
 * handle_game_state}'s {@code Launching} branch is host-only, so the host's {@link
 * #HOST_LAUNCH_DELAY} is the whole mechanism. The joiner never auto-launches in any run here; see
 * {@link #joinConfig(int)} for why giving it a delay would take the timing away from the test.
 *
 * <p><b>Every wait is bounded and named</b>, in the 3.1.2.7 pattern: a missed checkpoint fails with
 * the budget that ran out and what had been seen by then, so a regression names itself. The
 * class-level {@link Timeout} is only a total-runtime backstop, and it applies per test method.
 */
@Tag("integration")
@Timeout(value = 600, unit = TimeUnit.SECONDS)
final class TwoPeerSessionLiveTest {

    /** Environment override for the adapter jar, consistent with R74's documented setup. */
    private static final String ADAPTER_JAR_ENV = "FAF_ICE_ADAPTER_JAR";

    /** Environment override for the installed mock-game binary. */
    private static final String MOCK_GAME_ENV = "FAF_MOCK_GAME_BINARY";

    /** Environment override for the {@code faf-uid} binary. */
    private static final String UID_BINARY_ENV = "FAF_UID_BINARY";

    /** Environment override for the hosting account's refresh-token file. */
    private static final String TOKEN_A_ENV = "FAF_REFRESH_TOKEN_A";

    /** Environment override for the joining account's refresh-token file. */
    private static final String TOKEN_B_ENV = "FAF_REFRESH_TOKEN_B";

    /** Environment override for the lobby endpoint. */
    private static final String LOBBY_URL_ENV = "FAF_LOBBY_URL";

    /**
     * Lobby endpoint used when {@link #LOBBY_URL_ENV} is unset. The test environment's canonical
     * host is {@code lobby.faforever.xyz}, but it is unreachable from most networks (see {@code
     * LobbyConnectionLiveSmokeTest}'s javadoc); {@code ws.faforever.xyz} is what the 3.1.1.4 demo
     * actually ran against, so it is the default and the other is one environment variable away.
     */
    private static final String DEFAULT_LOBBY_URL = "wss://ws.faforever.xyz";

    /** Budget for one client's connect, auth handshake, and welcome — including the Hydra hop. */
    private static final Duration SESSION_TIMEOUT = Duration.ofSeconds(90);

    /**
     * Budget from a client reaching IDLE — where it sends its {@code game_host} / {@code game_join}
     * — to both of its subprocesses being up. Covers the server's {@code game_launch}, the adapter
     * JVM starting and binding, its setup RPCs, and the game JVM starting.
     */
    private static final Duration GAME_LAUNCH_TIMEOUT = Duration.ofSeconds(90);

    /**
     * Budget for a launched client to take up its role: the GPGNet handshake plus the server's
     * {@code HostGame} for the host, and the same plus {@code JoinGame} for the joiner. Generous —
     * it contains a JVM boot, the mock game's 500 ms settle, and two lobby round trips.
     */
    private static final Duration ROLE_TIMEOUT = Duration.ofSeconds(90);

    /**
     * Budget for ICE to complete once both peers know about each other. Two processes on one host
     * negotiate over host candidates, which is fast; this is headroom for the lobby relay hop, not
     * a measurement of the negotiation.
     */
    private static final Duration PEER_CONNECTED_TIMEOUT = Duration.ofSeconds(90);

    /**
     * Budget for peer traffic to show up in both games' logs once ICE is established. Generous
     * against a 1 s progress interval: two samples per direction need two intervals plus whatever
     * the first datagrams cost, and this is headroom rather than a measurement.
     */
    private static final Duration TRAFFIC_TIMEOUT = Duration.ofSeconds(30);

    /**
     * Datagrams a game must have attributed to the other player before the exchange counts as
     * proven. Small on purpose: this asserts that the path carries traffic, not how much.
     */
    private static final int MIN_DATAGRAMS = 3;

    /**
     * Progress lines required per direction. Two, because one line proves a count and two
     * consecutive lines are what proves the sequence is still advancing.
     */
    private static final int MIN_PROGRESS_SAMPLES = 2;

    /**
     * The mock game's progress line (WBS-4.3.2), as captured from its stdout. {@code
     * TwoGameTrafficLoopbackTest} in mock-game holds a second copy of this pattern and these
     * thresholds, and runs in the fast suite; change one and you must change the other.
     */
    private static final Pattern PROGRESS_LINE =
            Pattern.compile(
                    "player (\\d+) peer traffic from player (\\d+): (\\d+) datagrams, "
                            + "highest sequence (-?\\d+), gaps (\\d+)");

    /** A game that could not bind its lobby port, quoted into a failed traffic wait. */
    private static final String BIND_FAILURE = "failed to bind lobby port";

    /** Budget for a requested shutdown to drive a session to TERMINATED and run its teardown. */
    private static final Duration TEARDOWN_TIMEOUT = Duration.ofSeconds(30);

    /**
     * Budget for every subprocess to disappear from this JVM's descendants. Polled rather than
     * sampled: teardown returns once the processes are reaped, but the OS can hold the handles a
     * moment longer.
     */
    private static final Duration NO_ORPHANS_TIMEOUT = Duration.ofSeconds(20);

    /** Poll slice for every bounded wait built on a repeated probe. */
    private static final Duration POLL_SLICE = Duration.ofMillis(250);

    /** Launch-delay value that disables a mock game's auto-launch entirely. */
    private static final int NO_AUTO_LAUNCH = -1;

    /** Environment override for the post-launch run's host launch delay, in seconds. */
    private static final String LAUNCH_DELAY_ENV = "FAF_HOST_LAUNCH_DELAY_SECONDS";

    /**
     * Budget for the adapter's connectivity checker to declare a departed peer lost. Upstream
     * declares loss after 10 s of silence and echoes every 1 s ({@code
     * PeerConnectivityCheckerModule}), so this is that threshold with room for the departing side's
     * own teardown to finish first.
     *
     * <p>Declared ahead of {@link #HOST_LAUNCH_DELAY} because that field's validation reads it.
     */
    private static final Duration PEER_LOST_TIMEOUT = Duration.ofSeconds(45);

    /**
     * Seconds the post-launch run's host sits in the lobby before launching (WBS-4.3.4).
     *
     * <p>This is the one number that whole run is timed against, and it is a trade. The host's
     * timer starts when <em>its</em> game enters HOSTING, and the joiner's entire bring-up has to
     * finish inside it: faf-server accepts a {@code game_join} only while the game is in {@code
     * GameState.LOBBY}, so a host that launches first makes itself unjoinable. Too long and the run
     * idles; too short and it fails. It fails loudly either way, at the named {@code B:
     * game_launch} checkpoint with the server's own {@code game_join_failed} reason quoted, so a
     * bad value diagnoses itself rather than producing a confusing timeout elsewhere.
     *
     * <p>Two minutes sits comfortably above the observed bring-up while leaving room under the 600
     * s per-method {@link Timeout}, and it is overridable because a slow or distant network is
     * exactly the case where the default stops being generous. Note that {@code MockGameLauncher}'s
     * match duration is derived as twice this (mock-game's {@code Main.matchDuration}), and that
     * derived window is what bounds the observation below, so lowering this shortens the window the
     * adapter's ten-second detector has to fire in.
     */
    private static final Duration HOST_LAUNCH_DELAY = hostLaunchDelay();

    /**
     * Budget for the host to actually launch, derived from the delay it was given so the two cannot
     * drift apart. The headroom is one role timeout, which is what the bring-up ahead of the timer
     * is budgeted at elsewhere in this class.
     */
    private static final Duration LAUNCH_TIMEOUT = HOST_LAUNCH_DELAY.plus(ROLE_TIMEOUT);

    /**
     * Budget for the server to report the launched game as no longer joinable. One lobby round trip
     * after the host's own adapter relayed the frame, so this is latency headroom, not a wait on
     * anything slow.
     */
    private static final Duration SERVER_LIVE_TIMEOUT = Duration.ofSeconds(30);

    /**
     * Budget for the surviving side to observe a lobby-phase departure end to end: the server's
     * {@code DisconnectFromPeer}, the adapter RPC it produces, and the survivor's own game exiting
     * on the frame the adapter forwards.
     */
    private static final Duration DEPARTURE_TIMEOUT = Duration.ofSeconds(60);

    /** Map the host advertises; any real map folder name works. */
    private static final String HOST_MAP = "scmp_007";

    /** Featured mod the host advertises. */
    private static final String HOST_MOD = "faf";

    /**
     * The joining peer, once {@link #runSession} has built it. A field rather than a local, so the
     * test's teardown can reach it even when a checkpoint between its construction and the end of
     * the session fails.
     */
    private Peer joiner;

    /**
     * The hosting peer, once a test has built it. A field for the same reason {@link #joiner} is,
     * plus one of its own: the orphan check in {@link #stopCapturingAndAssertNoOrphans()} needs a
     * config to read the binary names off, and must run even when a test failed before it could
     * reach its own last line.
     */
    private Peer host;

    /** Root logger the mock-game capture appender is attached to. */
    private Logger root;

    /**
     * Captures every log record in this JVM, which includes both mock games' stdout as re-emitted
     * by {@code ProcessOutputLogger}. Backed by a copy-on-write list: two subprocess reader threads
     * and the test thread touch it at once.
     */
    private ListAppender<ILoggingEvent> captured;

    /**
     * One captured progress line, parsed.
     *
     * @param receiverId the game that logged the line
     * @param senderId the peer whose datagrams it counted
     * @param datagrams how many it had attributed to that peer
     * @param highestSequence the highest sequence number seen from that peer
     */
    private record TrafficSample(
            int receiverId, int senderId, long datagrams, long highestSequence) {
        @Override
        public String toString() {
            return "player "
                    + receiverId
                    + " <- player "
                    + senderId
                    + ": "
                    + datagrams
                    + " datagrams, highest sequence "
                    + highestSequence;
        }
    }

    @BeforeEach
    void captureSubprocessLogs() {
        LoggerContext context = (LoggerContext) LoggerFactory.getILoggerFactory();
        root = context.getLogger(Logger.ROOT_LOGGER_NAME);
        captured = new ListAppender<>();
        captured.list = new CopyOnWriteArrayList<>();
        captured.setContext(context);
        captured.start();
        root.addAppender(captured);
    }

    /**
     * Detaches the log capture and makes the pgrep-clean check, for every run including a failed
     * one.
     *
     * <p>Both live here rather than at the end of each test because a run that failed a checkpoint
     * is the one most likely to have leaked an adapter or a game, and a check written as the last
     * line of the test body is precisely the line a failure skips. It is not in the tests' own
     * {@code finally} blocks either: an exception thrown from {@code finally} replaces the original
     * one, so a leak would mask the failure that caused it. JUnit aggregates instead, reporting the
     * test's own failure and attaching this one as suppressed, so both survive.
     *
     * @throws InterruptedException if the wait for the processes to disappear is interrupted
     */
    @AfterEach
    void stopCapturingAndAssertNoOrphans() throws InterruptedException {
        if (captured != null) {
            captured.stop();
            root.detachAppender(captured);
        }
        // Null when the run self-skipped before building anything, which leaves nothing to leak.
        // Either peer's config names the same two binaries, so one check covers all four processes.
        if (host != null) {
            assertNoSurvivingSubprocesses(host.config);
        }
    }

    /**
     * One peer verdict as the adapter reported it.
     *
     * @param localId the reporting adapter's own player id
     * @param remoteId the peer the verdict is about
     * @param connected whether that peer is reachable
     */
    private record PeerVerdict(long localId, long remoteId, boolean connected) {
        @Override
        public String toString() {
            return "onConnected(" + localId + ", " + remoteId + ", " + connected + ")";
        }
    }

    /** One client under test: its config, its session, and what its adapter reported. */
    private static final class Peer {

        /** Name used in failure messages, so a red run says which side failed. */
        private final String name;

        private final MockClientConfig config;
        private final MockClientLifecycle lifecycle;
        private final SessionTeardown teardown;

        /** Peer verdicts in arrival order, filled from the adapter's reader thread. */
        private final BlockingQueue<PeerVerdict> verdicts = new LinkedBlockingQueue<>();

        /** Verdicts already taken off the queue, kept so a failure can report what was seen. */
        private final List<PeerVerdict> observed = new ArrayList<>();

        /**
         * A {@code game_join_failed} frame, if the server sent one. Recorded purely for the failure
         * message: the alternative is a bare 90 s timeout waiting for {@code game_launch} that
         * never says the server refused the join, and {@code game_not_ready} versus {@code
         * bad_password} are very different bugs.
         *
         * <p>Source-verified, because this command is missing from lobby-protocol-spec.md's §10.6
         * lookup table: faf-server's {@code lobbyconnection.command_game_join} sends {@code
         * {"command": "game_join_failed", "reason": …, "uid": …}} on every refusal, each followed
         * by a {@code notice} frame its own comment marks {@code DEPRECATED: use game_join_failed
         * instead}. The reason codes it can carry are {@code host_left_game}, {@code
         * game_not_ready}, and {@code bad_password}.
         */
        private final AtomicReference<String> joinRefusal = new AtomicReference<>();

        /**
         * Latest server-reported state per game uid, from {@code game_info} (WBS-4.3.4).
         *
         * <p>Test-only, and deliberately not a client feature: #237 puts handling {@code game_info}
         * out of scope for the mock client, which still ignores it. What this observes is the
         * server's own {@code Game.state}, which is the thing the departure behaviour actually
         * turns on and which no client-side signal reports. Registered the same way the
         * join-refusal recorder above is.
         */
        private final Map<Integer, String> serverGameStates = new ConcurrentHashMap<>();

        /** This peer's lobby-assigned identity, from its {@code welcome}. */
        private SessionState identity;

        private Peer(final String name, final MockClientConfig config) {
            this.name = name;
            this.config = config;
            LobbyConnection lobby = new LobbyConnection(config.lobbyWebSocketUrl());
            lobby.registerHandler("game_join_failed", frame -> joinRefusal.set(frame.toString()));
            lobby.registerHandler("game_info", this::recordGameInfo);
            IceAdapterConnection adapter = new IceAdapterConnection(config.iceAdapterRpcPort());
            adapter.registerNotification("onConnected", this::record);
            this.teardown = new SessionTeardown(lobby);
            this.lifecycle =
                    new MockClientLifecycle(
                            config,
                            new LobbySession(
                                    lobby,
                                    config.uniqueId(),
                                    config.clientVersion(),
                                    config.userAgent(),
                                    config.uidBinaryPath()),
                            adapter,
                            new MockGameLauncher(config),
                            new IceAdapterLauncher(config),
                            teardown);
        }

        /**
         * Records one {@code onConnected} notification off the R36 fan-out. Malformed ones are
         * dropped rather than failing here: this runs on the adapter's reader thread, where an
         * assertion error would be swallowed, so a missing verdict surfaces as the checkpoint that
         * timed out instead.
         *
         * @param notification the raw JSON-RPC notification
         */
        private void record(final JsonNode notification) {
            JsonNode params = notification.path("params");
            if (!params.isArray() || params.size() < 3 || !params.get(2).isBoolean()) {
                return;
            }
            verdicts.add(
                    new PeerVerdict(
                            params.get(0).asLong(),
                            params.get(1).asLong(),
                            params.get(2).asBoolean()));
        }

        /**
         * Records the server's view of every game a {@code game_info} frame describes.
         *
         * <p>Two shapes, both of which faf-server sends: a batch as {@code {"games": [...]}} on
         * connect, and a single game dict for each subsequent update. The {@code state} field is
         * the client-facing rendering of {@code Game.state}, which {@code Game.to_dict} maps as
         * LOBBY to {@code "open"}, LIVE to {@code "playing"}, and everything else to {@code
         * "closed"}.
         *
         * @param frame the full {@code game_info} frame
         */
        private void recordGameInfo(final JsonNode frame) {
            JsonNode games = frame.path("games");
            if (games.isArray()) {
                games.forEach(this::recordOneGame);
                return;
            }
            recordOneGame(frame);
        }

        /**
         * Records one game dict, ignoring anything without both a uid and a textual state.
         *
         * @param game one game as the server describes it
         */
        private void recordOneGame(final JsonNode game) {
            JsonNode uid = game.path("uid");
            JsonNode state = game.path("state");
            if (uid.isInt() && state.isTextual()) {
                serverGameStates.put(uid.asInt(), state.asText());
            }
        }

        /**
         * Appended to a failed checkpoint's message when the server refused this peer's join.
         *
         * @return the refusal frame in parentheses, or an empty string
         */
        private String refusalHint() {
            String refusal = joinRefusal.get();
            return refusal == null ? "" : " (the lobby refused the join: " + refusal + ")";
        }
    }

    @Test
    @EnabledIf("liveEnvironmentAvailable")
    void twoPeersEstablishTheirLinkThroughTheLiveLobby() throws Exception {
        assumeTrue(lobbyReachable(), unreachableLobbyMessage());

        // Unique per run, so a stale game from an earlier run is never what this one observes —
        // though it is the uid, not the title, that B actually targets.
        host = new Peer("A(host)", hostConfig("faf-test-harness 4.3.1 " + UUID.randomUUID()));
        try {
            runSession();
        } finally {
            // Always runs, so a failed checkpoint still leaves no adapter and no game behind.
            // Joiner first: it is the side that may not exist yet.
            //
            // The joiner is read out of the field rather than from runSession's return value: it
            // is constructed partway through that method, and four bounded waits follow. A failure
            // in any of them would leave a returned-value binding null while B's adapter, game and
            // lobby session were all up — the exact leak this block exists to prevent, and one
            // that also leaves B logged into the game server for the next run to trip over.
            try {
                shutdown(joiner);
            } finally {
                // Nested, so an unexpected throw while shutting B down cannot leave A's adapter
                // and game running — the same class of hole as the joiner field above.
                shutdown(host);
            }
        }
    }

    @Test
    @EnabledIf("liveEnvironmentAvailable")
    void lobbyPhaseDepartureTearsTheSurvivorDownCleanly() throws Exception {
        assumeTrue(lobbyReachable(), unreachableLobbyMessage());

        host = new Peer("A(host)", hostConfig("faf-test-harness 4.3.4 " + UUID.randomUUID()));
        try {
            runSession();

            // Taken before the kill, in the same spirit as runSession's own HOSTING future. This
            // one is terminal and so would complete even if asked for late, but the habit is what
            // keeps the next checkpoint added here honest.
            CompletableFuture<Void> terminated =
                    host.lifecycle.stateReached(ClientState.TERMINATED);
            int mark = logMark();

            // kill -9 on the joiner's game, leaving its client alive to notice. The client sends
            // GameState Ended on a game it never saw end cleanly, faf-server routes that to
            // on_connection_closed() then abort(), and abort() runs disconnect_all_peers() because
            // the game is still in GameState.LOBBY. There is a second path to the same frame if
            // that send loses its race with B's teardown closing the lobby: the socket closing
            // reaches on_connection_lost() and so the same abort().
            killGameOf(joiner);

            // The frame arrived and was read. This is the client half of the card.
            awaitLogLine(
                    mark,
                    "peer disconnect: id=" + joiner.identity.id(),
                    DEPARTURE_TIMEOUT,
                    "A never relayed the departure to its adapter" + adapterDisconnectHint(mark));

            // And the adapter acted on it. The RPC destroys the peer relay and makes the adapter
            // emit a GPGNet DisconnectFromPeer to A's own game, which is the only way that game can
            // reach ENDED here. Asserting the game's own exit line rather than the adapter's log
            // keeps this pinned to our contract instead of upstream's wording.
            //
            // The line carries no player id, so it is only unambiguous because B's game was killed
            // and never logs it: both games' stdout funnels into this one JVM's root logger.
            awaitLogLine(
                    mark,
                    "mock game finished: status=OK, exit code 0",
                    DEPARTURE_TIMEOUT,
                    "A's game did not end cleanly on the adapter's DisconnectFromPeer"
                            + adapterDisconnectHint(mark));

            await(terminated, DEPARTURE_TIMEOUT, "A: TERMINATED after its game ended");
        } finally {
            try {
                shutdown(joiner);
            } finally {
                shutdown(host);
            }
        }
    }

    @Test
    @EnabledIf("liveEnvironmentAvailable")
    void postLaunchDepartureIsObservedFromTheAdapterAlone() throws Exception {
        assumeTrue(lobbyReachable(), unreachableLobbyMessage());

        host =
                new Peer(
                        "A(host)",
                        hostConfig(
                                "faf-test-harness 4.3.4 " + UUID.randomUUID(),
                                (int) HOST_LAUNCH_DELAY.toSeconds()));
        try {
            runSession();
            int hostedUid = host.lifecycle.gameLaunched().getNow(null).uid();

            // The host's own adapter relaying GameState Launching is what moves this client to
            // PLAYING, and it is not the same event as faf-server processing that frame: the
            // forwarder's lobby send is fire-and-forget on the same notification fan-out. Waiting
            // only for PLAYING would leave a window in which the server's game is still LOBBY, and
            // a departure inside it would produce exactly the DisconnectFromPeer this run asserts
            // the absence of. So the server's own view is what gates the departure.
            await(
                    host.lifecycle.stateReached(ClientState.PLAYING),
                    LAUNCH_TIMEOUT,
                    "A: PLAYING (launch delay " + HOST_LAUNCH_DELAY.toSeconds() + "s)");
            awaitServerGameState(host, hostedUid, "playing");

            // Everything from here is the departure. Marked so both assertions below read only the
            // tail: the adapter emits onConnected(..., false) from several points during ICE
            // negotiation, so a scan of the whole run would find a bring-up line and pass without
            // the departure having produced anything at all.
            int mark = logMark();
            shutdown(joiner);

            awaitLogLine(
                    mark,
                    "peer connected: local="
                            + host.identity.id()
                            + " remote="
                            + joiner.identity.id()
                            + " connected=false",
                    PEER_LOST_TIMEOUT,
                    "A's adapter never reported the departed peer unreachable");

            // The card's central claim, and the reason the gate above is on the server's state
            // rather than on PLAYING: after launch faf-server sends no targeted departure notice at
            // all, because abort() guards disconnect_all_peers() on GameState.LOBBY. Checked only
            // once the positive assertion has fired, so it is a statement about the same window
            // rather than about a moment nothing had happened in yet.
            assertNoLogLine(
                    mark,
                    "peer disconnect: id=" + joiner.identity.id(),
                    "the server must send no DisconnectFromPeer once the game has launched");
        } finally {
            try {
                shutdown(joiner);
            } finally {
                shutdown(host);
            }
        }
    }

    /** The skip message shared by every test in this class. */
    private static String unreachableLobbyMessage() {
        return "lobby "
                + lobbyUrl()
                + " unreachable from this network (TCP timeout on :443). Self-skips "
                + "off-net; runs on a FAF-allowlisted host/VPN.";
    }

    /**
     * A mark in the captured log, so a later assertion reads only what followed it.
     *
     * @return the current size of the capture
     */
    private int logMark() {
        return captured.list.size();
    }

    /**
     * SIGKILL one peer's mock game, leaving its client running to notice the death.
     *
     * <p>Located among this JVM's descendants by binary name and player id rather than through a
     * process handle, because the lifecycle owns its subprocesses and hands out none. The id is
     * matched with its trailing separator: {@code MockGameLauncher} emits {@code --player-id} and
     * the value as two argv entries and always follows them with {@code --player-login}, so without
     * it an id would match any longer one that starts with the same digits.
     *
     * @param peer the peer whose game to kill
     * @throws InterruptedException if the wait for the process to die is interrupted
     */
    private void killGameOf(final Peer peer) throws InterruptedException {
        String gameNeedle = peer.config.mockGameBinaryPath().getFileName().toString();
        String idNeedle = "--player-id " + peer.identity.id() + " ";
        ProcessHandle game =
                ProcessHandle.current()
                        .descendants()
                        .filter(
                                handle ->
                                        handle.info()
                                                .commandLine()
                                                .filter(line -> line.contains(gameNeedle))
                                                .filter(line -> line.contains(idNeedle))
                                                .isPresent())
                        .findFirst()
                        .orElseGet(
                                () ->
                                        fail(
                                                "could not find "
                                                        + peer.name
                                                        + "'s mock game to kill; descendants: "
                                                        + descendantCommandLines()));
        game.destroyForcibly();
        try {
            game.onExit().get(TEARDOWN_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException | ExecutionException e) {
            fail("killed " + peer.name + "'s mock game but it did not die: " + e);
        }
    }

    /**
     * Wait until the server reports {@code uid} in {@code expected}, as seen in {@code game_info}.
     *
     * @param peer the peer whose lobby connection observes the frames
     * @param uid the game to watch
     * @param expected the client-facing state name to wait for
     * @throws InterruptedException if the wait is interrupted
     */
    private void awaitServerGameState(final Peer peer, final int uid, final String expected)
            throws InterruptedException {
        long deadline = System.nanoTime() + SERVER_LIVE_TIMEOUT.toNanos();
        do {
            if (expected.equals(peer.serverGameStates.get(uid))) {
                return;
            }
            Thread.sleep(POLL_SLICE.toMillis());
        } while (System.nanoTime() < deadline);
        fail(
                "the server never reported game "
                        + uid
                        + " as "
                        + expected
                        + " within "
                        + SERVER_LIVE_TIMEOUT
                        + "; last seen: "
                        + peer.serverGameStates.get(uid));
    }

    /**
     * Bounded wait for a captured log line containing {@code needle}, ignoring everything logged
     * before {@code mark}.
     *
     * @param mark the index returned by {@link #logMark()} before the event under test
     * @param needle the text the line must contain
     * @param timeout the budget
     * @param what what the line would have proven, used verbatim in the failure message
     * @throws InterruptedException if the wait is interrupted
     */
    private void awaitLogLine(
            final int mark, final String needle, final Duration timeout, final String what)
            throws InterruptedException {
        long deadline = System.nanoTime() + timeout.toNanos();
        do {
            if (!linesSince(mark, needle).isEmpty()) {
                return;
            }
            Thread.sleep(POLL_SLICE.toMillis());
        } while (System.nanoTime() < deadline);
        fail(what + ": no log line containing '" + needle + "' within " + timeout);
    }

    /**
     * Assert that nothing logged since {@code mark} contains {@code needle}.
     *
     * <p>What stops this being vacuous is the assertion that runs before it: {@code peer
     * disconnect:} and {@code peer connected:} are both emitted at INFO, so a positive match on the
     * second proves INFO capture was live across the same window the first is claimed absent from.
     * Move either line to DEBUG and this quietly stops proving anything.
     *
     * @param mark the index returned by {@link #logMark()} before the event under test
     * @param needle the text no line may contain
     * @param why what its presence would mean, used verbatim in the failure message
     */
    private void assertNoLogLine(final int mark, final String needle, final String why) {
        List<String> found = linesSince(mark, needle);
        if (!found.isEmpty()) {
            fail(why + "; found: " + found);
        }
    }

    /**
     * Captured messages logged at or after {@code mark} that contain {@code needle}.
     *
     * <p>Snapshotted into a plain list first: the backing capture is copy-on-write and written by
     * several subprocess reader threads, so an index-based read of the live list can drift.
     *
     * @param mark the index to start reading from
     * @param needle the text to match
     * @return the matching messages, oldest first
     */
    private List<String> linesSince(final int mark, final String needle) {
        List<ILoggingEvent> snapshot = new ArrayList<>(captured.list);
        List<String> found = new ArrayList<>();
        for (int i = Math.min(mark, snapshot.size()); i < snapshot.size(); i++) {
            String message = snapshot.get(i).getFormattedMessage();
            if (message.contains(needle)) {
                found.add(message);
            }
        }
        return found;
    }

    /**
     * The adapter's own view of a departure, quoted into a failed checkpoint.
     *
     * <p>A hint rather than an assertion. {@code onDisconnectFromPeer} is upstream's log text,
     * pinned to no contract of ours, so a reworded line upstream should not fail this test. It is
     * still the fastest way to tell "the RPC never left the client" from "the RPC arrived and the
     * adapter did nothing useful with it".
     *
     * @param mark the index to read from
     * @return the matching adapter lines in parentheses, or an empty string
     */
    private String adapterDisconnectHint(final int mark) {
        List<String> seen = linesSince(mark, "onDisconnectFromPeer");
        return seen.isEmpty()
                ? " (A's adapter logged no onDisconnectFromPeer at all)"
                : " (A's adapter logged: " + seen + ")";
    }

    /**
     * The ordered checkpoints, from A's welcome to both adapters reporting the link. Split out of
     * the test method so the shutdown above wraps every one of them.
     *
     * <p>Reads {@link #host} rather than taking it, so the field the orphan check needs is the same
     * object these checkpoints run against.
     *
     * @throws InterruptedException if any bounded wait is interrupted
     */
    private void runSession() throws InterruptedException {
        // Taken before the events that can reach them. StateMachine.stateReached only
        // short-circuits while the state is still current, so a future asked for after the FSM has
        // been through and left that state can never complete — and HOSTING is left the moment a
        // session dies. Taking it up front is what makes this checkpoint honest; it does not make a
        // dead session report faster, since nothing here races the wait against TERMINATED.
        CompletableFuture<Void> hosting = host.lifecycle.stateReached(ClientState.HOSTING);

        // A hosts. Reaching IDLE sends game_host; the server answers game_launch, which is what
        // spawns A's adapter and game and completes gameLaunched with the uid.
        host.identity = await(host.lifecycle.start(tokensFor(host)), SESSION_TIMEOUT, "A: welcome");
        GameConfig hosted =
                await(host.lifecycle.gameLaunched(), GAME_LAUNCH_TIMEOUT, "A: game_launch");
        await(hosting, ROLE_TIMEOUT, "A: HOSTING");

        // Only now is the game joinable: the server marks it hosted when A's game reports Lobby,
        // which reaches the server only because R72 forwards it. B is started with A's uid as its
        // join target — the one value that crosses between the two clients in-process. Assigned to
        // the field before anything can fail, so the teardown in the caller can always reach it.
        joiner = new Peer("B(joiner)", joinConfig(hosted.uid()));
        CompletableFuture<Void> joining = joiner.lifecycle.stateReached(ClientState.JOINING);
        joiner.identity =
                await(joiner.lifecycle.start(tokensFor(joiner)), SESSION_TIMEOUT, "B: welcome");
        await(joiner.lifecycle.gameLaunched(), GAME_LAUNCH_TIMEOUT, "B: game_launch", joiner);
        await(joining, ROLE_TIMEOUT, "B: JOINING");

        // The card's definitive signal, on both sides, for the ids the lobby assigned.
        awaitPeerConnected(host, joiner);
        awaitPeerConnected(joiner, host);

        // WBS-4.3.2: with the link up, each game's traffic must be reaching the other.
        awaitPeerTraffic(host, joiner);
    }

    /**
     * Wait until both games report receiving the other's datagrams (WBS-4.3.2).
     *
     * <p>Gated on the {@code onConnected} checkpoints above, deliberately: the adapter drops
     * anything sent before ICE completes, so counting from the start of the session would be
     * counting a window that is expected to be lossy. Each direction needs {@link
     * #MIN_PROGRESS_SAMPLES} progress lines whose datagram count reaches {@link #MIN_DATAGRAMS} and
     * whose highest sequence has moved between the first and the last — which is what "still
     * advancing" means, and is why a count alone is not enough.
     *
     * @param first one peer
     * @param second the other
     * @throws InterruptedException if the wait is interrupted
     */
    private void awaitPeerTraffic(final Peer first, final Peer second) throws InterruptedException {
        int firstId = first.identity.id();
        int secondId = second.identity.id();
        long deadline = System.nanoTime() + TRAFFIC_TIMEOUT.toNanos();
        do {
            if (exchangeProven(firstId, secondId) && exchangeProven(secondId, firstId)) {
                return;
            }
            Thread.sleep(POLL_SLICE.toMillis());
        } while (System.nanoTime() < deadline);

        fail(
                "no two-way peer traffic within "
                        + TRAFFIC_TIMEOUT
                        + " (wanted "
                        + MIN_PROGRESS_SAMPLES
                        + " progress lines per direction reaching "
                        + MIN_DATAGRAMS
                        + " datagrams with an advancing sequence). "
                        + first.name
                        + " received: "
                        + samples(firstId, secondId)
                        + "; "
                        + second.name
                        + " received: "
                        + samples(secondId, firstId)
                        + bindFailureHint());
    }

    /**
     * Whether {@code receiverId}'s game has proven it is receiving {@code senderId}'s traffic.
     *
     * @param receiverId the game doing the receiving
     * @param senderId the game whose datagrams it must have counted
     * @return {@code true} once the samples meet the thresholds and the sequence has advanced
     */
    private boolean exchangeProven(final int receiverId, final int senderId) {
        List<TrafficSample> seen = samples(receiverId, senderId);
        if (seen.size() < MIN_PROGRESS_SAMPLES) {
            return false;
        }
        TrafficSample oldest = seen.get(0);
        TrafficSample newest = seen.get(seen.size() - 1);
        return newest.datagrams() >= MIN_DATAGRAMS
                && newest.highestSequence() > oldest.highestSequence();
    }

    /**
     * Every progress line captured so far for one direction, oldest first.
     *
     * @param receiverId the game that logged the line
     * @param senderId the peer the line is about
     * @return the parsed samples
     */
    private List<TrafficSample> samples(final int receiverId, final int senderId) {
        List<TrafficSample> found = new ArrayList<>();
        for (ILoggingEvent event : captured.list) {
            Matcher matcher = PROGRESS_LINE.matcher(event.getFormattedMessage());
            if (!matcher.find()) {
                continue;
            }
            TrafficSample sample =
                    new TrafficSample(
                            Integer.parseInt(matcher.group(1)),
                            Integer.parseInt(matcher.group(2)),
                            Long.parseLong(matcher.group(3)),
                            Long.parseLong(matcher.group(4)));
            if (sample.receiverId() == receiverId && sample.senderId() == senderId) {
                found.add(sample);
            }
        }
        return found;
    }

    /**
     * A game that could not bind its lobby port explains a silent traffic path, so say so rather
     * than leaving a bare timeout to be re-diagnosed.
     *
     * @return the captured bind-failure lines in parentheses, or an empty string
     */
    private String bindFailureHint() {
        List<String> failures = new ArrayList<>();
        for (ILoggingEvent event : captured.list) {
            String message = event.getFormattedMessage();
            if (message.contains(BIND_FAILURE)) {
                failures.add(message);
            }
        }
        return failures.isEmpty()
                ? ""
                : " (a game could not bind its lobby port: " + failures + ")";
    }

    /**
     * Wait for {@code peer}'s adapter to report the link to {@code other} established.
     *
     * <p>Consumes verdicts in arrival order and fails with everything seen, so a {@code
     * connected=false} — the adapter's way of saying "this peer is unreachable" — is reported as
     * what it is rather than as a bare timeout.
     *
     * @param peer the side whose adapter must report
     * @param other the peer it must report about
     * @throws InterruptedException if the wait is interrupted
     */
    private void awaitPeerConnected(final Peer peer, final Peer other) throws InterruptedException {
        long deadline = System.nanoTime() + PEER_CONNECTED_TIMEOUT.toNanos();
        do {
            PeerVerdict verdict = peer.verdicts.poll(POLL_SLICE.toMillis(), TimeUnit.MILLISECONDS);
            if (verdict == null) {
                continue;
            }
            peer.observed.add(verdict);
            if (verdict.connected()) {
                assertEquals(
                        peer.identity.id(),
                        verdict.localId(),
                        peer.name + ": the adapter must report its own lobby-assigned id");
                assertEquals(
                        other.identity.id(),
                        verdict.remoteId(),
                        peer.name + ": the peer id must be the other client's lobby-assigned id");
                return;
            }
        } while (System.nanoTime() < deadline);
        fail(
                peer.name
                        + ": no onConnected(..., true) within "
                        + PEER_CONNECTED_TIMEOUT
                        + "; verdicts seen: "
                        + peer.observed);
    }

    /**
     * Shut one session down and wait for its teardown, tolerating a peer that never started.
     *
     * @param peer the peer to shut down, or {@code null} if it was never constructed
     */
    private void shutdown(final Peer peer) {
        if (peer == null) {
            return;
        }
        peer.lifecycle.shutdown();
        try {
            peer.lifecycle
                    .stateReached(ClientState.TERMINATED)
                    .get(TEARDOWN_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException | ExecutionException e) {
            // Deliberately not a failure here: this runs on the teardown path, where the job is to
            // leave nothing running even from a session in a state we did not expect. The
            // pgrep-clean assertion afterwards is what decides whether teardown actually worked.
            System.out.println("[4.3.1] " + peer.name + " did not reach TERMINATED: " + e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        // Once-guarded, so this is a no-op on the ordinary path and the real thing on every other.
        peer.teardown.run();
    }

    /**
     * The "pgrep-clean" checkpoint: once both sessions have shut down, neither binary may still be
     * running under this JVM. Both peers use the same two binaries, so one check covers all four
     * processes.
     *
     * @param config either peer's config, read only for the binary file names
     * @throws InterruptedException if the wait is interrupted
     */
    private void assertNoSurvivingSubprocesses(final MockClientConfig config)
            throws InterruptedException {
        String adapterNeedle = config.iceAdapterBinaryPath().getFileName().toString();
        String gameNeedle = config.mockGameBinaryPath().getFileName().toString();
        long deadline = System.nanoTime() + NO_ORPHANS_TIMEOUT.toNanos();
        List<String> survivors;
        do {
            survivors = new ArrayList<>();
            for (String line : descendantCommandLines()) {
                if (line.contains(adapterNeedle) || line.contains(gameNeedle)) {
                    survivors.add(line);
                }
            }
            if (survivors.isEmpty()) {
                return;
            }
            Thread.sleep(POLL_SLICE.toMillis());
        } while (System.nanoTime() < deadline);
        fail("subprocesses survived teardown after " + NO_ORPHANS_TIMEOUT + ": " + survivors);
    }

    /**
     * Command lines of every process descended from this JVM, skipping any we cannot read.
     *
     * @return the command lines, in discovery order
     */
    private static List<String> descendantCommandLines() {
        List<String> lines = new ArrayList<>();
        ProcessHandle.current()
                .descendants()
                .forEach(handle -> handle.info().commandLine().ifPresent(lines::add));
        return lines;
    }

    /**
     * Bounded wait on a checkpoint future, failing with the checkpoint's own name.
     *
     * @param future the checkpoint
     * @param timeout its named budget
     * @param what what reaching it proves, used verbatim in the failure message
     * @param <T> the checkpoint's value type
     * @return the future's value
     * @throws InterruptedException if the wait is interrupted
     */
    private static <T> T await(
            final CompletableFuture<T> future, final Duration timeout, final String what)
            throws InterruptedException {
        return await(future, timeout, what, null);
    }

    /**
     * As {@link #await(CompletableFuture, Duration, String)}, but reads {@code peer}'s join-refusal
     * recorder when the wait times out.
     *
     * <p>The hint is read here rather than folded into {@code what} by the caller because the
     * refusal, by construction, can only arrive <em>during</em> this wait: a message built before
     * the call would always report the empty string.
     *
     * @param future the checkpoint
     * @param timeout its named budget
     * @param what what reaching it proves
     * @param peer the peer whose refusal recorder to consult on timeout, or {@code null}
     * @param <T> the checkpoint's value type
     * @return the future's value
     * @throws InterruptedException if the wait is interrupted
     */
    private static <T> T await(
            final CompletableFuture<T> future,
            final Duration timeout,
            final String what,
            final Peer peer)
            throws InterruptedException {
        try {
            return future.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            String hint = peer == null ? "" : peer.refusalHint();
            return fail("timed out after " + timeout + " waiting for: " + what + hint);
        } catch (ExecutionException e) {
            return fail("failed waiting for: " + what, e.getCause());
        }
    }

    /**
     * The hosting client's config: its own port set, account A, and a unique game title.
     *
     * @param title the advertised game title
     * @return the validated config
     */
    private static MockClientConfig hostConfig(final String title) {
        return hostConfig(title, NO_AUTO_LAUNCH);
    }

    /**
     * The hosting client's config with an explicit auto-launch policy (WBS-4.3.4).
     *
     * @param title the advertised game title
     * @param launchDelaySeconds seconds its mock game waits in the lobby before launching the
     *     match, or {@link #NO_AUTO_LAUNCH} to disable auto-launch entirely
     * @return the validated config
     */
    private static MockClientConfig hostConfig(final String title, final int launchDelaySeconds) {
        List<String> args = new ArrayList<>(commonArgs(tokenFileA(), launchDelaySeconds));
        args.add("--host-title=" + title);
        args.add("--host-map=" + HOST_MAP);
        args.add("--host-mod=" + HOST_MOD);
        args.add("--host-visibility=public");
        return ConfigLoader.load(args.toArray(new String[0]), Map.of()).orElseThrow();
    }

    /**
     * The joining client's config: a second port set, account B, and A's uid as the target.
     *
     * @param targetGameId the uid A's session was launched under
     * @return the validated config
     */
    private static MockClientConfig joinConfig(final int targetGameId) {
        // Never auto-launches, in every run including WBS-4.3.4's. A joiner with a launch delay
        // starts its own match timer on entering JOINING and ends its session when that fires, so
        // the moment it leaves would stop being the test's to choose. The server's game state is
        // decided by the host alone in any case: handle_game_state's Launching branch is host-only.
        List<String> args = new ArrayList<>(commonArgs(tokenFileB(), NO_AUTO_LAUNCH));
        args.add("--target-game-id=" + targetGameId);
        return ConfigLoader.load(args.toArray(new String[0]), Map.of()).orElseThrow();
    }

    /**
     * The settings both peers share, with a freshly allocated port set per call so the two clients
     * never collide, and auto-launch disabled so the hosted game stays joinable.
     *
     * @param refreshTokenFile this peer's account
     * @return the argv for {@link ConfigLoader}
     */
    private static List<String> commonArgs(
            final Path refreshTokenFile, final int launchDelaySeconds) {
        AdapterPorts ports = freeAdapterPorts();
        return List.of(
                "--lobby-websocket-url=" + lobbyUrl(),
                "--oauth-token-url=https://hydra.faforever.xyz/oauth2/token",
                "--oauth-auth-endpoint=https://hydra.faforever.xyz/oauth2/auth",
                "--oauth-redirect-uri=http://127.0.0.1",
                "--oauth-scopes=openid offline lobby",
                "--oauth-client-id=95ecec08-29c1-4c48-ae0a-b000ff349cb8",
                "--oauth-refresh-token-file=" + refreshTokenFile.toAbsolutePath(),
                // Fallback only: the handshake derives the real unique_id from faf-uid, which the
                // lobby's policy server requires.
                "--unique-id=00000000-0000-0000-0000-000000000000",
                "--uid-binary-path=" + requireUidBinary().toAbsolutePath(),
                "--ice-adapter-binary-path=" + requireAdapterBinary().toAbsolutePath(),
                "--mock-game-binary-path=" + requireGameBinary().toAbsolutePath(),
                "--ice-adapter-rpc-port=" + ports.rpc(),
                "--ice-adapter-gpg-net-port=" + ports.gpgnet(),
                "--ice-adapter-lobby-port=" + ports.lobby(),
                // The reason the two-peer test can exist at all; see the class javadoc. WBS-4.3.4's
                // post-launch run is the one caller that passes anything else.
                "--mock-game-launch-delay-seconds=" + launchDelaySeconds);
    }

    /**
     * This peer's OAuth token source, reading and rotating its own refresh-token file.
     *
     * @param peer the peer whose account to authenticate as
     * @return a token source bound to that account
     */
    private static TokenSource tokensFor(final Peer peer) {
        return TokenSources.fromConfig(peer.config);
    }

    /**
     * The three adapter listener ports, allocated free per peer.
     *
     * @param rpc JSON-RPC port (TCP)
     * @param gpgnet GPGNet port (TCP), shared with mock-game per spec §2.8
     * @param lobby lobby game-traffic port (UDP), shared with mock-game per spec §2.8
     */
    private record AdapterPorts(int rpc, int gpgnet, int lobby) {}

    /**
     * Allocate three distinct free ports. The sockets are held open simultaneously so the OS hands
     * out distinct numbers, and released before the adapter binds them — a benign TOCTOU window
     * that surfaces as connect-retry exhaustion rather than a wrong answer.
     *
     * @return one peer's port set
     */
    private static AdapterPorts freeAdapterPorts() {
        try (ServerSocket rpc = new ServerSocket(0);
                ServerSocket gpgnet = new ServerSocket(0);
                DatagramSocket lobby = new DatagramSocket(0)) {
            return new AdapterPorts(
                    rpc.getLocalPort(), gpgnet.getLocalPort(), lobby.getLocalPort());
        } catch (IOException e) {
            throw new IllegalStateException("could not allocate a free port set for a peer", e);
        }
    }

    /**
     * The lobby endpoint this run targets.
     *
     * @return the environment override, or {@link #DEFAULT_LOBBY_URL}
     */
    private static URI lobbyUrl() {
        String override = System.getenv(LOBBY_URL_ENV);
        return URI.create(override == null || override.isBlank() ? DEFAULT_LOBBY_URL : override);
    }

    /**
     * The post-launch run's host launch delay: {@link #LAUNCH_DELAY_ENV} if it parses to a positive
     * number of seconds, two minutes otherwise. A malformed or non-positive override is ignored
     * rather than honoured, because a zero or negative one would disable the auto-launch this run
     * exists to perform and the failure would surface much later as an unexplained timeout.
     *
     * @return the delay to launch the host's match after
     */
    private static Duration hostLaunchDelay() {
        Duration fallback = Duration.ofMinutes(2);
        String override = System.getenv(LAUNCH_DELAY_ENV);
        if (override == null || override.isBlank()) {
            return fallback;
        }
        long seconds;
        try {
            seconds = Long.parseLong(override.trim());
        } catch (NumberFormatException e) {
            System.out.println(
                    "[4.3.4] ignoring unparseable "
                            + LAUNCH_DELAY_ENV
                            + "="
                            + override
                            + "; using "
                            + fallback);
            return fallback;
        }
        // Too small a value does not merely shorten the run, it breaks it, and it breaks it at a
        // checkpoint that would blame the adapter. The mock game derives its match length as twice
        // this (mock-game Main.matchDuration), and the observation below has to finish inside that
        // match: once the host's own timer ends the match its client terminates and its adapter
        // dies, so the connectivity checker never gets to report the departed peer.
        Duration proposed = Duration.ofSeconds(seconds);
        Duration matchWindow = proposed.multipliedBy(2);
        if (seconds <= 0 || matchWindow.compareTo(PEER_LOST_TIMEOUT.plus(TEARDOWN_TIMEOUT)) <= 0) {
            System.out.println(
                    "[4.3.4] ignoring "
                            + LAUNCH_DELAY_ENV
                            + "="
                            + override
                            + ": it yields a "
                            + matchWindow
                            + " match, too short to observe a departure needing up to "
                            + PEER_LOST_TIMEOUT
                            + "; using "
                            + fallback);
            return fallback;
        }
        return proposed;
    }

    /**
     * Whether the lobby host accepts a TCP connection within a short timeout.
     *
     * @return {@code true} if the lobby is reachable from this network
     */
    private static boolean lobbyReachable() {
        URI url = lobbyUrl();
        int port = url.getPort() == -1 ? 443 : url.getPort();
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(url.getHost(), port), 3000);
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    /**
     * {@code @EnabledIf} probe — skips cleanly (never fails) when the machine is not equipped.
     *
     * @return {@code true} when every prerequisite resolves
     */
    @SuppressWarnings("unused")
    static boolean liveEnvironmentAvailable() {
        // Every prerequisite is probed, not short-circuited, so one run reports everything missing
        // rather than making the operator rediscover them one at a time.
        boolean adapter =
                present(
                        "faf-ice-adapter jar",
                        findAdapterBinary(),
                        ADAPTER_JAR_ENV,
                        "run ./gradlew downloadIceAdapter");
        boolean game =
                present(
                        "mock-game binary",
                        findGameBinary(),
                        MOCK_GAME_ENV,
                        "run ./gradlew :mock-game:installDist");
        boolean uid =
                present(
                        "faf-uid binary",
                        findUidBinary(),
                        UID_BINARY_ENV,
                        "see documentation/demos/README.md");
        boolean tokenA =
                present(
                        "hosting account's refresh token",
                        findTokenA(),
                        TOKEN_A_ENV,
                        "bootstrap .secrets/refresh_token.txt");
        boolean tokenB =
                present(
                        "joining account's refresh token",
                        findTokenB(),
                        TOKEN_B_ENV,
                        "bootstrap .secrets/refresh_token_b.txt for a SECOND seeded account");
        return adapter && game && uid && tokenA && tokenB;
    }

    /**
     * Reports one missing prerequisite on stdout, so a skipped run says what it wanted.
     *
     * @param what the prerequisite's name
     * @param found the resolved path, or {@code null}
     * @param env the environment variable that overrides its location
     * @param remedy what to do about it
     * @return whether it was present
     */
    private static boolean present(
            final String what, final Path found, final String env, final String remedy) {
        if (found != null) {
            return true;
        }
        System.out.println(
                "[4.3.1] skipping two-peer session test: no "
                        + what
                        + " (set "
                        + env
                        + ", or "
                        + remedy
                        + ").");
        return false;
    }

    private static Path requireAdapterBinary() {
        return required(findAdapterBinary(), "adapter jar");
    }

    private static Path requireGameBinary() {
        return required(findGameBinary(), "mock-game binary");
    }

    private static Path requireUidBinary() {
        return required(findUidBinary(), "faf-uid binary");
    }

    private static Path tokenFileA() {
        return required(findTokenA(), "hosting account's refresh token");
    }

    private static Path tokenFileB() {
        return required(findTokenB(), "joining account's refresh token");
    }

    /**
     * Non-null variant for the test body; guaranteed present once the gate passes.
     *
     * @param resolved the resolved path, or {@code null}
     * @param what the prerequisite's name, for the failure message
     * @return {@code resolved}
     */
    private static Path required(final Path resolved, final String what) {
        if (resolved == null) {
            throw new IllegalStateException(what + " vanished after the @EnabledIf gate");
        }
        return resolved;
    }

    private static Path findAdapterBinary() {
        return resolve(ADAPTER_JAR_ENV, "faf-ice-adapter.jar", "../faf-ice-adapter.jar");
    }

    private static Path findGameBinary() {
        return resolve(
                MOCK_GAME_ENV,
                "mock-game/build/install/mock-game/bin/mock-game",
                "../mock-game/build/install/mock-game/bin/mock-game");
    }

    private static Path findUidBinary() {
        return resolve(UID_BINARY_ENV, "faf-uid", "../faf-uid");
    }

    private static Path findTokenA() {
        return resolve(TOKEN_A_ENV, ".secrets/refresh_token.txt", "../.secrets/refresh_token.txt");
    }

    private static Path findTokenB() {
        return resolve(
                TOKEN_B_ENV, ".secrets/refresh_token_b.txt", "../.secrets/refresh_token_b.txt");
    }

    /**
     * First readable candidate, with {@code env} taking precedence; {@code null} when none is. A
     * Gradle {@code Test} task runs with the subproject as its working directory, which is why each
     * caller passes both a repo-root-relative and a {@code ../} candidate.
     *
     * @param env the environment variable that overrides the location
     * @param candidates the default locations, in order
     * @return the first readable path, or {@code null}
     */
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
}
