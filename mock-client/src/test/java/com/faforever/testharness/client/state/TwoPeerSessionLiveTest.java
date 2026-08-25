package com.faforever.testharness.client.state;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.fail;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

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
import java.util.concurrent.ExecutionException;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledIf;

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
 * JVMs. Nothing is lost here: the peer link is established during the lobby phase, and game traffic
 * is 4.3.2's card.
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
 * <p><b>Every wait is bounded and named</b>, in the 3.1.2.7 pattern: a missed checkpoint fails with
 * the budget that ran out and what had been seen by then, so a regression names itself. The
 * class-level {@link Timeout} is only a total-runtime backstop.
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
         */
        private final AtomicReference<String> joinRefusal = new AtomicReference<>();

        /** This peer's lobby-assigned identity, from its {@code welcome}. */
        private SessionState identity;

        private Peer(final String name, final MockClientConfig config) {
            this.name = name;
            this.config = config;
            LobbyConnection lobby = new LobbyConnection(config.lobbyWebSocketUrl());
            lobby.registerHandler("game_join_failed", frame -> joinRefusal.set(frame.toString()));
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
        assumeTrue(
                lobbyReachable(),
                "lobby "
                        + lobbyUrl()
                        + " unreachable from this network (TCP timeout on :443). Self-skips "
                        + "off-net; runs on a FAF-allowlisted host/VPN.");

        // Unique per run, so a stale game from an earlier run is never what this one observes —
        // though it is the uid, not the title, that B actually targets.
        Peer host = new Peer("A(host)", hostConfig("faf-test-harness 4.3.1 " + UUID.randomUUID()));
        Peer joiner = null;
        try {
            joiner = runSession(host);
        } finally {
            // Always runs, so a failed checkpoint still leaves no adapter and no game behind.
            // Joiner first: it is the side that may not exist yet.
            shutdown(joiner);
            shutdown(host);
        }

        assertNoSurvivingSubprocesses(host.config);
    }

    /**
     * The ordered checkpoints, from A's welcome to both adapters reporting the link. Split out of
     * the test method so the shutdown above wraps every one of them.
     *
     * @param host the hosting peer, not yet started
     * @return the joining peer, once it exists, so the caller can shut it down too
     * @throws InterruptedException if any bounded wait is interrupted
     */
    private Peer runSession(final Peer host) throws InterruptedException {
        // A hosts. Reaching IDLE sends game_host; the server answers game_launch, which is what
        // spawns A's adapter and game and completes gameLaunched with the uid.
        host.identity = await(host.lifecycle.start(tokensFor(host)), SESSION_TIMEOUT, "A: welcome");
        GameConfig hosted =
                await(host.lifecycle.gameLaunched(), GAME_LAUNCH_TIMEOUT, "A: game_launch");
        await(host.lifecycle.stateReached(ClientState.HOSTING), ROLE_TIMEOUT, "A: HOSTING");

        // Only now is the game joinable: the server marks it hosted when A's game reports Lobby,
        // which reaches the server only because R72 forwards it. B is started with A's uid as its
        // join target — the one value that crosses between the two clients in-process.
        Peer joiner = new Peer("B(joiner)", joinConfig(hosted.uid()));
        joiner.identity =
                await(joiner.lifecycle.start(tokensFor(joiner)), SESSION_TIMEOUT, "B: welcome");
        await(
                joiner.lifecycle.gameLaunched(),
                GAME_LAUNCH_TIMEOUT,
                "B: game_launch" + joiner.refusalHint());
        await(joiner.lifecycle.stateReached(ClientState.JOINING), ROLE_TIMEOUT, "B: JOINING");

        // The card's definitive signal, on both sides, for the ids the lobby assigned.
        awaitPeerConnected(host, joiner);
        awaitPeerConnected(joiner, host);
        return joiner;
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
     * The pgrep-clean checkpoint: once both sessions have shut down, neither binary may still be
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
        try {
            return future.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            return fail("timed out after " + timeout + " waiting for: " + what);
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
        List<String> args = new ArrayList<>(commonArgs(tokenFileA()));
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
        List<String> args = new ArrayList<>(commonArgs(tokenFileB()));
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
    private static List<String> commonArgs(final Path refreshTokenFile) {
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
                // The reason this test can exist at all; see the class javadoc.
                "--mock-game-launch-delay-seconds=-1");
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
