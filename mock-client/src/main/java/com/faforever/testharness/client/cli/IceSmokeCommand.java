package com.faforever.testharness.client.cli;

import com.faforever.testharness.client.config.MockClientCli;
import com.faforever.testharness.client.config.MockClientConfig;
import com.faforever.testharness.shared.logging.LoggingSetup;
import java.util.concurrent.Callable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.ParentCommand;
import picocli.CommandLine.Spec;

/**
 * Stub for the {@code ice-smoke} subcommand. Eventually exercises {@code faf-ice-adapter}'s GPGNet
 * endpoint as a connectivity sanity check, suitable for a CI "is everything reachable?" gate.
 *
 * <p>For WBS-3.1.5.2 (CLI scaffolding) this validates config and logs a TODO line, returning {@link
 * ExitCodes#NOT_IMPLEMENTED} so CI cannot mistake the stub for a real success.
 */
@Command(
        name = "ice-smoke",
        mixinStandardHelpOptions = true,
        exitCodeOnExecutionException = ExitCodes.RUNTIME,
        description =
                "ICE-adapter connectivity smoke test: bring up the adapter, verify GPGNet "
                        + "handshake, exit.")
public final class IceSmokeCommand implements Callable<Integer> {

    /** Picocli auto-injects the root command so the stub can read the populated config. */
    @ParentCommand private MockClientCli parent;

    /** Picocli auto-injects the active {@link CommandSpec} for scoped error reporting. */
    @Spec private CommandSpec spec;

    /**
     * Validate the config, set up logging, log a TODO line, and exit with {@link
     * ExitCodes#NOT_IMPLEMENTED}.
     *
     * @return {@link ExitCodes#NOT_IMPLEMENTED}
     */
    @Override
    public Integer call() {
        MockClientConfig config = parent.toValidatedConfig(spec);
        MockClientCli.applyLoggingProperties(config);
        LoggingSetup.configure(MockClientCli.COMPONENT_NAME);

        Logger log = LoggerFactory.getLogger(IceSmokeCommand.class);
        log.info("TODO: 'ice-smoke' not implemented yet");
        return ExitCodes.NOT_IMPLEMENTED;
    }
}
