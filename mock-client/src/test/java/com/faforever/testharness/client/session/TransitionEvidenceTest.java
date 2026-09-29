package com.faforever.testharness.client.session;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.faforever.testharness.client.state.ClientState;
import com.faforever.testharness.client.state.MockClientLifecycle;
import com.faforever.testharness.shared.logging.LoggingSetup;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

/**
 * The path part of a session's verdict (WBS-4.2.6), checked without a lobby: each peer's lines are
 * fed as its client logs them, and each failure is read as an operator reads it.
 */
final class TransitionEvidenceTest {

    /** The lobby-assigned ids of A to D, in join order. */
    private static final long[] IDS = {7982, 330072, 441873, 512004};

    /** A host's lines up to its role. */
    private static final List<String> HOST =
            states("CONNECTING", "IDLE", "STARTING_GAME", "HOSTING");

    /** A joiner's lines up to its role. */
    private static final List<String> JOINER =
            states("CONNECTING", "IDLE", "STARTING_GAME", "JOINING");

    @ParameterizedTest
    @ValueSource(ints = {2, 3, 4})
    void everyPeerOnThePathTheLiveRunsLoggedPasses(final int peers) {
        TransitionEvidence evidence = new TransitionEvidence();

        feed(evidence, liveRun(peers));

        assertDoesNotThrow(() -> evidence.verify(peers(peers), Map.of()));
    }

    @Test
    void theConnectLinesAreComparedAsASetSinceArrivalOrdersThem() {
        TransitionEvidence evidence = new TransitionEvidence();
        Map<String, List<String>> run = liveRun(4);
        run.put("A", lines(HOST, connect("D", true), connect("B", true), connect("C", true)));

        feed(evidence, run);

        assertDoesNotThrow(() -> evidence.verify(peers(4), Map.of()));
    }

    @Test
    void oneFailureNamesEveryPeerThatDeviatedAndEachDeviation() {
        TransitionEvidence evidence = new TransitionEvidence();
        Map<String, List<String>> run = liveRun(3);
        run.put("A", lines(JOINER, connect("B", true)));
        run.remove("C");
        feed(evidence, run);

        CheckpointFailure failure =
                assertThrows(CheckpointFailure.class, () -> evidence.verify(peers(3), Map.of()));

        assertEquals(
                "A(host),C(joiner): transitions: A(host) logged state entries [CONNECTING, IDLE,"
                        + " STARTING_GAME, JOINING], expected [CONNECTING, IDLE, STARTING_GAME,"
                        + " HOSTING] and logged peer connect [B(joiner) offer=true], expected"
                        + " [B(joiner) offer=true, C(joiner) offer=true]; C(joiner) logged state"
                        + " entries [], expected [CONNECTING, IDLE, STARTING_GAME, JOINING] and"
                        + " logged peer connect [], expected [B(joiner) offer=true]",
                failure.getMessage());
    }

    @Test
    void aSurvivorWhoseClientEndedFailsThoughItReachedItsRole() {
        // #435's defect as the client logs it: one departure ended every survivor's game.
        TransitionEvidence evidence = new TransitionEvidence();
        Map<String, List<String>> run = liveRun(3);
        run.put("C", lines(JOINER, connect("B", true), state("TERMINATED")));
        feed(evidence, run);

        CheckpointFailure failure =
                assertThrows(CheckpointFailure.class, () -> evidence.verify(peers(3), Map.of()));

        assertEquals(
                "C(joiner): transitions: C(joiner) logged state entries [CONNECTING, IDLE,"
                        + " STARTING_GAME, JOINING, TERMINATED], expected [CONNECTING, IDLE,"
                        + " STARTING_GAME, JOINING]",
                failure.getMessage());
    }

