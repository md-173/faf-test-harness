package com.faforever.testharness.client.session;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.faforever.testharness.client.config.ConfigLoader;
import com.faforever.testharness.client.config.MockClientConfig;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.function.UnaryOperator;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Faults on one peer of a three-peer session against the live lobby (WBS-5.2.1, WBS-5.1.2): a
 * joiner's game crashing after launch, which the survivors must play on through, and one joiner
 * dropping or delaying, whose damage must stay on its own links.
 *
 * <p>Three accounts, A to C. At three peers both link kinds exist: the host offers to each joiner,
 * and C, the later joiner, offers to B. Crashing B therefore requires both survivors' adapters to
 * report the loss, since only the offering side of a link notices one (adapter 3.3.14's
 * connectivity checker), and leaves A and C a pair whose traffic must keep flowing.
 *
 * <p>The prerequisite plumbing is a third copy of {@code PeerDepartureLiveTest}'s, kept local so
 * that class, which open work still edits, stays untouched; both resolve the same binaries and
 * {@code .secrets} files. Not part of CI: {@code live-integration.yml} runs named classes only.
 *
 * <p>The class {@link Timeout} is a per-method backstop above the crash run's own budgets: the 420
 * s session deadline, the launch wait (the 120 s launch delay plus 60 s), the crash wait (150 s
 * plus 60 s), the 45 s survivor wait, three 30 s teardowns and the 20 s survivor sweep, about 965
 * s. Lower, and a slow but valid run dies on a bare JUnit timeout instead of a named checkpoint.
 */
@Tag("integration")
@Timeout(value = 1020, unit = TimeUnit.SECONDS)
final class SessionFaultLiveTest {

    /** Environment override for the adapter jar. */
    private static final String ADAPTER_JAR_ENV = "FAF_ICE_ADAPTER_JAR";

    /** Environment override for the installed mock-game binary. */
    private static final String MOCK_GAME_ENV = "FAF_MOCK_GAME_BINARY";

    /** Environment override for the {@code faf-uid} binary. */
    private static final String UID_BINARY_ENV = "FAF_UID_BINARY";

    /** Set to {@code true} where a missing prerequisite is a failure, not a skip. */
    private static final String LIVE_REQUIRED_ENV = "FAF_LIVE_REQUIRED";

    /** Environment override for the lobby endpoint. */
    private static final String LOBBY_URL_ENV = "FAF_LOBBY_URL";

    /** The FAF test lobby, Cloudflare-fronted and publicly reachable. */
    private static final String DEFAULT_LOBBY_URL = "wss://ws.faforever.xyz";

    /** How long the lobby probe waits for a TCP connection. */
    private static final Duration LOBBY_PROBE_TIMEOUT = Duration.ofSeconds(3);

    /** Each peer's refresh-token override and default file, host first. */
    private static final List<String[]> TOKENS =
            List.of(
                    new String[] {"FAF_REFRESH_TOKEN_A", ".secrets/refresh_token.txt"},
                    new String[] {"FAF_REFRESH_TOKEN_B", ".secrets/refresh_token_b.txt"},
                    new String[] {"FAF_REFRESH_TOKEN_C", ".secrets/refresh_token_c.txt"});

    /** The degraded peer in the drop and delay runs: C, the last joiner. */
    private static final int DEGRADED = 2;

    /**
     * The passing drop rate. Far inside the traffic check, which wants three datagrams over two
     * progress lines per direction from a stream of ten a second, so a failure at this rate is not
     * credible even unseeded (#358).
     */
    private static final int PASSING_DROP_PERCENT = 50;

    /** The relay delay: half the one-client ceiling in runbook §10 (keep under about 1000 ms). */
    private static final int RELAY_DELAY_MS = 500;

    private static final Logger LOG = LoggerFactory.getLogger(SessionFaultLiveTest.class);

    /** The session under test, kept for the teardown that runs even on a failed checkpoint. */
    private MultiPeerSession session;

