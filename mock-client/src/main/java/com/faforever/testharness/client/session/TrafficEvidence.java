package com.faforever.testharness.client.session;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.AppenderBase;
import com.faforever.testharness.shared.logging.ProcessOutputLogger;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.LoggerFactory;

/**
 * Game traffic evidence for a {@link MultiPeerSession} (WBS-4.3.2, in the verdict since WBS-4.2.1):
 * what each mock game says it has received from each other game, read from the progress lines its
 * stdout carries into this JVM's log.
 *
 * <p>Each mock game logs {@code player <me> peer traffic from player <sender>: <n> datagrams,
 * highest sequence <s>, gaps <g>} once a second at INFO, and {@code ProcessOutputLogger} re-emits
 * that stdout as a log record here. A line is attributed by the two player ids inside it, not by
 * its instance label, so a missing label cannot hide or misplace evidence.
 *
 * <p>A direction is proven by {@value #MIN_PROGRESS_SAMPLES} progress lines whose datagram count
 * reaches {@value #MIN_DATAGRAMS} and whose highest sequence has moved between the first and the
 * latest line, which is what "still advancing" means. Counts are "at least", never exact: the
 * adapter drops everything sent before ICE completes, so a stream that starts mid-sequence with
 * gaps is the expected shape.
 *
 * <p>The pattern is tied to the game by {@code TrafficEvidenceTest}, which feeds it lines formatted
 * by mock-game's own {@code GameTrafficSession.progressLine}. {@code TwoGameTrafficLoopbackTest} in
 * mock-game holds an independent copy of the pattern and these thresholds; change one and you must
 * change the other.
 */
final class TrafficEvidence extends AppenderBase<ILoggingEvent> {

    /** Datagrams a game must have attributed to a sender before that direction counts. */
    static final int MIN_DATAGRAMS = 3;

    /** Progress lines required per direction: one proves a count, two prove it is advancing. */
    static final int MIN_PROGRESS_SAMPLES = 2;

    /** The mock game's progress line, as {@code GameTrafficSession} formats it. */
    private static final Pattern PROGRESS_LINE =
            Pattern.compile(
                    "player (\\d+) peer traffic from player (\\d+): (\\d+) datagrams, "
                            + "highest sequence (-?\\d+), gaps (\\d+)");

    /** Regex group of the receiving player's id. */
    private static final int RECEIVER_GROUP = 1;

    /** Regex group of the sending player's id. */
    private static final int SENDER_GROUP = 2;

    /** Regex group of the datagram count. */
    private static final int DATAGRAMS_GROUP = 3;

    /** Regex group of the highest sequence seen. */
    private static final int SEQUENCE_GROUP = 4;

    /** Progress so far per direction, filled from subprocess reader threads. */
    private final Map<Direction, Progress> progress = new ConcurrentHashMap<>();

    /**
     * One direction of game traffic.
     *
     * @param receiverId the game that logged the line
     * @param senderId the game whose datagrams it counted
     */
    record Direction(long receiverId, long senderId) {}

    /**
     * What one direction has shown so far.
     *
     * @param lines progress lines seen
     * @param firstSequence the highest sequence on the first line
     * @param datagrams the datagram count on the latest line
     * @param highestSequence the highest sequence on the latest line
     */
    record Progress(int lines, long firstSequence, long datagrams, long highestSequence) {
        @Override
        public String toString() {
            return lines
                    + " lines, "
                    + datagrams
                    + " datagrams, highest sequence "
                    + highestSequence
                    + " (first "
                    + firstSequence
                    + ")";
        }
    }

    /**
     * Records one log message if it is a progress line; anything else is ignored.
     *
     * @param message a formatted log message
     */
    void accept(final String message) {
        Matcher matcher = PROGRESS_LINE.matcher(message);
        if (!matcher.find()) {
            return;
        }
        Direction direction =
                new Direction(
                        Long.parseLong(matcher.group(RECEIVER_GROUP)),
                        Long.parseLong(matcher.group(SENDER_GROUP)));
        long datagrams = Long.parseLong(matcher.group(DATAGRAMS_GROUP));
        long sequence = Long.parseLong(matcher.group(SEQUENCE_GROUP));
        progress.merge(
                direction,
                new Progress(1, sequence, datagrams, sequence),
                (seen, line) ->
                        new Progress(seen.lines() + 1, seen.firstSequence(), datagrams, sequence));
    }

    /**
     * Whether {@code receiverId}'s game has proven it receives {@code senderId}'s traffic.
     *
     * @param receiverId the receiving player's id
     * @param senderId the sending player's id
     * @return {@code true} once the thresholds are met and the sequence has advanced
     */
    boolean proven(final long receiverId, final long senderId) {
        Progress seen = progress.get(new Direction(receiverId, senderId));
        return seen != null
                && seen.lines() >= MIN_PROGRESS_SAMPLES
                && seen.datagrams() >= MIN_DATAGRAMS
                && seen.highestSequence() > seen.firstSequence();
    }

    /**
     * What one direction has shown so far, for a failure message.
     *
     * @param receiverId the receiving player's id
     * @param senderId the sending player's id
     * @return the progress, or {@code "nothing"}
     */
    String seen(final long receiverId, final long senderId) {
        Progress seen = progress.get(new Direction(receiverId, senderId));
        return seen == null ? "nothing" : seen.toString();
    }

    /** Starts capturing: attaches this appender to the root logger. */
    void attach() {
        LoggerContext context = (LoggerContext) LoggerFactory.getILoggerFactory();
        setContext(context);
        setName("session-traffic-evidence");
        start();
        context.getLogger(Logger.ROOT_LOGGER_NAME).addAppender(this);
    }

    /** Stops capturing. Safe to call when never attached. */
    void detach() {
        if (!isStarted()) {
            return;
        }
        // Detached before it stops, so no record reaches a stopped appender in between.
        LoggerContext context = (LoggerContext) LoggerFactory.getILoggerFactory();
        context.getLogger(Logger.ROOT_LOGGER_NAME).detachAppender(this);
        stop();
    }

    /**
     * Whether this JVM will log the captured subprocess output the evidence is read from. The
     * configured {@code --log-level} decides the games' level, but the level this JVM's Logback
     * actually runs at decides whether their lines reach the appender, and a caller can set the two
     * apart.
     *
     * @return {@code true} if {@code ProcessOutputLogger}'s INFO records are logged
     */
    static boolean capturable() {
        return LoggerFactory.getLogger(ProcessOutputLogger.class).isInfoEnabled();
    }

    @Override
    protected void append(final ILoggingEvent event) {
        accept(event.getFormattedMessage());
    }
}
