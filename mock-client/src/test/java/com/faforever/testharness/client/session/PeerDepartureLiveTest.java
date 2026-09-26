package com.faforever.testharness.client.session;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.fail;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.faforever.testharness.client.config.ConfigLoader;
import com.faforever.testharness.client.config.MockClientConfig;
import com.faforever.testharness.client.state.ClientState;
import com.faforever.testharness.shared.logging.LoggingSetup;
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
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

/**
 * Mid-session peer departure against the live lobby (WBS-4.3.4): a two-peer session is brought up,
 * one peer leaves, and the survivor is checked for the right reaction.
 *
 * <p>The survivor learns of a departure two different ways, and which one depends entirely on
 * whether the host has launched, so there is a run for each.
 *
 * <ul>
 *   <li><b>Lobby phase.</b> faf-server tells every remaining player: {@code GameConnection.abort}
 *       runs {@code disconnect_all_peers()} under a {@code GameState.LOBBY} guard, the client
 *       relays that to its adapter as {@code disconnectFromPeer}, and the frame the adapter
 *       forwards ends the survivor's own game. Provoked with {@code kill -9} on the joiner's game,
 *       which also proves the client's crash-side {@code GameState Ended} fallback reaches the
 *       server at all.
 *   <li><b>After launch.</b> That guard closes and no targeted notice is sent. The survivor finds
 *       out from its own adapter, whose connectivity checker declares a silent peer lost after ten
 *       seconds and pushes {@code onConnected(local, remote, false)}. Nothing is built for this
 *       path; it is asserted.
 * </ul>
 *
 * <p><b>Why this lives beside {@link MultiPeerSession} rather than in {@code client.state}.</b> A
 * departure run has to stop one peer mid-session and read that peer's lifecycle, which the session
 * keeps package-private. Running from this package uses the controls that already exist instead of
 * widening the session's public API for a test.
 *
 * <p>The prerequisite and config plumbing below is a second, smaller copy of {@code
 * MultiPeerSessionLiveTest}'s: that test sits in {@code client.state} and keeps its own private, so
 * the two cannot share. Both resolve the same binaries and the same {@code .secrets} token files;
 * change one and check the other.
 *
 * <p><b>Every wait is bounded and named</b>: a missed checkpoint fails with the budget that ran out
 * and what had been seen by then. The class-level {@link Timeout} is a per-method backstop, and it
 * sits above the sum of each run's own budgets on purpose: the post-launch run can spend the
 * session deadline (420 s) plus its launch wait, server-state gate, teardown and peer-loss budgets,
 * about 705 s in all. Set it any lower and a slow but valid run dies on a bare JUnit timeout
 * instead of the named checkpoint this class promises.
 */
@Tag("integration")
@Timeout(value = 780, unit = TimeUnit.SECONDS)
final class PeerDepartureLiveTest {

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

    /** Set to {@code true} where a missing prerequisite is a failure, not a skip (WBS-2.3.3.1). */
    private static final String LIVE_REQUIRED_ENV = "FAF_LIVE_REQUIRED";

    /** Environment override for the lobby endpoint. */
    private static final String LOBBY_URL_ENV = "FAF_LOBBY_URL";

    /** Environment override for the post-launch run's host launch delay, in seconds. */
    private static final String LAUNCH_DELAY_ENV = "FAF_HOST_LAUNCH_DELAY_SECONDS";

    /**
     * Lobby endpoint used when {@link #LOBBY_URL_ENV} is unset: the FAF test lobby. It is fronted
     * by Cloudflare and publicly reachable, so no VPN or allowlist is involved; the older {@code
     * lobby.faforever.xyz} host no longer answers.
     */
    private static final String DEFAULT_LOBBY_URL = "wss://ws.faforever.xyz";

    /** How long the lobby probe waits for a TCP connection before calling the lobby unreachable. */
    private static final Duration LOBBY_PROBE_TIMEOUT = Duration.ofSeconds(3);

