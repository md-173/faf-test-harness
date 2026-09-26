package com.faforever.testharness.client.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.faforever.testharness.client.config.ConfigLoader;
import com.faforever.testharness.client.config.MockClientCli;
import com.faforever.testharness.client.config.MockClientConfig;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import picocli.CommandLine;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.ParameterException;
import picocli.CommandLine.ParseResult;

/**
 * Which fault values each peer of a {@code session} gets (WBS-5.1.2), decided from the parsed
 * options without starting anything.
 */
final class SessionFaultOptionsTest {

    /** Peers in every case here: a host and two joiners, labels A to C. */
    private static final int PEERS = 3;

    @Test
    void withNoFaultOptionsTheRootFlagsReachEveryPeer() {
        List<MockClientConfig> peers = resolve(Map.of(), "--mock-game-udp-drop-percent=30");

        assertEquals(List.of(30, 30, 30), drops(peers));
    }

    @Test
    void faultPeerLimitsEveryRootFaultToTheNamedPeers() {
        List<MockClientConfig> peers =
                resolve(
                        Map.of(),
                        "--mock-game-udp-drop-percent=30",
                        "--ice-relay-delay-ms=200",
                        "--mock-game-crash-after-seconds=40",
                        "--fault-peer=C");

        assertEquals(List.of(0, 0, 30), drops(peers));
        assertEquals(List.of(0, 0, 200), delays(peers));
        assertEquals(List.of(-1, -1, 40), crashes(peers));
    }

    @Test
    void faultPeerTakesSeveralLabels() {
        List<MockClientConfig> peers =
                resolve(Map.of(), "--ice-relay-delay-ms=200", "--fault-peer=A,C");

        assertEquals(List.of(200, 0, 200), delays(peers));
    }

    @Test
    void aListGivesEachPeerItsOwnValue() {
        List<MockClientConfig> peers =
                resolve(
                        Map.of(),
                        "--peer-mock-game-udp-drop-percent=0,0,100",
                        "--peer-ice-relay-delay-ms=0,500,0",
                        "--peer-mock-game-crash-after-seconds=-1,40,-1");

        assertEquals(List.of(0, 0, 100), drops(peers));
        assertEquals(List.of(0, 500, 0), delays(peers));
        assertEquals(List.of(-1, 40, -1), crashes(peers));
    }

    @Test
    void aListAndATargetedRootFlagCombineWhenTheyAreDifferentFaults() {
        List<MockClientConfig> peers =
                resolve(
                        Map.of(),
                        "--ice-relay-delay-ms=300",
                        "--fault-peer=B",
                        "--peer-mock-game-udp-drop-percent=0,0,50");

        assertEquals(List.of(0, 300, 0), delays(peers));
        assertEquals(List.of(0, 0, 50), drops(peers));
    }

    @Test
    void aListWithTheWrongNumberOfValuesIsRefused() {
        ParameterException e =
                assertThrows(
                        ParameterException.class,
                        () -> resolve(Map.of(), "--peer-mock-game-udp-drop-percent=0,100"));

        assertTrue(
                e.getMessage()
                        .contains(
                                "--peer-mock-game-udp-drop-percent needs exactly one value per"
                                        + " peer"),
                e.getMessage());
    }

    @Test
    void aListValueOutOfRangeIsRefusedNamingThePeer() {
        ParameterException drop =
                assertThrows(
                        ParameterException.class,
                        () -> resolve(Map.of(), "--peer-mock-game-udp-drop-percent=0,0,101"));
        ParameterException delay =
                assertThrows(
                        ParameterException.class,
                        () -> resolve(Map.of(), "--peer-ice-relay-delay-ms=0,-5,0"));

        assertTrue(drop.getMessage().contains("value for peer C"), drop.getMessage());
        assertTrue(delay.getMessage().contains("value for peer B"), delay.getMessage());
    }

    @Test
    void aListGivenWithItsRootFlagIsRefusedNamingTheRootFlagsLayer() {
        ParameterException e =
                assertThrows(
                        ParameterException.class,
                        () ->
                                resolve(
                                        Map.of("FAF_MOCK_CLIENT_MOCK_GAME_UDP_DROP_PERCENT", "25"),
                                        "--peer-mock-game-udp-drop-percent=0,0,50"));

        assertTrue(
                e.getMessage().contains("in FAF_MOCK_CLIENT_* environment variables"),
                e.getMessage());
    }

    @Test
    void faultPeerNamingNoPeerOfThisSessionIsRefused() {
        ParameterException e =
                assertThrows(
                        ParameterException.class,
                        () -> resolve(Map.of(), "--ice-relay-delay-ms=200", "--fault-peer=D"));

        assertTrue(e.getMessage().contains("the labels are A to C"), e.getMessage());
    }

    @Test
    void faultPeerWithNoRootFaultIsRefused() {
        ParameterException e =
                assertThrows(ParameterException.class, () -> resolve(Map.of(), "--fault-peer=C"));

        assertTrue(e.getMessage().contains("none of --ice-relay-delay-ms"), e.getMessage());
    }

    @Test
    void describeNamesOnlyThePeersWithAFault() {
        List<MockClientConfig> peers =
                resolve(Map.of(), "--peer-mock-game-udp-drop-percent=0,0,50");

        assertEquals(List.of("C: UDP drop 50%"), SessionFaultOptions.describe(peers));
    }

    /**
     * Parses a {@code session} invocation and resolves its fault options for {@value #PEERS} peers
     * whose bases carry the root values, as {@code SessionCommand} builds them.
     *
     * @param env the environment layer
     * @param args root fault flags and {@code session} fault options
     * @return each peer's base after the options were applied
     */
    private static List<MockClientConfig> resolve(
            final Map<String, String> env, final String... args) {
        List<String> argv = new ArrayList<>(List.of(CliTestFixtures.withSubcommand("session")));
        argv.addAll(List.of(args));
        String[] array = argv.toArray(new String[0]);
        CommandLine root = ConfigLoader.newCommandLine(array, env);
        ParseResult parsed = root.parseArgs(array);
        CommandSpec session = parsed.subcommand().commandSpec();
        SessionFaultOptions options =
                (SessionFaultOptions) session.mixins().get("faults").userObject();
        MockClientConfig base =
                ((MockClientCli) root.getCommand()).toValidatedConfig(root.getCommandSpec());
        return options.apply(session, Collections.nCopies(PEERS, base));
    }

    private static List<Integer> drops(final List<MockClientConfig> peers) {
        return peers.stream().map(MockClientConfig::mockGameUdpDropPercent).toList();
    }

    private static List<Integer> delays(final List<MockClientConfig> peers) {
        return peers.stream().map(MockClientConfig::iceRelayDelayMs).toList();
    }

    private static List<Integer> crashes(final List<MockClientConfig> peers) {
        return peers.stream().map(MockClientConfig::mockGameCrashAfterSeconds).toList();
    }
}
