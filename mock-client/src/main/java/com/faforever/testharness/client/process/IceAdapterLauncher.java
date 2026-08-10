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
 * <p>Argument list (subprocess-orchestration-spec §2.6, json-rpc-spec §8). {@code --id} and {@code
 * --login} are emitted first because the upstream synopsis lists them first.
 *
 * <pre>{@code
 * <binary> --id <id> --login <login> --game-id <uid>
 *          --rpc-port <rpc> --gpgnet-port <gpgnet> --lobby-port <lobby>
 * }</pre>
 *
 * <p>Those three identity values have two sources (WBS-3.1.2.9). {@link #start(LaunchIdentity)} is
 * the orchestrated path and takes the id, login, and game uid the lobby assigned. {@link #start()}
 * is the {@code launch-ice} diagnostic path and falls back to config values, which is also the only
 * place {@code playerIdOverride} applies.
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
 * <p>Side effect of the override: under a {@code .jar} launch the adapter no longer writes its own
 * {@code <LOG_DIR>/ice-adapter.log} (the console-only config has no file appender); its output is
 * preserved only via the harness {@code ProcessOutputLogger} capture. The config path and {@link
 * #LOG_DIR} are fixed, so concurrent harness instances share them — the contents are identical, so
 * the shared write is benign; per-session isolation is future work (spec §2.4).
 *
 * <p>Not thread-safe; a launcher is expected to be used by a single caller for a single launch.
 */
public class IceAdapterLauncher {

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
        return start(configIdentity());
    }

    /**
     * Launches the adapter under {@code identity} rather than under the config defaults.
     *
     * <p>This is the orchestrated path (WBS-3.1.2.9). The lifecycle calls it once the lobby has
     * answered with a {@code welcome} and a {@code game_launch}, so the adapter is started as the
     * player the lobby actually authenticated, for the game the lobby actually assigned. Everything
     * else about the launch is identical to {@link #start()}.
     *
     * @param identity the session identity to launch under; must not be {@code null}
     * @return the manager wrapping the started adapter process
     * @throws IceAdapterLaunchException if the binary path is not a regular file ("binary not
     *     found") or the process could not be started ("binary failed to start")
     */
    public SubprocessManager start(final LaunchIdentity identity) throws IceAdapterLaunchException {
        Path binary = resolveBinary();
        List<String> argv = buildArgv(binary, identity);

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
     * Builds the adapter argument list for {@code binary} under the config identity.
     *
     * @param binary the resolved adapter binary path
     * @return the argv list, ready to hand to {@link ProcessBuilder}
     */
    List<String> buildArgv(final Path binary) {
        return buildArgv(binary, configIdentity());
    }

    /**
     * Builds the adapter argument list for {@code binary} under {@code identity}.
     *
     * @param binary the resolved adapter binary path
     * @param identity the identity the adapter is launched under
     * @return the argv list, ready to hand to {@link ProcessBuilder}
     */
    List<String> buildArgv(final Path binary, final LaunchIdentity identity) {
        // Spec §2.2: JAR → java -jar on the same JRE; native binary → exec directly. The headless
        // logback override is handed to commandPrefix so it lands right after the `java` token —
        // robust against a future setpriv/setsid launch prefix (spec §7.3) that shifts argv[0].
        List<String> jvmArgs =
                BinaryLaunchCommand.isJar(binary)
                        ? List.of("-Dlogback.configurationFile=" + headlessLogbackPath())
                        : List.of();
        List<String> argv = new ArrayList<>(BinaryLaunchCommand.commandPrefix(binary, jvmArgs));

        // Spec §2.6, --id and --login must precede every other flag.
        argv.add("--id");
        argv.add(Integer.toString(identity.playerId()));
        argv.add("--login");
        argv.add(identity.login());
        // Required by faf-ice-adapter 3.3.x and later. Without it the adapter prints usage and
        // exits, and exits 0 while doing so (its main discards picocli's return value), so a
        // missing --game-id never surfaces as a non-zero exit code.
        argv.add("--game-id");
        argv.add(Integer.toString(identity.gameUid()));
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