    @Test
    void aScenarioAddsStatesThatThePeersItNamesMustLog() {
        // A deliberate crash of B: the host launched, and B's client ended with its game.
        Map<String, List<ClientState>> crash =
                Map.of("A", List.of(ClientState.PLAYING), "B", List.of(ClientState.TERMINATED));
        Map<String, List<String>> run = liveRun(3);
        run.put("B", lines(JOINER, connect("C", false), state("TERMINATED")));
        TransitionEvidence notLaunched = new TransitionEvidence();
        feed(notLaunched, run);
        run.put("A", lines(HOST, connect("B", true), connect("C", true), state("PLAYING")));
        TransitionEvidence launched = new TransitionEvidence();
        feed(launched, run);

        assertDoesNotThrow(() -> launched.verify(peers(3), crash));
        CheckpointFailure failure =
                assertThrows(CheckpointFailure.class, () -> notLaunched.verify(peers(3), crash));
        assertEquals(
                "A(host): transitions: A(host) logged state entries [CONNECTING, IDLE,"
                        + " STARTING_GAME, HOSTING], expected [CONNECTING, IDLE, STARTING_GAME,"
                        + " HOSTING, PLAYING]",
                failure.getMessage());
    }

    @Test
    void aJoinerOfferingToALaterJoinerFails() {
        TransitionEvidence evidence = new TransitionEvidence();
        Map<String, List<String>> run = liveRun(3);
        run.put("B", lines(JOINER, connect("C", true)));
        feed(evidence, run);

        CheckpointFailure failure =
                assertThrows(CheckpointFailure.class, () -> evidence.verify(peers(3), Map.of()));

        assertEquals(
                "B(joiner): transitions: B(joiner) logged peer connect [C(joiner) offer=true],"
                        + " expected [C(joiner) offer=false]",
                failure.getMessage());
    }

    @Test
    void aConnectLoggedTwiceFails() {
        TransitionEvidence evidence = new TransitionEvidence();
        Map<String, List<String>> run = liveRun(3);
        run.put("B", lines(JOINER, connect("C", false), connect("C", false)));
        feed(evidence, run);

        CheckpointFailure failure =
                assertThrows(CheckpointFailure.class, () -> evidence.verify(peers(3), Map.of()));

        assertEquals(
                "B(joiner): transitions: B(joiner) logged peer connect [C(joiner) offer=false,"
                        + " C(joiner) offer=false], expected [C(joiner) offer=false]",
                failure.getMessage());
    }

    @Test
    void aConnectToAPlayerOutsideTheSessionIsNamedByItsId() {
        TransitionEvidence evidence = new TransitionEvidence();
        Map<String, List<String>> run = liveRun(2);
        run.put("A", lines(HOST, connect("B", true), "peer connect: login=other id=99 offer=true"));
        feed(evidence, run);

        CheckpointFailure failure =
                assertThrows(CheckpointFailure.class, () -> evidence.verify(peers(2), Map.of()));

        assertEquals(
                "A(host): transitions: A(host) logged peer connect [B(joiner) offer=true, id 99"
                        + " offer=true], expected [B(joiner) offer=true]",
                failure.getMessage());
    }

    @Test
    void aLineWithNoLabelFailsTheCheckQuotingTheFirstFive() {
        TransitionEvidence evidence = new TransitionEvidence();
        feed(evidence, liveRun(2));
        List<String> unlabelled = new ArrayList<>();
        for (int i = 1; i <= 6; i++) {
            unlabelled.add("peer connect: login=p" + i + " id=" + i + " offer=true");
        }
        unlabelled.forEach(line -> evidence.accept(null, line));

        CheckpointFailure failure =
                assertThrows(CheckpointFailure.class, () -> evidence.verify(peers(2), Map.of()));

        assertEquals(
                "(no label): transitions: 6 line(s) carried no instance label, so no peer can be"
                        + " held to them: "
                        + unlabelled.subList(0, 5),
                failure.getMessage());
    }

    @Test
    void linesUnderAnotherLabelAndLinesOfAnotherKindAreIgnored() {
        TransitionEvidence evidence = new TransitionEvidence();
        feed(evidence, liveRun(2));
        // Provably no peer of this session's.
        evidence.accept("Z", "state entry: TERMINATED");
        evidence.accept("Z", connect("B", false));
        // Not lines the check reads, labelled or not.
        evidence.accept("A", "peer connected: local=7982 remote=330072 connected=true");
        evidence.accept(null, "tearing down session");

        assertDoesNotThrow(() -> evidence.verify(peers(2), Map.of()));
    }

