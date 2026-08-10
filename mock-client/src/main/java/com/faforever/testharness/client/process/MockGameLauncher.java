package com.faforever.testharness.client.process;

import com.faforever.testharness.client.config.MockClientConfig;
import com.faforever.testharness.shared.logging.LoggingSetup;
import com.faforever.testharness.shared.process.SubprocessManager;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Launches the in-repo {@code mock-game} binary as a child process (WBS-3.1.2.3).
 *
 * <p>This class only <em>spawns and reaps</em>: it builds the game's argument list from {@link
 * MockClientConfig} and hands a fully-configured {@link ProcessBuilder} to {@link
 * SubprocessManager}, which owns output capture, the JVM shutdown hook, and SIGTERM/SIGKILL
 * teardown. Lifecycle decisions ("launch the game after {@code game_launch} is received") belong to
 * the FSM orchestration tasks and are deliberately out of scope here.
 *
 * <p>Argument list (subprocess-orchestration-spec §2.8):
 *
 * <pre>{@code
 * <binary> --gpgnet-port <gpgnet> --lobby-port <lobby>
 *          --player-id <id> --player-login <login> --game-uid <uid>
 * }</pre>
 *
 * <p>The {@code --gpgnet-port} and {@code --lobby-port} values are sourced from the same {@link
 * MockClientConfig} fields the ICE adapter uses ({@code iceAdapterGpgNetPort}, {@code
 * iceAdapterLobbyPort}), because spec §2.8 requires the values to match between adapter and game.
 *
 * <p>The identity has two sources (WBS-3.1.2.9). {@link #start(LaunchIdentity)} is the orchestrated
 * path and takes the id and login the lobby assigned plus the {@code game_launch} uid. {@link
 * #start()} is the {@code launch-game} diagnostic path and falls back to config values, which is
 * also the only place {@code playerIdOverride} applies. Its uid is {@code iceAdapterGameId},
 * default 0, meaning no session.
 *
 * <p>Spec §2.8 also sketched {@code game_launch}-derived mod, map, faction, and team flags. None
 * are emitted, for two different reasons. Verified against downlords-faf-client v2026.7.1, {@code
 * LaunchCommandBuilder} has no mod argument at all and its {@code /map} is set only by {@code
 * launchOfflineGame}, so no online game receives either. Faction, team, expected players, and start
 * spot are genuinely passed on every online launch, but the client does not branch on matchmaker.
 * It forwards whatever {@code game_launch} carried, and the FAF server is what leaves those fields
 * null for a custom game and fills them for a matchmaker one. Those four are deferred rather than
 * dismissed, and are tracked in spec §2.8.
 *
 * <p>If the configured binary path ends in {@code .jar} it is launched via {@code java -jar} on the
 * same JRE as the parent (spec §2.2); otherwise it is executed directly. Log level is forwarded to
 * the child via the {@code LOG_LEVEL} environment variable so {@code mock-game}'s {@link
 * LoggingSetup} observes the same level as the harness.
 *
 * <p>No {@code LOG_FILE} / {@code LOG_DIR} is set on the child. mock-game's stdout and stderr are
 * captured by {@code ProcessOutputLogger} tagged {@link #COMPONENT_TAG} and merged into the parent
 * log stream, so giving the child its own log file would duplicate that capture (or worse, pit two
 * writers against one file). This is intentionally asymmetric with {@link IceAdapterLauncher},
 * which sets {@code LOG_DIR} only because faf-ice-adapter is an external binary with its own
 * logging conventions; mock-game uses our {@link LoggingSetup} and inherits the parent's stream by
 * design.
 *
 * <p>Not thread-safe; a launcher is expected to be used by a single caller for a single launch.
 */
public class MockGameLauncher {

    /** MDC component tag applied to every captured mock-game log line. */
    public static final String COMPONENT_TAG = "MockGame";

    /** Player id used when {@link MockClientConfig#playerIdOverride()} is empty. */
    static final int DEFAULT_PLAYER_ID = 1;

    /**
     * Diagnostic logger for the launcher itself; mock-game output is tagged {@link #COMPONENT_TAG}.
     */
    private static final Logger LOG = LoggerFactory.getLogger(MockGameLauncher.class);

    /** Grace between SIGTERM and SIGKILL for mock-game (spec §5.3). */
    private static final Duration TERMINATE_GRACE = Duration.ofSeconds(5);

    /** Validated configuration the argument list is built from. */
    private final MockClientConfig config;

    /**
     * Creates a launcher bound to {@code config}.
     *
     * @param config the validated Mock Client configuration; must not be {@code null}
     */
    public MockGameLauncher(final MockClientConfig config) {
        this.config = Objects.requireNonNull(config, "config");
    }

    /**
     * Resolves and launches the {@code mock-game} binary, returning the {@link SubprocessManager}
     * that owns its lifecycle.
     *
     * <p>The returned manager has already wired stdout/stderr capture (tagged {@link
     * #COMPONENT_TAG}) and registered itself with the JVM shutdown hook, so a Ctrl-C of the parent
     * tears mock-game down with it — no zombie process. Callers terminate it explicitly via {@link
     * SubprocessManager#terminate()}.
     *
     * @return the manager wrapping the started mock-game process
     * @throws MockGameLaunchException if the binary path is not a regular file ("binary not found")
     *     or the process could not be started ("binary failed to start")
     */
    public SubprocessManager start() throws MockGameLaunchException {
        return start(configIdentity());
    }

    /**
     * Launches mock-game under {@code identity} rather than under the config defaults.
     *
     * <p>This is the orchestrated path (WBS-3.1.2.9). The lifecycle calls it once the lobby has
     * answered with a {@code welcome}, so the game is started as the player the lobby actually
     * authenticated. Everything else about the launch is identical to {@link #start()}.
     *
     * @param identity the session identity to launch under; must not be {@code null}
     * @return the manager wrapping the started mock-game process
     * @throws MockGameLaunchException if the binary path is not a regular file ("binary not found")
     *     or the process could not be started ("binary failed to start")
     */
    public SubprocessManager start(final LaunchIdentity identity) throws MockGameLaunchException {
        Path binary = resolveBinary();
        List<String> argv = buildArgv(binary, identity);

        ProcessBuilder pb = new ProcessBuilder(argv);
        // Forward LOG_LEVEL so mock-game's LoggingSetup observes the same level as the harness.
        pb.environment().put(LoggingSetup.LOG_LEVEL_ENV, config.logLevel());
        // Note: redirectErrorStream is intentionally NOT set — SubprocessManager keeps stdout and
        // stderr separate so stderr can be routed to WARN (spec §4 / §5.3).

        LOG.info("Launching mock-game: {}", String.join(" ", argv));
        try {
            SubprocessManager manager = SubprocessManager.start(pb, COMPONENT_TAG, TERMINATE_GRACE);
            LOG.info("mock-game started, pid={}", manager.pid());
            return manager;
        } catch (IOException e) {
            throw new MockGameLaunchException(
                    "mock-game binary failed to start: "
                            + binary.toAbsolutePath()
                            + " ("
                            + e.getMessage()
                            + ")",
                    e);
        }
    }

    /**
     * Resolves the configured mock-game binary path and verifies it points at an existing regular
     * file.
     *
     * @return the configured binary path
     * @throws MockGameLaunchException if the path is missing or not a regular file
     */
    Path resolveBinary() throws MockGameLaunchException {
        Path binary = config.mockGameBinaryPath();
        if (!Files.isRegularFile(binary)) {
            throw new MockGameLaunchException(
                    "mock-game binary not found: " + binary.toAbsolutePath());
        }
        return binary;
    }

    /**
     * The identity the diagnostic launch path uses, assembled from {@link MockClientConfig}. {@code
     * playerIdOverride} is honoured here and only here, since a session launch is bound to the
     * identity the lobby assigned.
     *
     * @return the config-derived launch identity
     */
    LaunchIdentity configIdentity() {
        return LaunchIdentity.fromConfig(config, DEFAULT_PLAYER_ID);
    }

    /**
     * Builds the mock-game argument list for {@code binary} under the config identity.
     *
     * @param binary the resolved mock-game binary path
     * @return the argv list, ready to hand to {@link ProcessBuilder}
     */
    List<String> buildArgv(final Path binary) {
        return buildArgv(binary, configIdentity());
    }

    /**
     * Builds the mock-game argument list for {@code binary} under {@code identity}.
     *
     * <p>{@code --game-uid} is a mock adaptation rather than a copy of an upstream flag. The real
     * client hands Forged Alliance its game uid inside the {@code /savereplay
     * gpgnet://.../uid/login.SCFAreplay} URL and the {@code /log} filename, with no flag of its
     * own. mock-game has neither, so it takes the uid directly.
     *
     * @param binary the resolved mock-game binary path
     * @param identity the identity mock-game is launched under
     * @return the argv list, ready to hand to {@link ProcessBuilder}
     */
    List<String> buildArgv(final Path binary, final LaunchIdentity identity) {
        // Spec §2.2, JAR runs via java -jar on the same JRE and a native binary is exec'd directly.
        List<String> argv = new ArrayList<>(BinaryLaunchCommand.commandPrefix(binary));

        // Spec §2.8 order, --gpgnet-port, --lobby-port, --player-id, --player-login. mock-game's
        // CLI parser is ours (MockGameCli, WBS 3.2.1.1), no positional-prefix constraint like the
        // upstream adapter has.
        argv.add("--gpgnet-port");
        argv.add(Integer.toString(config.iceAdapterGpgNetPort()));
        argv.add("--lobby-port");
        argv.add(Integer.toString(config.iceAdapterLobbyPort()));
        argv.add("--player-id");
        argv.add(Integer.toString(identity.playerId()));
        argv.add("--player-login");
        argv.add(identity.login());
        argv.add("--game-uid");
        argv.add(Integer.toString(identity.gameUid()));
        return argv;
    }
}
