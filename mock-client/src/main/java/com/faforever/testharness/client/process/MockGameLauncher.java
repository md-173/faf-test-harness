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
 *          --player-id <id> --player-login <login>
 * }</pre>
 *
 * <p>The {@code --gpgnet-port} and {@code --lobby-port} values are sourced from the same {@link
 * MockClientConfig} fields the ICE adapter uses ({@code iceAdapterGpgNetPort}, {@code
 * iceAdapterLobbyPort}) — spec §2.8 requires the values to match between adapter and game.
 *
 * <p>The full §2.8 argv also includes {@code --game-uid} plus {@code game_launch}-derived flags
 * (mod, map, faction, team). Those are session-derived and only meaningful inside an orchestrated
 * lobby session; the standalone {@code launch-game} diagnostic has no lobby, so this launcher emits
 * only the config-derivable subset. Orchestration will extend the argv when the FSM that owns
 * {@code game_launch} parsing arrives.
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
public final class MockGameLauncher {

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
        Path binary = resolveBinary();
        List<String> argv = buildArgv(binary);

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
     * Builds the mock-game argument list for {@code binary} from {@link MockClientConfig}.
     *
     * @param binary the resolved mock-game binary path
     * @return the argv list, ready to hand to {@link ProcessBuilder}
     */
    List<String> buildArgv(final Path binary) {
        // Spec §2.2: JAR → java -jar on the same JRE; native binary → exec directly.
        List<String> argv = new ArrayList<>(BinaryLaunchCommand.commandPrefix(binary));

        int playerId = config.playerIdOverride().orElse(DEFAULT_PLAYER_ID);
        // Spec §2.8 order: --gpgnet-port, --lobby-port, --player-id, --player-login. mock-game's
        // CLI parser is ours (WBS 3.2.3), no positional-prefix constraint like the upstream
        // adapter has.
        argv.add("--gpgnet-port");
        argv.add(Integer.toString(config.iceAdapterGpgNetPort()));
        argv.add("--lobby-port");
        argv.add(Integer.toString(config.iceAdapterLobbyPort()));
        argv.add("--player-id");
        argv.add(Integer.toString(playerId));
        argv.add("--player-login");
        argv.add(config.playerLogin());
        return argv;
    }
}
