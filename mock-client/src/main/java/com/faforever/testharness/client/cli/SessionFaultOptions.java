package com.faforever.testharness.client.cli;

import com.faforever.testharness.client.config.MockClientCli;
import com.faforever.testharness.client.config.MockClientConfig;
import com.faforever.testharness.client.session.MultiPeerSession;
import java.util.ArrayList;
import java.util.List;
import java.util.OptionalInt;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.ToIntFunction;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.ParameterException;

/**
 * {@code session}'s per-peer fault options (WBS-5.1.2): which peers the root fault flags reach, and
 * one value per peer for each fault.
 *
 * <p>Without these, every root fault flag reaches every peer, because {@code session} builds each
 * peer's base config from the same root options. {@link MultiPeerSession} already runs each peer
 * with its own base's values, so this class only decides those values and never touches the session
 * itself.
 *
 * <p>Two ways to say which peer gets what, and a fault takes its values from one of them only:
 *
 * <ul>
 *   <li>{@value #FAULT_PEER_FLAG} limits the root {@code --ice-relay-delay-ms}, {@code
 *       --mock-game-udp-drop-percent} and {@code --mock-game-crash-after-seconds} to the named
 *       peers. Unset, they reach every peer, as they always have.
 *   <li>A per-peer list gives each peer its own value, host first, one value per peer.
 * </ul>
 *
 * <p>A crash set there is not expected: a peer whose game dies fails the session, as it always has.
 * {@value #CRASH_PEER_FLAG} is the one expected crash (WBS-5.2.1): it names a joiner, and the
 * session times that crash itself and plays on through it.
 */
final class SessionFaultOptions {

    /** Limits the root fault flags to the named peers. */
    static final String FAULT_PEER_FLAG = "--fault-peer";

    /** Names the joiner whose deliberate crash the session plays on through. */
    static final String CRASH_PEER_FLAG = "--crash-peer";

    /** One ICE relay delay per peer. */
    static final String PEER_RELAY_DELAY_FLAG = "--peer-ice-relay-delay-ms";

    /** One outbound drop percentage per peer. */
    static final String PEER_DROP_FLAG = "--peer-mock-game-udp-drop-percent";

    /** One crash delay per peer. */
    static final String PEER_CRASH_FLAG = "--peer-mock-game-crash-after-seconds";

    /** The highest drop percentage, as the root flag allows. */
    private static final int MAX_DROP_PERCENT = 100;

    /** The peers the root fault flags reach, by instance label; empty for every peer. */
    @Option(
            names = FAULT_PEER_FLAG,
            split = ",",
            paramLabel = "<label>",
            description =
                    "Limit --ice-relay-delay-ms, --mock-game-udp-drop-percent and "
                            + "--mock-game-crash-after-seconds to these peers, by label: A is the "
                            + "host, B the first joiner, and so on. Repeat the flag or separate "
                            + "labels with commas. Unset, those flags reach every peer.")
    private List<String> faultPeers = new ArrayList<>();

    /** The joiner whose game crashes deliberately after launch, by label; {@code null} for none. */
    @Option(
            names = CRASH_PEER_FLAG,
            paramLabel = "<label>",
            description =
                    "Crash this joiner's game after the match has launched, and pass only if the "
                            + "survivors play on (B is the first joiner). The session launches the "
                            + "host and times the crash itself, so the run takes a few minutes. "
                            + "Cannot be combined with any other crash.")
    private String crashPeer;

    /** One ICE relay delay per peer, host first. */
    @Option(
            names = PEER_RELAY_DELAY_FLAG,
            split = ",",
            paramLabel = "<ms>",
            description =
                    "Each peer's --ice-relay-delay-ms, host first, exactly one value per peer, "
                            + "instead of the root flag.")
    private List<Integer> peerRelayDelayMs = new ArrayList<>();

    /** One outbound drop percentage per peer, host first. */
    @Option(
            names = PEER_DROP_FLAG,
            split = ",",
            paramLabel = "<percent>",
            description =
                    "Each peer's --mock-game-udp-drop-percent, host first, exactly one value per "
                            + "peer, instead of the root flag.")
    private List<Integer> peerDropPercent = new ArrayList<>();

    /** One crash delay per peer, host first. */
    @Option(
            names = PEER_CRASH_FLAG,
            split = ",",
            paramLabel = "<seconds>",
            description =
                    "Each peer's --mock-game-crash-after-seconds, host first, exactly one value "
                            + "per peer, instead of the root flag; negative never crashes. Give it "
                            + "as "
                            + PEER_CRASH_FLAG
                            + "=-1,-1,40 so a leading negative is not read as an option. A crash "
                            + "set here fails the session.")
    private List<Integer> peerCrashSeconds = new ArrayList<>();

