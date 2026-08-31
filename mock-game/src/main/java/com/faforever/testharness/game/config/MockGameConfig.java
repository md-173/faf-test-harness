package com.faforever.testharness.game.config;

import java.time.Duration;
import java.util.Map;
import java.util.Optional;

/**
 * Immutable launch configuration for one mock-game instance (WBS-3.2.1.1), parsed from the argv the
 * Mock Client's {@code MockGameLauncher} (WBS-3.1.2.3) passes at spawn
 * (subprocess-orchestration-spec.md §2.8). The launcher and {@link MockGameCli} are two ends of one
 * contract: the argument list changes only when both change together.
 *
 * <p>Deliberately smaller than the client's {@code MockClientConfig}: the game has exactly one
 * caller (the launcher), so there are no environment-variable or config-file sources. Every field
 * that states a <em>session fact</em> — the ports, the identity, the game uid — comes from a
 * required argument, because guessing one is always wrong. {@code launchDelaySeconds} is the one
 * exception and is defaulted; see {@link MockGameCli} for why.
 *
 * @param gpgNetPort TCP port of the ICE adapter's local GPGNet server the game connects to; must
 *     match the port the adapter was started with
 * @param lobbyPort UDP port the game binds for lobby/peer traffic; must match the adapter's lobby
 *     port
 * @param playerId FAF player id of the owning client's session
 * @param playerLogin FAF player login of the owning client's session
 * @param gameUid id of the game being played, from the lobby's {@code game_launch.uid}. Zero means
 *     no orchestrated session, which is what the standalone {@code launch-game} diagnostic passes.
 *     A mock adaptation, since the real client hands Forged Alliance its game uid inside the {@code
 *     /savereplay} URL and the {@code /log} filename rather than as its own flag
 * @param gameOptions a set of game options to be sent by the host. Ignored if this is a joiner. Can
 *     be empty.
 * @param launchDelaySeconds how long the game sits in the lobby before starting the match on its
 *     own; negative means it never does, and the match is then launched only on an explicit {@code
 *     launchMatch()}. Read through {@link #launchDelay()} rather than directly
 */
public record MockGameConfig(
        int gpgNetPort,
        int lobbyPort,
        int playerId,
        String playerLogin,
        int gameUid,
        Map<String, String> gameOptions,
        int launchDelaySeconds) {

    /**
     * The auto-launch delay as the lifecycle wants it: a duration to arm the timer with, or empty
     * when this game must not launch on its own.
     *
     * <p>Empty is what a multi-peer session needs (WBS-4.3.1). The FAF server only accepts a {@code
     * game_join} while the game is in {@code GameState.LOBBY} and moves it out of that state the
     * moment the <em>host</em> reports {@code GameState Launching} (faf-server {@code
     * lobbyconnection.command_game_join} and {@code gameconnection._handle_game_state}), so a host
     * that auto-launches on a timer makes itself unjoinable while the joiner is still booting.
     *
     * @return the delay to arm the launch timer with, or empty to never auto-launch
     */
    public Optional<Duration> launchDelay() {
        return launchDelaySeconds < 0
                ? Optional.empty()
                : Optional.of(Duration.ofSeconds(launchDelaySeconds));
    }
}
