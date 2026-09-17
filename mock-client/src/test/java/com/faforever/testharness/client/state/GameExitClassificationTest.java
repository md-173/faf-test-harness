package com.faforever.testharness.client.state;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.faforever.testharness.client.config.MockClientConfig;
import com.faforever.testharness.client.lobby.LobbyConnection;
import com.faforever.testharness.client.lobby.LobbySession;
import com.faforever.testharness.client.lobby.ScriptedWebSocketServer;
import com.faforever.testharness.client.process.SessionTeardown;
import java.net.URI;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.slf4j.LoggerFactory;

/**
 * How the harness reports the game process ending, read against whether a {@code GameEnded} frame
 * was ever observed (WBS-3.1.2.6-fix, #295).
 *
 * <p>The exit code alone cannot answer it. A game whose adapter died as the match ended writes
 * every closing frame into a dead socket's buffer without error and exits {@code 0} having
 * delivered nothing — indistinguishable, from the code alone, from one that delivered everything.
 * {@code isCleanEndSeen} is the observer's half of the signal, and this is its first production
 * reader.
 *
 * <p>Driven through {@code classifyGameExit} directly. Reaching these combinations through real
 * subprocess exits would mean staging a delivered-versus-undelivered {@code GameEnded} on a live
 * GPGNet link, which is the very race the classification exists to describe.
 */
@Timeout(30)
final class GameExitClassificationTest {

    private static final MockClientConfig MINIMAL_CONFIG =
            new MockClientConfig(
                    URI.create("wss://ws.faforever.xyz"),
                    URI.create("https://hydra.faforever.xyz/oauth2/token"),
                    URI.create("https://hydra.faforever.xyz/oauth2/auth"),
                    URI.create("http://127.0.0.1"),
                    "openid offline lobby",
                    "95ecec08-29c1-4c48-ae0a-b000ff349cb8",
                    Path.of("/nonexistent/test-refresh-token"),
                    Optional.empty(),
                    "00000000-0000-0000-0000-000000000000",
                    "0.0.0-mock",
                    "faf-test-harness",
                    Optional.empty(),
                    Path.of("/bin/faf-ice-adapter"),
                    Path.of("/bin/mock-game"),
                    0,
                    0,
                    0,
                    0,
                    5,
                    "WARN",
                    Optional.empty(),
                    OptionalInt.empty(),
                    "Rhiza",
                    Optional.empty(),
                    Optional.empty(),
                    Optional.empty(),
                    0,
                    0,
                    -1);

    private ScriptedWebSocketServer server;
    private LobbyConnection lobby;
    private SessionTeardown teardown;
    private MockClientLifecycle lifecycle;
    private ListAppender<ILoggingEvent> appender;
    private Logger root;
    private Level originalLevel;

    @BeforeEach
    void setUp() throws Exception {
        server = new ScriptedWebSocketServer();
        server.startAndAwait();
        lobby = new LobbyConnection(server.uri());
        lobby.connect().get(5, TimeUnit.SECONDS);
        server.awaitFirstClient();
        teardown = new SessionTeardown(lobby);
        LobbySession session = new LobbySession(lobby, "uid-fixture", "1.0.0", "mock-client-test");
        lifecycle = new MockClientLifecycle(MINIMAL_CONFIG, session, teardown);

        LoggerContext context = (LoggerContext) LoggerFactory.getILoggerFactory();
        root = context.getLogger(Logger.ROOT_LOGGER_NAME);
        originalLevel = root.getLevel();
        appender = new ListAppender<>();
        // Concurrent: the lobby's reader threads log onto the same root logger while this runs.
        appender.list = new CopyOnWriteArrayList<>();
        appender.setContext(context);
        appender.start();
        root.addAppender(appender);
        root.setLevel(Level.INFO);
    }

    @AfterEach
    void tearDown() throws Exception {
        if (appender != null) {
            root.setLevel(originalLevel);
            root.detachAppender(appender);
            appender.stop();
        }
        if (lobby != null) {
            try {
                lobby.close().get(2, TimeUnit.SECONDS);
            } catch (Exception ignored) {
                // best effort
            }
        }
        server.stop(1000);
    }

    /** The one record {@code classifyGameExit} emitted, with its level. */
    private ILoggingEvent classify(
            final int exitCode, final boolean cleanEnd, final boolean matchStarted) {
        appender.list.clear();
        lifecycle.classifyGameExit(exitCode, cleanEnd, matchStarted);
        List<ILoggingEvent> mine =
                appender.list.stream()
                        .filter(e -> e.getFormattedMessage().startsWith("mock-game exited"))
                        .toList();
        assertEquals(1, mine.size(), "expected exactly one classification record, got: " + mine);
        return mine.get(0);
    }

