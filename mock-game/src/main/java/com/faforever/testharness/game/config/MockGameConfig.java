package com.faforever.testharness.game.config;

import java.util.Map;

/**
 * Immutable launch configuration for one mock-game instance (WBS-3.2.1.1), parsed from the argv the
 * Mock Client's {@code MockGameLauncher} (WBS-3.1.2.3) passes at spawn
 * (subprocess-orchestration-spec.md §2.8). The launcher and {@link MockGameCli} are two ends of one
 * contract: the argument list changes only when both change together.
 *
 * <p>Deliberately smaller than the client's {@code MockClientConfig}: the game has exactly one
 * caller (the launcher), so there are no environment-variable or config-file sources and no
 * defaults — every field comes from a required argument.
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
 */
public record MockGameConfig(
        int gpgNetPort,
        int lobbyPort,
        int playerId,
        String playerLogin,
        int gameUid,
        Map<String, String> gameOptions) {}
