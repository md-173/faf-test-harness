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
import com.faforever.testharness.shared.logging.LoggingSetup;
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
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

/**
 * The two-peer (WBS-4.3.1) and three/four-peer (WBS-4.3.3) milestones: two to four Mock Clients on
 * one host, each with its own lobby account, its own port set, and its own real {@code
 * faf-ice-adapter} and mock-game, complete a host/join through the <em>live</em> lobby, and every
 * adapter reports a link to every other peer: one connection at two players, three at three, six at
 * four.
 *
 * <p>The clients never touch each other in-process. A's game uid reaches each joiner through {@link
 * MockClientLifecycle#gameLaunched()}, the same value an operator reads off A's {@code game
 * launch:} log line. Every other exchange (the {@code game_host}/{@code game_join} pair, {@code
 * JoinGame}, {@code ConnectToPeer}, and every ICE candidate) crosses the FAF test lobby, exactly as
 * separate machines would.
 *
 * <p><b>What three players add (WBS-4.3.3).</b> faf-server's {@code connect_to_host} gives the host
 * {@code offer=true} for each joiner, and {@code connect_to_peer} gives a joiner {@code offer=true}
 * for every earlier joiner and each earlier joiner {@code offer=false} for it. Two peers only ever
 * use the host path; the peer-to-peer path and its offer asymmetry start at three, and {@link
 * #assertOfferDirections} checks both. Each peer runs under an instance label, A to D, and {@link
 * #assertInstanceLabels} checks that its client lines, adapter output and game output carry it and
 * that the mesh is visible from those lines alone. The host uses {@code friends} visibility.
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
 * <p><b>Why the empty ICE server list is enough.</b> All peers are on one machine, so they connect
 * over host candidates and never need STUN or TURN. The session's only network dependence is that
 * the lobby is reachable, which this test probes and self-skips on.
 *
 * <p><b>Auto-launch is off on all peers</b> ({@code --mock-game-launch-delay-seconds=-1},
 * WBS-4.3.1). faf-server accepts a {@code game_join} only while the game is in {@code
 * GameState.LOBBY} and leaves that state the moment the host reports {@code GameState Launching},
 * so a host on the default 5 s timer would make itself unjoinable while a joiner is still booting
 * two JVMs. Nothing is lost here: the peer link is established during the lobby phase, and so is
 * the game traffic this test now also asserts.
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
 * {@link #TRAFFIC_TIMEOUT}. The same shape is what WBS-4.3.4 will hit deliberately when a peer
 * rejoins.
 *
 * <p><b>Prerequisites</b>, all probed by {@link #missingPrerequisites(int)} so an unequipped
 * machine skips rather than fails: the adapter jar ({@code ./gradlew downloadIceAdapter}), the
 * installed mock-game binary ({@code ./gradlew :mock-game:installDist}), the {@code faf-uid} binary
 * (the lobby's policy server rejects a placeholder {@code unique_id}), and one seeded account's
 * refresh token per peer: {@code .secrets/refresh_token.txt}, {@code _b.txt}, {@code _c.txt} and
 * {@code _d.txt}, overridable by {@code FAF_REFRESH_TOKEN_A} to {@code _D}. A case with fewer
 * joiners needs only the files it uses. One account cannot host and join its own game, and one
 * account in two peers signs the first out. Every file used is rewritten in place on every run,
 * because Hydra rotates the refresh token on use. See {@code documentation/demos/README.md} for the
 * bootstrap and run notes.
 *
 * <p><b>Every wait is bounded and named</b>, in the 3.1.2.7 pattern: a missed checkpoint throws
 * {@link CheckpointFailure} with the peer, the stage, the limit that ran out and what had been seen
 * by then, so a regression names itself. Each wait is bound by its own budget and by {@link
 * #CASE_DEADLINE}, whichever is sooner.
 *
 * <p><b>Duration.</b> A case typically takes 20 to 40 s and all three under two minutes, most of it
 * logins and JVM starts. {@link Timeout} applies to each case, not the class: 600 s, above the 420
 * s case deadline plus the at most 140 s of teardown, so it only fires if teardown itself hangs.
 * The worst case for all three is therefore 30 minutes.
 *
 * <p><b>Evidence.</b> Every line carries its instance label, A to D. In the JSONL they land under
 * {@code mock-client/logs/}: {@code test-harness.jsonl} for the clients and their captured adapter
 * and game output, and {@code mockgame-A.jsonl} to {@code mockgame-D.jsonl} from the games
 * themselves. Cases append to the same files; each starts with a {@code case: N peers, run <uuid>}
 * line, and the uuid is also in the hosted game's title.
 */
@Tag("integration")
@Timeout(value = 600, unit = TimeUnit.SECONDS)
final class MultiPeerSessionLiveTest {

    /** Environment override for the adapter jar, consistent with R74's documented setup. */
    private static final String ADAPTER_JAR_ENV = "FAF_ICE_ADAPTER_JAR";

    /** Environment override for the installed mock-game binary. */
    private static final String MOCK_GAME_ENV = "FAF_MOCK_GAME_BINARY";

    /** Environment override for the {@code faf-uid} binary. */
    private static final String UID_BINARY_ENV = "FAF_UID_BINARY";

    /**
     * One peer's fixed slot: its label, role, and where its account's credential comes from.
     *
     * @param label the instance label and failure-message name, A to D
     * @param role {@code host} or {@code joiner}
     * @param tokenEnv the environment variable overriding its refresh-token file
     * @param tokenFile its refresh-token file relative to the repository root
     */
    private record PeerSlot(String label, String role, String tokenEnv, String tokenFile) {}

