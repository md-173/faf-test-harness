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
import com.faforever.testharness.client.process.IceAdapterLauncher;
import com.faforever.testharness.client.process.MockGameLauncher;
import com.faforever.testharness.client.session.CheckpointFailure;
import com.faforever.testharness.client.session.MultiPeerSession;
import com.faforever.testharness.client.session.SessionPeer;
import com.faforever.testharness.shared.logging.LoggingSetup;
import java.io.IOException;
import java.net.InetSocketAddress;
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
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.LoggerFactory;

/**
 * The two-peer (WBS-4.3.1) and three/four-peer (WBS-4.3.3) milestones: two to four Mock Clients on
 * one host, each with its own lobby account, its own port set, and its own real {@code
 * faf-ice-adapter} and mock-game, complete a host/join through the <em>live</em> lobby, and every
 * adapter reports a link to every other peer: one connection at two players, three at three, six at
 * four.
 *
 * <p><b>The session itself</b> is {@link MultiPeerSession} in {@code main} (WBS-4.2.1), the same
 * orchestration the {@code session} command runs: host, joiners one at a time, the full mesh on the
 * adapter's {@code onConnected} verdict, and teardown. Its javadoc covers why auto-launch is off,
 * why the host uses {@code friends} visibility, and the in-process shape. This test adds the checks
 * a consumer's verdict leaves out, run on the finished session.
 *
 * <p><b>What three players add (WBS-4.3.3).</b> faf-server's {@code connect_to_host} gives the host
 * {@code offer=true} for each joiner, and {@code connect_to_peer} gives a joiner {@code offer=true}
 * for every earlier joiner and each earlier joiner {@code offer=false} for it. Two peers only ever
 * use the host path; the peer-to-peer path and its offer asymmetry start at three, and {@link
 * #assertOfferDirections} checks both. Each peer runs under an instance label, A to D, and {@link
 * #assertInstanceLabels} checks that its client lines, adapter output and game output carry it and
 * that the mesh is visible from those lines alone.
 *
 * <p><b>Why the empty ICE server list is enough.</b> All peers are on one machine, so they connect
 * over host candidates and never need STUN or TURN. The session's only network dependence is that
 * the lobby is reachable, which this test probes and self-skips on.
 *
 * <p><b>Game traffic (WBS-4.3.2)</b> is part of the session's verdict: {@link MultiPeerSession}
 * fails with the {@code traffic} stage unless every game has received every other game's datagrams.
 * Each mock game binds its lobby port on {@code CreateLobby} and starts sending to a peer as soon
 * as the adapter names one, so datagrams cross the finished ICE path during the lobby phase, as the
 * real game's autolobby does, without the launch this session never performs.
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
 * <p><b>Every wait is bounded and named.</b> The session's checkpoints fail with {@link
 * CheckpointFailure}, reported here with any captured bind failure appended.
 *
 * <p><b>Duration.</b> A case typically takes 20 to 40 s and all three under two minutes, most of it
 * logins and JVM starts. {@link Timeout} applies to each case, not the class: 600 s, above the 420
 * s session deadline plus the at most 140 s of teardown, so it only fires if teardown itself hangs.
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

    /** Set to {@code true} where a missing prerequisite is a failure, not a skip (WBS-2.3.3.1). */
    private static final String LIVE_REQUIRED_ENV = "FAF_LIVE_REQUIRED";

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
     * The mock game's progress line (WBS-4.3.2), as captured from its stdout. Read here only by the
     * attribution checkpoint, which checks a game's line carries that game's label; the traffic
     * verdict itself is {@code TrafficEvidence} in {@code main}.
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

    /** Case markers, so a JSONL file several cases appended to can be split by case. */
    private static final org.slf4j.Logger LOG =
            LoggerFactory.getLogger(MultiPeerSessionLiveTest.class);

    /**
     * The current case's session, once built. A field rather than a local, so the case's teardown
     * reaches every peer even when a later checkpoint fails.
     */
    private MultiPeerSession session;

    /** Root logger the mock-game capture appender is attached to. */
    private Logger root;

    /**
     * Captures every log record in this JVM, which includes every adapter's and mock game's output
     * as re-emitted by {@code ProcessOutputLogger}. Backed by a copy-on-write list: subprocess
     * reader threads, client threads and the test thread touch it at once.
     */
    private ListAppender<ILoggingEvent> captured;

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
     * A peer's account credential, which is all that differs between peers' lobby login settings.
     * Here it is always a refresh-token file exchanged at Hydra, which is what the local accounts
     * hold. {@code session} also accepts one pre-signed access-token file per peer, which this test
     * does not need.
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

    @ParameterizedTest
    @ValueSource(ints = {1, 2, 3})
    void multiplePeersEstablishTheirLinkThroughTheLiveLobby(int joinerAmount) throws Exception {
        // The one prerequisite gate. A machine without the live environment skips here; under
        // FAF_LIVE_REQUIRED (WBS-2.3.3.1) the same list fails the case instead, so a job that
        // means to run this cannot go green having run nothing. No CI job runs this test today:
        // every peer needs its own refresh token, which rotates on use, so a CI job runs the
        // session command on access-token files instead (the #364 follow-up).
        List<String> missing = missingPrerequisites(joinerAmount + 1);
        if (!missing.isEmpty()) {
            if (Boolean.parseBoolean(System.getenv(LIVE_REQUIRED_ENV))) {
                fail(LIVE_REQUIRED_ENV + "=true but missing live prerequisites: " + missing);
            }
            System.out.println("[4.3.1] skipping multi-peer session test: " + missing);
        }
        assumeTrue(missing.isEmpty(), () -> "missing live prerequisites: " + missing);

        String runId = UUID.randomUUID().toString();
        LOG.info("case: {} peers, run {}", joinerAmount + 1, runId);
        session = null;
        Throwable failure = null;
        try {
            runSession(joinerAmount + 1, runId);
            // Checks on what the session produced, which the session command leaves out.
            assertOfferDirections(session.peers());
            assertInstanceLabels(session.peers());
        } catch (Throwable t) {
            failure = t;
            throw t;
        } finally {
            // A @Timeout interrupt would otherwise cut every bounded teardown wait below short.
            // Clear it for the teardown and the sweep, and restore it afterwards.
            boolean interrupted = Thread.interrupted();
            try {
                List<String> survivors = new ArrayList<>();
                if (session != null) {
                    session.close();
                    for (ProcessHandle survivor : session.survivingSubprocesses()) {
                        survivors.add(session.describe(survivor));
                    }
                }
                // Checked on every path. The sweep sees every descendant of this JVM and the cases
                // run back to back in it, so a failed case that skipped it would hand its survivors
                // to the next case. After a checkpoint failure it is attached rather than thrown,
                // so the checkpoint stays the reported cause.
                if (!survivors.isEmpty()) {
                    AssertionError leak =
                            new AssertionError("subprocesses survived teardown: " + survivors);
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
     * Runs one session through {@link MultiPeerSession}, full mesh and game traffic included. A
     * checkpoint failure is re-reported with any captured bind failure appended, since a subprocess
     * that could not bind a port explains a silent path.
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
        List<MockClientConfig> bases = new ArrayList<>();
        for (PeerSlot slot : SLOTS.subList(0, peerCount)) {
            bases.add(baseConfig(credentialFor(slot)));
        }
        // Unique per case, so a stale game from an earlier run is never what this one observes,
        // though it is the uid, not the title, that the joiners actually target.
        session = new MultiPeerSession(bases, "faf-test-harness 4.3.1 " + runId);

        try {
            session.run();
        } catch (CheckpointFailure f) {
            throw new AssertionError(f.getMessage() + bindFailureHint(), f);
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
     * @param peers every peer, host first and joiners in join order
     */
    private static void assertOfferDirections(final List<SessionPeer> peers) {
        SessionPeer host = peers.get(0);
        List<SessionPeer> joiners = peers.subList(1, peers.size());
        Set<SessionPeer.Offer> hostExpected = new HashSet<>();
        for (SessionPeer joiner : joiners) {
            hostExpected.add(new SessionPeer.Offer(joiner.identity().id(), true));
        }
        assertEquals(hostExpected, host.offers(), host.name() + ": ConnectToPeer offers");
        for (int k = 0; k < joiners.size(); k++) {
            Set<SessionPeer.Offer> expected = new HashSet<>();
            for (int i = 0; i < joiners.size(); i++) {
                if (i != k) {
                    expected.add(new SessionPeer.Offer(joiners.get(i).identity().id(), i < k));
                }
            }
            SessionPeer joiner = joiners.get(k);
            assertEquals(expected, joiner.offers(), joiner.name() + ": ConnectToPeer offers");
        }
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
     * @param peers every peer in the session
     */
    private void assertInstanceLabels(final List<SessionPeer> peers) {
        Map<String, SessionPeer> byLabel = new HashMap<>();
        Map<SessionPeer, List<String>> wanted = new HashMap<>();
        Map<SessionPeer, Set<Long>> loggedLinks = new HashMap<>();
        for (SessionPeer peer : peers) {
            byLabel.put(peer.label(), peer);
            List<String> kinds = new ArrayList<>(LABELLED_CLIENT_LINES);
            kinds.add("component " + IceAdapterLauncher.COMPONENT_TAG);
            kinds.add("component " + MockGameLauncher.COMPONENT_TAG);
            wanted.put(peer, kinds);
            loggedLinks.put(peer, new HashSet<>());
        }

        List<String> problems = new ArrayList<>();
        List<String> unlabelledOther = new ArrayList<>();
        for (ILoggingEvent event : captured.list) {
            String instance = event.getMDCPropertyMap().get(LoggingSetup.INSTANCE_MDC_KEY);
            if (LOG.getName().equals(event.getLoggerName())
                    || (instance == null
                            && MultiPeerSession.class.getName().equals(event.getLoggerName()))) {
                // This test's case marker and the session's start marker belong to no instance.
                // The component can still be set on this thread by an earlier integration test's
                // CLI run, so they are skipped by logger rather than by an empty label.
                continue;
            }
            String component = event.getMDCPropertyMap().get(LoggingSetup.COMPONENT_MDC_KEY);
            String message = event.getFormattedMessage();
            SessionPeer peer = instance == null ? null : byLabel.get(instance);
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

            long ownId = peer.identity().id();
            Matcher link = PEER_CONNECTED_LINE.matcher(message);
            if (link.find()) {
                if (Long.parseLong(link.group(1)) != ownId) {
                    problems.add(peer.name() + " carries another peer's verdict: " + message);
                } else if (Boolean.parseBoolean(link.group(3))) {
                    loggedLinks.get(peer).add(Long.parseLong(link.group(2)));
                }
            }
            Matcher ready = SESSION_READY_LINE.matcher(message);
            if (ready.find() && Long.parseLong(ready.group(1)) != ownId) {
                problems.add(peer.name() + " carries another peer's session: " + message);
            }
            Matcher progress = PROGRESS_LINE.matcher(message);
            if (progress.find() && Long.parseLong(progress.group(1)) != ownId) {
                problems.add(peer.name() + " carries another game's traffic: " + message);
            }
        }

        for (SessionPeer peer : peers) {
            if (!wanted.get(peer).isEmpty()) {
                problems.add(
                        peer.name()
                                + " has no line labelled "
                                + peer.label()
                                + " for "
                                + wanted.get(peer));
            }
            for (SessionPeer other : peers) {
                if (other != peer
                        && !loggedLinks.get(peer).contains((long) other.identity().id())) {
                    problems.add(
                            peer.name()
                                    + " logged no 'peer connected ... connected=true' for "
                                    + other.name());
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
        for (SessionPeer peer : session.peers()) {
            ports.append(' ').append(peer.name()).append(' ').append(portsOf(peer));
        }
        return " (a subprocess could not bind a port: " + failures + "; ports:" + ports + ")";
    }

    /**
     * One peer's adapter ports, named by protocol, for the bind-failure hint.
     *
     * @param peer the peer
     * @return its RPC, GPGNet and lobby ports
     */
    private static List<String> portsOf(final SessionPeer peer) {
        MockClientConfig c = peer.config();
        return List.of(
                "tcp " + c.iceAdapterRpcPort(),
                "tcp " + c.iceAdapterGpgNetPort(),
                "udp " + c.iceAdapterLobbyPort());
    }

    /**
     * One peer's base config: the settings every peer shares plus its own credential. The session
     * sets the ports, the launch delay and the host or join intent itself.
     *
     * @param credential this peer's account
     * @return the validated config
     */
    private static MockClientConfig baseConfig(final PeerCredential credential) {
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
                        "--mock-game-binary-path=" + requireGameBinary().toAbsolutePath()));
        return ConfigLoader.load(args.toArray(new String[0]), Map.of()).orElseThrow();
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
