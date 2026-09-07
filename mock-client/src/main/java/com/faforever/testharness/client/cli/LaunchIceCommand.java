package com.faforever.testharness.client.cli;

import com.faforever.testharness.client.config.IceAdapterSettings;
import com.faforever.testharness.client.config.MockClientCli;
import com.faforever.testharness.client.ice.IceAdapterConnection;
import com.faforever.testharness.client.process.IceAdapterLaunchException;
import com.faforever.testharness.client.process.IceAdapterLauncher;
import com.faforever.testharness.shared.logging.LoggingSetup;
import com.faforever.testharness.shared.process.SubprocessManager;
import java.time.Duration;
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
 * the FSM. It does not spawn {@code mock-game}; that is what makes the pair below useful.
 *
 * <p><b>It holds a JSON-RPC peer open, which is what lets {@code launch-game} compose with it</b>
 * (WBS-3.1.6.3, #279). The adapter will not serve a game until an RPC client exists: when the game
 * opens the GPGNet socket, {@code GPGNetClient}'s constructor calls {@code
 * rpcService.onConnectionStateChanged("Connected")}, which reaches {@code
 * RPCService.getPeerOrWait()} — an unbounded wait for the first JSON-RPC client. With no peer that
 * constructor never returns, {@code GPGNetServer.currentClient} is never assigned, and the game's
 * first frame hits a null {@code currentClient}: the adapter throws {@code IllegalStateException:
 * gameState must not change to null} and drops the socket. Verified against faf-ice-adapter 3.3.14.
 *
 * <p>So {@code launch-ice} in one shell and {@code launch-game} in another now complete a real
 * GPGNet handshake. That is a deliberate widening of the boundary this command's javadoc used to
 * draw — the narrower "did the binary start" check it used to be is now {@code ice-smoke}
 * (WBS-3.1.4.3), which spawns, connects, probes and gives a single verdict.
 *
 * <p><b>No lobby credentials.</b> It validates only the adapter settings ({@link
 * MockClientCli#toValidatedAdapterSettings}), matching {@code ice-smoke}, so {@code mock-client
 * launch-ice --ice-adapter-binary-path=…} runs with no other flags (WBS-3.1.5.2-fix, #308).
 *
 * <p>Exit codes: {@link ExitCodes#OK} when the adapter ran for the full window and was terminated
 * cleanly; {@link ExitCodes#RUNTIME} when the binary could not be launched, the RPC peer could not
 * be established, or the adapter exited on its own before the window elapsed.
 */
@Command(
        name = "launch-ice",
        mixinStandardHelpOptions = true,
        description =
                "Spawn faf-ice-adapter only and forward its output through the harness logger.")
public final class LaunchIceCommand implements Callable<Integer> {

    /**
     * Retry budget for attaching the JSON-RPC peer: 20 attempts at 250 ms is the 5 s window
     * downlords-faf-client allows the adapter to bind its port (subprocess-orchestration-spec
     * §2.7).
     */
    private static final int RPC_CONNECT_ATTEMPTS = 20;

    /** Delay between JSON-RPC connect attempts. */
    private static final Duration RPC_RETRY_DELAY = Duration.ofMillis(250);

    /** Overall bound on attaching the peer, comfortably past the retry budget above. */
    private static final Duration RPC_CONNECT_TIMEOUT = Duration.ofSeconds(10);

    /** Call timeout for the peer. Nothing is called on it here; it holds the socket open. */
    private static final Duration RPC_CALL_TIMEOUT = Duration.ofSeconds(5);

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

        IceAdapterSettings settings = parent.toValidatedAdapterSettings(spec);
        MockClientCli.applyLoggingProperties(settings);
        LoggingSetup.configure(MockClientCli.COMPONENT_NAME);
        Logger log = LoggerFactory.getLogger(LaunchIceCommand.class);

        SubprocessManager adapter;
        try {
            adapter = new IceAdapterLauncher(settings).start();
        } catch (IceAdapterLaunchException e) {
            // Single-line, log-ready message — no stack trace (WBS-3.1.2.2 acceptance criteria).
            log.error(e.getMessage());
            return ExitCodes.RUNTIME;
        }

        IceAdapterConnection rpc =
                new IceAdapterConnection(
                        settings.rpcPort(),
                        RPC_CONNECT_ATTEMPTS,
                        RPC_RETRY_DELAY,
                        RPC_CALL_TIMEOUT);
        try {
            // The adapter is not usable by a game until this exists (see the class javadoc), so a
            // failure to establish it is a failed run rather than a warning: the command would
            // otherwise report OK while sitting next to an adapter that drops the first game that
            // connects to it.
            rpc.connect().get(RPC_CONNECT_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
            log.info(
                    "JSON-RPC peer attached on port {}; the adapter can now serve a game",
                    settings.rpcPort());
        } catch (TimeoutException | ExecutionException e) {
            log.error(
                    "could not attach a JSON-RPC peer on port {}: {}",
                    settings.rpcPort(),
                    e.getMessage());
            rpc.close();
            adapter.terminate();
            return ExitCodes.RUNTIME;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            rpc.close();
            adapter.terminate();
            return ExitCodes.RUNTIME;
        }

        try {
            int earlyCode = adapter.onExit().get(durationSeconds, TimeUnit.SECONDS);
            log.error(
                    "ICE adapter exited on its own before the {}s run window; exit code {}",
                    durationSeconds,
                    earlyCode);
            rpc.close();
            return ExitCodes.RUNTIME;
        } catch (TimeoutException e) {
            // Healthy path: the adapter is still running after the window — fall through and stop.
            log.info("Run window of {}s elapsed; terminating ICE adapter", durationSeconds);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("Interrupted while running ICE adapter; terminating");
            rpc.close();
            adapter.terminate();
            return ExitCodes.RUNTIME;
        } catch (ExecutionException e) {
            log.error("Failed to track ICE adapter exit: {}", e.getMessage());
            rpc.close();
            adapter.terminate();
            return ExitCodes.RUNTIME;
        }

        // Closed before the adapter is terminated, so the peer goes away first and the adapter
        // sees an ordinary client disconnect rather than dying with one attached.
        rpc.close();
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
