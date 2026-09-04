package com.faforever.testharness.client.state;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
                    URI.create("wss://lobby.faforever.xyz"),
                    URI.create("https://hydra.faforever.xyz/oauth2/token"),
                    URI.create("https://hydra.faforever.xyz/oauth2/auth"),
                    URI.create("http://127.0.0.1"),
                    "openid offline lobby",
                    "95ecec08-29c1-4c48-ae0a-b000ff349cb8",
                    Path.of("/nonexistent/test-refresh-token"),
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
                    Optional.empty());

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
     * Harness-initiated teardown wins over both, whatever the code and whether or not the match
     * ended. R41 relies on this for a teardown-time 143, and #295 must not disturb it.
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
}
