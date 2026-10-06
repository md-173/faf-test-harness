package com.faforever.testharness.client.session;

import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.AppenderBase;
import com.faforever.testharness.client.state.ClientState;
import com.faforever.testharness.client.state.MockClientLifecycle;
import com.faforever.testharness.shared.logging.LoggingSetup;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.LoggerFactory;

/**
 * Each peer's own account of its path through a {@link MultiPeerSession} (WBS-4.2.6, #430): the
 * {@code state entry:} and {@code peer connect:} lines its client logs, read back under the peer's
 * instance label and compared with the path its role takes. It fails the session's last stage,
 * {@value #STAGE}.
 *
 * <p>The session's other checkpoints are outcomes: every peer reached HOSTING or JOINING, the mesh
 * formed, the traffic flowed. A peer can reach the right outcome by a wrong path, and this is the
 * check that sees it. It reads two rows of the harness log contract ({@code mock-client/README.md},
 * pinned by {@code HarnessLogContractTest}), so it checks what an operator can see rather than the
 * client's internal state.
 *
 * <p><b>The paths</b>, from faf-server's {@code connect_to_host} and {@code connect_to_peer}, and
 * as the live runs at two to four peers log them:
 *
 * <ul>
 *   <li>the host: {@link #HOST_PATH}, then one {@code peer connect} with {@code offer=true} per
 *       joiner;
 *   <li>a joiner: {@link #JOINER_PATH}, then {@code offer=true} for each earlier joiner and {@code
 *       offer=false} for each later one.
 * </ul>
 *
 * <p>The states are compared as a sequence, since one client's state machine orders them. The
 * connect lines are compared as a set, each logged once: their order across joiners is arrival
 * timing, not protocol. A scenario that moves a peer on after bring-up names the states it adds,
 * such as {@code PLAYING} for a host that launched.
 *
 * <p><b>Labels.</b> A line without a label fails the check. Every peer is built, started and shut
 * down under its own label, so an unlabelled line is one the log cannot attribute to its peer. A
 * line under a label no peer of the session has is ignored, since it provably is not one of theirs.
 *
 * <p><b>Order.</b> A client logs every one of these lines under its state machine's monitor, apart
 * from {@code CONNECTING}, which its constructor logs before anything else can run, and {@code
 * AppenderBase.doAppend} is synchronized, so each label's lines are recorded in the order its
 * machine produced them. {@link #verify} takes the same monitor.
 */
public final class TransitionEvidence extends AppenderBase<ILoggingEvent> {

    /** The stage a deviation fails. */
    static final String STAGE = "transitions";

    /** The host's path up to its role. */
    static final List<ClientState> HOST_PATH =
            List.of(
                    ClientState.CONNECTING,
                    ClientState.IDLE,
                    ClientState.STARTING_GAME,
                    ClientState.HOSTING);

    /** A joiner's path up to its role. */
    static final List<ClientState> JOINER_PATH =
            List.of(
                    ClientState.CONNECTING,
                    ClientState.IDLE,
                    ClientState.STARTING_GAME,
                    ClientState.JOINING);

    /** The name a failure gives lines that carry no label, in place of a peer's. */
    static final String NO_LABEL = "(no label)";

    /** {@code MockClientLifecycle}'s state line, as the log contract fixes it. */
    private static final Pattern STATE_ENTRY = Pattern.compile("^state entry: (\\w+)$");

    /** {@code MockClientLifecycle}'s peer-connect line, as the log contract fixes it. */
    private static final Pattern PEER_CONNECT =
            Pattern.compile("^peer connect: login=.* id=(-?\\d+) offer=(true|false)$");

    /** Unlabelled lines quoted in a failure; the rest are counted. */
    private static final int QUOTED_UNLABELLED = 5;

    /** Per label, the states entered, in order. Guarded by this appender. */
    private final Map<String, List<String>> states = new HashMap<>();

    /** Per label, the peer connect lines, in order. Guarded by this appender. */
    private final Map<String, List<Connect>> connects = new HashMap<>();

    /** Lines of either kind that carried no label, as logged. Guarded by this appender. */
    private final List<String> unlabelled = new ArrayList<>();

    /**
     * One peer of the session, as the check needs it.
     *
     * @param name the name a failure gives it, e.g. {@code B(joiner)}
     * @param label its instance label, e.g. {@code B}
     * @param id its lobby-assigned player id
     */
    public record Peer(String name, String label, long id) {}

    /**
     * One {@code peer connect} line.
     *
     * @param remoteId the player the lobby told this client to connect to
     * @param offer whether this client makes the ICE offer
     */
    private record Connect(long remoteId, boolean offer) {}

    /** Starts capturing: attaches to {@code MockClientLifecycle}'s logger. */
    public void attach() {
        LoggerContext context = (LoggerContext) LoggerFactory.getILoggerFactory();
        setContext(context);
        setName("session-transition-evidence");
        start();
        context.getLogger(MockClientLifecycle.class).addAppender(this);
    }

    /** Stops capturing. Safe to call when never attached. */
    public void detach() {
        if (!isStarted()) {
            return;
        }
        // Detached before it stops, so no record reaches a stopped appender in between.
        LoggerContext context = (LoggerContext) LoggerFactory.getILoggerFactory();
        context.getLogger(MockClientLifecycle.class).detachAppender(this);
        stop();
    }

    /**
     * Whether this JVM logs the clients' own INFO lines, which the check reads. The configured
     * {@code --log-level} says what a session wants, but the level this JVM's Logback runs at
     * decides whether the lines reach the appender.
     *
     * @return {@code true} if {@code MockClientLifecycle}'s INFO records are logged
     */
    static boolean capturable() {
        return LoggerFactory.getLogger(MockClientLifecycle.class).isInfoEnabled();
    }

