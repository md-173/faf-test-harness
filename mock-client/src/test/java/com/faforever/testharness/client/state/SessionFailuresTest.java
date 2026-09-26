package com.faforever.testharness.client.state;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.faforever.testharness.client.ice.IceAdapterConnection.DisconnectEvent;
import com.faforever.testharness.client.ice.IceAdapterConnection.DisconnectReason;
import com.faforever.testharness.client.ice.IceRpcException;
import com.faforever.testharness.client.lobby.LobbyConnection;
import com.faforever.testharness.client.process.SessionTeardown;
import com.faforever.testharness.shared.process.CommonPoolOccupier;
import com.faforever.testharness.shared.process.SubprocessManager;
import com.faforever.testharness.shared.statemachine.FailedTransitionException;
import com.faforever.testharness.shared.statemachine.State;
import com.fasterxml.jackson.core.JsonParseException;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.SocketException;
import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.LoggerFactory;

/**
 * How a failure ends a session: the one line that names it and the verdict it records
 * (WBS-3.1.3.3-fix, #445). The lifecycle tests pin that each route reaches these methods, and
 * reaches them before TERMINATED commits; this pins what they decide, which a lifecycle test cannot
 * isolate. Running teardown there to test the teardown rule would kill the stand-in subprocesses,
 * and their exits could end the session before the failure under test arrives.
 *
 * <p>The teardown here holds a lobby connection that never connected, so running it touches no
 * network. The tests of teardown's adapter check (#438, #452) register an adapter stand-in with it,
 * a real {@code sleep} or {@code sh} child, since the check reads the exit the process reaper
 * records.
 */
@Timeout(value = 30, unit = TimeUnit.SECONDS)
final class SessionFailuresTest {

    /** Long enough that a wait the check should not have taken hangs into the timeout. */
    private static final Duration FOREVER = Duration.ofHours(1);

    /** The check's DEBUG line, logged just before it waits for a dying adapter. */
    private static final String WAITING = "ICE adapter closed its JSON-RPC link; waiting";

    private final State terminated = new State("TERMINATED");
    private final SessionVerdicts verdicts = new SessionVerdicts();
    private final AtomicBoolean signalled = new AtomicBoolean();
    private final SessionTeardown teardown =
            new SessionTeardown(
                    new LobbyConnection(URI.create("ws://127.0.0.1:1")), signalled::get);
    private final SessionFailures failures = new SessionFailures(teardown, verdicts, terminated);

    /** Adapter stand-ins a test started, terminated afterwards whatever the test did. */
    private final List<SubprocessManager> children = new ArrayList<>();

