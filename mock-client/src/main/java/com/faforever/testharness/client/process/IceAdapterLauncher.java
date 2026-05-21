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
import java.util.Locale;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Launches the external {@code faf-ice-adapter} binary as a child process (WBS-3.1.2.2).
 *
 * <p>This class only <em>spawns and reaps</em>: it builds the adapter's argument list from {@link
 * MockClientConfig} and hands a fully-configured {@link ProcessBuilder} to {@link
 * SubprocessManager}, which owns output capture, the JVM shutdown hook, and SIGTERM/SIGKILL
 * teardown. Lifecycle decisions ("launch the adapter when entering matchmaking") belong to the FSM
 * orchestration tasks and are deliberately out of scope here.
 *
 * <p>Argument list (subprocess-orchestration-spec §2.6, json-rpc-spec §8); {@code --id} and {@code
 * --login} are emitted first because the upstream parser is positional-prefix:
 *
 * <pre>{@code
 * <binary> --id <id> --login <login>
 *          --rpc-port <rpc> --gpgnet-port <gpgnet> --lobby-port <lobby>
 * }</pre>
 *
 * <p>If the configured binary path ends in {@code .jar} it is launched via {@code java -jar} on the
 * same JRE as the parent (spec §2.2); otherwise it is executed directly. Log level is passed to the
 * child through the {@code LOG_LEVEL} environment variable, not a CLI flag — the adapter has no
 * log-level flag and reads {@code LOG_LEVEL} per its upstream README (spec §2.3).
 *
 * <p>Not thread-safe; a launcher is expected to be used by a single caller for a single launch.
 */
public final class IceAdapterLauncher {

    /** MDC component tag applied to every captured adapter log line. */
    public static final String COMPONENT_TAG = "ICEAdapter";

    /** Player id used when {@link MockClientConfig#playerIdOverride()} is empty. */
    static final int DEFAULT_PLAYER_ID = 1;

    /**
     * Diagnostic logger for the launcher itself; adapter output is tagged {@link #COMPONENT_TAG}.
     */
    private static final Logger LOG = LoggerFactory.getLogger(IceAdapterLauncher.class);

    /** Grace between SIGTERM and SIGKILL for the adapter (spec §5.3). */
    private static final Duration TERMINATE_GRACE = Duration.ofSeconds(5);

    /** Per-child log directory handed to the adapter via {@code LOG_DIR} (spec §2.3). */
    private static final Path LOG_DIR = Path.of("logs", "ice-adapter");

    /** Validated configuration the argument list is built from. */
    private final MockClientConfig config;

    /**
     * Creates a launcher bound to {@code config}.
     *
     * @param config the validated Mock Client configuration; must not be {@code null}
     */
    public IceAdapterLauncher(final MockClientConfig config) {
        this.config = Objects.requireNonNull(config, "config");
    }

    /**
     * Resolves and launches the {@code faf-ice-adapter} binary, returning the {@link
     * SubprocessManager} that owns its lifecycle.
     *
     * <p>The returned manager has already wired stdout/stderr capture (tagged {@link
     * #COMPONENT_TAG}) and registered itself with the JVM shutdown hook, so a Ctrl-C of the parent
     * tears the adapter down with it — no zombie process. Callers terminate it explicitly via
     * {@link SubprocessManager#terminate()}.
     *
     * @return the manager wrapping the started adapter process
     * @throws IceAdapterLaunchException if the binary path is not a regular file ("binary not
     *     found") or the process could not be started ("binary failed to start")
     */
    public SubprocessManager start() throws IceAdapterLaunchException {
        Path binary = resolveBinary();
        List<String> argv = buildArgv(binary);

        ProcessBuilder pb = new ProcessBuilder(argv);
        // Spec §2.3: per-child LOG_DIR, and LOG_LEVEL sourced from the harness config so the
        // adapter logs at the same level as the Mock Client.
        pb.environment().put("LOG_DIR", LOG_DIR + "/");
        pb.environment().put(LoggingSetup.LOG_LEVEL_ENV, config.logLevel());
        // Note: redirectErrorStream is intentionally NOT set — SubprocessManager keeps stdout and
        // stderr separate so stderr can be routed to WARN (spec §4 / §5.3).
        createLogDir();

        LOG.info("Launching ICE adapter: {}", String.join(" ", argv));
        try {
            SubprocessManager manager = SubprocessManager.start(pb, COMPONENT_TAG, TERMINATE_GRACE);
            LOG.info("ICE adapter started, pid={}", manager.pid());
            return manager;
        } catch (IOException e) {
            throw new IceAdapterLaunchException(
                    "faf-ice-adapter binary failed to start: "
                            + binary
                            + " ("
                            + e.getMessage()
                            + ")",
                    e);
        }
    }

    /**
     * Resolves the configured adapter binary path and verifies it points at an existing regular
     * file.
     *
     * @return the configured binary path
     * @throws IceAdapterLaunchException if the path is missing or not a regular file
     */
    Path resolveBinary() throws IceAdapterLaunchException {
        Path binary = config.iceAdapterBinaryPath();
        if (!Files.isRegularFile(binary)) {
            throw new IceAdapterLaunchException(
                    "faf-ice-adapter binary not found: " + binary.toAbsolutePath());
        }
        return binary;
    }

    /**
     * Builds the adapter argument list for {@code binary} from {@link MockClientConfig}.
     *
     * @param binary the resolved adapter binary path
     * @return the argv list, ready to hand to {@link ProcessBuilder}
     */
    List<String> buildArgv(final Path binary) {
        List<String> argv = new ArrayList<>();
        if (isJar(binary)) {
            // Spec §2.2: run the JAR on the same JRE as the parent; never rely on PATH.
            argv.add(javaBinary());
            argv.add("-jar");
        }
        argv.add(binary.toString());

        int playerId = config.playerIdOverride().orElse(DEFAULT_PLAYER_ID);
        // Spec §2.6: --id and --login must precede every other flag.
        argv.add("--id");
        argv.add(Integer.toString(playerId));
        argv.add("--login");
        argv.add(config.playerLogin());
        argv.add("--rpc-port");
        argv.add(Integer.toString(config.iceAdapterRpcPort()));
        argv.add("--gpgnet-port");
        argv.add(Integer.toString(config.iceAdapterGpgNetPort()));
        argv.add("--lobby-port");
        argv.add(Integer.toString(config.iceAdapterLobbyPort()));
        return argv;
    }

    /**
     * Returns whether {@code binary} is a Java archive that must be launched via {@code java -jar}.
     *
     * @param binary the binary path
     * @return {@code true} if the file name ends in {@code .jar} (case-insensitive)
     */
    private static boolean isJar(final Path binary) {
        return binary.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".jar");
    }

    /**
     * Resolves the {@code java} executable, mirroring spec §2.2: prefer the JRE running the parent,
     * fall back to {@code ${java.home}/bin/java} when the OS withholds the command path.
     *
     * @return an absolute path to a {@code java} binary
     */
    private static String javaBinary() {
        return ProcessHandle.current()
                .info()
                .command()
                .orElse(System.getProperty("java.home") + "/bin/java");
    }

    /** Best-effort creation of the per-child log directory; a failure here is not fatal. */
    private void createLogDir() {
        try {
            Files.createDirectories(LOG_DIR);
        } catch (IOException e) {
            LOG.debug("Could not create ICE adapter log directory {}: {}", LOG_DIR, e.getMessage());
        }
    }
}
