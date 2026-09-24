package com.faforever.testharness.client.state;

import com.faforever.testharness.client.process.SessionTeardown;
import com.faforever.testharness.shared.statemachine.FailedTransitionException;
import com.faforever.testharness.shared.statemachine.State;
import java.io.IOException;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Ends a {@link MockClientLifecycle} session on a failure, deciding the one line that names it and
 * the verdict it records together, so the two cannot disagree (WBS-3.1.3.3-fix, #437, #439, #445,
 * and WBS-3.1.1.9-fix, #344).
 *
 * <p>Every failure path here follows the rule the verdicts share: once {@link SessionTeardown} has
 * started, a failure is the harness's own doing, so nothing is recorded and its line drops to
 * DEBUG. A defect's line is the one exception, kept at ERROR; see {@link #defect}. The paths that
 * fail a transition return the {@link FailedTransitionException} that takes the session to
 * TERMINATED, for the transition action to throw.
 *
 * <p>Split out of the lifecycle to keep that file within Checkstyle's length limit. It logs under
 * the lifecycle's name, deliberately: these are the lifecycle's lines, and tests and log readers
 * select them by that name.
 */
final class SessionFailures {

    /** The lifecycle's logger, not this class's; see the class javadoc. */
    private static final Logger LOG = LoggerFactory.getLogger(MockClientLifecycle.class);

    /** How deep {@link #describe} follows a cause chain, so one that loops cannot hang a line. */
    private static final int MAX_CAUSE_DEPTH = 16;

    /** The session's teardown; once it has started, a failure records nothing. */
    private final SessionTeardown teardown;

    /** Where the verdicts are recorded. */
    private final SessionVerdicts verdicts;

    /** The state every failure here takes the session to. */
    private final State terminated;

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
        this.teardown = teardown;
        this.verdicts = verdicts;
        this.terminated = terminated;
    }

    /**
     * A launch that never came up (#437), recorded as {@link SessionVerdicts#launchFailed()}.
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
     * adapter's connection closed: Jackson closes the socket once the reader reaches the end of its
     * input, and a reset breaks the pipe, so a dead adapter fails a call with one at once (pinned
     * by {@code IceAdapterConnectionTest}). The adapter's own exit is the finding then (#406,
     * #438), so this logs at INFO and records nothing. Anything else, an error answer or no answer
     * in time, came from an adapter that was still connected, and is {@link #session}.
     *
     * <p>One case reads wrong. A live adapter whose stream went out of sync fails the calls in
     * flight with an {@code IOException} too, as the reader gives up on a frame it cannot parse, so
     * those read as a dead adapter and the run can exit {@code 0}. The INFO line still names the
     * parse error underneath, and a call made after that point times out and does record the
     * verdict. Telling them apart needs to know whether the adapter process outlives its RPC link,
     * which is #452's.
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
     * Strips the {@link ExecutionException} and {@link CompletionException} wrappers a future adds,
     * so a failure is judged by what actually caused it.
     *
     * @param failure the failure as a future reported it
     * @return the first cause that is not such a wrapper
     */
    static Throwable unwrap(final Throwable failure) {
        Throwable cause = failure;
        while ((cause instanceof ExecutionException || cause instanceof CompletionException)
                && cause.getCause() != null) {
            cause = cause.getCause();
        }
        return cause;
    }

    /**
     * Names a failure for a log line: its type, which carries the meaning, then its message when it
     * has one (the {@code TimeoutException} a call's timer completes it with has none), then the
     * failure at the bottom of its cause chain, when there is one. That last part is what tells a
     * closed connection's kinds apart: nothing for a clean end of the stream, a {@code
     * SocketException} for a reset, a {@code JsonProcessingException} for a stream that stopped
     * parsing.
     *
     * @param cause the failure to name
     * @return its simple class name and message, and its root cause's when it has one
     */
    static String describe(final Throwable cause) {
        Throwable root = cause;
        for (int depth = 0; depth < MAX_CAUSE_DEPTH && root.getCause() != null; depth++) {
            root = root.getCause();
        }
        return root == cause ? name(cause) : name(cause) + ", caused by " + name(root);
    }

    private static String name(final Throwable failure) {
        String message = failure.getMessage();
        return message == null
                ? failure.getClass().getSimpleName()
                : failure.getClass().getSimpleName() + ": " + message;
    }
}