    /**
     * Every peer this test can run, in join order: the host first. Each slot has its own variable
     * and its own file and nothing falls back from one to another, so no peer can resolve another
     * peer's account. A case with N peers uses the first N.
     */
    private static final List<PeerSlot> SLOTS =
            List.of(
                    new PeerSlot("A", "host", "FAF_REFRESH_TOKEN_A", ".secrets/refresh_token.txt"),
                    new PeerSlot(
                            "B", "joiner", "FAF_REFRESH_TOKEN_B", ".secrets/refresh_token_b.txt"),
                    new PeerSlot(
                            "C", "joiner", "FAF_REFRESH_TOKEN_C", ".secrets/refresh_token_c.txt"),
                    new PeerSlot(
                            "D", "joiner", "FAF_REFRESH_TOKEN_D", ".secrets/refresh_token_d.txt"));

    /** Environment override for the lobby endpoint. */
    private static final String LOBBY_URL_ENV = "FAF_LOBBY_URL";

    /**
     * Lobby endpoint used when {@link #LOBBY_URL_ENV} is unset: the FAF test lobby. It is fronted
     * by Cloudflare and publicly reachable, so no VPN or allowlist is involved; the older {@code
     * lobby.faforever.xyz} host no longer answers.
     */
    private static final String DEFAULT_LOBBY_URL = "wss://ws.faforever.xyz";

    /** How long the lobby probe waits for a TCP connection before calling the lobby unreachable. */
    private static final Duration LOBBY_PROBE_TIMEOUT = Duration.ofSeconds(3);

    /**
     * Budget for one whole case, from the first login to the last checkpoint. Every named wait
     * below draws from it as well as from its own budget, and a failure says which ran out, so a
     * slow runner fails on a named checkpoint rather than on {@link Timeout}. Observed cases take
     * 20 to 40 s; this is headroom for slower ICE negotiation on a CI runner (#343, #366), not a
     * target. Teardown runs after it: at most {@link #TEARDOWN_TIMEOUT} per peer plus {@link
     * #NO_ORPHANS_TIMEOUT}, 140 s at four peers, which with this stays under the 600 s {@link
     * Timeout}.
     */
    private static final Duration CASE_DEADLINE = Duration.ofSeconds(420);

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
     * Budget for ICE to complete on every link once the last joiner is in, shared by the whole
     * mesh. Processes on one host negotiate over host candidates, which is fast; this is headroom
     * for the lobby relay hop, not a measurement of the negotiation.
     */
    private static final Duration PEER_CONNECTED_TIMEOUT = Duration.ofSeconds(90);

    /**
     * Budget for one pair's traffic to show up in both games' logs once ICE is established.
     * Generous against a 1 s progress interval: two samples per direction need two intervals plus
     * whatever the first datagrams cost, and this is headroom rather than a measurement.
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

    /**
     * Log text that means a subprocess could not bind a port it was given: mock-game's own line for
     * its lobby port, and the JDK's wording when the adapter cannot bind its RPC or GPGNet port.
     * Quoted into any failed wait, because the ports are released between allocation and bind, so
     * another process on a busy runner can take one in between (see {@link #freeAdapterPorts}).
     */
    private static final List<String> BIND_FAILURES =
            List.of("failed to bind lobby port", "BindException", "Address already in use");

    /**
     * Client log lines every peer must have emitted under its own label by the time the mesh and
     * traffic checkpoints pass (WBS-4.3.3). Each comes from a different thread source: the test
     * thread, the lobby listener and the adapter reader.
     */
    private static final List<String> LABELLED_CLIENT_LINES =
            List.of("state entry: CONNECTING", "session ready: ", "peer connected: ");

    /** {@code IceEventLogger}'s verdict line, read by the attribution checkpoint. */
    private static final Pattern PEER_CONNECTED_LINE =
            Pattern.compile("peer connected: local=(\\d+) remote=(\\d+) connected=(true|false)");

    /** {@code WelcomeStateSync}'s identity line, read by the attribution checkpoint. */
    private static final Pattern SESSION_READY_LINE = Pattern.compile("session ready: id=(\\d+) ");

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

    /** Map the host advertises; any real map folder name works. */
    private static final String HOST_MAP = "scmp_007";

    /** Featured mod the host advertises. */
    private static final String HOST_MOD = "faf";

    /** Case markers, so a JSONL file several cases appended to can be split by case. */
    private static final org.slf4j.Logger LOG =
            LoggerFactory.getLogger(MultiPeerSessionLiveTest.class);

    /**
     * Every peer built so far, host first, added by {@link #runSession} as each is built. A field
     * rather than a local, so the test's teardown can reach every peer even when a later checkpoint
     * fails.
     */
    private List<Peer> peers;

    /** {@link System#nanoTime()} at which the current case's {@link #CASE_DEADLINE} runs out. */
    private long caseDeadline;

    /** Root logger the mock-game capture appender is attached to. */
    private Logger root;

    /**
     * Captures every log record in this JVM, which includes every adapter's and mock game's output
     * as re-emitted by {@code ProcessOutputLogger}. Backed by a copy-on-write list: subprocess
     * reader threads, client threads and the test thread touch it at once.
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
        captured =
                new ListAppender<>() {
                    @Override
                    protected void append(final ILoggingEvent event) {
                        // Logback fills an event's MDC map lazily, from whichever thread reads it
                        // first. Read it here, on the logging thread, so the label checkpoint sees
                        // the label that thread carried rather than the test thread's.
                        event.prepareForDeferredProcessing();
                        super.append(event);
                    }
                };
        captured.list = new CopyOnWriteArrayList<>();
        captured.setContext(context);
        captured.start();
        root.addAppender(captured);
    }

    @AfterEach
    void stopCapturingSubprocessLogs() {
        if (captured != null) {
            captured.stop();
            root.detachAppender(captured);
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

    /**
     * A session checkpoint that did not pass, naming the peer and the stage. Deliberately not a
     * JUnit type: the orchestration in {@link #runSession} and its waits throw only this, so the
     * #87 {@code session} command can lift them into {@code main} unchanged and map this verdict to
     * its exit code. JUnit reports it as the test's failure like any other exception.
     */
    static final class CheckpointFailure extends RuntimeException {

        private static final long serialVersionUID = 1L;

        /** The peer, or peers, the checkpoint was about. */
        private final String peer;

