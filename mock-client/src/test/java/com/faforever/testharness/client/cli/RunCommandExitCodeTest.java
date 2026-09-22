package com.faforever.testharness.client.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.slf4j.LoggerFactory;

/**
 * How {@code run} turns a finished session's verdicts into one exit code (WBS-3.1.2.8-fix, #406,
 * and WBS-3.1.3.3-fix, #437).
 *
 * <p>The precedence is the part worth pinning. {@code RunCommand.call()} builds a real {@link
 * com.faforever.testharness.client.lobby.LobbyConnection} and cannot be driven from a unit test,
 * which is why #357 left its own {@code 71} mapping uncovered; the ordering lives in a static
 * method so it can be exercised without a session at all.
 *
 * <p>Several rows below are rare or unreachable. The ones with both a lost adapter and a crashed
 * game need a genuine double race: on the ordinary adapter route {@code classifyGameExit} takes its
 * teardown branch and leaves {@code gameCrashed} false, and on the game-crash route the adapter's
 * own exit is classified after teardown and leaves {@code adapterLost} false. A failed launch meets
 * neither, since no session ran for an adapter or a game to die in. They are here because the
 * precedence must be defined for them, not because a session reaches them often.
 */
final class RunCommandExitCodeTest {

    /**
     * Name of the logger handed to {@code sessionExitCode}, which no other class uses. The appender
     * sits on it rather than on the root because the Gradle task runs every test class in one JVM
     * at DEBUG, where an earlier class's lingering threads can log at any moment, and the
     * assertions here count records exactly.
     */
    private static final String VERDICT_LOGGER =
            RunCommandExitCodeTest.class.getName() + ".verdict";

    private ListAppender<ILoggingEvent> appender;
    private Logger log;
    private Level originalLevel;
    private boolean originalAdditive;

    @BeforeEach
    void setUp() {
        LoggerContext context = (LoggerContext) LoggerFactory.getILoggerFactory();
        log = context.getLogger(VERDICT_LOGGER);
        // Pinned rather than inherited, so the WARN assertions do not depend on the root level,
        // and not additive, so these records stay out of the console and every other appender.
        // Both are restored afterwards: the context caches the logger for the whole JVM.
        originalLevel = log.getLevel();
        originalAdditive = log.isAdditive();
        log.setLevel(Level.DEBUG);
        log.setAdditive(false);
        appender = new ListAppender<>();
        appender.setContext(context);
        appender.start();
        log.addAppender(appender);
    }

    @AfterEach
    void tearDown() {
        log.detachAppender(appender);
        appender.stop();
        log.setLevel(originalLevel);
        log.setAdditive(originalAdditive);
    }

    /**
     * Every combination of the four verdicts, and the code each must produce. A lobby drop and a
     * failed launch both give {@code 70}, so which of those two outranks the other is pinned by the
     * logged line instead, in {@link #aLobbyDropOutranksAFailedLaunch()}.
     *
     * @param lobbyDropped whether the lobby closed abruptly under the session
     * @param launchFailed whether the adapter or game never came up
     * @param adapterLost whether the adapter died unaccounted for
     * @param gameCrashed whether the game died unaccounted for
     * @param expected the exit code the combination must yield
     */
    @ParameterizedTest(name = "lobby={0} launch={1} adapter={2} game={3} -> {4}")
    @CsvSource({
        "false, false, false, false, 0",
        "false, false, false, true, 71",
        "false, false, true, false, 72",
        "false, false, true, true, 72",
        "false, true, false, false, 70",
        "false, true, false, true, 70",
        "false, true, true, false, 70",
        "false, true, true, true, 70",
        "true, false, false, false, 70",
        "true, false, false, true, 70",
        "true, false, true, false, 70",
        "true, false, true, true, 70",
        "true, true, false, false, 70",
        "true, true, false, true, 70",
        "true, true, true, false, 70",
        "true, true, true, true, 70",
    })
    void theVerdictsAreOrderedLobbyLaunchAdapterGame(
            final boolean lobbyDropped,
            final boolean launchFailed,
            final boolean adapterLost,
            final boolean gameCrashed,
            final int expected) {
        assertEquals(
                expected,
                RunCommand.sessionExitCode(
                        lobbyDropped, launchFailed, adapterLost, gameCrashed, log));
    }

    /**
     * The adapter outranks the game deliberately, so this states it as its own case rather than
     * leaving it to one row of the table above. The adapter verdict is the one written in the
     * transition action that drives TERMINATED, so it is ordered against this read where {@code
     * gameCrashed} is not; reversing these two would let a race pick the code for a run whose
     * adapter died.
     */
    @Test
    void aLostAdapterOutranksACrashedGame() {
        assertEquals(
                ExitCodes.ADAPTER_LOST, RunCommand.sessionExitCode(false, false, true, true, log));
    }

    /** A clean session says nothing: the log surface is a documented interface. */
    @Test
    void aCleanSessionLogsNothing() {
        RunCommand.sessionExitCode(false, false, false, false, log);

        assertTrue(appender.list.isEmpty(), "captured: " + appender.list);
    }

    /** Each reported verdict names itself once, at WARN, so a run's log says which one it was. */
    @Test
    void aLostAdapterIsReportedAtWarn() {
        RunCommand.sessionExitCode(false, false, true, false, log);

        assertEquals(1, appender.list.size(), "captured: " + appender.list);
        ILoggingEvent event = appender.list.get(0);
        assertEquals(Level.WARN, event.getLevel());
        assertTrue(
                event.getFormattedMessage().contains("ICE adapter"),
                "the line must name the adapter: " + event.getFormattedMessage());
    }

    /** A launch that never came up names itself once, at WARN, like the others (#437). */
    @Test
    void aFailedLaunchIsReportedAtWarn() {
        assertEquals(ExitCodes.RUNTIME, RunCommand.sessionExitCode(false, true, false, false, log));

        assertEquals(1, appender.list.size(), "captured: " + appender.list);
        ILoggingEvent event = appender.list.get(0);
        assertEquals(Level.WARN, event.getLevel());
        assertTrue(
                event.getFormattedMessage().contains("never came up"),
                "the line must say the launch never came up: " + event.getFormattedMessage());
    }

    /**
     * The lobby drop outranks a failed launch. Both give {@code 70}, so the code cannot show the
     * order, and the one line logged has to.
     */
    @Test
    void aLobbyDropOutranksAFailedLaunch() {
        RunCommand.sessionExitCode(true, true, false, false, log);

        assertEquals(1, appender.list.size(), "captured: " + appender.list);
        assertTrue(
                appender.list.get(0).getFormattedMessage().contains("lobby connection dropped"),
                "the lobby drop must be the line reported: " + appender.list);
    }
}
