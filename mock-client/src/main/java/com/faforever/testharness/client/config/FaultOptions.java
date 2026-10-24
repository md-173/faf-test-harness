package com.faforever.testharness.client.config;

import picocli.CommandLine.Option;
import picocli.CommandLine.ScopeType;

/**
 * The fault-injection options of {@link MockClientCli} (WBS-5.1, 5.2), the root fault flags that
 * {@code session}'s {@code --fault-peer} limits. Mixed into the root command; they build the {@link
 * FaultConfig} group of {@link MockClientConfig}, whose constructor checks their ranges.
 *
 * <p>Each field is named for its option in camel case, because the config-file key is the field
 * name.
 */
final class FaultOptions {

    /**
     * Milliseconds the ICE signal relay holds each relayed candidate before forwarding it
     * (WBS-5.1). Off by default: fault injection is something a test asks for explicitly.
     */
    @Option(
            names = "--ice-relay-delay-ms",
            scope = ScopeType.INHERIT,
            defaultValue = "0",
            description =
                    "Milliseconds to delay each relayed ICE candidate, in both directions, "
                            + "simulating slow negotiation (default: ${DEFAULT-VALUE}). Delays "
                            + "signalling only; the adapter's own connectivity checks are "
                            + "untouched. Candidates are delayed, never dropped or reordered.")
    private int iceRelayDelayMs;

    /**
     * Percentage of outbound peer datagrams the launched mock-game suppresses (WBS-5.1-fix, #322).
     *
     * <p>Passed through to mock-game as {@code --udp-drop-percent}. Without this, the flag existed
     * only on a hand-run mock-game: {@code MockGameLauncher.buildArgv} never emitted it and no
     * client-side option sourced a value, so an orchestrated run, the normal one, had no way to
     * turn the fault on at all.
     */
    @Option(
            names = "--mock-game-udp-drop-percent",
            scope = ScopeType.INHERIT,
            defaultValue = "0",
            description =
                    "Percentage of outbound peer datagrams the launched mock-game suppresses, "
                            + "drawn independently per peer per round (default: ${DEFAULT-VALUE}). "
                            + "Dropped datagrams still consume their sequence number, so the "
                            + "receiving peer sees them as gaps.")
    private int mockGameUdpDropPercent;

    /**
     * Seconds after entering a session before the launched mock-game halts itself, standing in for
     * a game crash (WBS-5.2). Off by default, like the other fault-injection knobs.
     *
     * <p>Named {@code --mock-game-*} rather than {@code --game-*} because that is the prefix every
     * flag targeting a subprocess already uses here: {@code --mock-game-binary-path}, {@code
     * --mock-game-launch-delay-seconds}, {@code --mock-game-udp-drop-percent}, and {@code
     * --ice-adapter-*} for the adapter. {@code --game-*} on this CLI already means the FAF lobby
     * game rather than the child process, as in {@code --game-join-password}.
     *
     * <p>Without this the fault existed only on a hand-run mock-game: {@code
     * MockGameLauncher.buildArgv} would never emit it and no client-side option would source a
     * value, so an orchestrated run, the normal one and the one where the client owns the child and
     * its crash recovery is worth exercising, could not turn the fault on at all.
     */
    @Option(
            names = "--mock-game-crash-after-seconds",
            scope = ScopeType.INHERIT,
            defaultValue = "-1",
            description =
                    "Seconds after the launched mock-game enters a session before it halts "
                            + "without a shutdown, simulating a game crash (default: "
                            + "${DEFAULT-VALUE}). Negative never crashes. The game's timer starts "
                            + "when a peer connects or its match goes live, whichever is first.")
    private int mockGameCrashAfterSeconds;

    /**
     * The fault group from these options.
     *
     * @return the group, unvalidated: {@link MockClientConfig} checks its ranges
     */
    FaultConfig toConfig() {
        return new FaultConfig(iceRelayDelayMs, mockGameUdpDropPercent, mockGameCrashAfterSeconds);
    }
}