        /** The checkpoint that did not pass, e.g. {@code welcome} or {@code full mesh}. */
        private final String stage;

        CheckpointFailure(final String peer, final String stage, final String detail) {
            this(peer, stage, detail, null);
        }

        CheckpointFailure(
                final String peer, final String stage, final String detail, final Throwable cause) {
            super(peer + ": " + stage + ": " + detail, cause);
            this.peer = peer;
            this.stage = stage;
        }

        String peer() {
            return peer;
        }

        String stage() {
            return stage;
        }
    }

    /**
     * A peer's account credential, which is all that differs between peers' lobby login settings.
     * Today it is a refresh-token file exchanged at Hydra. The pre-signed access-token file (#340)
     * becomes a second implementation with its own {@link #args()}, and nothing else changes.
     *
     * @param slot the peer this credential belongs to
     * @param refreshTokenFile the account's refresh-token file, rewritten in place on every use
     */
    private record PeerCredential(PeerSlot slot, Path refreshTokenFile) {

        /**
         * The login arguments for this credential.
         *
         * @return argv entries for {@link ConfigLoader}
         */
        List<String> args() {
            return List.of(
                    "--oauth-token-url=https://hydra.faforever.xyz/oauth2/token",
                    "--oauth-auth-endpoint=https://hydra.faforever.xyz/oauth2/auth",
                    "--oauth-redirect-uri=http://127.0.0.1",
                    "--oauth-scopes=openid offline lobby",
                    "--oauth-client-id=95ecec08-29c1-4c48-ae0a-b000ff349cb8",
                    "--oauth-refresh-token-file=" + refreshTokenFile.toAbsolutePath());
        }
    }

    /**
     * One {@code ConnectToPeer} as the lobby sent it.
     *
     * @param remoteId the peer to connect to
     * @param offer whether this side makes the ICE offer
     */
    private record Offer(long remoteId, boolean offer) {}

    /** One client under test: its config, its session, and what its adapter reported. */
    private static final class Peer {

        /** Name used in failure messages, so a red run says which side failed. */
        private final String name;

        /**
         * This peer's instance label (WBS-4.3.3), put on the test thread while the peer is built,
         * started and shut down. Its components capture it at construction and carry it onto their
         * own threads, and its adapter and game output inherit it through capture.
         */
        private final String label;

        private final MockClientConfig config;
        private final MockClientLifecycle lifecycle;
        private final SessionTeardown teardown;

        /** Peer verdicts in arrival order, filled from the adapter's reader thread. */
        private final BlockingQueue<PeerVerdict> verdicts = new LinkedBlockingQueue<>();

        /** Verdicts already taken off the queue, kept so a failure can report what was seen. */
        private final List<PeerVerdict> observed = new ArrayList<>();

        /**
         * The latest verdict per remote player id. Latest rather than first: a link reported up and
         * then down is not part of a mesh.
         */
        private final Map<Long, Boolean> latestByRemote = new HashMap<>();

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
         * Every {@code ConnectToPeer} this peer's lobby sent, as (remote id, offer). Filled on the
         * lobby listener thread, read on the test thread.
         */
        private final Set<Offer> offers = ConcurrentHashMap.newKeySet();

        /** This peer's lobby-assigned identity, from its {@code welcome}. */
        private SessionState identity;