    /**
     * One fault as the options describe it.
     *
     * @param rootFlag the root option
     * @param listFlag the per-peer option
     * @param off the value that injects nothing
     * @param min the lowest value the per-peer option accepts
     * @param max the highest value the per-peer option accepts
     * @param read the fault's value in a config
     * @param list the per-peer values, empty when the list is not given
     */
    private record Fault(
            String rootFlag,
            String listFlag,
            int off,
            int min,
            int max,
            ToIntFunction<MockClientConfig> read,
            List<Integer> list) {

        /**
         * This fault's value for one peer.
         *
         * @param root the root value
         * @param peer the peer's index
         * @param targeted whether the root flags reach that peer
         * @return the list's value when given, else the root value or the off value
         */
        int valueFor(final int root, final int peer, final boolean targeted) {
            if (!list.isEmpty()) {
                return list.get(peer);
            }
            return targeted ? root : off;
        }
    }

    /**
     * Gives each peer its fault values, refusing an invocation that does not say one thing clearly.
     *
     * @param spec the {@code session} command's spec, for errors and option layers
     * @param bases one validated base per peer, host first, all carrying the root fault values
     * @return the bases with each peer's own fault values
     * @throws ParameterException for a list whose length is not the peer count, a value out of
     *     range, a list given with its root flag, or a {@value #FAULT_PEER_FLAG} that names no peer
     *     or reaches no fault
     */
    List<MockClientConfig> apply(final CommandSpec spec, final List<MockClientConfig> bases) {
        int peers = bases.size();
        MockClientConfig root = bases.get(0);
        List<Fault> faults =
                List.of(
                        new Fault(
                                "--ice-relay-delay-ms",
                                PEER_RELAY_DELAY_FLAG,
                                0,
                                0,
                                Integer.MAX_VALUE,
                                MockClientConfig::iceRelayDelayMs,
                                peerRelayDelayMs),
                        new Fault(
                                "--mock-game-udp-drop-percent",
                                PEER_DROP_FLAG,
                                0,
                                0,
                                MAX_DROP_PERCENT,
                                MockClientConfig::mockGameUdpDropPercent,
                                peerDropPercent),
                        new Fault(
                                "--mock-game-crash-after-seconds",
                                PEER_CRASH_FLAG,
                                -1,
                                Integer.MIN_VALUE,
                                Integer.MAX_VALUE,
                                MockClientConfig::mockGameCrashAfterSeconds,
                                peerCrashSeconds));
        boolean anyRootFault = false;
        for (Fault fault : faults) {
            checkList(spec, fault, peers);
            boolean rootOn = isOn(fault, fault.read().applyAsInt(root));
            if (rootOn && !fault.list().isEmpty()) {
                throw new ParameterException(
                        spec.commandLine(),
                        fault.listFlag()
                                + " and "
                                + fault.rootFlag()
                                + " both set a value ("
                                + fault.rootFlag()
                                + " "
                                + MockClientCli.layerDescription(spec, fault.rootFlag())
                                + "); give one");
            }
            anyRootFault |= rootOn;
        }
        if (crashPeer(spec, peers).isPresent()) {
            boolean anyCrash =
                    root.mockGameCrashAfterSeconds() >= 0
                            || peerCrashSeconds.stream().anyMatch(seconds -> seconds >= 0);
            if (anyCrash) {
                throw new ParameterException(
                        spec.commandLine(),
                        CRASH_PEER_FLAG
                                + " times its own crash and cannot share a run with "
                                + "--mock-game-crash-after-seconds or "
                                + PEER_CRASH_FLAG
                                + ", whose crash would fail the session it is meant to pass");
            }
        }
        Set<Integer> targets = targets(spec, peers);
        if (!targets.isEmpty() && !anyRootFault) {
            throw new ParameterException(
                    spec.commandLine(),
                    FAULT_PEER_FLAG
                            + " limits the root fault flags, but none of --ice-relay-delay-ms, "
                            + "--mock-game-udp-drop-percent or --mock-game-crash-after-seconds is"
                            + " set");
        }
        List<MockClientConfig> faulted = new ArrayList<>();
        for (int i = 0; i < peers; i++) {
            MockClientConfig base = bases.get(i);
            boolean targeted = targets.isEmpty() || targets.contains(i);
            int[] values = new int[faults.size()];
            for (int f = 0; f < faults.size(); f++) {
                Fault fault = faults.get(f);
                values[f] = fault.valueFor(fault.read().applyAsInt(base), i, targeted);
            }
            faulted.add(MultiPeerSession.withFaults(base, values[0], values[1], values[2]));
        }
        return faulted;
    }

