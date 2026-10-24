package com.faforever.testharness.client.config;

import java.nio.file.Path;

/**
 * The ICE adapter part of {@link MockClientConfig}: which {@code faf-ice-adapter} to launch, the
 * three local ports it serves, and the game id it is launched for.
 *
 * <p>Not {@link IceAdapterSettings}, the slice one adapter launch reads, which adds the player
 * identity and the logging settings and is built from this group by {@link
 * IceAdapterSettings#from(MockClientConfig)}.
 *
 * @param binaryPath path to the faf-ice-adapter executable
 * @param rpcPort local JSON-RPC port exposed by faf-ice-adapter
 * @param gpgNetPort local GPGNet port exposed by faf-ice-adapter
 * @param lobbyPort local UDP port the game lobby uses for game traffic; passed to faf-ice-adapter
 *     as {@code --lobby-port}
 * @param gameId game ID passed to faf-ice-adapter as {@code --game-id} (required by the adapter); a
 *     default for the {@code launch-ice} diagnostic, overridden by the lobby {@code
 *     game_launch.uid} during a full {@code run} session
 */
public record IceAdapterConfig(
        Path binaryPath, int rpcPort, int gpgNetPort, int lobbyPort, int gameId) {}
