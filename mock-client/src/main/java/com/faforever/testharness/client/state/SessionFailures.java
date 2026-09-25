package com.faforever.testharness.client.state;

import com.faforever.testharness.client.ice.IceAdapterConnection.DisconnectEvent;
import com.faforever.testharness.client.ice.IceAdapterConnection.DisconnectReason;
import com.faforever.testharness.client.process.SessionTeardown;
import com.faforever.testharness.shared.logging.Failures;
import com.faforever.testharness.shared.process.SubprocessManager;
import com.faforever.testharness.shared.statemachine.Event;
import com.faforever.testharness.shared.statemachine.FailedTransitionException;
import com.faforever.testharness.shared.statemachine.State;
import java.io.IOException;
import java.time.Duration;
import java.util.Optional;
import java.util.OptionalInt;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Ends a {@link MockClientLifecycle} session on a failure, deciding the one line that names it and
 * the verdict it records together, so the two cannot disagree (WBS-3.1.3.3-fix, #437, #439, #445,
 * and WBS-3.1.1.9-fix, #344).
 *
 * <p>Every failure path here follows the rule the verdicts share: once {@link SessionTeardown} has
 * started, a failure is the harness's own doing, so nothing is recorded and its line drops to
 * DEBUG. A defect's line is one exception, kept at ERROR; see {@link #defect}. The other is {@link
 * #adapterAtTeardown}, which teardown itself runs, and which records what happened to the adapter
 * before teardown touched it. The paths that fail a transition return the {@link
 * FailedTransitionException} that takes the session to TERMINATED, for the transition action to
 * throw.
 *
 * <p>Split out of the lifecycle to keep that file within Checkstyle's length limit. It logs under
 * the lifecycle's name, deliberately: these are the lifecycle's lines, and tests and log readers
 * select them by that name.
 */
final class SessionFailures {

    /** The lifecycle's logger, not this class's; see the class javadoc. */
    private static final Logger LOG = LoggerFactory.getLogger(MockClientLifecycle.class);

    /**
     * How long {@link #adapterAtTeardown} gives an adapter whose JSON-RPC link closed from its side
     * to exit, before reading it as alive (WBS-3.1.2.8-fix, #438, #452).
     *
     * <p>Measured against the pinned 3.3.14 over 80 kills, one RPC client each: the exit followed
     * the socket's close by at most 0.9 ms after a SIGKILL, and by at most 330 ms after a SIGTERM,
     * whose shutdown closes the socket first. Two seconds clears both several times over, and only
     * an adapter that is still alive waits it out, so a dying adapter reads as lost ({@code 72})
     * rather than as one that dropped its link ({@code 70}).
     *
     * <p>The adapter's own {@code IceAdapter.close(status)} is not one of these routes. It would
     * stop the RPC server and call {@code System.exit} two 250 ms steps later, but on a box with no
     * system tray, CI's included, it throws at {@code TrayIcon.close()} before it schedules the
     * exit (see {@code SessionTeardown}'s quit note). After a {@code quit} the pinned adapter kept
     * its client's socket open and was still running 20 s later.
     */
    private static final Duration ADAPTER_EXIT_WAIT = Duration.ofSeconds(2);

    /** The session's teardown; once it has started, a failure records nothing. */
    private final SessionTeardown teardown;

    /** Where the verdicts are recorded. */
    private final SessionVerdicts verdicts;

    /** The state every failure here takes the session to. */
    private final State terminated;

    /** {@link #ADAPTER_EXIT_WAIT}, unless a test shortens or lengthens it. */
    private final Duration adapterExitWait;

    /**
     * Creates the failure paths for one session.
     *
     * @param teardown the session's teardown, whose start silences every verdict
     * @param verdicts where the verdicts are recorded
     * @param terminated the state a failure takes the session to
     */
    SessionFailures(
            final SessionTeardown teardown,
            final SessionVerdicts verdicts,
            final State terminated) {
        this(teardown, verdicts, terminated, ADAPTER_EXIT_WAIT);
    }

    /**
     * Creates the failure paths for one session with its own wait for a dying adapter, for tests.
     *
     * @param teardown the session's teardown, whose start silences every verdict
     * @param verdicts where the verdicts are recorded
     * @param terminated the state a failure takes the session to
     * @param adapterExitWait how long {@link #adapterAtTeardown} waits for a dying adapter
     */
    SessionFailures(
            final SessionTeardown teardown,
            final SessionVerdicts verdicts,
            final State terminated,
            final Duration adapterExitWait) {
        this.teardown = teardown;
        this.verdicts = verdicts;
        this.terminated = terminated;
        this.adapterExitWait = adapterExitWait;
    }

    /**
     * A launch that never came up (#437), a {@code game_launch} the client could not use among them
     * (#457), recorded as {@link SessionVerdicts#launchFailed()}.
     *
     * @param what the action that failed, for the log line
     * @param reason why it failed
     * @return the exception that fails the transition into TERMINATED
     */
    FailedTransitionException launch(final String what, final String reason) {
        return fail(verdicts::recordLaunchFailed, what, reason);
    }

    /**
     * A session that failed after it came up (#445, #344), recorded as {@link
     * SessionVerdicts#sessionFailed()}.
     *
     * @param what the action that failed, for the log line
     * @param reason why it failed
     * @return the exception that fails the transition into TERMINATED
     */
    FailedTransitionException session(final String what, final String reason) {
        return fail(verdicts::recordSessionFailed, what, reason);
    }

    /**
     * A failed adapter call, judged by what failed it (#445). An {@link IOException} means the
     * adapter's connection closed: once it has, every call fails with one at once (pinned by {@code
     * IceAdapterConnectionTest}). That closed connection is the adapter's finding, not the call's,
     * so this logs at INFO and records nothing, and {@link #adapterAtTeardown} decides it once the
     * session ends: an adapter that died is lost ({@code 72}, #438), and a live adapter whose
     * stream stopped parsing, which fails its calls the same way, dropped its link ({@code 70},
     * #452). Anything else, an error answer or no answer in time, came from an adapter that was
     * still connected, and is {@link #session}.
     *
     * @param what the action that failed, for the log line
     * @param failure what the call's future failed with, wrapped or not
     * @return the exception that fails the transition into TERMINATED
     */
    FailedTransitionException call(final String what, final Throwable failure) {
        Throwable cause = unwrap(failure);
        if (!(cause instanceof IOException)) {
            return session(what, describe(cause));
        }
        if (teardown.hasRun()) {
            LOG.debug("Could not {} during session teardown ({})", what, describe(cause));
        } else {
            LOG.info(
                    "Could not {}: the ICE adapter connection closed ({}); ending session",
                    what,
                    describe(cause));
        }
        return new FailedTransitionException(describe(cause), terminated);
    }

    /**
     * An ICE adapter lost under the session (#406, #438), recorded as {@link
     * SessionVerdicts#adapterLost()} with its one WARN. Shared by the adapter's own exit event and
     * {@link #adapterAtTeardown}, so the line reads the same whichever of them found the death.
     *
     * @param exitCode the adapter's non-zero exit code
     */
    void adapterLost(final int exitCode) {
        verdicts.recordAdapterLost();
        LOG.warn("ICE adapter exited abnormally (code={})", exitCode);
    }

    /**
     * Teardown's look at the ICE adapter once the game is down and before the adapter step
     * (WBS-3.1.2.8-fix, #438, #452): the verdict for an adapter that failed while something else
     * ended the session.
     *
     * <p>#406 records a lost adapter when its exit is the event that ends the session. When
     * something the same death caused gets there first, a call in flight failing as the adapter's
     * socket closes, or the game exiting {@code 69} on its dead GPGNet link, that event ends the
     * session and the adapter's exit is only classified after teardown has begun, too late to
     * count. A live adapter whose JSON-RPC stream stopped parsing fails its calls exactly as a dead
     * one does, and then carries on, which nothing else notices at all. Every such session passes
     * through teardown, so this runs there, before the adapter is touched:
     *
     * <ul>
     *   <li>adapter dead with a non-zero code: {@link #adapterLost}, exit {@code 72};
     *   <li>link closed from the adapter's side and the adapter still running after {@link
     *       #ADAPTER_EXIT_WAIT}: {@link SessionVerdicts#sessionFailed()}, exit {@code 70}, with one
     *       WARN naming the dropped link;
     *   <li>anything else records nothing. An adapter that exited {@code 0} quit on its own.
     * </ul>
     *
     * <p>It reads the exit the process reaper records, not the lifecycle's exit future, which the
     * common pool completes. On the connectToPeer route this runs on a pool thread that holds the
     * state machine, with another pool thread waiting on it for the game's exit, and a small pool
     * would then have no thread left to complete that future inside the wait.
     *
     * <p>Records nothing on a signalled teardown, read again after the wait since a signal can land
     * during it: the signal kills the adapter itself. Nor once the session has a verdict, so a run
     * keeps one cause line: a failed launch (#437) already reported its adapter, and a lost
     * adapter, crashed game or failed session already named its cause. Unlike every other path
     * here, it records although teardown has started, because what it judges happened before
     * teardown touched the adapter. Never keyed on the game's {@code 69}: mock-game reports a
     * truncated frame or a bug in its own decoder the same way as a link that went away.
     *
     * @param link how the adapter's JSON-RPC connection ended, if it has
     */
    void adapterAtTeardown(final Optional<DisconnectEvent> link) {
        Optional<SubprocessManager> adapter = teardown.adapterProcess();
        if (adapter.isEmpty() || teardown.signalled() || verdicts.any()) {
            return;
        }
        SubprocessManager process = adapter.get();
        Optional<DisconnectEvent> dropped =
                link.filter(event -> event.reason() == DisconnectReason.REMOTE_CLOSE);
        if (process.isAlive() && dropped.isPresent()) {
            LOG.debug(
                    "ICE adapter closed its JSON-RPC link; waiting up to {} for it to exit",
                    adapterExitWait);
            try {
                process.waitFor(adapterExitWait);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
            if (teardown.signalled()) {
                return;
            }
        }
        OptionalInt exitCode = process.exitCode();
        if (exitCode.isPresent()) {
            if (exitCode.getAsInt() != 0) {
                adapterLost(exitCode.getAsInt());
            }
        } else if (dropped.isPresent()) {
            Throwable error = dropped.get().error();
            verdicts.recordSessionFailed();
            LOG.warn(
                    "ICE adapter JSON-RPC link dropped while the adapter kept running ({})",
                    error == null ? "end of stream" : describe(error));
        }
    }

    /**
     * A match the server cancelled after {@code game_launch} (#344), recorded as {@link
     * SessionVerdicts#sessionFailed()} with the one WARN that names it. Nothing is returned: the
     * lifecycle registers a transition to TERMINATED for this event rather than failing into one.
     *
     * @param gameId the cancelled game's id, as the frame carried it
     */
    void matchCancelled(final String gameId) {
        if (teardown.hasRun()) {
            LOG.debug("match_cancelled after game_launch (game_id={}) during teardown", gameId);
            return;
        }
        LOG.warn(
                "match_cancelled after game_launch (game_id={}); the matched game will not start,"
                        + " terminating",
                gameId);
        verdicts.recordSessionFailed();
    }

    /**
     * The CONNECTING to TERMINATED action for {@code AuthFailed} (WBS-3.1.1.4-fix, #455): the
     * handshake's failure ended the session, so it is named ahead of {@code state entry:
     * TERMINATED}. A connection that fails ends the session first, through the lobby's disconnect,
     * and the failure then reaches TERMINATED's no-op instead of a WARN after the session had
     * ended. The caller of {@code start} names the cause either way.
     *
     * @param event the {@code AuthFailed} event
     */
    void handshakeFailed(final Event event) {
        LOG.warn("Handshake could not be completed");
    }

    /**
     * The IDLE and SEARCHING action for a {@code game_launch} the client cannot use
     * (WBS-3.1.1.6-fix, #457): a launch that never came up, recorded as {@link #launch}. The frame
     * used to be dropped with a WARN, leaving the run waiting for a launch it had already received,
     * in IDLE for good or in SEARCHING until faf-server's {@code match_cancelled}. faf-server
     * writes {@code game_launch} only to a player who is idle or starting a matched game, so no
     * other state has the edge, as none has one for {@code LaunchGame}. When a match's host fails
     * to host in time, faf-server still sends each guest its {@code game_launch}, then {@code
     * match_cancelled}, which then reaches TERMINATED's no-op.
     *
     * @param event the {@link LaunchRejected} event
     * @throws FailedTransitionException always, which takes the session to TERMINATED
     */
    void launchRejected(final Event event) throws FailedTransitionException {
        throw launch("read the game_launch frame", ((LaunchRejected) event).reason());
    }

    /**
     * An unchecked throw during a launch (#439), recorded as {@link
     * SessionVerdicts#launchFailed()}; see {@link #defect}.
     *
     * @param what the action that failed, for the log line
     * @param defect what was thrown
     * @return the exception that fails the transition into TERMINATED
     */
    FailedTransitionException launchDefect(final String what, final RuntimeException defect) {
        return defect(verdicts::recordLaunchFailed, what, defect);
    }

    /**
     * An unchecked throw after the session came up (#439), recorded as {@link
     * SessionVerdicts#sessionFailed()}; see {@link #defect}.
     *
     * @param what the action that failed, for the log line
     * @param defect what was thrown
     * @return the exception that fails the transition into TERMINATED
     */
    FailedTransitionException sessionDefect(final String what, final RuntimeException defect) {
        return defect(verdicts::recordSessionFailed, what, defect);
    }

    /**
     * Ends the session on a defect rather than a modelled failure: an unchecked throw out of a
     * transition action with live subprocesses (#439). Left to {@code Transition}, it would be
     * contained and the session left in the state it was leaving, where nothing moves it on.
     *
     * <p>Its one cause line is at ERROR with the stack trace, as {@code Transition} logs it,
     * because the trace is the only record of the bug. So that line is logged even once teardown
     * has started, while the verdict still follows the usual rule.
     *
     * @param verdict records this failure's verdict
     * @param what the action that failed, for the log line
     * @param defect what was thrown
     * @return the exception that fails the transition into TERMINATED
     */
    private FailedTransitionException defect(
            final Runnable verdict, final String what, final RuntimeException defect) {
        String reason = describe(defect);
        LOG.error("Could not {} ({})", what, reason, defect);
        if (!teardown.hasRun()) {
            verdict.run();
        }
        return new FailedTransitionException(reason, terminated);
    }

    private FailedTransitionException fail(
            final Runnable verdict, final String what, final String reason) {
        if (teardown.hasRun()) {
            LOG.debug("Could not {} during session teardown ({})", what, reason);
        } else {
            // Recorded before the line, not after: connectToPeer's continuation gets here outside
            // the FSM, where another route can end the session meanwhile. See
            // SessionVerdicts.sessionFailed().
            verdict.run();
            LOG.warn("Could not {} ({})", what, reason);
        }
        return new FailedTransitionException(reason, terminated);
    }

    /**
     * Strips the wrappers a future adds; the lifecycle's name for {@link Failures#unwrap}, which
     * the lobby's lines share (#455).
     *
     * @param failure the failure as a future reported it
     * @return the first cause that is not such a wrapper
     */
    static Throwable unwrap(final Throwable failure) {
        return Failures.unwrap(failure);
    }

    /**
     * Names a failure for a log line; the lifecycle's name for {@link Failures#describe}, which the
     * lobby's lines share (#455).
     *
     * @param cause the failure to name
     * @return its simple class name and message, and its root cause's when it has one
     */
    static String describe(final Throwable cause) {
        return Failures.describe(cause);
    }
}
