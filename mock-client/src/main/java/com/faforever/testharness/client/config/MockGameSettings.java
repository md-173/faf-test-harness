package com.faforever.testharness.client.config;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;

/**
 * The slice of Mock Client configuration a no-lobby {@code mock-game} launch needs: what to launch,
 * on which ports, under which identity, with which game options, and how to log (WBS-3.1.5.2-fix,
 * #308).
 *
 * <p>The sibling of {@link IceAdapterSettings}, and it exists for the same reason: {@code
 * launch-game} never opens a lobby connection, so demanding the seven lobby/OAuth fields and {@code
 * --unique-id} that {@link MockClientConfig} mandates meant every one of them was a placeholder
 * invented to get past validation. The runbook worked around it by printing an eight-flag
 * invocation with {@code --oauth-refresh-token-file=dummy-unused-by-launch-ice} in it, which is the
 * clearest possible statement that the validation was in the wrong place.
 *
 * <p>Every field here is also a {@link MockClientConfig} field, and {@link #from(MockClientConfig)}
 * is the narrowing view the lobby-driven path uses, so both routes launch mock-game identically.
 *
 * <p>Validation here covers only what would otherwise reach {@code mock-game} as a nonsense
 * argument. Value-range checks on operator input belong to the CLI layer that reads it — see {@link
 * MockClientCli#toValidatedGameSettings} — so this record stays a faithful narrowing of an
 * already-validated {@link MockClientConfig} and never rejects a configuration the full-session
 * path accepts.
 *
 * @param binaryPath path to the {@code mock-game} executable
 * @param gpgNetPort the adapter's GPGNet TCP port, which mock-game connects out to
 * @param lobbyPort the UDP port mock-game announces as its lobby port
 * @param gameUid game uid passed as {@code --game-uid}; {@code 0} means no orchestrated session
 * @param playerIdOverride optional player id for deterministic local testing; empty means the
 *     caller's default applies
 * @param playerLogin player login passed as {@code --player-login}
 * @param launchDelaySeconds auto-launch delay passed as {@code --launch-delay-seconds}; negative
 *     never auto-launches
 * @param gameOptions game options a host forwards as {@code --game-option}; may be empty
 * @param logLevel log level handed to the mock-game child through {@code LOG_LEVEL}
 * @param logFile optional JSONL log file for the harness's own records
 */
public record MockGameSettings(
        Path binaryPath,
        int gpgNetPort,
        int lobbyPort,
        int gameUid,
        OptionalInt playerIdOverride,
        String playerLogin,
        int launchDelaySeconds,
        Map<String, String> gameOptions,
        String logLevel,
        Optional<Path> logFile) {

    /**
     * Rejects only what {@code mock-game} could not be launched with at all. Ranges and
     * relationships between operator-supplied values are the CLI's business.
     *
     * @throws NullPointerException if a required reference is {@code null}
     * @throws IllegalArgumentException if {@code playerLogin} is blank
     */
    public MockGameSettings {
        Objects.requireNonNull(binaryPath, "binaryPath");
        Objects.requireNonNull(playerIdOverride, "playerIdOverride");
        Objects.requireNonNull(logFile, "logFile");
        Objects.requireNonNull(logLevel, "logLevel");
        Objects.requireNonNull(gameOptions, "gameOptions");
        gameOptions = Map.copyOf(new LinkedHashMap<>(gameOptions));
        if (playerLogin == null || playerLogin.isBlank()) {
            throw new IllegalArgumentException(
                    "playerLogin must not be blank: it is passed to mock-game as --player-login");
        }
    }

    /**
     * Narrows a full configuration to the fields a mock-game launch reads.
     *
     * <p>The game options come from {@code hostConfig} when one is configured, matching what the
     * orchestrated launch forwards: a joiner has none to send.
     *
     * @param config the validated full configuration
     * @return the mock-game slice of it
     */
    public static MockGameSettings from(final MockClientConfig config) {
        Objects.requireNonNull(config, "config");
        return new MockGameSettings(
                config.mockGameBinaryPath(),
                config.iceAdapterGpgNetPort(),
                config.iceAdapterLobbyPort(),
                config.iceAdapterGameId(),
                config.playerIdOverride(),
                config.playerLogin(),
                config.mockGameLaunchDelaySeconds(),
                config.hostConfig().map(GameHostConfig::gameOptions).orElseGet(Map::of),
                config.logLevel(),
                config.logFile());
    }
}