    /** Poll slice for every bounded wait built on a repeated probe. */
    private static final Duration POLL_SLICE = Duration.ofMillis(250);

    /** Peers in a departure session: one host and one joiner. */
    private static final int SESSION_PEERS = 2;

    /**
     * The class-level {@link Timeout}, repeated as a value the delay bound below can read. Keep the
     * two in step.
     */
    private static final Duration CLASS_TIMEOUT = Duration.ofSeconds(780);

    /**
     * Headroom added to the host's launch delay when waiting for it to launch, covering the
     * bring-up already spent before the timer started. Named separately from {@link
     * #DEPARTURE_TIMEOUT} so tightening one does not silently move the other.
     */
    private static final Duration LAUNCH_SLACK = Duration.ofSeconds(60);

    /**
     * Budget for the surviving side to observe a lobby-phase departure end to end: the server's
     * {@code DisconnectFromPeer}, the adapter RPC it produces, and the survivor's own game exiting
     * on the frame the adapter forwards.
     */
    private static final Duration DEPARTURE_TIMEOUT = Duration.ofSeconds(60);

    /**
     * Budget for the adapter's connectivity checker to declare a departed peer lost. Upstream
     * echoes every 1 s and declares loss after 10 s of silence ({@code
     * PeerConnectivityCheckerModule}), so this is that threshold with room for the departing side's
     * own teardown to finish first.
     */
    private static final Duration PEER_LOST_TIMEOUT = Duration.ofSeconds(45);

    /** Budget for one peer's teardown to finish once its shutdown has been requested. */
    private static final Duration TEARDOWN_TIMEOUT = Duration.ofSeconds(30);

    /**
     * Budget for the server to report the launched game as no longer joinable. One lobby round trip
     * after the host's own adapter relayed the frame, so this is latency headroom rather than a
     * wait on anything slow.
     */
    private static final Duration SERVER_LIVE_TIMEOUT = Duration.ofSeconds(30);

    /**
     * Seconds the post-launch run's host sits in the lobby before launching.
     *
     * <p>This is the one number that run times itself against, and it is a trade. The host's timer
     * starts when its own game enters HOSTING, and the joiner's whole bring-up has to finish inside
     * it, because faf-server accepts a {@code game_join} only while the game is in {@code
     * GameState.LOBBY}. Too long and the run idles; too short and the join is refused, which fails
     * loudly with the server's own {@code game_not_ready} rather than as a confusing timeout.
     *
     * <p>Overridable because a slow or distant network is exactly where the default stops being
     * generous. {@link MultiPeerSession#MIN_HOST_LAUNCH_DELAY} is the floor the session enforces.
     */
    private static final int HOST_LAUNCH_DELAY_SECONDS = hostLaunchDelaySeconds();

    /** Logger for the per-run marker. */
    private static final org.slf4j.Logger LOG =
            LoggerFactory.getLogger(PeerDepartureLiveTest.class);

    /** The session under test, kept for the teardown that must run even on a failed checkpoint. */
    private MultiPeerSession session;

    /** Root logger the capture appender is attached to. */
    private Logger root;

