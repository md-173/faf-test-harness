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
 * <p>For a {@code .jar} adapter the launcher also injects {@code -Dlogback.configurationFile}
 * pointing at a console-only config it writes under {@link #LOG_DIR}. The upstream {@code -nojfx}
 * jar's bundled {@code logback.xml} wires in a JavaFX {@code TextAreaLogAppender}, which crashes a
 * JavaFX-less JRE with {@code NoClassDefFoundError: javafx/application/Application} on the first
 * log line; the override keeps the adapter fully headless. The adapter is also passed {@code
 * --game-id} (required by faf-ice-adapter 3.3.x and later, which otherwise prints usage and exits).
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

    /** File name of the headless logback config materialised under {@link #LOG_DIR}. */
    private static final String HEADLESS_LOGBACK_FILE = "logback-headless.xml";

    /**
     * Console-only logback config written for the adapter child JVM. The upstream {@code -nojfx}
     * jar bundles a {@code logback.xml} that wires in a JavaFX {@code TextAreaLogAppender}; on a
     * JavaFX-less JRE the first log line then fails with {@code NoClassDefFoundError:
     * javafx/application/Application}. Pointing the child at this config (via {@code
     * -Dlogback.configurationFile}) keeps the adapter headless; its output still reaches the
     * harness through {@code ProcessOutputLogger}.
     */
    private static final String HEADLESS_LOGBACK_XML =
            """
            <?xml version="1.0" encoding="UTF-8"?>
            <configuration>
              <appender name="STDOUT" class="ch.qos.logback.core.ConsoleAppender">
                <encoder>
                  <pattern>%d{HH:mm:ss.SSS} [%thread] %-5level %logger{24} - %msg%n</pattern>
                </encoder>
              </appender>
              <root level="${LOG_LEVEL:-INFO}">
                <appender-ref ref="STDOUT"/>
              </root>
            </configuration>
            """;

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
        if (BinaryLaunchCommand.isJar(binary)) {
            writeHeadlessLogbackConfig();
        }

        LOG.info("Launching ICE adapter: {}", String.join(" ", argv));
        try {
            SubprocessManager manager = SubprocessManager.start(pb, COMPONENT_TAG, TERMINATE_GRACE);
            LOG.info("ICE adapter started, pid={}", manager.pid());
            return manager;
        } catch (IOException e) {
            throw new IceAdapterLaunchException(
                    "faf-ice-adapter binary failed to start: "
                            + binary.toAbsolutePath()
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
        // Spec §2.2: JAR → java -jar on the same JRE; native binary → exec directly.
        List<String> argv = new ArrayList<>(BinaryLaunchCommand.commandPrefix(binary));

        if (BinaryLaunchCommand.isJar(binary)) {
            // Override the jar's bundled JavaFX logback so the -nojfx adapter runs headless;
            // inserted right after "java", before "-jar".
            argv.add(1, "-Dlogback.configurationFile=" + headlessLogbackPath());
        }

        int playerId = config.playerIdOverride().orElse(DEFAULT_PLAYER_ID);
        // Spec §2.6: --id and --login must precede every other flag.
        argv.add("--id");
        argv.add(Integer.toString(playerId));
        argv.add("--login");
        argv.add(config.playerLogin());
        // Required by faf-ice-adapter 3.3.x+; without it the adapter prints usage and exits.
        argv.add("--game-id");
        argv.add(Integer.toString(config.iceAdapterGameId()));
        argv.add("--rpc-port");
        argv.add(Integer.toString(config.iceAdapterRpcPort()));
        argv.add("--gpgnet-port");
        argv.add(Integer.toString(config.iceAdapterGpgNetPort()));
        argv.add("--lobby-port");
        argv.add(Integer.toString(config.iceAdapterLobbyPort()));
        return argv;
    }

    /** Best-effort creation of the per-child log directory; a failure here is not fatal. */
    private void createLogDir() {
        try {
            Files.createDirectories(LOG_DIR);
        } catch (IOException e) {
            LOG.debug("Could not create ICE adapter log directory {}: {}", LOG_DIR, e.getMessage());
        }
    }

    /**
     * Absolute path of the headless logback config handed to the adapter child JVM via {@code
     * -Dlogback.configurationFile} (see {@link #HEADLESS_LOGBACK_XML}).
     *
     * @return the absolute path of the materialised config under {@link #LOG_DIR}
     */
    private static Path headlessLogbackPath() {
        return LOG_DIR.resolve(HEADLESS_LOGBACK_FILE).toAbsolutePath();
    }

    /**
     * Writes {@link #HEADLESS_LOGBACK_XML} to {@link #headlessLogbackPath()} so the {@code .jar}
     * adapter can be pointed at it. Best-effort: if the write fails, logback simply falls back to a
     * built-in console default (still JavaFX-free), so the adapter remains launchable.
     */
    private void writeHeadlessLogbackConfig() {
        Path target = headlessLogbackPath();
        try {
            Files.writeString(target, HEADLESS_LOGBACK_XML);
        } catch (IOException e) {
            LOG.warn("Could not write headless logback config {}: {}", target, e.getMessage());
        }
    }
}