    /** Exit 0 with the frame confirmed: the only genuinely clean outcome, and stays INFO. */
    @Test
    void zeroWithACleanEndIsInfo() {
        ILoggingEvent event = classify(0, true, true);
        assertEquals(Level.INFO, event.getLevel());
        assertTrue(event.getFormattedMessage().contains("exited cleanly"));
    }

    /**
     * The case this card exists for: exit 0, nothing ever observed coming back. Previously reported
     * identically to a fully delivered session, which is what made a false clean exit silent.
     */
    @Test
    void zeroWithNoCleanEndIsNotReportedAsSuccess() {
        ILoggingEvent event = classify(0, false, true);
        assertEquals(
                Level.WARN,
                event.getLevel(),
                "a game that delivered nothing must not read as a clean completion");
        assertTrue(
                event.getFormattedMessage().contains("no GameEnded frame"),
                "the message must say what was missing: " + event.getFormattedMessage());
    }

    /**
     * A clean exit with no match ever started: there was no {@code GameEnded} to deliver, so its
     * absence is not evidence of anything. This is the gate the second commit adds, and the only
     * case in this class that drives {@code matchStarted = false}.
     */
    @Test
    void zeroWithNoCleanEndStaysInfoWhenNoMatchEverStarted() {
        ILoggingEvent event = classify(0, false, false);
        assertEquals(
                Level.INFO,
                event.getLevel(),
                "a session that never reached PLAYING was owed no GameEnded");
        assertTrue(event.getFormattedMessage().contains("exited cleanly"));
    }

    /**
     * Non-zero after the frames landed: the session completed, the process then died. Not a crash.
     */
    @Test
    void nonZeroWithACleanEndIsNotReportedAsACrash() {
        ILoggingEvent event = classify(70, true, true);
        assertEquals(Level.INFO, event.getLevel());
        assertTrue(event.getFormattedMessage().contains("clean game end"));
    }

    /** Non-zero with nothing observed: an ordinary crash, unchanged. */
    @Test
    void nonZeroWithNoCleanEndStaysAWarning() {
        ILoggingEvent event = classify(70, false, true);
        assertEquals(Level.WARN, event.getLevel());
        assertTrue(event.getFormattedMessage().contains("abnormally"));
    }

    /**
     * Harness-initiated teardown suppresses the crash reading, whatever the non-zero code and
     * whether or not the match ended. R41 relies on this for a teardown-time 143, and #295 must not
     * disturb it.
     */
    @Test
    void aNonZeroExitAfterTeardownStaysInfoRegardlessOfTheCleanEndFlag() {
        teardown.run();

        for (boolean cleanEnd : new boolean[] {true, false}) {
            ILoggingEvent event = classify(143, cleanEnd, true);
            assertEquals(
                    Level.INFO,
                    event.getLevel(),
                    "deliberate teardown is not a finding (cleanEnd=" + cleanEnd + ")");
            assertTrue(event.getFormattedMessage().contains("harness-initiated teardown"));
        }
    }

    /**
     * Teardown suppresses the crash reading, not the no-delivery reading. In #295's own scenario
     * the adapter's death drives TERMINATED, so teardown has usually already run by the time the
     * async exit handler classifies a game that exited 0 on its own; checking teardown first would
     * suppress the warning in exactly the case the card exists for.
     */
    @Test
    void teardownDoesNotSuppressTheNoDeliveryWarningOnACleanExit() {
        teardown.run();

        ILoggingEvent event = classify(0, false, true);
        assertEquals(
                Level.WARN,
                event.getLevel(),
                "teardown must not mask a match that delivered nothing");
        assertTrue(event.getFormattedMessage().contains("no GameEnded frame"));
    }

    // The harness's own exit code (WBS-5.2). RunCommand returns ExitCodes.GAME_CRASHED when the
    // flag below is set, and the flag is set by the same branch that emits "exited abnormally",
    // so these cases pin that the log line and the exit code can never disagree. Before this, a
    // run whose game died reported success.

    /** Nothing has exited yet, so there is nothing to report. */
    @Test
    void noCrashIsReportedBeforeTheGameHasExited() {
        assertFalse(lifecycle.gameCrashed(), "a running game has not crashed");
    }

    /**
     * mock-game's own {@code ADAPTER_LOST} is carved out of the crash reading (#357 review).
     *
     * <p>The game diagnosed its own end and named the cause, which an arbitrary non-zero exit does
     * not. Reporting it as a crash was also non-deterministic: the adapter's death drives
     * TERMINATED and so teardown, racing this classification, and whichever won decided between
     * exit {@code 71} and exit {@code 0} for one scenario. Keyed on the code the game reported,
     * which does not race.
     */
    @Test
    void anAdapterLostExitIsReportedAsAdapterLossRatherThanACrash() {
        ILoggingEvent event = classify(69, false, true);

        assertFalse(
                lifecycle.gameCrashed(), "a diagnosed adapter loss is not an unexplained death");
        assertTrue(lifecycle.gameAdapterLost(), "it must drive the adapter-lost exit code instead");
        assertTrue(
                event.getFormattedMessage().contains("losing its GPGNet link"),
                "the log line must name the cause: " + event.getFormattedMessage());
    }