    /**
     * Captures every log record in this JVM, which includes both adapters' and both mock games'
     * output as re-emitted by {@code ProcessOutputLogger}. Copy-on-write: subprocess reader
     * threads, client threads and the test thread touch it at once.
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
                        // first. Read it here, on the logging thread, so a label-sensitive check
                        // sees the label that thread carried rather than the test thread's.
                        event.prepareForDeferredProcessing();
                        super.append(event);
                    }
                };
        captured.list = new CopyOnWriteArrayList<>();
        captured.setContext(context);
        captured.start();
        root.addAppender(captured);
    }

    /**
     * Detaches the capture, tears the session down, and makes the pgrep-clean check, for every run
     * including a failed one.
     *
     * <p>The sweep lives here rather than at the end of each test because a run that failed a
     * checkpoint is the one most likely to have leaked an adapter or a game, and a check written as
     * the last line of a test body is the line a failure skips. It is not in a {@code finally}
     * either: an exception thrown from {@code finally} replaces the original, so a leak would mask
     * the failure that caused it, whereas JUnit reports the test's own failure and attaches this
     * one as suppressed.
     *
     * @throws InterruptedException if the sweep is interrupted
     */
    @AfterEach
    void tearDownAndAssertNoOrphans() throws InterruptedException {
        if (captured != null) {
            captured.stop();
            root.detachAppender(captured);
        }
        if (session == null) {
            return;
        }
        // A @Timeout interrupt would otherwise cut every bounded teardown wait short.
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
    void lobbyPhaseDepartureTearsTheSurvivorDownCleanly() throws Exception {
        requireLiveEnvironment();
        List<SessionPeer> peers = runSession(MultiPeerSession.LAUNCH_DISABLED);
        SessionPeer host = peers.get(0);
        SessionPeer joiner = peers.get(1);

        int mark = logMark();
        // kill -9 on the joiner's game, leaving its client alive to notice. The client sends
        // GameState Ended for a game it never saw end cleanly, faf-server routes that to
        // on_connection_closed() then abort(), and abort() runs disconnect_all_peers() because the
        // game is still in GameState.LOBBY. Teardown sends it before it closes the lobby (#454),
        // and that close is a second path to the same frame should the send fail: the socket
        // closing reaches on_connection_lost() and so the same abort().
        killGameOf(joiner);

        // The frame arrived and was relayed. This is the client half of the card.
        awaitLogLine(
                mark,
                host.label(),
                "peer disconnect: id=" + joiner.identity().id(),
                DEPARTURE_TIMEOUT,
                "the host never relayed the departure to its adapter"
                        + adapterDisconnectHint(mark, host.label()));

        // And the adapter acted on it. The RPC destroys the peer relay and makes the adapter emit a
        // GPGNet DisconnectFromPeer to the host's own game, which is the only way that game can
        // reach ENDED here. Asserting the game's own exit line rather than the adapter's keeps this
        // pinned to our contract instead of upstream's wording.
        //
        // The line carries no player id, and both games' stdout funnels into this one JVM's root
        // logger, so it is scoped to the host's instance label rather than left to be attributed
        // by argument. That is what the capture's prepareForDeferredProcessing override is for.
        awaitLogLine(
                mark,
                host.label(),
                "mock game finished: status=OK, exit code 0",
                DEPARTURE_TIMEOUT,
                "the host's game did not end cleanly on the adapter's DisconnectFromPeer"
                        + adapterDisconnectHint(mark, host.label()));

        awaitState(host, ClientState.TERMINATED, DEPARTURE_TIMEOUT);
    }

    @Test
    void postLaunchDepartureIsObservedFromTheAdapterAlone() throws Exception {
        requireLiveEnvironment();
        List<SessionPeer> peers = runSession(HOST_LAUNCH_DELAY_SECONDS);
        SessionPeer host = peers.get(0);
        SessionPeer joiner = peers.get(1);

        // Reaching PLAYING means the host's own adapter relayed GameState Launching, which is not
        // the same event as faf-server processing that frame: the forward to the lobby is fire and
        // forget. Waiting only for PLAYING would leave a window in which the server's game is still
        // LOBBY, and a departure inside it would produce exactly the DisconnectFromPeer this run
        // asserts the absence of. So the server's own view is what gates the departure.
        awaitState(host, ClientState.PLAYING, launchTimeout());
        int hostedUid = host.lifecycle().gameLaunched().getNow(null).uid();
        awaitServerGameState(host, hostedUid, "playing");

        // Everything from here is the departure. Marked so the assertions below read only the tail:
        // the adapter emits onConnected(..., false) from several points during ICE negotiation, so
        // a scan of the whole run would find a bring-up line and pass without the departure having
        // produced anything at all.
        int mark = logMark();
        shutdown(joiner);

        awaitLogLine(
                mark,
                host.label(),
                "peer connected: local="
                        + host.identity().id()
                        + " remote="
                        + joiner.identity().id()
                        + " connected=false",
                PEER_LOST_TIMEOUT,
                "the host's adapter never reported the departed peer unreachable");

        // The card's central claim, and the reason the gate above is on the server's state rather
        // than on PLAYING: after launch faf-server sends no targeted departure notice at all,
        // because abort() guards disconnect_all_peers() on GameState.LOBBY. Both log variants are
        // checked, because the host is in PLAYING for this whole window and a relayed frame would
        // be logged there as "ignored" rather than as the plain relay line. Asserting only the
        // relay line would pass no matter what the server sent.
        assertNoLogLine(
                mark,
                host.label(),
                "peer disconnect: id=" + joiner.identity().id(),
                "the server must send no DisconnectFromPeer once the game has launched");
        assertNoLogLine(
                mark,
                host.label(),
                "peer disconnect ignored during a live match: id=" + joiner.identity().id(),
                "the server must send no DisconnectFromPeer once the game has launched");
    }

    /**
     * Brings a two-peer session up to a full mesh with proven traffic.
     *
     * @param hostLaunchDelaySeconds the host's auto-launch policy, passed to the session
     * @return the session's peers, host first
     * @throws InterruptedException if a bounded wait is interrupted
     */
    private List<SessionPeer> runSession(final int hostLaunchDelaySeconds)
            throws InterruptedException {
        List<MockClientConfig> bases = List.of(baseConfig(tokenFileA()), baseConfig(tokenFileB()));
        String runId = UUID.randomUUID().toString();
        // One marker per run, so the two runs' lines can be told apart in a shared JSONL.
        LOG.info(
                "case: departure run {}, host launch delay {}",
                runId,
                hostLaunchDelaySeconds == MultiPeerSession.LAUNCH_DISABLED
                        ? "disabled"
                        : hostLaunchDelaySeconds + "s");
        session =
                new MultiPeerSession(
                        bases, "faf-test-harness 4.3.4 " + runId, hostLaunchDelaySeconds);
        try {
            session.run();
        } catch (CheckpointFailure f) {
            throw new AssertionError(f.getMessage(), f);
        }
        return session.peers();
    }

    /**
     * Shuts one peer down and waits for its teardown, the clean departure the post-launch run
     * needs.
     *
     * @param peer the peer to remove from the session
     */
    private static void shutdown(final SessionPeer peer) {
        // Under the peer's own label, as MultiPeerSession.shutdown does, so the teardown lines of a
        // two-peer run stay attributable in one shared JSONL.
        try (MDC.MDCCloseable ignored = peer.labelled()) {
            peer.lifecycle().shutdown();
            awaitState(peer, ClientState.TERMINATED, TEARDOWN_TIMEOUT);
            // Once-guarded, so a no-op on the ordinary path and the real thing on every other.
            peer.teardown().run();
        }
    }

    /**
     * SIGKILL one peer's mock game, leaving its client running to notice the death.
     *
     * <p>Located among this JVM's descendants, because the session owns its subprocesses and hands
     * out none. The discriminator is the peer's GPGNet port, not its player id: Gradle runs every
     * {@code integrationTest} class in one JVM, {@code MultiPeerSessionLiveTest} uses these same
     * two accounts, and a game leaked by an earlier case would carry the same {@code --player-id}.
     * The port is freshly allocated per peer per session, so it cannot collide with a stale
     * process. Matched with its trailing separator, since {@code MockGameLauncher} always emits
     * another flag after it and an unterminated number would match a longer one starting with the
     * same digits.
     *
     * <p>Exactly one match is required. More than one means a stale game is still running and the
     * run should say so rather than kill whichever the stream happened to yield first.
     *
     * @param peer the peer whose game to kill
     */
    private static void killGameOf(final SessionPeer peer) {
        String gameNeedle = peer.config().mockGameBinaryPath().getFileName().toString();
        String portNeedle = "--gpgnet-port " + peer.config().iceAdapterGpgNetPort() + " ";
        List<ProcessHandle> matches =
                ProcessHandle.current()
                        .descendants()
                        .filter(
                                handle ->
                                        handle.info()
                                                .commandLine()
                                                .filter(line -> line.contains(gameNeedle))
                                                .filter(line -> line.contains(portNeedle))
                                                .isPresent())
                        .toList();
        if (matches.size() != 1) {
            fail(
                    "expected exactly one mock game for "
                            + peer.name()
                            + " on "
                            + portNeedle.trim()
                            + ", found "
                            + matches.size()
                            + "; descendants: "
                            + descendantCommandLines());
        }
        ProcessHandle game = matches.get(0);
        game.destroyForcibly();
        try {
            game.onExit().get(TEARDOWN_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            fail("interrupted waiting for " + peer.name() + "'s killed game to die");
        } catch (Exception e) {
            fail("killed " + peer.name() + "'s mock game but it did not die: " + e);
        }
    }

    /**
     * Bounded wait for one peer's client to reach {@code state}.
     *
     * @param peer the peer to watch
     * @param state the state it must reach
     * @param timeout the budget
     */
    private static void awaitState(
            final SessionPeer peer, final ClientState state, final Duration timeout) {
        try {
            peer.lifecycle().stateReached(state).get(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            fail("interrupted waiting for " + peer.name() + " to reach " + state);
        } catch (Exception e) {
            fail(peer.name() + " did not reach " + state + " within " + timeout + ": " + e);
        }
    }

    /**
     * Wait until the server reports {@code uid} in {@code expected}, as seen in {@code game_info}
     * on that peer's own lobby connection.
     *
     * @param peer the peer whose lobby connection observes the frames
     * @param uid the game to watch
     * @param expected the client-facing state name to wait for
     * @throws InterruptedException if the wait is interrupted
     */
    private static void awaitServerGameState(
            final SessionPeer peer, final int uid, final String expected)
            throws InterruptedException {
        long deadline = System.nanoTime() + SERVER_LIVE_TIMEOUT.toNanos();
        do {
            if (peer.serverGameState(uid).filter(expected::equals).isPresent()) {
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
                        + peer.serverGameState(uid).orElse("nothing"));
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
            final int mark,
            final String label,
            final String needle,
            final Duration timeout,
            final String what)
            throws InterruptedException {
        long deadline = System.nanoTime() + timeout.toNanos();
        do {
            if (!linesSince(mark, label, needle).isEmpty()) {
                return;
            }
            Thread.sleep(POLL_SLICE.toMillis());
        } while (System.nanoTime() < deadline);
        fail(what + ": no log line containing '" + needle + "' within " + timeout);
    }

    /**
     * Assert that nothing logged since {@code mark} contains {@code needle}.
     *
     * <p>What stops this being vacuous is the assertion that runs before it: the departure's
     * positive signal and both of these lines are emitted at INFO, so a positive match proves INFO
     * capture was live across the same window these are claimed absent from. Move any of them to
     * DEBUG and this quietly stops proving anything.
     *
     * @param mark the index returned by {@link #logMark()} before the event under test
     * @param needle the text no line may contain
     * @param why what its presence would mean, used verbatim in the failure message
     */
    private void assertNoLogLine(
            final int mark, final String label, final String needle, final String why) {
        List<String> found = linesSince(mark, label, needle);
        if (!found.isEmpty()) {
            fail(why + "; found: " + found);
        }
    }

    /**
     * Captured messages logged at or after {@code mark} that contain {@code needle}.
     *
     * @param mark the index to start reading from
     * @param needle the text to match
     * @return the matching messages, oldest first
     */
    private List<String> linesSince(final int mark, final String label, final String needle) {
        List<ILoggingEvent> snapshot = new ArrayList<>(captured.list);
        List<String> found = new ArrayList<>();
        for (int i = Math.min(mark, snapshot.size()); i < snapshot.size(); i++) {
            ILoggingEvent event = snapshot.get(i);
            if (label != null
                    && !label.equals(
                            event.getMDCPropertyMap().get(LoggingSetup.INSTANCE_MDC_KEY))) {
                continue;
            }
            String message = event.getFormattedMessage();
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
     * @return the matching adapter lines in parentheses, or a note that there were none
     */
    private String adapterDisconnectHint(final int mark, final String label) {
        List<String> seen = linesSince(mark, label, "onDisconnectFromPeer");
        return seen.isEmpty()
                ? " (the host's adapter logged no onDisconnectFromPeer at all)"
                : " (the host's adapter logged: " + seen + ")";
    }

    /** Command lines of every process descended from this JVM, skipping any we cannot read. */
    private static List<String> descendantCommandLines() {
        List<String> lines = new ArrayList<>();
        ProcessHandle.current()
                .descendants()
                .forEach(handle -> handle.info().commandLine().ifPresent(lines::add));
        return lines;
    }

    /**
     * The budget for the host to launch, derived from the delay it was given so the two cannot
     * drift apart.
     *
     * @return the launch budget
     */
    private static Duration launchTimeout() {
        return Duration.ofSeconds(HOST_LAUNCH_DELAY_SECONDS).plus(LAUNCH_SLACK);
    }

    /**
     * The post-launch run's host launch delay: {@link #LAUNCH_DELAY_ENV} when it parses to a usable
     * number of seconds, two minutes otherwise.
     *
     * <p>A value the session would reject, or one so small that the host's match ends before the
     * adapter's ten-second detector can report the departure, is ignored rather than honoured: it
     * would otherwise fail much later at a checkpoint that blames the adapter. The match length is
     * derived as twice this delay (mock-game's {@code Main.matchDuration}), and the observation has
     * to finish inside it.
     *
     * @return the delay in seconds
     */
    private static int hostLaunchDelaySeconds() {
        // Both bounds first, so the fallback itself cannot sit outside them if a constant moves.
        long floor =
                Math.max(
                        MultiPeerSession.minHostLaunchDelaySeconds(SESSION_PEERS),
                        PEER_LOST_TIMEOUT.plus(TEARDOWN_TIMEOUT).toSeconds() / 2 + 1);
        // Everything the post-launch run can spend besides the delay itself: bringing the session
        // up, then the slack on the launch wait, the server-state gate, the departing peer's
        // teardown and the peer-loss detection. What is left is what the delay may be.
        long ceiling =
                CLASS_TIMEOUT
                        .minus(MultiPeerSession.SESSION_DEADLINE)
                        .minus(LAUNCH_SLACK)
                        .minus(SERVER_LIVE_TIMEOUT)
                        .minus(TEARDOWN_TIMEOUT)
                        .minus(PEER_LOST_TIMEOUT)
                        .toSeconds();
        int fallback = (int) Math.max(120, floor);
        String override = System.getenv(LAUNCH_DELAY_ENV);
        if (override == null || override.isBlank()) {
            return fallback;
        }
        int seconds;
        try {
            seconds = Integer.parseInt(override.trim());
        } catch (NumberFormatException e) {
            System.out.println(
                    "[4.3.4] ignoring unparseable "
                            + LAUNCH_DELAY_ENV
                            + "="
                            + override
                            + "; using "
                            + fallback
                            + "s");
            return fallback;
        }
        // Compared, never multiplied: arithmetic on an unvalidated value would overflow out of
        // this static initializer and error every test in the class rather than be ignored.
        if (seconds > ceiling) {
            System.out.println(
                    "[4.3.4] ignoring "
                            + LAUNCH_DELAY_ENV
                            + "="
                            + override
                            + ": above the "
                            + ceiling
                            + "s that leaves room under the class timeout, so the run would die on"
                            + " a bare JUnit timeout rather than a named checkpoint; using "
                            + fallback
                            + "s");
            return fallback;
        }
        if (seconds < floor) {
            System.out.println(
                    "[4.3.4] ignoring "
                            + LAUNCH_DELAY_ENV
                            + "="
                            + override
                            + ": below the "
                            + floor
                            + "s needed to keep the game joinable and the match long enough to"
                            + " observe a departure; using "
                            + fallback
                            + "s");
            return fallback;
        }
        return seconds;
    }

    /**
     * Skips, or fails under {@link #LIVE_REQUIRED_ENV}, when the machine cannot run a live session.
     */
    private static void requireLiveEnvironment() {
        List<String> missing = missingPrerequisites();
        if (!missing.isEmpty() && Boolean.parseBoolean(System.getenv(LIVE_REQUIRED_ENV))) {
            fail(LIVE_REQUIRED_ENV + "=true but missing live prerequisites: " + missing);
        }
        assumeTrue(missing.isEmpty(), () -> "missing live prerequisites: " + missing);
    }

    /**
     * Everything a live two-peer run needs and this machine does not have.
     *
     * @return one line per missing prerequisite, naming its override and its remedy
     */
    private static List<String> missingPrerequisites() {
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
        expect(
                missing,
                "hosting account's refresh token",
                findTokenA(),
                TOKEN_A_ENV,
                "bootstrap .secrets/refresh_token.txt");
        expect(
                missing,
                "joining account's refresh token",
                findTokenB(),
                TOKEN_B_ENV,
                "bootstrap .secrets/refresh_token_b.txt for a SECOND seeded account");
        // In the list rather than a separate assumption, so FAF_LIVE_REQUIRED covers it too
        // (WBS-2.3.3.1). A runner with no outbound network must fail rather than go green having
        // run nothing, which is the whole point of that variable.
        if (!lobbyReachable()) {
            missing.add(
                    "a reachable lobby at "
                            + lobbyUrl()
                            + " (TCP timeout on :443). The test lobby is Cloudflare-fronted and"
                            + " publicly reachable, so check local DNS, proxy or firewall");
        }
        return missing;
    }

    /**
     * Adds one line to {@code missing} when {@code found} is absent.
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
            missing.add(what + " (set " + env + ", or " + remedy + ")");
        }
    }

    /**
     * One peer's base config: the shared lobby and binary settings plus that peer's account. Ports,
     * launch delay and host or join intent are all replaced by the session.
     *
     * @param refreshTokenFile the account's refresh-token file
     * @return the validated config
     */
    private static MockClientConfig baseConfig(final Path refreshTokenFile) {
        List<String> args =
                List.of(
                        "--lobby-websocket-url=" + lobbyUrl(),
                        "--oauth-token-url=https://hydra.faforever.xyz/oauth2/token",
                        "--oauth-auth-endpoint=https://hydra.faforever.xyz/oauth2/auth",
                        "--oauth-redirect-uri=http://127.0.0.1",
                        "--oauth-scopes=openid offline lobby",
                        "--oauth-client-id=95ecec08-29c1-4c48-ae0a-b000ff349cb8",
                        "--oauth-refresh-token-file=" + refreshTokenFile.toAbsolutePath(),
                        // Fallback only: the handshake derives the real unique_id from faf-uid,
                        // which the lobby's policy server requires.
                        "--unique-id=00000000-0000-0000-0000-000000000000",
                        "--uid-binary-path=" + requireUidBinary().toAbsolutePath(),
                        "--ice-adapter-binary-path=" + requireAdapterBinary().toAbsolutePath(),
                        "--mock-game-binary-path=" + requireGameBinary().toAbsolutePath());
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
     * Whether the lobby host accepts a TCP connection within a short timeout.
     *
     * @return {@code true} if the lobby is reachable from this network
     */
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
     * Non-null variant for the test body; guaranteed present once the prerequisite gate passes.
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
