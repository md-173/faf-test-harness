package com.faforever.testharness.client.session;

/**
 * A session checkpoint that did not pass, naming the peer and the stage. It is the verdict {@link
 * MultiPeerSession#run()} reports: the {@code session} command maps it to its exit code and log
 * line, and {@code MultiPeerSessionLiveTest} reports it as the test's failure.
 *
 * <p>Stages: {@code ports}, {@code shutdown}, {@code welcome}, {@code game_launch}, {@code
 * HOSTING}, {@code JOINING} and {@code full mesh}.
 */
public final class CheckpointFailure extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /** The peer, or peers, the checkpoint was about. */
    private final String peer;

    /** The checkpoint that did not pass, e.g. {@code welcome} or {@code full mesh}. */
    private final String stage;

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
        this.peer = peer;
        this.stage = stage;
    }

    /**
     * The peer, or peers, the checkpoint was about.
     *
     * @return the peer name, or several joined by commas
     */
    public String peer() {
        return peer;
    }

    /**
     * The checkpoint that did not pass.
     *
     * @return the stage name
     */
    public String stage() {
        return stage;
    }
}