    /**
     * The joiner {@value #CRASH_PEER_FLAG} names, by index.
     *
     * @param spec the command's spec
     * @param peers the peer count
     * @return its index, or empty when the flag is not given
     * @throws ParameterException for a label that is not one of this session's joiners
     */
    OptionalInt crashPeer(final CommandSpec spec, final int peers) {
        if (crashPeer == null) {
            return OptionalInt.empty();
        }
        int index = indexOf(spec, CRASH_PEER_FLAG, crashPeer, peers);
        if (index == 0) {
            throw new ParameterException(
                    spec.commandLine(),
                    CRASH_PEER_FLAG
                            + " must name a joiner, B to "
                            + MultiPeerSession.labelFor(peers - 1)
                            + "; crashing the host is out of scope");
        }
        return OptionalInt.of(index);
    }

    /**
     * Each peer's fault values, for the run's log.
     *
     * @param bases the bases {@link #apply} returned
     * @return one entry per peer that has a fault, or empty when none does
     */
    static List<String> describe(final List<MockClientConfig> bases) {
        List<String> described = new ArrayList<>();
        for (int i = 0; i < bases.size(); i++) {
            MockClientConfig base = bases.get(i);
            List<String> parts = new ArrayList<>();
            if (base.iceRelayDelayMs() != 0) {
                parts.add("ICE relay delay " + base.iceRelayDelayMs() + " ms");
            }
            if (base.mockGameUdpDropPercent() != 0) {
                parts.add("UDP drop " + base.mockGameUdpDropPercent() + "%");
            }
            if (base.mockGameCrashAfterSeconds() >= 0) {
                parts.add("crash after " + base.mockGameCrashAfterSeconds() + " s");
            }
            if (!parts.isEmpty()) {
                described.add(MultiPeerSession.labelFor(i) + ": " + String.join(", ", parts));
            }
        }
        return described;
    }

    /**
     * Refuses a per-peer list with the wrong number of values or a value out of range. An exact
     * count rather than "at least", since a value misplaced by one position would otherwise land on
     * the wrong peer without a word.
     *
     * @param spec the command's spec
     * @param fault the fault
     * @param peers the peer count
     */
    private static void checkList(final CommandSpec spec, final Fault fault, final int peers) {
        List<Integer> list = fault.list();
        if (list.isEmpty()) {
            return;
        }
        if (list.size() != peers) {
            throw new ParameterException(
                    spec.commandLine(),
                    fault.listFlag()
                            + " needs exactly one value per peer, host first: --peers is "
                            + peers
                            + " but "
                            + list.size()
                            + " given");
        }
        for (int i = 0; i < peers; i++) {
            int value = list.get(i);
            if (value < fault.min() || value > fault.max()) {
                throw new ParameterException(
                        spec.commandLine(),
                        fault.listFlag()
                                + " value for peer "
                                + MultiPeerSession.labelFor(i)
                                + " must be "
                                + (fault.max() == Integer.MAX_VALUE
                                        ? "at least " + fault.min()
                                        : "between " + fault.min() + " and " + fault.max())
                                + "; got "
                                + value);
            }
        }
    }

    /**
     * Whether a fault value injects anything. A crash is on at zero or above; the others above
     * zero.
     *
     * @param fault the fault
     * @param value its value
     * @return {@code true} if the value injects a fault
     */
    private static boolean isOn(final Fault fault, final int value) {
        return fault.off() < 0 ? value >= 0 : value != fault.off();
    }

    /**
     * The peers {@value #FAULT_PEER_FLAG} names, by index.
     *
     * @param spec the command's spec
     * @param peers the peer count
     * @return their indexes, empty when the flag is not given
     * @throws ParameterException for a label that is not one of this session's
     */
    private Set<Integer> targets(final CommandSpec spec, final int peers) {
        Set<Integer> indexes = new TreeSet<>();
        for (String label : faultPeers) {
            indexes.add(indexOf(spec, FAULT_PEER_FLAG, label, peers));
        }
        return indexes;
    }

    /**
     * A peer's index from its instance label.
     *
     * @param spec the command's spec
     * @param flag the option the label came from
     * @param label the label, e.g. {@code C}
     * @param peers the peer count
     * @return the index, 0 for the host
     * @throws ParameterException if the label is not one of this session's
     */
    static int indexOf(
            final CommandSpec spec, final String flag, final String label, final int peers) {
        String trimmed = label.trim();
        if (trimmed.length() == 1) {
            int index = trimmed.charAt(0) - 'A';
            if (index >= 0 && index < peers) {
                return index;
            }
        }
        throw new ParameterException(
                spec.commandLine(),
                flag
                        + " '"
                        + label
                        + "' is not a peer of this session; with --peers "
                        + peers
                        + " the labels are A to "
                        + MultiPeerSession.labelFor(peers - 1));
    }
}