    /**
     * Tears the session down and checks nothing survived, for every run including a failed one.
     * Here rather than in the test body, which a failure skips; see {@code PeerDepartureLiveTest}.
     *
     * @throws InterruptedException if the sweep is interrupted
     */
    @AfterEach
    void tearDownAndAssertNoOrphans() throws InterruptedException {
        if (session == null) {
            return;
        }
        boolean interrupted = Thread.interrupted();
        try {
            session.close();
            List<String> survivors = new ArrayList<>();
            for (ProcessHandle survivor : session.survivingSubprocesses()) {
                survivors.add(session.describe(survivor));
            }
            assertEquals(List.of(), survivors, "subprocesses survived teardown");
        } finally {
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }

    @Test
    void theSurvivorsPlayOnThroughAJoinersCrashAfterLaunch() throws Exception {
        requireLiveEnvironment();
        session = MultiPeerSession.withDeliberateCrash(bases(UnaryOperator.identity()), title(), 1);

        run();

        SessionPeer host = session.peers().get(0);
        SessionPeer crashed = session.peers().get(1);
        SessionPeer later = session.peers().get(2);
        assertEquals(
                MultiPeerSession.INJECTED_CRASH_EXIT, crashed.lifecycle().gameExit().getNow(null));
        assertTrue(crashed.lifecycle().verdicts().gameCrashed());
        // Both survivors offered on their link to B, so the run required both loss reports.
        SessionPeer.Offer toCrashed = new SessionPeer.Offer(crashed.identity().id(), true);
        assertTrue(host.offers().contains(toCrashed), host.offers().toString());
        assertTrue(later.offers().contains(toCrashed), later.offers().toString());
    }

    @Test
    void aPeerDroppingHalfItsTrafficStillPasses() throws Exception {
        requireLiveEnvironment();
        session =
                new MultiPeerSession(
                        bases(
                                base ->
                                        MultiPeerSession.withFaults(
                                                base, 0, PASSING_DROP_PERCENT, -1)),
                        title());

        run();

        assertEquals(
                List.of(0, 0, PASSING_DROP_PERCENT),
                session.peers().stream()
                        .map(peer -> peer.config().mockGameUdpDropPercent())
                        .toList());
    }

    @Test
    void aPeerDroppingAllItsTrafficFailsOnItsOwnLinksOnly() {
        requireLiveEnvironment();
        session =
                new MultiPeerSession(
                        bases(base -> MultiPeerSession.withFaults(base, 0, 100, -1)), title());

        CheckpointFailure failure = assertThrows(CheckpointFailure.class, session::run);

        String message = failure.getMessage();
        assertTrue(message.startsWith("A(host),B(joiner): traffic: no two-way"), message);
        assertTrue(message.contains("A(host) from C(joiner): nothing"), message);
        assertTrue(message.contains("B(joiner) from C(joiner): nothing"), message);
        assertFalse(message.contains("from A(host)"), message);
        assertFalse(message.contains("from B(joiner)"), message);
    }

    @Test
    void aPeerWithDelayedIceStillPasses() throws Exception {
        requireLiveEnvironment();
        session =
                new MultiPeerSession(
                        bases(base -> MultiPeerSession.withFaults(base, RELAY_DELAY_MS, 0, -1)),
                        title());

        run();

        assertEquals(
                List.of(0, 0, RELAY_DELAY_MS),
                session.peers().stream().map(peer -> peer.config().iceRelayDelayMs()).toList());
    }

    /**
     * Runs the session, reporting a failed checkpoint as the test's failure.
     *
     * @throws InterruptedException if a wait is interrupted
     */
    private void run() throws InterruptedException {
        try {
            session.run();
        } catch (CheckpointFailure f) {
            throw new AssertionError(f.getMessage(), f);
        }
    }

    /**
     * A title that tells this class's runs apart in a shared log.
     *
     * @return the title, also logged as the run's marker
     */
    private static String title() {
        String title = "faf-test-harness 5.2.1 " + UUID.randomUUID();
        LOG.info("case: session fault run '{}'", title);
        return title;
    }

    /**
     * One base per account, host first, with {@code degrade} applied to peer C only.
     *
     * @param degrade the change to peer C's base
     * @return the bases
     */
    private static List<MockClientConfig> bases(final UnaryOperator<MockClientConfig> degrade) {
        List<MockClientConfig> bases = new ArrayList<>();
        for (int i = 0; i < TOKENS.size(); i++) {
            MockClientConfig base = baseConfig(required(token(i), "peer token " + i));
            bases.add(i == DEGRADED ? degrade.apply(base) : base);
        }
        return bases;
    }

    /**
     * One peer's base config: the shared lobby and binary settings plus that peer's account.
     *
     * @param refreshTokenFile the account's refresh-token file, rewritten in place on rotation
     * @return the validated config
     */
    private static MockClientConfig baseConfig(final Path refreshTokenFile) {
        List<String> args =
                List.of(
                        "--lobby-websocket-url=" + lobbyUrl(),
                        "--oauth-token-url=https://hydra.faforever.xyz/oauth2/token",
                        "--oauth-client-id=95ecec08-29c1-4c48-ae0a-b000ff349cb8",
                        "--oauth-refresh-token-file=" + refreshTokenFile.toAbsolutePath(),
                        // Fallback only: the handshake derives the real unique_id from faf-uid.
                        "--unique-id=00000000-0000-0000-0000-000000000000",
                        "--uid-binary-path=" + required(uidBinary(), "faf-uid").toAbsolutePath(),
                        "--ice-adapter-binary-path="
                                + required(adapterBinary(), "adapter jar").toAbsolutePath(),
                        "--mock-game-binary-path="
                                + required(gameBinary(), "mock-game").toAbsolutePath());
        return ConfigLoader.load(args.toArray(new String[0]), Map.of()).orElseThrow();
    }

    /** Skips, or fails under {@link #LIVE_REQUIRED_ENV}, when a live run cannot happen here. */
    private static void requireLiveEnvironment() {
        List<String> missing = new ArrayList<>();
        expect(missing, "faf-ice-adapter jar", adapterBinary(), ADAPTER_JAR_ENV);
        expect(missing, "mock-game binary", gameBinary(), MOCK_GAME_ENV);
        expect(missing, "faf-uid binary", uidBinary(), UID_BINARY_ENV);
        for (int i = 0; i < TOKENS.size(); i++) {
            expect(missing, "refresh token for peer " + i, token(i), TOKENS.get(i)[0]);
        }
        if (!lobbyReachable()) {
            missing.add("a reachable lobby at " + lobbyUrl());
        }
        if (!missing.isEmpty() && Boolean.parseBoolean(System.getenv(LIVE_REQUIRED_ENV))) {
            fail(LIVE_REQUIRED_ENV + "=true but missing live prerequisites: " + missing);
        }
        assumeTrue(missing.isEmpty(), () -> "missing live prerequisites: " + missing);
    }

    private static void expect(
            final List<String> missing, final String what, final Path found, final String env) {
        if (found == null) {
            missing.add(what + " (set " + env + ")");
        }
    }

    private static URI lobbyUrl() {
        String override = System.getenv(LOBBY_URL_ENV);
        return URI.create(override == null || override.isBlank() ? DEFAULT_LOBBY_URL : override);
    }

    private static boolean lobbyReachable() {
        URI url = lobbyUrl();
        int port = url.getPort() == -1 ? 443 : url.getPort();
        try (Socket socket = new Socket()) {
            socket.connect(
                    new InetSocketAddress(url.getHost(), port),
                    (int) LOBBY_PROBE_TIMEOUT.toMillis());
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    private static Path required(final Path resolved, final String what) {
        if (resolved == null) {
            throw new IllegalStateException(what + " vanished after the prerequisite gate");
        }
        return resolved;
    }

    private static Path token(final int peer) {
        String[] token = TOKENS.get(peer);
        return resolve(token[0], token[1], "../" + token[1]);
    }

    private static Path adapterBinary() {
        return resolve(ADAPTER_JAR_ENV, "faf-ice-adapter.jar", "../faf-ice-adapter.jar");
    }

    private static Path gameBinary() {
        return resolve(
                MOCK_GAME_ENV,
                "mock-game/build/install/mock-game/bin/mock-game",
                "../mock-game/build/install/mock-game/bin/mock-game");
    }

    private static Path uidBinary() {
        return resolve(UID_BINARY_ENV, "faf-uid", "../faf-uid");
    }

    /**
     * First readable candidate, with {@code env} taking precedence; {@code null} when none is.
     * Gradle runs a test with the subproject as its working directory, hence the {@code ../}
     * candidates.
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
