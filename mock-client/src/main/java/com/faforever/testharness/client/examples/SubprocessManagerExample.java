package com.faforever.testharness.client.examples;

import com.faforever.testharness.shared.logging.LoggingSetup;
import com.faforever.testharness.shared.process.SubprocessManager;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Runnable example showing the SubprocessManager API the way the ICE adapter (WBS-3.1.2.2) and Mock
 * Game (WBS-3.1.2.3) launchers will consume it.
 *
 * <p>Run via:
 *
 * <pre>{@code
 * ./gradlew :mock-client:runSubprocessExample
 * }</pre>
 *
 * <p>The example re-invokes the current JVM with {@code --sleep} as the child, so no external
 * binary is needed. Sequence:
 *
 * <ol>
 *   <li>Build a {@link ProcessBuilder} with argv + env overlay + working dir.
 *   <li>Hand it to {@link SubprocessManager#start} with a component tag and terminate grace.
 *   <li>Wire an "unexpected exit" reaction via {@link SubprocessManager#onExit()}.
 *   <li>Sleep briefly, then call {@link SubprocessManager#terminate()} to exercise the
 *       SIGTERM→SIGKILL path.
 * </ol>
 *
 * <p>Subprocess stdout / stderr is captured by SubprocessManager and routed through the harness
 * logger tagged with the supplied component name (see {@code ProcessOutputLogger}).
 */
public final class SubprocessManagerExample {

    /** Diagnostic logger for the demo itself. */
    private static final Logger LOG = LoggerFactory.getLogger(SubprocessManagerExample.class);

    /** Grace passed to {@link SubprocessManager#start} for the no-arg terminate. */
    private static final Duration GRACE = Duration.ofSeconds(5);

    /** How long to let the child run before terminating it. */
    private static final long DEMO_LIFETIME_MS = 1_000;

    /** How long the child sleeps in {@code --sleep} mode (well past the demo's lifetime). */
    private static final long CHILD_SLEEP_MS = 60_000;

    /** Budget for the post-terminate await. */
    private static final long EXIT_AWAIT_SECONDS = 10;

    private SubprocessManagerExample() {}

    /**
     * Entry point. With no arguments, runs the parent-side demo. With {@code --sleep}, this same
     * binary is re-invoked as the child and just sleeps until killed.
     *
     * @param args command-line arguments
     * @throws Exception if subprocess wiring fails
     */
    public static void main(String[] args) throws Exception {
        // Child mode: invoked by parent with --sleep,
        // blocks until the parent’s SubprocessManager.terminate() kills this process.
        if (args.length > 0 && "--sleep".equals(args[0])) {
            Thread.sleep(CHILD_SLEEP_MS);
            return;
        }

        LoggingSetup.configure("Example");

        // 1. Build argv. Real launchers source the binary path from MockClientConfig and the
        //    remaining flags from lobby messages or runtime port allocation.
        // command() is Optional — the OS can withhold it under strict security policies,
        // so fall back to java.home rather than blowing up.
        String javaBin = ProcessHandle.current().info().command()
                .orElse(System.getProperty("java.home") + "/bin/java");
        String classpath = System.getProperty("java.class.path");
        ProcessBuilder pb =
                new ProcessBuilder(
                        javaBin,
                        "-cp",
                        classpath,
                        SubprocessManagerExample.class.getName(),
                        "--sleep");

        // 2. Overlay environment variables. Spec §2.3 — set LOG_DIR for the child.
        pb.environment().put("LOG_DIR", "logs/example/");

        // 3. Start the child. SubprocessManager handles output capture, exit tracking, JVM
        //    shutdown-hook registration, and the SIGTERM→SIGKILL escalation in terminate().
        SubprocessManager child = SubprocessManager.start(pb, "Example", GRACE);
        LOG.info("Started Example child pid={}", child.pid());

        // 4. Wire an "unexpected exit" reaction. The "shuttingDown" flag lives in the caller
        //    because that's where the FSM knows whether the exit was expected. A real launcher
        //    would emit a SubprocessExited FSM event here.
        AtomicBoolean shuttingDown = new AtomicBoolean();
        child.onExit()
                .thenAccept(
                        code -> {
                            if (shuttingDown.get()) {
                                LOG.info("Example child exited as expected: code={}", code);
                            } else {
                                LOG.warn("Example child exited UNEXPECTEDLY: code={}", code);
                            }
                        });

        // 5. Let it run for a bit, then trigger graceful shutdown. Setting shuttingDown before
        //    terminate() ensures the onExit callback knows this exit was intentional.
        Thread.sleep(DEMO_LIFETIME_MS);
        LOG.info("Demo timer elapsed; terminating child");
        shuttingDown.set(true);
        child.terminate();

        // 6. Block until exit-side callbacks have run so the log lines stay ordered.
        int code = child.onExit().get(EXIT_AWAIT_SECONDS, TimeUnit.SECONDS);
        LOG.info("Demo complete, child exit code={}", code);
    }
}
