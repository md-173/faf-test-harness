package com.faforever.testharness.client.config;

import java.nio.file.Path;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;

/**
 * The slice of Mock Client configuration a no-lobby ICE-adapter diagnostic needs: what to launch,
 * on which ports, under which identity, and how to log (WBS-3.1.4.3).
 *
 * <p>It exists so that {@code ice-smoke} — a localhost-only reachability check — can run without
 * the lobby endpoint and OAuth credentials {@link MockClientConfig} mandates. Those are required
 * there because a full {@code run} session cannot proceed without them; an adapter-only check never
 * touches the lobby, so demanding placeholder values would be a wall in front of exactly the
 * consumer this command exists for — someone with no FAF account, checking their harness works.
 *
 * <p>Every field here is also a {@link MockClientConfig} field, and {@link #from(MockClientConfig)}
 * is the narrowing view the lobby-driven paths use, so both routes launch the adapter identically.
 *
 * <p>Validation here covers only what would otherwise reach {@code faf-ice-adapter} as a nonsense
 * argument. Value-range checks on operator input (port bounds, the JSON-RPC and GPGNet ports being
 * distinct) belong to the CLI layer that reads that input — see {@link
 * MockClientCli#toValidatedAdapterSettings} — so this record stays a faithful narrowing of an
 * already-validated {@link MockClientConfig} and never rejects a configuration the full-session
 * path accepts.
 *
 * @param binaryPath path to the {@code faf-ice-adapter} executable
 * @param rpcPort local JSON-RPC port the adapter serves ({@code --rpc-port})
 * @param gpgNetPort local GPGNet TCP port the game connects to ({@code --gpgnet-port})
 * @param lobbyPort local UDP port used for game traffic ({@code --lobby-port})
 * @param gameId game id passed as {@code --game-id}, required by faf-ice-adapter 3.3.x and later
 * @param playerIdOverride optional player id for deterministic local testing; empty means the
 *     caller's default applies
 * @param playerLogin player login passed as {@code --login}
 * @param logLevel log level handed to the adapter child through {@code LOG_LEVEL}
 * @param logFile optional JSONL log file for the harness's own records
 */
public record IceAdapterSettings(
        Path binaryPath,
        int rpcPort,
        int gpgNetPort,
        int lobbyPort,
        int gameId,
        OptionalInt playerIdOverride,
        String playerLogin,
        String logLevel,
        Optional<Path> logFile) {

    /**
     * Rejects only values the full-session path rejects too, so narrowing a {@link
     * MockClientConfig} can never fail where using that configuration directly would have
     * succeeded: the nulls that would be an NPE deep in the launcher either way, and the blank
     * {@code playerLogin} that {@link MockClientConfig} already refuses.
     *
     * <p>Nothing else is checked here, deliberately. {@code logLevel}, for one, is unvalidated by
     * {@link MockClientConfig}: rejecting a blank one here would make {@code launch-ice
     * --log-level=} start failing, which is a change to a shipped command and none of this card's
     * business. Diagnostic-input validation belongs to {@link
     * MockClientCli#toValidatedAdapterSettings}, where it surfaces as a usage error.
     *
     * @throws NullPointerException if {@code binaryPath}, {@code playerIdOverride}, or {@code
     *     logFile} is {@code null}
     * @throws IllegalArgumentException if {@code playerLogin} is blank
     */
    public IceAdapterSettings {
        Objects.requireNonNull(binaryPath, "binaryPath");
        Objects.requireNonNull(playerIdOverride, "playerIdOverride");
        Objects.requireNonNull(logFile, "logFile");
        if (playerLogin == null || playerLogin.isBlank()) {
            throw new IllegalArgumentException(
                    "playerLogin must not be blank: it is passed to faf-ice-adapter as --login.");
        }
    }

    /**
     * Narrows a full {@link MockClientConfig} to the adapter-only fields, so a lobby-driven session
     * and a standalone diagnostic launch the adapter through one code path.
     *
     * @param config the validated Mock Client configuration; must not be {@code null}
     * @return the adapter settings that configuration implies
     */
    public static IceAdapterSettings from(final MockClientConfig config) {
        Objects.requireNonNull(config, "config");
        return new IceAdapterSettings(
                config.iceAdapterBinaryPath(),
                config.iceAdapterRpcPort(),
                config.iceAdapterGpgNetPort(),
                config.iceAdapterLobbyPort(),
                config.iceAdapterGameId(),
                config.playerIdOverride(),
                config.playerLogin(),
                config.logLevel(),
                config.logFile());
    }
}
