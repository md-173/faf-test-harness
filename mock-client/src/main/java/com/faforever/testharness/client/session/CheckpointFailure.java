package com.faforever.testharness.client.session;

/**
 * A session checkpoint that did not pass, naming the peer and the stage. It is the verdict {@link
 * MultiPeerSession#run()} reports: the {@code session} command maps it to its exit code and log
 * line, and {@code MultiPeerSessionLiveTest} reports it as the test's failure.
 *
 * <p>Stages: {@code ports}, {@code shutdown}, {@code welcome}, {@code game_launch}, {@code
 * HOSTING}, {@code JOINING}, {@code full mesh} and {@code traffic}.
 */
public final class CheckpointFailure extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /**
     * A failure with no underlying cause.
     *
     * @param peer the peer or peers, e.g. {@code B(joiner)} or {@code A(host),B(joiner)}
     * @param stage the checkpoint that did not pass
     * @param detail what was expected and what had been seen by then
     */
    CheckpointFailure(final String peer, final String stage, final String detail) {
        this(peer, stage, detail, null);
    }

    /**
     * A failure caused by {@code cause}.
     *
     * @param peer the peer or peers, e.g. {@code B(joiner)} or {@code A(host),B(joiner)}
     * @param stage the checkpoint that did not pass
     * @param detail what was expected and what had been seen by then
     * @param cause the underlying failure, or {@code null}
     */
    CheckpointFailure(
            final String peer, final String stage, final String detail, final Throwable cause) {
        super(peer + ": " + stage + ": " + detail, cause);
    }
}
