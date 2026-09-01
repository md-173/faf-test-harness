package com.faforever.testharness.client.process;

import com.faforever.testharness.client.config.IceAdapterSettings;
import com.faforever.testharness.client.config.MockClientConfig;
import java.util.Objects;

/**
 * The identity a single launch is performed under (WBS-3.1.2.9).
 *
 * <p>In an orchestrated session the lifecycle builds this from the lobby's own answers, the {@code
 * welcome.me} block for the player and {@code game_launch.uid} for the game, so both subprocesses
 * are told who this client actually is rather than what its config file guessed. The standalone
 * {@code launch-ice} and {@code launch-game} diagnostics have no lobby, so they build the same
 * record from config values instead.
 *
 * <p>The adapter half is the load-bearing one. faf-ice-adapter turns its {@code --login} and {@code
 * --id} straight into the GPGNet {@code CreateLobby(initMode, lobbyPort, login, id, 1)} frame it
 * sends the game (java-ice-adapter 3.3.14, GPGNetServer), which is the game's authoritative view of
 * its own identity. A stale config id therefore misinforms the game and keys adapter telemetry on a
 * player who is not playing.
 *
 * @param playerId lobby-assigned player id, from {@code welcome.me.id}
 * @param login lobby-assigned player login, from {@code welcome.me.login}
 * @param gameUid id of the game being launched, from {@code game_launch.uid}
 */
public record LaunchIdentity(int playerId, String login, int gameUid) {

    /**
     * Compact canonical constructor rejecting a null login, which would otherwise reach the adapter
     * argv as the four characters "null".
     */
    public LaunchIdentity {
        Objects.requireNonNull(login, "login");
    }

    /**
     * The identity a diagnostic launch runs under, assembled from config. This is the only place
     * {@code playerIdOverride} applies, because a session launch is bound to the identity the lobby
     * assigned.
     *
     * @param config the validated Mock Client configuration
     * @param defaultPlayerId player id to use when {@code playerIdOverride} is empty
     * @return the config-derived launch identity
     */
    static LaunchIdentity fromConfig(final MockClientConfig config, final int defaultPlayerId) {
        return new LaunchIdentity(
                config.playerIdOverride().orElse(defaultPlayerId),
                config.playerLogin(),
                config.iceAdapterGameId());
    }

    /**
     * The same diagnostic identity, assembled from the adapter-only settings the no-lobby {@code
     * ice-smoke} check runs on (WBS-3.1.4.3). Identical to {@link #fromConfig} for a settings
     * object narrowed from the same configuration — the two differ only in which fields the caller
     * had to supply to get here.
     *
     * @param settings the validated adapter settings
     * @param defaultPlayerId player id to use when {@code playerIdOverride} is empty
     * @return the settings-derived launch identity
     */
    static LaunchIdentity fromSettings(
            final IceAdapterSettings settings, final int defaultPlayerId) {
        return new LaunchIdentity(
                settings.playerIdOverride().orElse(defaultPlayerId),
                settings.playerLogin(),
                settings.gameId());
    }
}