    /** Run on the logging thread when the check logs {@link #WAITING}; a no-op unless set. */
    private volatile Runnable onWaiting = () -> {};

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
        appender =
                new ListAppender<>() {
                    @Override
                    protected void append(final ILoggingEvent event) {
                        super.append(event);
                        // Synchronously, on the thread that is about to wait: whatever this does
                        // is done before the wait begins, which is what makes the tests using it
                        // deterministic.
                        if (event.getMessage().startsWith(WAITING)) {
                            onWaiting.run();
                        }
                    }
                };
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
        for (SubprocessManager child : children) {
            child.terminate();
        }
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
     * A call that failed because the adapter's connection closed records nothing: teardown's check
     * decides that, as a lost adapter or a dropped link (#438, #452). The line is INFO, since no
     * verdict comes with it, and the session still ends. Unwrapped whether the future's wrapper was
     * an {@code ExecutionException} or a {@code CompletionException}.
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
     * An adapter teardown finds already dead with a non-zero code is lost (#438), with the same one
     * WARN its own exit event logs. It is not waited for, being dead already.
     */
    @Test
    void anAdapterFoundDeadIsLostWithOneWarning() throws Exception {
        exitedAdapter(3);

        waitingFailures(FOREVER).adapterAtTeardown(linkDropped(null));

        assertTrue(verdicts.adapterLost());
        assertFalse(verdicts.sessionFailed());
        assertEquals(List.of("WARN ICE adapter exited abnormally (code=3)"), lines());
    }

    /**
     * An adapter whose link has closed but whose exit the reaper has not recorded yet is waited
     * for, and read by its exit once it comes (#438): a dying adapter, not one that dropped its
     * link. It is killed the moment the check says it is waiting, so the exit lands inside the wait
     * with no timing involved.
     */
    @Test
    void anAdapterThatDiesDuringTheWaitIsLost() throws Exception {
        SubprocessManager adapter = liveAdapter();
        onWaiting = adapter::terminate;

        waitingFailures(FOREVER).adapterAtTeardown(linkDropped(new IOException("closed")));

        assertTrue(verdicts.adapterLost());
        assertFalse(verdicts.sessionFailed());
        assertEquals(
                List.of(
                        "DEBUG ICE adapter closed its JSON-RPC link; waiting up to PT1H for it to"
                                + " exit",
                        "WARN ICE adapter exited abnormally (code=143)"),
                lines());
    }

    /**
     * A live adapter whose JSON-RPC link closed from its side, still running once the wait is up,
     * dropped its own link (#452): the failed session's verdict, with one WARN naming the link and
     * what closed it.
     */
    @Test
    void aLiveAdapterWhoseLinkDroppedFailsTheSession() throws Exception {
        liveAdapter();

        waitingFailures(Duration.ofMillis(100))
                .adapterAtTeardown(
                        linkDropped(
                                new JsonParseException(
                                        null,
                                        "Unexpected character ('}' (code 125)): expected a"
                                                + " value")));

        assertTrue(verdicts.sessionFailed());
        assertFalse(verdicts.adapterLost());
        assertEquals(
                List.of(
                        "DEBUG ICE adapter closed its JSON-RPC link; waiting up to PT0.1S for it to"
                                + " exit",
                        "WARN ICE adapter JSON-RPC link dropped while the adapter kept running"
                                + " (JsonParseException: Unexpected character ('}' (code 125)):"
                                + " expected a value)"),
                lines());
    }

    /**
     * A real parse error is named on one line. Jackson's {@code getMessage()} adds the source
     * location on a second line, which a stand-in exception built without a parser never shows.
     */
    @Test
    void aParseErrorIsNamedOnOneLine() throws Exception {
        JsonProcessingException parseError =
                assertThrows(
                        JsonProcessingException.class,
                        () -> new ObjectMapper().readTree("{\"jsonrpc\":\"2.0\",\"result\":}"));
        assertTrue(parseError.getMessage().contains("\n"), "the premise: Jackson adds a line");
        liveAdapter();

        waitingFailures(Duration.ofMillis(100)).adapterAtTeardown(linkDropped(parseError));

        String warning = lines().get(lines().size() - 1);
        assertFalse(warning.contains("\n"), "one line: " + warning);
        assertTrue(
                warning.endsWith(
                        "("
                                + parseError.getClass().getSimpleName()
                                + ": "
                                + parseError.getOriginalMessage()
                                + ")"),
                warning);
    }

    /** A live adapter that closed its end cleanly is named for that: the stream simply ended. */
    @Test
    void aLinkThatEndedCleanlyIsNamedAsTheEndOfTheStream() throws Exception {
        liveAdapter();

        waitingFailures(Duration.ofMillis(100)).adapterAtTeardown(linkDropped(null));

        assertTrue(verdicts.sessionFailed());
        assertTrue(
                lines().get(lines().size() - 1)
                        .endsWith("dropped while the adapter kept running (end of stream)"),
                "the WARN must say the stream ended: " + lines());
    }

    /** An adapter that quit with {@code 0} did so on its own, which records nothing, as ever. */
    @Test
    void anAdapterThatQuitCleanlyRecordsNothing() throws Exception {
        exitedAdapter(0);

        waitingFailures(FOREVER).adapterAtTeardown(linkDropped(null));

        assertNothingRecorded();
    }

    /**
     * Only a link closed from the adapter's side is waited on. One still open, closed by this side,
     * or never connected says nothing about the adapter, and with an hour's wait any wait here
     * would hang into the class timeout rather than pass.
     */
    @Test
    void onlyALinkTheAdapterClosedIsWaitedOn() throws Exception {
        liveAdapter();
        SessionFailures check = waitingFailures(FOREVER);

        check.adapterAtTeardown(Optional.empty());
        check.adapterAtTeardown(
                Optional.of(new DisconnectEvent(DisconnectReason.LOCAL_CLOSE, null)));
        check.adapterAtTeardown(
                Optional.of(
                        new DisconnectEvent(
                                DisconnectReason.CONNECT_FAILED, new IOException("refused"))));

        assertNothingRecorded();
    }

    /**
     * A signalled teardown records nothing: the signal kills the adapter itself (#438), so its
     * death is not a finding.
     */
    @Test
    void aSignalledTeardownRecordsNothing() throws Exception {
        exitedAdapter(143);
        signalled.set(true);

        waitingFailures(FOREVER).adapterAtTeardown(linkDropped(null));

        assertNothingRecorded();
    }

    /**
     * A signal that lands during the wait counts too, which is why the flag is read again after it.
     * Here the signal comes as {@code SubprocessRegistry}'s hook does, killing the adapter.
     */
    @Test
    void aSignalDuringTheWaitRecordsNothing() throws Exception {
        SubprocessManager adapter = liveAdapter();
        onWaiting =
                () -> {
                    signalled.set(true);
                    adapter.terminate();
                };

        waitingFailures(FOREVER).adapterAtTeardown(linkDropped(null));

        assertFalse(verdicts.adapterLost());
        assertFalse(verdicts.sessionFailed());
        assertEquals(1, lines().size(), "only the wait's own line: " + lines());
    }

    /**
     * A session that already has a verdict keeps its one cause line: the check adds neither a
     * second verdict nor a second WARN, whichever verdict it is.
     *
     * @param verdict which verdict the session already has
     */
    @ParameterizedTest
    @ValueSource(strings = {"launchFailed", "adapterLost", "gameCrashed", "sessionFailed"})
    void aSessionThatHasAVerdictIsLeftAlone(final String verdict) throws Exception {
        switch (verdict) {
            case "launchFailed" -> verdicts.recordLaunchFailed();
            case "adapterLost" -> verdicts.recordAdapterLost();
            case "gameCrashed" -> verdicts.recordGameCrashed();
            default -> verdicts.recordSessionFailed();
        }
        exitedAdapter(3);

        waitingFailures(FOREVER).adapterAtTeardown(linkDropped(null));

        assertEquals(List.of(), lines(), "no second cause line");
        assertEquals(verdict.equals("adapterLost"), verdicts.adapterLost());
    }

    /** A session that never launched an adapter has nothing for the check to judge. */
    @Test
    void noAdapterMeansNoVerdict() {
        waitingFailures(FOREVER).adapterAtTeardown(linkDropped(null));

        assertNothingRecorded();
    }

    /**
     * The wait ends on the reaper's record of the exit, not on a future the common pool completes
     * (#438 review). With every common-pool thread blocked, as the connectToPeer route can leave a
     * small pool, a wait on such a future would sit out its whole bound for an adapter that died at
     * its start, holding the state machine all the while.
     */
    @Test
    void theWaitDoesNotNeedTheCommonPool() throws Exception {
        // Below two, CompletableFuture runs async stages on fresh threads, not the common pool.
        assumeTrue(ForkJoinPool.getCommonPoolParallelism() > 1, "no common pool to occupy");
        SubprocessManager adapter = liveAdapter();
        onWaiting = adapter::terminate;
        Duration wait = Duration.ofSeconds(10);
        CountDownLatch release = new CountDownLatch(1);
        long elapsedMs;
        try {
            CommonPoolOccupier.occupy(release);
            long start = System.nanoTime();

            waitingFailures(wait).adapterAtTeardown(linkDropped(null));

            elapsedMs = (System.nanoTime() - start) / 1_000_000L;
        } finally {
            release.countDown();
        }

        assertTrue(verdicts.adapterLost(), "read as lost, not as a live adapter: " + lines());
        assertTrue(
                elapsedMs < wait.toMillis() / 2,
                "the wait sat out the busy common pool: " + elapsedMs + "ms");
    }

    /**
     * Failure paths whose check waits {@code wait} for a dying adapter.
     *
     * @param wait how long the check waits
     * @return the failure paths, sharing this test's teardown and verdicts
     */
    private SessionFailures waitingFailures(final Duration wait) {
        return new SessionFailures(teardown, verdicts, terminated, wait);
    }

    /**
     * Starts a long-running adapter stand-in and registers it with the teardown.
     *
     * @return its manager
     * @throws IOException if it cannot start
     */
    private SubprocessManager liveAdapter() throws IOException {
        SubprocessManager adapter =
                SubprocessManager.start(
                        new ProcessBuilder("sleep", "60"),
                        "adapter-stand-in",
                        Duration.ofSeconds(1));
        children.add(adapter);
        teardown.registerAdapterProcess(adapter);
        return adapter;
    }

    /**
     * Starts an adapter stand-in that exits with {@code code}, registers it with the teardown, and
     * waits until the reaper has recorded the exit.
     *
     * @param code what it exits with
     * @throws Exception if it cannot start, or does not exit in time
     */
    private void exitedAdapter(final int code) throws Exception {
        SubprocessManager adapter =
                SubprocessManager.start(
                        new ProcessBuilder("sh", "-c", "exit " + code),
                        "adapter-stand-in",
                        Duration.ofSeconds(1));
        children.add(adapter);
        teardown.registerAdapterProcess(adapter);
        assertTrue(adapter.waitFor(Duration.ofSeconds(5)), "the stand-in never exited");
    }

    /**
     * A link the adapter closed from its side, as the connection records it.
     *
     * @param error what ended it, or {@code null} for a clean end of the stream
     * @return the recorded disconnect
     */
    private static Optional<DisconnectEvent> linkDropped(final Throwable error) {
        return Optional.of(new DisconnectEvent(DisconnectReason.REMOTE_CLOSE, error));
    }

    /** Fails unless the check recorded no verdict and logged nothing. */
    private void assertNothingRecorded() {
        assertFalse(verdicts.adapterLost());
        assertFalse(verdicts.sessionFailed());
        assertEquals(List.of(), lines());
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
