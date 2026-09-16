package com.faforever.testharness.client.session;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.faforever.testharness.game.net.GameTrafficSession;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The game traffic part of a session's verdict (WBS-4.2.1), checked without a lobby: which progress
 * lines prove a direction, and how the traffic wait fails when they never arrive.
 */
final class TrafficEvidenceTest {

    private static final long HOST_ID = 7982;

    private static final long JOINER_ID = 330072;

    /** Short enough to keep the failure cases fast, long enough for several polls. */
    private static final Duration SHORT_WAIT = Duration.ofMillis(600);

    @Test
    void twoAdvancingLinesWithThreeDatagramsProveADirection() {
        TrafficEvidence evidence = new TrafficEvidence();

        evidence.accept(line(JOINER_ID, HOST_ID, 1, 4));
        assertFalse(evidence.proven(JOINER_ID, HOST_ID), "one line proves a count, not progress");

        evidence.accept(line(JOINER_ID, HOST_ID, 11, 14));
        assertTrue(evidence.proven(JOINER_ID, HOST_ID));
        assertFalse(evidence.proven(HOST_ID, JOINER_ID), "each direction is proven on its own");
    }

    @Test
    void parsesTheLineMockGameActuallyLogs() {
        TrafficEvidence evidence = new TrafficEvidence();

        evidence.accept(GameTrafficSession.progressLine(JOINER_ID, HOST_ID, 4, 9, 2));
        evidence.accept(GameTrafficSession.progressLine(JOINER_ID, HOST_ID, 14, 19, 2));

        assertTrue(evidence.proven(JOINER_ID, HOST_ID), evidence.seen(JOINER_ID, HOST_ID));
    }

    @Test
    void aStalledSequenceDoesNotProveADirection() {
        TrafficEvidence evidence = new TrafficEvidence();

        evidence.accept(line(JOINER_ID, HOST_ID, 5, 9));
        evidence.accept(line(JOINER_ID, HOST_ID, 5, 9));

        assertFalse(evidence.proven(JOINER_ID, HOST_ID));
        assertTrue(
                evidence.seen(JOINER_ID, HOST_ID).endsWith("highest sequence 9 (first 9)"),
                evidence.seen(JOINER_ID, HOST_ID));
    }

    @Test
    void otherLinesAreIgnored() {
        TrafficEvidence evidence = new TrafficEvidence();

        evidence.accept("peer connected: local=330072 remote=7982 connected=true");
        evidence.accept("game UDP receiver stopped");

        assertTrue(evidence.seen(JOINER_ID, HOST_ID).equals("nothing"));
    }

    @Test
    void noTrafficFailsTheSessionAtTheTrafficStageNamingEveryReceiver() {
        TrafficEvidence evidence = new TrafficEvidence();

        CheckpointFailure failure =
                assertThrows(CheckpointFailure.class, () -> awaitTraffic(evidence, List.of()));

        assertTrue(
                failure.getMessage()
                        .startsWith("A(host),B(joiner): traffic: no two-way game traffic"),
                failure.getMessage());
        assertTrue(
                failure.getMessage().contains("A(host) from B(joiner): nothing;"),
                failure.getMessage());
        assertTrue(
                failure.getMessage().contains("B(joiner) from A(host): nothing;"),
                failure.getMessage());
    }

    @Test
    void oneWayTrafficFailsNamingTheReceiverStillWaiting() {
        TrafficEvidence evidence = new TrafficEvidence();
        evidence.accept(line(JOINER_ID, HOST_ID, 3, 6));
        evidence.accept(line(JOINER_ID, HOST_ID, 13, 16));

        CheckpointFailure failure =
                assertThrows(CheckpointFailure.class, () -> awaitTraffic(evidence, List.of()));

        assertTrue(failure.getMessage().startsWith("A(host): traffic:"), failure.getMessage());
    }

    @Test
    void twoWayTrafficPasses() {
        TrafficEvidence evidence = new TrafficEvidence();
        evidence.accept(line(JOINER_ID, HOST_ID, 3, 6));
        evidence.accept(line(JOINER_ID, HOST_ID, 13, 16));
        evidence.accept(line(HOST_ID, JOINER_ID, 4, 7));
        evidence.accept(line(HOST_ID, JOINER_ID, 14, 17));

        assertDoesNotThrow(() -> awaitTraffic(evidence, List.of()));
    }

    @Test
    void aTerminatedPeerFailsTheTrafficWaitWithoutWaitingOutItsBudget() {
        TrafficEvidence evidence = new TrafficEvidence();
        long started = System.nanoTime();

        CheckpointFailure failure =
                assertThrows(
                        CheckpointFailure.class,
                        () ->
                                MultiPeerSession.awaitTraffic(
                                        evidence,
                                        names(),
                                        System.nanoTime() + Duration.ofMinutes(1).toNanos(),
                                        "PT1M",
                                        () -> List.of("B(joiner)")));

        assertTrue(
                failure.getMessage().startsWith("B(joiner): traffic: session ended"),
                failure.getMessage());
        assertTrue(Duration.ofNanos(System.nanoTime() - started).toSeconds() < 5);
    }

    private static void awaitTraffic(final TrafficEvidence evidence, final List<String> ended)
            throws InterruptedException {
        MultiPeerSession.awaitTraffic(
                evidence,
                names(),
                System.nanoTime() + SHORT_WAIT.toNanos(),
                SHORT_WAIT.toString(),
                () -> ended);
    }

    private static Map<Long, String> names() {
        Map<Long, String> names = new LinkedHashMap<>();
        names.put(HOST_ID, "A(host)");
        names.put(JOINER_ID, "B(joiner)");
        return names;
    }

    private static String line(
            final long receiver, final long sender, final long datagrams, final long sequence) {
        return GameTrafficSession.progressLine(receiver, sender, datagrams, sequence, 0);
    }
}
