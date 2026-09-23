package com.faforever.testharness.client.state;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.faforever.testharness.client.ice.IceRpcException;
import com.faforever.testharness.client.lobby.LobbyConnection;
import com.faforever.testharness.client.process.SessionTeardown;
import com.faforever.testharness.shared.statemachine.FailedTransitionException;
import com.faforever.testharness.shared.statemachine.State;
import java.io.IOException;
import java.net.SocketException;
import java.net.URI;
import java.util.List;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

/**
 * How a failure ends a session: the one line that names it and the verdict it records
 * (WBS-3.1.3.3-fix, #445). The lifecycle tests pin that each route reaches these methods, and
 * reaches them before TERMINATED commits; this pins what they decide, which a lifecycle test cannot
 * isolate. Running teardown there to test the teardown rule would kill the stand-in subprocesses,
 * and their exits could end the session before the failure under test arrives.
 *
 * <p>The teardown here holds only a lobby connection that never connected, so running it touches no
 * network and no process.
 */
final class SessionFailuresTest {

    private final State terminated = new State("TERMINATED");
    private final SessionVerdicts verdicts = new SessionVerdicts();
    private final SessionTeardown teardown =
            new SessionTeardown(new LobbyConnection(URI.create("ws://127.0.0.1:1")));
    private final SessionFailures failures = new SessionFailures(teardown, verdicts, terminated);

    private ListAppender<ILoggingEvent> appender;
    private Logger logger;
    private Level originalLevel;

    @BeforeEach
    void setUp() {
        LoggerContext context = (LoggerContext) LoggerFactory.getILoggerFactory();
        // The lifecycle's logger, which SessionFailures logs under. Pinned to DEBUG so the teardown
        // branch is visible whatever the build's level, and restored afterwards.
        logger = context.getLogger(MockClientLifecycle.class);
        originalLevel = logger.getLevel();
        logger.setLevel(Level.DEBUG);
        appender = new ListAppender<>();
        appender.list = new CopyOnWriteArrayList<>();
        appender.setContext(context);
        appender.start();
        logger.addAppender(appender);
    }

    @AfterEach
    void tearDown() {
        logger.detachAppender(appender);
        appender.stop();
        logger.setLevel(originalLevel);
    }

    /** A failed session is recorded, named once at WARN, and ends in the terminated state. */
    @Test
    void aSessionFailureRecordsItsVerdictAndWarnsOnce() {
        FailedTransitionException thrown = failures.session("host the game", "bad frame");

        assertTrue(verdicts.sessionFailed());
        assertFalse(verdicts.launchFailed(), "a failed session is not a failed launch");
        assertSame(terminated, thrown.getFailureState());
        assertEquals(List.of("WARN Could not host the game (bad frame)"), lines());
    }

    /** The launch keeps its own verdict (#437); only the flag differs. */
    @Test
    void aLaunchFailureRecordsTheLaunchVerdict() {
        failures.launch("launch game binary", "not found");

        assertTrue(verdicts.launchFailed());
        assertFalse(verdicts.sessionFailed());
        assertEquals(List.of("WARN Could not launch game binary (not found)"), lines());
    }

    /**
     * An adapter that answered with an error, or not in time, was still connected, so the call is
     * the finding. The timeout's message is null, which is why the line names the type.
     */
    @Test
    void aCallTheAdapterAnsweredOrLetTimeOutRecordsTheVerdict() {
        failures.call(
                "host the game", new ExecutionException(new IceRpcException(-32000, "refused")));
        failures.call("join the game", new ExecutionException(new TimeoutException()));

        assertTrue(verdicts.sessionFailed());
        assertEquals(
                List.of(
                        "WARN Could not host the game (IceRpcException: JSON-RPC error -32000:"
                                + " refused)",
                        "WARN Could not join the game (TimeoutException)"),
                lines());
    }

    /**
     * A call that failed because the adapter's connection closed records nothing: the adapter is
     * gone, and its own exit is the finding (#406, #438). The line is INFO, since no verdict comes
     * with it, and the session still ends. Unwrapped whether the future's wrapper was an {@code
     * ExecutionException} or a {@code CompletionException}.
     */
    @Test
    void aCallWhoseConnectionClosedRecordsNothing() {
        FailedTransitionException fromGet =
                failures.call("host the game", new ExecutionException(new IOException("closed")));
        failures.call("set up the peer relay", new CompletionException(new IOException("closed")));

        assertFalse(verdicts.sessionFailed());
        assertSame(terminated, fromGet.getFailureState(), "the session still ends");
        assertEquals(
                List.of(
                        "INFO Could not host the game: the ICE adapter connection closed"
                                + " (IOException: closed); ending session",
                        "INFO Could not set up the peer relay: the ICE adapter connection closed"
                                + " (IOException: closed); ending session"),
                lines());
    }

