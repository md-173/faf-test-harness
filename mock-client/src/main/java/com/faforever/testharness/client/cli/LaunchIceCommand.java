package com.faforever.testharness.client.cli;

import com.faforever.testharness.client.config.MockClientCli;
import com.faforever.testharness.client.config.MockClientConfig;
import com.faforever.testharness.client.process.IceAdapterLaunchException;
import com.faforever.testharness.client.process.IceAdapterLauncher;
import com.faforever.testharness.shared.logging.LoggingSetup;
import com.faforever.testharness.shared.process.SubprocessManager;
import java.util.OptionalInt;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.ParameterException;
import picocli.CommandLine.ParentCommand;
import picocli.CommandLine.Spec;

/**
 * {@code launch-ice} subcommand: spawn {@code faf-ice-adapter} only, let it run for a fixed window,
 * terminate it, and log its exit code (WBS-3.1.2.2).
 *
 * <p>This is a lower-level diagnostic — it exercises the subprocess plumbing without the lobby or
 * the FSM. It does not connect to the adapter's JSON-RPC socket or spawn {@code mock-game}; those
 * are owned by sibling tracks.
 *
 * <p>Exit codes: {@link ExitCodes#OK} when the adapter ran for the full window and was terminated
 * cleanly; {@link ExitCodes#RUNTIME} when the binary could not be launched or the adapter exited on
 * its own before the window elapsed.
 */
@Command(
        name = "launch-ice",
        mixinStandardHelpOptions = true,
        description =
                "Spawn faf-ice-adapter only and forward its output through the harness logger.")
public final class LaunchIceCommand implements Callable<Integer> {

    /** Picocli auto-injects the root command so the subcommand can read the populated config. */
    @ParentCommand private MockClientCli parent;

    /** Picocli auto-injects the active {@link CommandSpec} for scoped error reporting. */
    @Spec private CommandSpec spec;

    /** How long to let the adapter run before terminating it. */
    @Option(
            names = "--duration-seconds",
            defaultValue = "10",
            description =
                    "Seconds to let the adapter run before terminating it "
                            + "(default: ${DEFAULT-VALUE}).")
    private int durationSeconds;

    /**
     * Spawn the adapter, run it for {@code --duration-seconds}, terminate it, and log the exit
     * code.
     *
     * @return {@link ExitCodes#OK} on a clean spawn-and-terminate cycle, otherwise {@link
     *     ExitCodes#RUNTIME}
     * @throws ParameterException if {@code --duration-seconds} is not a positive integer; picocli
     *     renders this as a usage error and exits {@link ExitCodes#USAGE}
     */
    @Override
    public Integer call() {
        if (durationSeconds <= 0) {
            // A non-positive timeout makes CompletableFuture.get expire immediately; reject it up
            // front so the user sees a clean usage error rather than a bogus "0s window elapsed".
            throw new ParameterException(
                    spec.commandLine(),
                    "--duration-seconds must be a positive integer; got " + durationSeconds);
        }

        MockClientConfig config = parent.toValidatedConfig(spec);
        MockClientCli.applyLoggingProperties(config);
        LoggingSetup.configure(MockClientCli.COMPONENT_NAME);
        Logger log = LoggerFactory.getLogger(LaunchIceCommand.class);

        SubprocessManager adapter;
        try {
            adapter = new IceAdapterLauncher(config).start();
        } catch (IceAdapterLaunchException e) {
            // Single-line, log-ready message — no stack trace (WBS-3.1.2.2 acceptance criteria).
            log.error(e.getMessage());
            return ExitCodes.RUNTIME;
        }

        try {
            int earlyCode = adapter.onExit().get(durationSeconds, TimeUnit.SECONDS);
            log.error(
                    "ICE adapter exited on its own before the {}s run window; exit code {}",
                    durationSeconds,
                    earlyCode);
            return ExitCodes.RUNTIME;
        } catch (TimeoutException e) {
            // Healthy path: the adapter is still running after the window — fall through and stop.
            log.info("Run window of {}s elapsed; terminating ICE adapter", durationSeconds);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("Interrupted while running ICE adapter; terminating");
            adapter.terminate();
            return ExitCodes.RUNTIME;
        } catch (ExecutionException e) {
            log.error("Failed to track ICE adapter exit: {}", e.getMessage());
            adapter.terminate();
            return ExitCodes.RUNTIME;
        }

        adapter.terminate();
        OptionalInt exitCode = adapter.exitCode();
        if (exitCode.isPresent()) {
            log.info("ICE adapter terminated; exit code {}", exitCode.getAsInt());
        } else {
            log.warn("ICE adapter did not exit after terminate()");
        }
        return ExitCodes.OK;
    }
}
