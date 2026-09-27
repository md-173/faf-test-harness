package com.faforever.testharness.client.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.faforever.testharness.client.lobby.LobbyConnection;
import com.faforever.testharness.client.process.SessionTeardown;
import com.faforever.testharness.client.state.SessionVerdictsFixture;
import java.net.URI;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.slf4j.LoggerFactory;

/**
 * How {@code run} turns a finished session's verdicts into one exit code (WBS-3.1.2.8-fix, #406,
 * WBS-3.1.3.3-fix, #437 and #445, and WBS-3.1.1.9-fix, #344).
 *
 * <p>The precedence is the part worth pinning. {@code RunCommand.call()} builds a real {@link
 * com.faforever.testharness.client.lobby.LobbyConnection} and cannot be driven from a unit test,
 * which is why #357 left its own {@code 71} mapping uncovered; the ordering lives in a static
 * method so it can be exercised without a session at all. That method takes the lifecycle's {@code
 * SessionVerdicts} whole, so which verdict feeds which code is pinned here too; {@link
 * SessionVerdictsFixture} builds them.
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
     * Every combination of the four verdicts that outrank a failed session, and the code each must
     * produce; {@link #aFailedSessionRanksLast} adds the fifth. A lobby drop and a failed launch
     * both give {@code 70}, so which of those two outranks the other is pinned by the logged line
     * instead, in {@link #aLobbyDropOutranksAFailedLaunch()}.
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
                        false,
                        lobbyDropped,
                        false,
                        SessionVerdictsFixture.of(launchFailed, adapterLost, gameCrashed, false),
                        log));
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
                ExitCodes.ADAPTER_LOST,
                RunCommand.sessionExitCode(
                        false,
                        false,
                        false,
                        SessionVerdictsFixture.of(false, true, true, false),
                        log));
    }

    /** A clean session says nothing: the log surface is a documented interface. */
    @Test
    void aCleanSessionLogsNothing() {
        RunCommand.sessionExitCode(
                false, false, false, SessionVerdictsFixture.of(false, false, false, false), log);

        assertTrue(appender.list.isEmpty(), "captured: " + appender.list);
    }

    /** Each reported verdict names itself once, at WARN, so a run's log says which one it was. */
    @Test
    void aLostAdapterIsReportedAtWarn() {
        RunCommand.sessionExitCode(
                false, false, false, SessionVerdictsFixture.of(false, true, false, false), log);

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
        assertEquals(
                ExitCodes.RUNTIME,
                RunCommand.sessionExitCode(
                        false,
                        false,
                        false,
                        SessionVerdictsFixture.of(true, false, false, false),
                        log));

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
        RunCommand.sessionExitCode(
                false, true, false, SessionVerdictsFixture.of(true, false, false, false), log);

        assertEquals(1, appender.list.size(), "captured: " + appender.list);
        assertTrue(
                appender.list.get(0).getFormattedMessage().contains("lobby connection dropped"),
                "the lobby drop must be the line reported: " + appender.list);
    }

    /**
     * A command the lobby answered with {@code invalid} is a {@code 70} of its own, named once at
     * WARN (#486). faf-server closes the connection cleanly after it, which used to read as the
     * lobby ending the session, so the run exited {@code 0}.
     */
    @Test
    void aRefusedCommandIsReportedAt70() {
        assertEquals(
                ExitCodes.RUNTIME,
                RunCommand.sessionExitCode(
                        false,
                        false,
                        true,
                        SessionVerdictsFixture.of(false, false, false, false),
                        log));

        assertEquals(1, appender.list.size(), "captured: " + appender.list);
        assertEquals(Level.WARN, appender.list.get(0).getLevel());
        assertEquals(
                "the lobby answered one of this run's commands with invalid; reporting it in this"
                        + " run's exit code",
                appender.list.get(0).getFormattedMessage());
    }

    /**
     * The two lobby findings come first, the drop ahead of the refusal, and the refusal ahead of
     * everything the lifecycle found (#486). The line logged shows the order where the code cannot.
     */
    @Test
    void aRefusedCommandRanksAfterTheDropAndAheadOfTheRest() {
        RunCommand.sessionExitCode(
                false, true, true, SessionVerdictsFixture.of(false, false, false, false), log);
        assertEquals(
                ExitCodes.RUNTIME,
                RunCommand.sessionExitCode(
                        false,
                        false,
                        true,
                        SessionVerdictsFixture.of(true, true, true, true),
                        log));

        assertEquals(2, appender.list.size(), "captured: " + appender.list);
        assertTrue(
                appender.list.get(0).getFormattedMessage().contains("lobby connection dropped"),
                "the drop must outrank the refusal: " + appender.list);
        assertTrue(
                appender.list.get(1).getFormattedMessage().contains("commands with invalid"),
                "the refusal must outrank the lifecycle's verdicts: " + appender.list);
    }

    /**
     * A run a signal ended names no verdict, whatever it found (#437). The code is still computed,
     * because the caller returns it either way, but the process exits on the signal's own code and
     * a verdict line would contradict it. Only a live run exercises the hook that sets this, so
     * this is the one place the rule itself is pinned.
     */
    @Test
    void aSignalledRunNamesNoVerdict() {
        assertEquals(
                ExitCodes.RUNTIME,
                RunCommand.sessionExitCode(
                        true,
                        false,
                        false,
                        SessionVerdictsFixture.of(true, false, false, false),
                        log));
        assertEquals(
                ExitCodes.ADAPTER_LOST,
                RunCommand.sessionExitCode(
                        true,
                        false,
                        false,
                        SessionVerdictsFixture.of(false, true, false, false),
                        log));
        assertEquals(
                ExitCodes.RUNTIME,
                RunCommand.sessionExitCode(
                        true,
                        true,
                        false,
                        SessionVerdictsFixture.of(false, false, false, false),
                        log));
        assertEquals(
                ExitCodes.GAME_CRASHED,
                RunCommand.sessionExitCode(
                        true,
                        false,
                        false,
                        SessionVerdictsFixture.of(false, false, true, false),
                        log));
        assertEquals(
                ExitCodes.RUNTIME,
                RunCommand.sessionExitCode(
                        true,
                        false,
                        false,
                        SessionVerdictsFixture.of(false, false, false, true),
                        log));
        assertEquals(
                ExitCodes.RUNTIME,
                RunCommand.sessionExitCode(
                        true,
                        false,
                        true,
                        SessionVerdictsFixture.of(false, false, false, false),
                        log));

        assertTrue(appender.list.isEmpty(), "captured: " + appender.list);
    }

    /**
     * A session that failed after it came up ranks last (#445, #344). Alone it reports {@code 70}
     * with its own line; next to any other verdict, that other one is reported instead. Two of
     * those also give {@code 70}, so the line is asserted as well as the code.
     *
     * @param lobbyDropped whether the lobby closed abruptly under the session
     * @param launchFailed whether the adapter or game never came up
     * @param adapterLost whether the adapter died unaccounted for
     * @param gameCrashed whether the game died unaccounted for
     * @param expected the exit code the combination must yield
     * @param line text the one reported line must contain
     */
    @ParameterizedTest(name = "lobby={0} launch={1} adapter={2} game={3}, session failed -> {4}")
    @CsvSource({
        "false, false, false, false, 70, the session failed after its ICE adapter and game came up",
        "true, false, false, false, 70, lobby connection dropped",
        "false, true, false, false, 70, never came up",
        "false, false, true, false, 72, the ICE adapter died mid-session",
        "false, false, false, true, 71, the game process died unexpectedly",
    })
    void aFailedSessionRanksLast(
            final boolean lobbyDropped,
            final boolean launchFailed,
            final boolean adapterLost,
            final boolean gameCrashed,
            final int expected,
            final String line) {
        assertEquals(
                expected,
                RunCommand.sessionExitCode(
                        false,
                        lobbyDropped,
                        false,
                        SessionVerdictsFixture.of(launchFailed, adapterLost, gameCrashed, true),
                        log));

        assertEquals(1, appender.list.size(), "captured: " + appender.list);
        ILoggingEvent event = appender.list.get(0);
        assertEquals(Level.WARN, event.getLevel());
        assertTrue(
                event.getFormattedMessage().contains(line),
                "the reported line must be the one that won: " + event.getFormattedMessage());
    }

    /**
     * The shutdown hook raises the flag before it tears down (#437). Swapped, a signal during
     * bring-up can leave the hook blocked in the synchronized teardown, behind the launch thread
     * that got there first, with the flag still down: the main thread then reads it as a session
     * that ended on its own, and the verdict line returns on a signalled run. Nothing else pins
     * that order, since the hook itself only ever runs on a real signal.
     */
    @Test
    void theShutdownHookRaisesTheFlagBeforeTearingDown() {
        AtomicBoolean shuttingDown = new AtomicBoolean();
        AtomicBoolean flagWasUp = new AtomicBoolean();

        RunCommand.shutdownHook(shuttingDown, () -> flagWasUp.set(shuttingDown.get())).run();

        assertTrue(flagWasUp.get(), "teardown must not start before the flag is raised");
        assertTrue(shuttingDown.get(), "and the flag must stay raised afterwards");
    }

    /**
     * The hook names a signal only while {@code call()} still had the session (#446). It runs on
     * every JVM exit, and the guard it used before, the lobby's disconnect, lost a race on a normal
     * one: teardown's close returns once its frame is sent, before the server's echo marks the
     * session disconnected, so a run that ended on its own logged the line after its verdict.
     * {@code call()} raises the flag on every way out, so a hook that finds it down started under a
     * live run. Teardown runs either way.
     */
    @Test
    void theShutdownHookNamesASignalOnlyWhileTheRunWasLive() {
        SessionTeardown underALiveRun = unconnectedTeardown();
        RunCommand.teardownOnShutdown(new AtomicBoolean(false), underALiveRun, log);

        assertTrue(underALiveRun.hasRun(), "a signal's teardown must run");
        assertEquals(1, appender.list.size(), "captured: " + appender.list);
        assertEquals(
                "shutdown signal received; tearing down session",
                appender.list.get(0).getFormattedMessage());

        appender.list.clear();
        SessionTeardown afterTheRunEnded = unconnectedTeardown();
        RunCommand.teardownOnShutdown(new AtomicBoolean(true), afterTheRunEnded, log);

        assertTrue(afterTheRunEnded.hasRun(), "teardown still runs on a normal exit");
        assertTrue(appender.list.isEmpty(), "a normal exit names no signal: " + appender.list);
    }

    /**
     * A teardown whose lobby connection never connected, so running it touches no network and no
     * process.
     *
     * @return the teardown
     */
    private static SessionTeardown unconnectedTeardown() {
        return new SessionTeardown(new LobbyConnection(URI.create("ws://127.0.0.1:1")));
    }
}
