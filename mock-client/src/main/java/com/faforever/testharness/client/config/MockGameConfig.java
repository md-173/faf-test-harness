package com.faforever.testharness.client.config;

import java.nio.file.Path;

/**
 * The mock-game part of {@link MockClientConfig}: which {@code mock-game} to launch, and how long
 * it waits in the lobby before starting the match on its own.
 *
 * <p>Not mock-game's own {@code com.faforever.testharness.game.config.MockGameConfig}, which the
 * game parses from its argv, nor {@link MockGameSettings}, the slice one launch reads. The two
 * fault flags that reach the game, {@code --mock-game-udp-drop-percent} and {@code
 * --mock-game-crash-after-seconds}, are in {@link FaultConfig} with the other fault.
 *
 * @param binaryPath path to the mock-game executable
 * @param launchDelaySeconds how long mock-game sits in the lobby before starting the match on its
 *     own, passed straight through as its {@code --launch-delay-seconds} (WBS-4.3.1). Negative
 *     disables auto-launch, which is what a multi-peer session needs: the FAF server refuses a
 *     {@code game_join} once the host has reported {@code GameState Launching}, so a host on a
 *     timer makes itself unjoinable while the joiner is still booting
 */
public record MockGameConfig(Path binaryPath, int launchDelaySeconds) {}