        private Peer(final PeerSlot slot, final MockClientConfig config) {
            this.label = slot.label();
            this.name = slot.label() + "(" + slot.role() + ")";
            this.config = config;
            try (MDC.MDCCloseable ignored = labelled(this)) {
                LobbyConnection lobby = new LobbyConnection(config.lobbyWebSocketUrl());
                lobby.registerHandler(
                        "game_join_failed", frame -> joinRefusal.set(frame.toString()));
                // Registered before the lifecycle's own handler, so it runs first on each frame.
                lobby.registerHandler("ConnectToPeer", this::recordOffer);
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
         * Records one {@code ConnectToPeer} frame, {@code args: [login, id, offer]} (faf-server
         * {@code GpgNetServerProtocol.send_ConnectToPeer}). A malformed frame is recorded with id
         * -1, so it fails the offer check by name instead of vanishing on the listener thread.
         *
         * @param frame the lobby frame
         */
        private void recordOffer(final JsonNode frame) {
            JsonNode args = frame.path("args");
            boolean wellFormed = args.path(1).canConvertToLong() && args.path(2).isBoolean();
            offers.add(
                    new Offer(
                            wellFormed ? args.path(1).asLong() : -1,
                            wellFormed && args.path(2).asBoolean()));
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

    @ParameterizedTest
    @ValueSource(ints = {1, 2, 3})
    void multiplePeersEstablishTheirLinkThroughTheLiveLobby(int joinerAmount) throws Exception {
        // The one prerequisite gate. A machine without the live environment skips here, and
        // #364's FAF_LIVE_REQUIRED only has to turn this assumption into a failure.
        List<String> missing = missingPrerequisites(joinerAmount + 1);
        if (!missing.isEmpty()) {
            System.out.println("[4.3.1] skipping multi-peer session test: " + missing);
        }
        assumeTrue(missing.isEmpty(), () -> "missing live prerequisites: " + missing);

        String runId = UUID.randomUUID().toString();
        LOG.info("case: {} peers, run {}", joinerAmount + 1, runId);
        peers = new ArrayList<>();
        caseDeadline = System.nanoTime() + CASE_DEADLINE.toNanos();
        Throwable failure = null;
        try {
            runSession(joinerAmount + 1, runId);
            // Checks on what the session produced. runSession and its waits are JUnit-free, so
            // #87 can lift them; these two are specific to this test.
            assertOfferDirections(peers);
            assertInstanceLabels(peers);
        } catch (Throwable t) {
            failure = t;
            throw t;
        } finally {
            // A @Timeout interrupt would otherwise cut every bounded teardown wait below short.
            // Clear it for the teardown and restore it afterwards.
            boolean interrupted = Thread.interrupted();
            try {
                shutdownAll();
                List<String> survivors = survivingSubprocesses();
                // Checked on every path. The sweep sees every descendant of this JVM and the cases
                // run back to back in it, so a failed case that skipped it would hand its survivors
                // to the next case. After a checkpoint failure it is attached rather than thrown,
                // so the checkpoint stays the reported cause.
                if (!survivors.isEmpty()) {
                    AssertionError leak =
                            new AssertionError(
                                    "subprocesses survived teardown after "
                                            + NO_ORPHANS_TIMEOUT
                                            + ": "
                                            + survivors);
                    if (failure == null) {
                        throw leak;
                    }
                    failure.addSuppressed(leak);
                }
            } finally {
                if (interrupted) {
                    Thread.currentThread().interrupt();
                }
            }
        }
    }

    /**
     * Builds, starts and verifies one session: the host, then each joiner in turn, then the full
     * mesh and every pair's traffic. JUnit-free: every checkpoint that does not pass throws {@link
     * CheckpointFailure} naming the peer and the stage, which is what the #87 {@code session}
     * command reuses as its verdict. Every peer is added to {@link #peers} before anything can
     * fail, so the caller's teardown always reaches it.
     *
     * @param peerCount the number of peers, host included, 2 to {@link #SLOTS}'s size
     * @param runId unique per case, carried into the hosted game's title
     * @throws InterruptedException if any bounded wait is interrupted
     */
    private void runSession(final int peerCount, final String runId) throws InterruptedException {
        if (peerCount < 2 || peerCount > SLOTS.size()) {
            throw new IllegalArgumentException(
                    "peerCount must be 2 to " + SLOTS.size() + ": " + peerCount);
        }
        List<PeerCredential> credentials = distinctCredentials(peerCount);

        // Unique per case, so a stale game from an earlier run is never what this one observes,
        // though it is the uid, not the title, that the joiners actually target.
        Peer host =
                new Peer(
                        SLOTS.get(0),
                        hostConfig(credentials.get(0), "faf-test-harness 4.3.1 " + runId));
        peers.add(host);
        // Taken before the events that can reach them. StateMachine.stateReached only
        // short-circuits while the state is still current, so a future asked for after the FSM has
        // been through and left that state can never complete, and HOSTING is left the moment a
        // session dies. Taking it up front is what makes this checkpoint honest; it does not make a
        // dead session report faster, since nothing here races the wait against TERMINATED.
        CompletableFuture<Void> hosting = host.lifecycle.stateReached(ClientState.HOSTING);

        // A hosts. Reaching IDLE sends game_host; the server answers game_launch, which is what
        // spawns A's adapter and game and completes gameLaunched with the uid.
        GameConfig hosted;
        try (MDC.MDCCloseable ignored = labelled(host)) {
            host.identity =
                    await(host.lifecycle.start(tokensFor(host)), SESSION_TIMEOUT, host, "welcome");
            hosted = await(host.lifecycle.gameLaunched(), GAME_LAUNCH_TIMEOUT, host, "game_launch");
            await(hosting, ROLE_TIMEOUT, host, "HOSTING");
        }

        // Only now is the game joinable: the server marks it hosted when A's game reports Lobby,
        // which reaches the server only because R72 forwards it. Each joiner is started with A's
        // uid as its join target, the one value that crosses between clients in-process. They
        // join one at a time, each after the previous reached JOINING, so join order (and with it
        // the offer directions the test asserts) is the slot order.
        for (int i = 1; i < peerCount; i++) {
            Peer joiner = new Peer(SLOTS.get(i), joinConfig(credentials.get(i), hosted.uid()));
            peers.add(joiner);
            checkDistinctPorts(joiner);
            CompletableFuture<Void> joining = joiner.lifecycle.stateReached(ClientState.JOINING);
            try (MDC.MDCCloseable ignored = labelled(joiner)) {
                joiner.identity =
                        await(
                                joiner.lifecycle.start(tokensFor(joiner)),
                                SESSION_TIMEOUT,
                                joiner,
                                "welcome");
                checkDistinctAccount(joiner);
                await(joiner.lifecycle.gameLaunched(), GAME_LAUNCH_TIMEOUT, joiner, "game_launch");
                await(joining, ROLE_TIMEOUT, joiner, "JOINING");
            }
        }

        // The card's definitive signal: every adapter reports every other peer connected.
        awaitFullMesh();

        // WBS-4.3.2: with every link up, each pair's traffic must be reaching both sides. Once per
        // pair rather than per direction, since one wait proves both.
        for (int i = 0; i < peers.size(); i++) {
            for (int j = i + 1; j < peers.size(); j++) {
                awaitPeerTraffic(peers.get(i), peers.get(j));
            }
        }
    }

    /**
     * Every peer received exactly the {@code ConnectToPeer} offers faf-server's protocol predicts
     * (WBS-4.3.3), in join order. {@code connect_to_host} tells the host {@code offer=true} for
     * each joiner. {@code connect_to_peer} tells a joiner {@code offer=true} for every earlier
     * joiner and each earlier joiner {@code offer=false} for it. A joiner hears about the host
     * through {@code JoinGame}, never {@code ConnectToPeer}.
     *
     * <p>Compared as sets, so a repeated frame does not fail the check. Checked after the mesh,
     * which cannot complete before every frame has been dispatched: each peer's recorder is
     * registered before its lifecycle's own handler, so it sees a frame before the adapter does.
     *
     * @param session every peer, host first and joiners in join order
     */
    private static void assertOfferDirections(final List<Peer> session) {
        Peer host = session.get(0);
        List<Peer> joiners = session.subList(1, session.size());
        Set<Offer> hostExpected = new HashSet<>();
        for (Peer joiner : joiners) {
            hostExpected.add(new Offer(joiner.identity.id(), true));
        }
        assertEquals(hostExpected, host.offers, host.name + ": ConnectToPeer offers");
        for (int k = 0; k < joiners.size(); k++) {
            Set<Offer> expected = new HashSet<>();
            for (int i = 0; i < joiners.size(); i++) {
                if (i != k) {
                    expected.add(new Offer(joiners.get(i).identity.id(), i < k));
                }
            }
            Peer joiner = joiners.get(k);
            assertEquals(expected, joiner.offers, joiner.name + ": ConnectToPeer offers");
        }
    }

    /**
     * Resolves one credential per peer and refuses two peers that would share an account, before
     * any of them logs in. A second login with the same account signs the first out, which would
     * otherwise surface much later as an unexplained lobby disconnect.
     *
     * @param peerCount the number of peers, host included
     * @return the credentials, in slot order
     * @throws CheckpointFailure if two slots resolve to the same file
     */
    private static List<PeerCredential> distinctCredentials(final int peerCount) {
        List<PeerCredential> credentials = new ArrayList<>();
        Map<Path, PeerSlot> owners = new HashMap<>();
        for (PeerSlot slot : SLOTS.subList(0, peerCount)) {
            PeerCredential credential = credentialFor(slot);
            Path file;
            try {
                file = credential.refreshTokenFile().toRealPath();
            } catch (IOException e) {
                throw new CheckpointFailure(
                        slot.label(), "setup", "cannot read " + credential.refreshTokenFile(), e);
            }
            PeerSlot previous = owners.putIfAbsent(file, slot);
            if (previous != null) {
                throw new CheckpointFailure(
                        slot.label(),
                        "setup",
                        "resolves to the same token file as "
                                + previous.label()
                                + " ("
                                + file
                                + "); set "
                                + slot.tokenEnv()
                                + " to its own account");
            }
            credentials.add(credential);
        }
        return credentials;
    }

    /**
     * Refuses a joiner the lobby authenticated as an account an earlier peer already uses: two
     * different token files can still belong to one account.
     *
     * @param joiner the joiner that just received its welcome
     * @throws CheckpointFailure if its id matches an earlier peer's
     */
    private void checkDistinctAccount(final Peer joiner) {
        for (Peer other : peers) {
            if (other != joiner
                    && other.identity != null
                    && other.identity.id() == joiner.identity.id()) {
                throw new CheckpointFailure(
                        joiner.name,
                        "welcome",
                        "logged in as id "
                                + joiner.identity.id()
                                + ", the same account as "
                                + other.name
                                + "; every peer needs its own account");
            }
        }
    }

    /**
     * Refuses a joiner whose adapter ports repeat an earlier peer's, before it starts (WBS-4.3.3).
     * Each port set is allocated after the earlier peers' adapters hold theirs, so a repeat means
     * the OS handed out a bound port. Ports taken by an unrelated process in the gap before the
     * adapter binds are not visible here; those surface as a bind failure quoted by the next wait.
     *
     * @param joiner the joiner just built
     * @throws CheckpointFailure if a port repeats
     */
    private void checkDistinctPorts(final Peer joiner) {
        for (Peer other : peers) {
            if (other == joiner) {
                continue;
            }
            for (String port : portsOf(joiner)) {
                if (portsOf(other).contains(port)) {
                    throw new CheckpointFailure(
                            joiner.name, "ports", port + " was already given to " + other.name);
                }
            }
        }
    }

    /**
     * One peer's adapter ports, named by protocol so a TCP and a UDP port with one number differ.
     *
     * @param peer the peer
     * @return its RPC, GPGNet and lobby ports
     */
    private static List<String> portsOf(final Peer peer) {
        MockClientConfig c = peer.config;
        return List.of(
                "tcp " + c.iceAdapterRpcPort(),
                "tcp " + c.iceAdapterGpgNetPort(),
                "udp " + c.iceAdapterLobbyPort());
    }

    /**
     * The attribution checkpoint (WBS-4.3.3), run on captured log lines alone. It fails when:
     *
     * <ul>
     *   <li>an adapter or game line carries no label;
     *   <li>a peer has no labelled client line of each kind in {@link #LABELLED_CLIENT_LINES}, or
     *       no labelled adapter or game line;
     *   <li>a labelled line names another peer as its own ({@code session ready: id=}, {@code peer
     *       connected: local=}, or a progress line's receiving player), which is what a line
     *       attributed to the wrong instance looks like;
     *   <li>a peer's labelled {@code peer connected: ... connected=true} lines do not name every
     *       other peer, so the full mesh is also shown from the log output alone.
     * </ul>
     *
     * <p>Other unlabelled client lines are printed rather than failed: they come from rare paths
     * such as send-failure continuations on JDK threads, and failing on them would make the live
     * run flaky without making the checked lines any less attributable. Runs after the traffic
     * wait, seconds after the in-process mesh check, so the adapter reader has long since logged
     * every verdict it delivered.
     *
     * @param session every peer in the session
     */
    private void assertInstanceLabels(final List<Peer> session) {
        Map<String, Peer> byLabel = new HashMap<>();
        Map<Peer, List<String>> wanted = new HashMap<>();
        Map<Peer, Set<Long>> loggedLinks = new HashMap<>();
        for (Peer peer : session) {
            byLabel.put(peer.label, peer);
            List<String> kinds = new ArrayList<>(LABELLED_CLIENT_LINES);
            kinds.add("component " + IceAdapterLauncher.COMPONENT_TAG);
            kinds.add("component " + MockGameLauncher.COMPONENT_TAG);
            wanted.put(peer, kinds);
            loggedLinks.put(peer, new HashSet<>());
        }

        List<String> problems = new ArrayList<>();
        List<String> unlabelledOther = new ArrayList<>();
        for (ILoggingEvent event : captured.list) {
            if (LOG.getName().equals(event.getLoggerName())) {
                // This test's own case marker belongs to no instance.
                continue;
            }
            String instance = event.getMDCPropertyMap().get(LoggingSetup.INSTANCE_MDC_KEY);
            String component = event.getMDCPropertyMap().get(LoggingSetup.COMPONENT_MDC_KEY);
            String message = event.getFormattedMessage();
            Peer peer = instance == null ? null : byLabel.get(instance);
            if (peer == null) {
                if (instance != null || component != null) {
                    problems.add(
                            "line labelled " + instance + " from " + component + ": " + message);
                } else {
                    unlabelledOther.add(message);
                }
                continue;
            }
            wanted.get(peer).remove("component " + component);
            wanted.get(peer).removeIf(message::startsWith);

            long ownId = peer.identity.id();
            Matcher link = PEER_CONNECTED_LINE.matcher(message);
            if (link.find()) {
                if (Long.parseLong(link.group(1)) != ownId) {
                    problems.add(peer.name + " carries another peer's verdict: " + message);
                } else if (Boolean.parseBoolean(link.group(3))) {
                    loggedLinks.get(peer).add(Long.parseLong(link.group(2)));
                }
            }
            Matcher ready = SESSION_READY_LINE.matcher(message);
            if (ready.find() && Long.parseLong(ready.group(1)) != ownId) {
                problems.add(peer.name + " carries another peer's session: " + message);
            }
            Matcher progress = PROGRESS_LINE.matcher(message);
            if (progress.find() && Long.parseLong(progress.group(1)) != ownId) {
                problems.add(peer.name + " carries another game's traffic: " + message);
            }
        }

        for (Peer peer : session) {
            if (!wanted.get(peer).isEmpty()) {
                problems.add(
                        peer.name
                                + " has no line labelled "
                                + peer.label
                                + " for "
                                + wanted.get(peer));
            }
            for (Peer other : session) {
                if (other != peer && !loggedLinks.get(peer).contains((long) other.identity.id())) {
                    problems.add(
                            peer.name
                                    + " logged no 'peer connected ... connected=true' for "
                                    + other.name);
                }
            }
        }
        if (!unlabelledOther.isEmpty()) {
            System.out.println(
                    "[4.3.1] unlabelled client lines (reported, not failed): " + unlabelledOther);
        }
        if (!problems.isEmpty()) {
            fail("instance attribution incomplete: " + problems);
        }
    }

    /**
     * Puts {@code peer}'s label on the test thread until the returned scope is closed.
     *
     * @param peer the peer whose components are about to be built, started or shut down
     * @return the scope that removes the label again
     */
    private static MDC.MDCCloseable labelled(final Peer peer) {
        return MDC.putCloseable(LoggingSetup.INSTANCE_MDC_KEY, peer.label);
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
     * @throws CheckpointFailure if the traffic is not proven in time
     */
    private void awaitPeerTraffic(final Peer first, final Peer second) throws InterruptedException {
        int firstId = first.identity.id();
        int secondId = second.identity.id();
        String budget = budgetFor(TRAFFIC_TIMEOUT);
        long deadline = System.nanoTime() + boundedNanos(TRAFFIC_TIMEOUT);
        do {
            if (exchangeProven(firstId, secondId) && exchangeProven(secondId, firstId)) {
                return;
            }
            pollPause(deadline);
        } while (System.nanoTime() < deadline);

        throw new CheckpointFailure(
                first.name + "/" + second.name,
                "traffic",
                "no two-way peer traffic within "
                        + budget
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
     * A subprocess that could not bind a port it was given explains a silent path, so say so, with
     * every peer's ports, rather than leaving a bare timeout to be re-diagnosed.
     *
     * @return the captured bind-failure lines and the port sets in parentheses, or an empty string
     */
    private String bindFailureHint() {
        List<String> failures = new ArrayList<>();
        for (ILoggingEvent event : captured.list) {
            String message = event.getFormattedMessage();
            if (BIND_FAILURES.stream().anyMatch(message::contains)) {
                String instance = event.getMDCPropertyMap().get(LoggingSetup.INSTANCE_MDC_KEY);
                failures.add("[" + instance + "] " + message);
            }
        }
        if (failures.isEmpty()) {
            return "";
        }
        StringBuilder ports = new StringBuilder();
        for (Peer peer : peers) {
            ports.append(' ').append(peer.name).append(' ').append(portsOf(peer));
        }
        return " (a subprocess could not bind a port: " + failures + "; ports:" + ports + ")";
    }

    /**
     * Wait until every peer's adapter reports every other peer connected: two directed links per
     * pair, so one connection at two players, three at three and six at four.
     *
     * <p>Verdicts arrive in no fixed order once there are more than two peers (a joiner hears about
     * the host and about other joiners concurrently), so this keeps the latest verdict per remote
     * id and polls all peers under one deadline. A verdict about an id outside the session is kept
     * in the failure report but neither satisfies nor fails the wait.
     *
     * @throws InterruptedException if the wait is interrupted
     * @throws CheckpointFailure if the mesh is not complete in time
     */
    private void awaitFullMesh() throws InterruptedException {
        String budget = budgetFor(PEER_CONNECTED_TIMEOUT);
        long deadline = System.nanoTime() + boundedNanos(PEER_CONNECTED_TIMEOUT);
        while (true) {
            boolean complete = true;
            for (Peer peer : peers) {
                drainVerdicts(peer);
                complete &= missingLinks(peer).isEmpty();
            }
            if (complete) {
                return;
            }
            if (System.nanoTime() >= deadline) {
                break;
            }
            pollPause(deadline);
        }
        List<String> incomplete = new ArrayList<>();
        StringBuilder report = new StringBuilder();
        for (Peer peer : peers) {
            List<String> missing = missingLinks(peer).stream().map(p -> p.name).toList();
            if (!missing.isEmpty()) {
                incomplete.add(peer.name);
            }
            report.append(' ')
                    .append(peer.name)
                    .append(" missing ")
                    .append(missing)
                    .append(", seen ")
                    .append(peer.observed)
                    .append(';');
        }
        throw new CheckpointFailure(
                String.join(",", incomplete),
                "full mesh",
                "not every link up within " + budget + ":" + report + bindFailureHint());
    }

    /**
     * Moves every queued verdict into {@code peer}'s record, checking each is about this adapter.
     *
     * @param peer the peer whose queue to drain
     * @throws CheckpointFailure if the adapter reports another player as itself
     */
    private static void drainVerdicts(final Peer peer) {
        PeerVerdict verdict;
        while ((verdict = peer.verdicts.poll()) != null) {
            peer.observed.add(verdict);
            if (verdict.localId() != peer.identity.id()) {
                throw new CheckpointFailure(
                        peer.name,
                        "full mesh",
                        "adapter reported "
                                + verdict
                                + " but its lobby-assigned id is "
                                + peer.identity.id());
            }
            peer.latestByRemote.put(verdict.remoteId(), verdict.connected());
        }
    }

    /**
     * The session's other peers that {@code peer}'s adapter does not currently report connected.
     *
     * @param peer the reporting peer
     * @return the peers it is missing, empty when all its links are up
     */
    private List<Peer> missingLinks(final Peer peer) {
        List<Peer> missing = new ArrayList<>();
        for (Peer other : peers) {
            if (other != peer
                    && !peer.latestByRemote.getOrDefault((long) other.identity.id(), false)) {
                missing.add(other);
            }
        }
        return missing;
    }

    /**
     * The stage budget capped by what is left of the case deadline.
     *
     * @param stageBudget the wait's own budget
     * @return the nanoseconds the wait may take
     */
    private long boundedNanos(final Duration stageBudget) {
        long remaining = Math.max(0, caseDeadline - System.nanoTime());
        return Math.min(stageBudget.toNanos(), remaining);
    }

    /**
     * Names the limit a wait started now is bound by, for its failure message.
     *
     * @param stageBudget the wait's own budget
     * @return the stage budget, or the case deadline when less of it remains
     */
    private String budgetFor(final Duration stageBudget) {
        return caseDeadline - System.nanoTime() < stageBudget.toNanos()
                ? "the remaining case deadline (" + CASE_DEADLINE + " per case)"
                : stageBudget.toString();
    }

    /**
     * One poll interval, cut short at the deadline so a wait never overruns it.
     *
     * @param deadline the wait's {@link System#nanoTime()} deadline
     * @throws InterruptedException if interrupted
     */
    private static void pollPause(final long deadline) throws InterruptedException {
        long remaining = deadline - System.nanoTime();
        if (remaining > 0) {
            TimeUnit.NANOSECONDS.sleep(Math.min(POLL_SLICE.toNanos(), remaining));
        }
    }

    /**
     * Shuts every peer down, joiners first in reverse join order and the host last. One throwing
     * shutdown never stops the others, so every adapter and game gets its teardown.
     */
    private void shutdownAll() {
        if (peers == null) {
            return;
        }
        for (int i = peers.size() - 1; i >= 0; i--) {
            Peer peer = peers.get(i);
            try {
                shutdown(peer);
            } catch (RuntimeException e) {
                System.out.println("[4.3.1] " + peer.name + " shutdown threw: " + e);
            }
        }
    }

    /**
     * Shut one session down and wait for its teardown.
     *
     * @param peer the peer to shut down
     */
    private void shutdown(final Peer peer) {
        try (MDC.MDCCloseable ignored = labelled(peer)) {
            shutdownLabelled(peer);
        }
    }

    /**
     * {@link #shutdown(Peer)}'s body, run under the peer's label so its teardown lines, which are
     * logged on the test thread, stay attributable.
     *
     * @param peer the peer to shut down
     */
    private void shutdownLabelled(final Peer peer) {
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
     * The "pgrep-clean" sweep: once every session has shut down, neither binary may still be
     * running under this JVM. All peers use the same two binaries, so one sweep covers every
     * adapter and game. JUnit-free; the test decides what a survivor means.
     *
     * @return the command lines still running after {@link #NO_ORPHANS_TIMEOUT}, or empty
     * @throws InterruptedException if the wait is interrupted
     */
    private static List<String> survivingSubprocesses() throws InterruptedException {
        String adapterNeedle = requireAdapterBinary().getFileName().toString();
        String gameNeedle = requireGameBinary().getFileName().toString();
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
                return survivors;
            }
            pollPause(deadline);
        } while (System.nanoTime() < deadline);
        return survivors;
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
     * Bounded wait on one peer's checkpoint future, bound by the stage budget and the case
     * deadline, failing with the peer, the stage and the limit that ran out.
     *
     * <p>{@code peer}'s join refusal and any bind failure are read here rather than folded in by
     * the caller because both, by construction, can only arrive <em>during</em> this wait: a
     * message built before the call would always report the empty string.
     *
     * @param future the checkpoint
     * @param stageBudget its named budget
     * @param peer the peer the checkpoint is about
     * @param stage what reaching it proves, e.g. {@code welcome}
     * @param <T> the checkpoint's value type
     * @return the future's value
     * @throws InterruptedException if the wait is interrupted
     * @throws CheckpointFailure if the checkpoint times out or fails
     */
    private <T> T await(
            final CompletableFuture<T> future,
            final Duration stageBudget,
            final Peer peer,
            final String stage)
            throws InterruptedException {
        String budget = budgetFor(stageBudget);
        try {
            return future.get(boundedNanos(stageBudget), TimeUnit.NANOSECONDS);
        } catch (TimeoutException e) {
            throw new CheckpointFailure(
                    peer.name,
                    stage,
                    "timed out after " + budget + peer.refusalHint() + bindFailureHint());
        } catch (ExecutionException e) {
            throw new CheckpointFailure(peer.name, stage, "failed", e.getCause());
        }
    }

    /**
     * The hosting client's config: its own port set, account A, and a unique game title.
     *
     * @param credential account A's credential
     * @param title the advertised game title
     * @return the validated config
     */
    private static MockClientConfig hostConfig(
            final PeerCredential credential, final String title) {
        List<String> args = new ArrayList<>(commonArgs(credential));
        args.add("--host-title=" + title);
        args.add("--host-map=" + HOST_MAP);
        args.add("--host-mod=" + HOST_MOD);
        // WBS-4.3.3: friends-only. faf-server's command_game_join checks foes, lobby state, init
        // mode and password but never visibility, which only filters the game list, so joiners
        // still join by uid. It also keeps strangers in the test lobby from seeing and joining the
        // game, which would add peers the mesh and offer checks do not expect.
        args.add("--host-visibility=friends");
        return ConfigLoader.load(args.toArray(new String[0]), Map.of()).orElseThrow();
    }

    /**
     * A joining client's config: its own port set, its own account, and A's uid as the target.
     *
     * @param credential this joiner's credential
     * @param targetGameId the uid A's session was launched under
     * @return the validated config
     */
    private static MockClientConfig joinConfig(
            final PeerCredential credential, final int targetGameId) {
        List<String> args = new ArrayList<>(commonArgs(credential));
        args.add("--target-game-id=" + targetGameId);
        return ConfigLoader.load(args.toArray(new String[0]), Map.of()).orElseThrow();
    }

    /**
     * The settings every peer shares plus its own credential, with a freshly allocated port set per
     * call so clients never collide, and auto-launch disabled so the hosted game stays joinable.
     *
     * @param credential this peer's account
     * @return the argv for {@link ConfigLoader}
     */
    private static List<String> commonArgs(final PeerCredential credential) {
        AdapterPorts ports = freeAdapterPorts();
        List<String> args = new ArrayList<>();
        args.add("--lobby-websocket-url=" + lobbyUrl());
        args.addAll(credential.args());
        args.addAll(
                List.of(
                        // Fallback only: the handshake derives the real unique_id from faf-uid,
                        // which the lobby's policy server requires.
                        "--unique-id=00000000-0000-0000-0000-000000000000",
                        "--uid-binary-path=" + requireUidBinary().toAbsolutePath(),
                        "--ice-adapter-binary-path=" + requireAdapterBinary().toAbsolutePath(),
                        "--mock-game-binary-path=" + requireGameBinary().toAbsolutePath(),
                        "--ice-adapter-rpc-port=" + ports.rpc(),
                        "--ice-adapter-gpg-net-port=" + ports.gpgnet(),
                        "--ice-adapter-lobby-port=" + ports.lobby(),
                        // The reason this test can exist at all; see the class javadoc.
                        "--mock-game-launch-delay-seconds=-1"));
        return args;
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
     * Whether the lobby host accepts a TCP connection within {@link #LOBBY_PROBE_TIMEOUT}.
     *
     * @return {@code true} if the lobby is reachable from this machine
     */
    private static boolean lobbyReachable() {
        URI url = lobbyUrl();
        try (Socket socket = new Socket()) {
            socket.connect(
                    new InetSocketAddress(url.getHost(), lobbyPort(url)),
                    (int) LOBBY_PROBE_TIMEOUT.toMillis());
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    /**
     * The lobby URL's port, defaulting to the {@code wss} one.
     *
     * @param url the lobby URL
     * @return its explicit port, or 443
     */
    private static int lobbyPort(final URI url) {
        return url.getPort() == -1 ? 443 : url.getPort();
    }

    /**
     * Every live prerequisite this case lacks, in one place: the binaries, one token per peer, and
     * a reachable lobby. Everything is probed rather than short-circuited, so one run reports all
     * of it. The test skips on a non-empty result, which is the single spot #364's {@code
     * FAF_LIVE_REQUIRED} turns into a failure.
     *
     * @param peerCount the number of peers the case needs, host included
     * @return one line per missing prerequisite naming its override and remedy; empty when ready
     */
    static List<String> missingPrerequisites(final int peerCount) {
        List<String> missing = new ArrayList<>();
        expect(
                missing,
                "faf-ice-adapter jar",
                findAdapterBinary(),
                ADAPTER_JAR_ENV,
                "run ./gradlew downloadIceAdapter");
        expect(
                missing,
                "mock-game binary",
                findGameBinary(),
                MOCK_GAME_ENV,
                "run ./gradlew :mock-game:installDist");
        expect(
                missing,
                "faf-uid binary",
                findUidBinary(),
                UID_BINARY_ENV,
                "see documentation/demos/README.md");
        for (PeerSlot slot : SLOTS.subList(0, peerCount)) {
            expect(
                    missing,
                    slot.label() + "'s refresh token",
                    findRefreshToken(slot),
                    slot.tokenEnv(),
                    "bootstrap " + slot.tokenFile() + " for its own seeded account");
        }
        if (!lobbyReachable()) {
            URI url = lobbyUrl();
            missing.add(
                    "lobby "
                            + url
                            + " did not accept a TCP connection on :"
                            + lobbyPort(url)
                            + " within "
                            + LOBBY_PROBE_TIMEOUT
                            + " (the FAF test lobby is public; check outbound network access or "
                            + LOBBY_URL_ENV
                            + ")");
        }
        return missing;
    }

    /**
     * Adds a line to {@code missing} when a prerequisite did not resolve.
     *
     * @param missing the list being built
     * @param what the prerequisite's name
     * @param found the resolved path, or {@code null}
     * @param env the environment variable that overrides its location
     * @param remedy what to do about it
     */
    private static void expect(
            final List<String> missing,
            final String what,
            final Path found,
            final String env,
            final String remedy) {
        if (found == null) {
            missing.add("no " + what + " (set " + env + ", or " + remedy + ")");
        }
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

    /**
     * The credential for one peer, resolved from its own variable or file only.
     *
     * @param slot the peer
     * @return its credential
     */
    private static PeerCredential credentialFor(final PeerSlot slot) {
        return new PeerCredential(
                slot, required(findRefreshToken(slot), slot.label() + "'s refresh token"));
    }

    /**
     * Non-null variant for the session; guaranteed present once the prerequisite gate passed.
     *
     * @param resolved the resolved path, or {@code null}
     * @param what the prerequisite's name, for the failure message
     * @return {@code resolved}
     */
    private static Path required(final Path resolved, final String what) {
        if (resolved == null) {
            throw new IllegalStateException(what + " vanished after the prerequisite gate");
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

    private static Path findRefreshToken(final PeerSlot slot) {
        return resolve(slot.tokenEnv(), slot.tokenFile(), "../" + slot.tokenFile());
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
