package com.faforever.testharness.client.config;

import java.time.Duration;

/**
 * The fault-injection part of {@link MockClientConfig} (WBS-5.1, 5.2): the three root fault flags
 * that {@code session}'s {@code --fault-peer} limits, kept together because a session sets them per
 * peer as one unit. Their ranges are checked by {@link MockClientConfig}'s compact constructor.
 *
 * <p>The components keep the flags' full names, since the three share no prefix: one fault is the
 * client's own ICE signal relay, and the other two are passed to mock-game.
 *
 * @param iceRelayDelayMs how long the ICE signal relay holds each relayed candidate before
 *     forwarding it, in milliseconds; {@code 0} (the default) forwards inline. The delayed-ICE half
 *     of WBS-5.1's fault injection. Read through {@link #iceRelayDelay()} rather than directly
 * @param mockGameUdpDropPercent percentage of outbound peer datagrams the launched mock-game
 *     suppresses, passed straight through as its {@code --udp-drop-percent} (WBS-5.1-fix, #322).
 *     {@code 0} (the default) drops nothing. The lossy-link half of WBS-5.1's fault injection, and
 *     the only way to reach it in an orchestrated run: mock-game's own flag is otherwise settable
 *     only by hand
 * @param mockGameCrashAfterSeconds how long after entering a session the launched mock-game halts
 *     itself without an orderly shutdown, standing in for a game crash (WBS-5.2); negative, the
 *     default, never crashes. Passed through to mock-game as {@code --crash-after-seconds}, and
 *     emitted only when set so a run that asks for no fault produces the argv it always did
 */
public record FaultConfig(
        int iceRelayDelayMs, int mockGameUdpDropPercent, int mockGameCrashAfterSeconds) {

    /**
     * The ICE relay's forward delay as {@link com.faforever.testharness.client.ice.IceSignalRelay}
     * wants it (WBS-5.1).
     *
     * @return the delay to hold each relayed candidate for; {@link Duration#ZERO} forwards inline
     */
    public Duration iceRelayDelay() {
        return Duration.ofMillis(iceRelayDelayMs);
    }
}