    @Override
    protected void append(final ILoggingEvent event) {
        // Read on the logging thread: Logback fills an event's MDC map from whichever thread asks
        // first, and only this one carries the label.
        accept(
                event.getMDCPropertyMap().get(LoggingSetup.INSTANCE_MDC_KEY),
                event.getFormattedMessage());
    }

    /**
     * Records one of the client's lines under its label, if it is a state or peer connect line.
     * Package-private so a test can feed lines without the process-wide logger.
     *
     * @param label the line's instance label, or {@code null} if it had none
     * @param message the formatted message
     */
    synchronized void accept(final String label, final String message) {
        Matcher state = STATE_ENTRY.matcher(message);
        Matcher connect = PEER_CONNECT.matcher(message);
        boolean isState = state.matches();
        if (!isState && !connect.matches()) {
            return;
        }
        if (label == null) {
            unlabelled.add(message);
        } else if (isState) {
            states.computeIfAbsent(label, k -> new ArrayList<>()).add(state.group(1));
        } else {
            connects.computeIfAbsent(label, k -> new ArrayList<>())
                    .add(
                            new Connect(
                                    Long.parseLong(connect.group(1)),
                                    Boolean.parseBoolean(connect.group(2))));
        }
    }

    /**
     * The states recorded under one label so far, for a test to see what was captured.
     *
     * @param label the instance label
     * @return the state names in the order they were entered
     */
    public synchronized List<String> loggedStates(final String label) {
        return List.copyOf(states.getOrDefault(label, List.of()));
    }

    /**
     * Checks every peer's recorded path against its role's, the host first and the joiners in join
     * order.
     *
     * @param joinOrder every peer of the session, host first
     * @param after the states each peer's path adds after its role, by label; a label not given
     *     adds none
     * @throws CheckpointFailure at stage {@value #STAGE}, naming every peer that deviated and what
     *     it logged, or naming {@value #NO_LABEL} for lines that carried no label
     */
    public synchronized void verify(
            final List<Peer> joinOrder, final Map<String, List<ClientState>> after) {
        Map<Long, String> names = new HashMap<>();
        for (Peer peer : joinOrder) {
            names.put(peer.id(), peer.name());
        }
        List<String> deviating = new ArrayList<>();
        List<String> details = new ArrayList<>();
        for (int k = 0; k < joinOrder.size(); k++) {
            Peer peer = joinOrder.get(k);
            List<String> problems = new ArrayList<>();
            List<String> expectedStates = new ArrayList<>();
            for (ClientState state : k == 0 ? HOST_PATH : JOINER_PATH) {
                expectedStates.add(state.name());
            }
            for (ClientState state : after.getOrDefault(peer.label(), List.of())) {
                expectedStates.add(state.name());
            }
            List<String> loggedStates = states.getOrDefault(peer.label(), List.of());
            if (!loggedStates.equals(expectedStates)) {
                problems.add(
                        "logged state entries " + loggedStates + ", expected " + expectedStates);
            }
            List<Connect> expectedConnects = new ArrayList<>();
            for (int j = 1; j < joinOrder.size(); j++) {
                if (j != k) {
                    // The host offers to every joiner; a joiner to every earlier joiner only.
                    expectedConnects.add(new Connect(joinOrder.get(j).id(), k == 0 || j < k));
                }
            }
            List<Connect> loggedConnects = connects.getOrDefault(peer.label(), List.of());
            if (loggedConnects.size() != expectedConnects.size()
                    || !new HashSet<>(loggedConnects).equals(new HashSet<>(expectedConnects))) {
                problems.add(
                        "logged peer connect "
                                + describe(loggedConnects, names)
                                + ", expected "
                                + describe(expectedConnects, names));
            }
            if (!problems.isEmpty()) {
                deviating.add(peer.name());
                details.add(peer.name() + " " + String.join(" and ", problems));
            }
        }
        if (!unlabelled.isEmpty()) {
            deviating.add(NO_LABEL);
            details.add(
                    unlabelled.size()
                            + " line(s) carried no instance label, so no peer can be held to them: "
                            + unlabelled.subList(
                                    0, Math.min(QUOTED_UNLABELLED, unlabelled.size())));
        }
        if (!deviating.isEmpty()) {
            throw new CheckpointFailure(
                    String.join(",", deviating), STAGE, String.join("; ", details));
        }
    }

    /**
     * As {@link #verify(List, Map)}, for the session's own peers.
     *
     * @param peers every peer, host first and joiners in join order, each with its identity
     * @param after the states each peer's path adds after its role, by label
     * @throws CheckpointFailure at stage {@value #STAGE} if any peer deviated
     */
    void verifySession(final List<SessionPeer> peers, final Map<String, List<ClientState>> after) {
        List<Peer> joinOrder = new ArrayList<>();
        for (SessionPeer peer : peers) {
            joinOrder.add(new Peer(peer.name(), peer.label(), peer.identity().id()));
        }
        verify(joinOrder, after);
    }

    /**
     * The connect lines for a failure message, each peer named where the session knows it.
     *
     * @param lines the connect lines
     * @param names each session peer's id mapped to its name
     * @return e.g. {@code [B(joiner) offer=true, id 99 offer=false]}
     */
    private static String describe(final List<Connect> lines, final Map<Long, String> names) {
        List<String> described = new ArrayList<>();
        for (Connect line : lines) {
            String who = names.getOrDefault(line.remoteId(), "id " + line.remoteId());
            described.add(who + " offer=" + line.offer());
        }
        return described.toString();
    }
}