    /**
     * The determinism this carve-out exists for, pinned.
     *
     * <p>An adapter dying mid-session drives TERMINATED and so teardown, which races the game's own
     * exit classification. Reading {@code teardown.hasRun()} here therefore answered differently
     * run to run, and the same scenario reported two different exit codes. The verdict must not
     * move when teardown has already run for a reason nobody signalled.
     */
    @Test
    void anAdapterLostExitIsStillAdapterLostWhenAnUnsignalledTeardownWonTheRace() {
        teardown.run();
        classify(69, false, true);

        assertTrue(
                lifecycle.gameAdapterLost(),
                "the verdict must not depend on which of teardown and classification ran first");
    }

    /** A signalled teardown does explain it: the operator stopped the run. */
    @Test
    void anAdapterLostExitAfterASignalIsNeitherVerdict() {
        teardown.markSignalled();
        teardown.run();
        ILoggingEvent event = classify(69, false, true);

        assertFalse(lifecycle.gameCrashed());
        assertFalse(lifecycle.gameAdapterLost(), "the harness asked for this one");
        assertTrue(event.getFormattedMessage().contains("harness-initiated teardown"));
    }

    /**
     * A game killed by the operator's signal before teardown has started is still the operator's
     * doing (#357 review).
     *
     * <p>The shutdown hook marks the signal a statement before it runs teardown, so there is a
     * window in which the signal is known but {@code hasRun()} is still false. A death landing
     * there, realistically {@code 143} from another shutdown hook reaching the game first, matched
     * neither suppressing branch and fell through to the crash reading: a crash reported for a run
     * the operator had just stopped. {@code markSignalled()} without {@code run()} is that window.
     */
    @Test
    void anExitInsideTheSignalToTeardownWindowIsNeitherVerdict() {
        teardown.markSignalled();
        ILoggingEvent event = classify(143, false, true);

        assertFalse(lifecycle.gameCrashed(), "the operator stopped this run");
        assertFalse(lifecycle.gameAdapterLost());
        assertTrue(event.getFormattedMessage().contains("harness-initiated teardown"));
    }

    /**
     * A confirmed clean end outranks the code: the session delivered its closing frames and the
     * process died afterwards, which the branch above this one already reported as unremarkable.
     */
    @Test
    void anAdapterLostExitAfterACleanEndIsNeitherVerdict() {
        classify(69, true, true);

        assertFalse(lifecycle.gameCrashed());
        assertFalse(lifecycle.gameAdapterLost());
    }

    /** Neither verdict is reported until the game has actually exited. */
    @Test
    void noVerdictIsReportedBeforeTheGameHasExited() {
        assertFalse(lifecycle.gameAdapterLost(), "a running game has not lost its adapter");
    }

    /** The crash proper: a non-zero exit, nothing observed, no teardown to explain it. */
    @Test
    void anAbnormalExitIsReportedAsACrash() {
        classify(70, false, true);

        assertTrue(lifecycle.gameCrashed(), "the abnormal branch must drive the crash exit code");
    }

    /**
     * A game that died before a match ever started counts too, and this is the widening the
     * constant's javadoc calls out. From the harness's side a mock-game that failed to boot and one
     * that died mid-match are the same finding: it is gone and nothing accounted for it. Before
     * this the harness exited {@code 0} for both.
     */
    @Test
    void aGameThatDiedBeforeStartingCountsAsACrash() {
        classify(70, false, false);

        assertTrue(lifecycle.gameCrashed(), "a game that never started still died unaccounted for");
    }

    /** The exact case a naive teardown guard in RunCommand would have got wrong, inverted. */
    @Test
    void aNonZeroExitAfterTeardownIsNotACrash() {
        teardown.run();
        classify(143, false, true);

        assertFalse(
                lifecycle.gameCrashed(),
                "the harness's own SIGTERM must never be reported as a crash");
    }

    /** A clean run must leave the exit code alone. */
    @Test
    void aCleanExitIsNotACrash() {
        classify(0, true, true);

        assertFalse(lifecycle.gameCrashed());
    }

    /**
     * The no-delivery case warns, but it is not a crash: the game ran its whole program and exited
     * zero. Reporting it in the exit code would conflate "nothing arrived" with "the game died",
     * which are different findings with different causes.
     */
    @Test
    void theNoDeliveryWarningIsNotACrash() {
        classify(0, false, true);

        assertFalse(
                lifecycle.gameCrashed(),
                "a game that completed its own program did not crash, however little arrived");
    }

    /** Non-zero after the frames landed: the session completed, so the code stays clean. */
    @Test
    void aNonZeroExitAfterAConfirmedCleanEndIsNotACrash() {
        classify(70, true, true);

        assertFalse(lifecycle.gameCrashed());
    }
}
