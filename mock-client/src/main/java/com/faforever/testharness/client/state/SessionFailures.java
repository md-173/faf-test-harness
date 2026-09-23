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
 * the verdict it records together, so the two cannot disagree (WBS-3.1.3.3-fix, #437, #445).
 *
 * <p>Every method follows the rule the verdicts share: once {@link SessionTeardown} has started, a
 * failure is the harness's own doing, so its line drops to DEBUG and nothing is recorded. Each
 * returns the {@link FailedTransitionException} that takes the session to TERMINATED, for a
 * transition action to throw.
 *
 * <p>Split out of the lifecycle to keep that file within Checkstyle's length limit. It logs under
 * the lifecycle's name, deliberately: these are the lifecycle's lines, and tests and log readers
 * select them by that name.
 */
final class SessionFailures {

    /** The lifecycle's logger, not this class's; see the class javadoc. */
    private static final Logger LOG = LoggerFactory.getLogger(MockClientLifecycle.class);

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
     * those read as a dead adapter and the run can exit {@code 0}. A call made after that point
     * times out and does record the verdict. Telling them apart needs to know whether the adapter
     * process outlives its RPC link, which is left to a follow-up.
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

    private FailedTransitionException fail(
            final Runnable verdict, final String what, final String reason) {
        if (teardown.hasRun()) {
            LOG.debug("Could not {} during session teardown ({})", what, reason);
        } else {
            LOG.warn("Could not {} ({})", what, reason);
            verdict.run();
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
     * has one. The {@code TimeoutException} a call's timer completes it with has none.
     *
     * @param cause the failure to name
     * @return its simple class name, and its message when it has one
     */
    static String describe(final Throwable cause) {
        String message = cause.getMessage();
        return message == null
                ? cause.getClass().getSimpleName()
                : cause.getClass().getSimpleName() + ": " + message;
    }
}