    /**
     * Once teardown has started, a failure is the harness's own doing: nothing is recorded, for
     * either verdict or any cause, and every line drops to DEBUG. This is what keeps a signalled
     * run from naming a verdict (#437).
     */
    @Test
    void nothingIsRecordedOnceTeardownHasStarted() {
        teardown.run();

        failures.launch("launch game binary", "killed");
        failures.session("host the game", "bad frame");
        failures.call("join the game", new ExecutionException(new TimeoutException()));
        failures.call("host the game", new ExecutionException(new IOException("closed")));
        failures.matchCancelled("4242");

        assertFalse(verdicts.launchFailed());
        assertFalse(verdicts.sessionFailed());
        assertTrue(
                lines().stream().allMatch(line -> line.startsWith("DEBUG ")),
                "every line must drop to DEBUG: " + lines());
        assertEquals(5, lines().size(), "one line per failure: " + lines());
    }

    /**
     * A closed connection's line names what closed it: the root of the cause chain, which is what
     * tells a reset or a stream that stopped parsing from a clean end of the stream (#445 review).
     */
    @Test
    void aClosedConnectionsLineNamesWhatClosedIt() {
        IOException closed =
                new IOException(
                        "ICE adapter connection closed (REMOTE_CLOSE)",
                        new SocketException("Connection reset"));

        failures.call("host the game", new ExecutionException(closed));

        assertFalse(verdicts.sessionFailed());
        assertEquals(
                List.of(
                        "INFO Could not host the game: the ICE adapter connection closed"
                                + " (IOException: ICE adapter connection closed (REMOTE_CLOSE),"
                                + " caused by SocketException: Connection reset); ending session"),
                lines());
    }

    /**
     * A match the server cancelled after {@code game_launch} is a failed session (#344), named by
     * the one WARN the lifecycle has always logged for it.
     */
    @Test
    void aMatchCancelledAfterLaunchRecordsTheSessionVerdict() {
        failures.matchCancelled("4242");

        assertTrue(verdicts.sessionFailed());
        assertEquals(
                List.of(
                        "WARN match_cancelled after game_launch (game_id=4242); the matched game"
                                + " will not start, terminating"),
                lines());
    }

    /**
     * An unchecked throw is a defect (#439): its one line is at ERROR and carries the stack trace,
     * which is the only record of the bug, and it records the verdict of the stage it broke.
     */
    @Test
    void aDefectLogsItsTraceAtErrorAndRecordsItsStagesVerdict() {
        FailedTransitionException launch =
                failures.launchDefect("launch the game session", new IllegalStateException("boom"));
        failures.sessionDefect("host the game", new NullPointerException());

        assertTrue(verdicts.launchFailed());
        assertTrue(verdicts.sessionFailed());
        assertSame(terminated, launch.getFailureState());
        assertEquals(
                List.of(
                        "ERROR Could not launch the game session (IllegalStateException: boom)",
                        "ERROR Could not host the game (NullPointerException)"),
                lines());
        assertTrue(
                testThreadEvents().stream().allMatch(e -> e.getThrowableProxy() != null),
                "each defect's line must carry its stack trace");
    }

    /**
     * Once teardown has started, a defect still records nothing, but its line stays at ERROR with
     * the trace: a bug is still a bug when the session is already ending, and {@code Transition}
     * logs an uncaught one at ERROR whatever the state.
     */
    @Test
    void aDefectOnceTeardownHasStartedIsStillLoggedButRecordsNothing() {
        teardown.run();

        failures.launchDefect("launch the game session", new IllegalStateException("boom"));

        assertFalse(verdicts.launchFailed());
        assertEquals(
                List.of("ERROR Could not launch the game session (IllegalStateException: boom)"),
                lines());
    }

    /**
     * The events this test logged through the lifecycle's logger; see {@link #lines()}.
     *
     * @return the captured events on the test thread, in order
     */
    private List<ILoggingEvent> testThreadEvents() {
        String testThread = Thread.currentThread().getName();
        return appender.list.stream().filter(e -> testThread.equals(e.getThreadName())).toList();
    }

    /**
     * The lines this test logged through the lifecycle's logger, as {@code LEVEL message}.
     *
     * <p>Only the test thread's. The Gradle task runs every class in one JVM, and a lifecycle an
     * earlier class left behind can log on a pool or timer thread at any moment; everything this
     * class calls runs synchronously on the test thread.
     *
     * @return the captured lines, in order
     */
    private List<String> lines() {
        String testThread = Thread.currentThread().getName();
        return appender.list.stream()
                .filter(e -> testThread.equals(e.getThreadName()))
                .map(e -> e.getLevel() + " " + e.getFormattedMessage())
                .toList();
    }
}