    @Test
    void recordsTheLifecyclesLinesOnlyWhileAttached() {
        Logger lifecycle = LoggerFactory.getLogger(MockClientLifecycle.class);
        // Unique, so a line another test's lifecycle logs meanwhile cannot land under it.
        String label = "evidence-" + UUID.randomUUID();
        TransitionEvidence evidence = new TransitionEvidence();
        assertDoesNotThrow(evidence::detach, "a session closed before it ran never attached");

        try (MDC.MDCCloseable ignored = MDC.putCloseable(LoggingSetup.INSTANCE_MDC_KEY, label)) {
            lifecycle.info("state entry: {}", ClientState.CONNECTING);
            evidence.attach();
            try {
                lifecycle.info("state entry: {}", ClientState.IDLE);
            } finally {
                evidence.detach();
            }
            lifecycle.info("state entry: {}", ClientState.STARTING_GAME);
        }

        assertEquals(List.of("IDLE"), evidence.loggedStates(label));
    }

    /**
     * Each peer's lines as the live runs of 2026-09-28 logged them, by label: its role's states,
     * then its {@code peer connect} lines in the order the joiners arrived.
     *
     * @param peers the session's size, two to four
     * @return a mutable map, so a test can replace one peer's lines
     */
    private static Map<String, List<String>> liveRun(final int peers) {
        Map<String, List<String>> run = new HashMap<>();
        switch (peers) {
            case 2 -> {
                run.put("A", lines(HOST, connect("B", true)));
                run.put("B", JOINER);
            }
            case 3 -> {
                run.put("A", lines(HOST, connect("B", true), connect("C", true)));
                run.put("B", lines(JOINER, connect("C", false)));
                run.put("C", lines(JOINER, connect("B", true)));
            }
            case 4 -> {
                run.put(
                        "A",
                        lines(HOST, connect("B", true), connect("C", true), connect("D", true)));
                run.put("B", lines(JOINER, connect("C", false), connect("D", false)));
                run.put("C", lines(JOINER, connect("B", true), connect("D", false)));
                run.put("D", lines(JOINER, connect("B", true), connect("C", true)));
            }
            default -> throw new IllegalArgumentException("no live run at " + peers + " peers");
        }
        return run;
    }

    /**
     * Feeds a run's lines interleaved across peers, as one log interleaves its clients, each peer's
     * own lines in order.
     *
     * @param evidence the evidence to feed
     * @param run each peer's lines, by label
     */
    private static void feed(
            final TransitionEvidence evidence, final Map<String, List<String>> run) {
        Map<String, List<String>> byLabel = new TreeMap<>(run);
        int longest = byLabel.values().stream().mapToInt(List::size).max().orElse(0);
        for (int i = 0; i < longest; i++) {
            for (Map.Entry<String, List<String>> peer : byLabel.entrySet()) {
                if (i < peer.getValue().size()) {
                    evidence.accept(peer.getKey(), peer.getValue().get(i));
                }
            }
        }
    }

    /**
     * A session's peers, host first, named as a session names them.
     *
     * @param count the session's size, two to four
     * @return the peers in join order
     */
    private static List<TransitionEvidence.Peer> peers(final int count) {
        List<TransitionEvidence.Peer> peers = new ArrayList<>();
        for (int k = 0; k < count; k++) {
            String label = MultiPeerSession.labelFor(k);
            String role = k == 0 ? "(host)" : "(joiner)";
            peers.add(new TransitionEvidence.Peer(label + role, label, IDS[k]));
        }
        return peers;
    }

    private static List<String> states(final String... names) {
        List<String> lines = new ArrayList<>();
        for (String name : names) {
            lines.add(state(name));
        }
        return List.copyOf(lines);
    }

    private static String state(final String name) {
        return "state entry: " + name;
    }

    /**
     * The line a client logs on {@code ConnectToPeer}, in the log contract's shape.
     *
     * @param label the peer it names
     * @param offer whether the logging client makes the offer
     * @return the line
     */
    private static String connect(final String label, final boolean offer) {
        long id = IDS[label.charAt(0) - 'A'];
        return "peer connect: login=login-" + label + " id=" + id + " offer=" + offer;
    }

    private static List<String> lines(final List<String> first, final String... more) {
        List<String> lines = new ArrayList<>(first);
        lines.addAll(List.of(more));
        return lines;
    }
}
