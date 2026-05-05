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
 * Stub for the {@code run} subcommand. Eventually drives a full Mock Client session: authenticate
 * against the lobby, join the queue, spawn {@code faf-ice-adapter} and {@code mock-game}, run the
 * lifecycle FSM, and tear down on game-end.
 *
 * <p>For WBS-3.1.5.2 (CLI scaffolding) this validates config and logs a TODO line, returning {@link
 * ExitCodes#NOT_IMPLEMENTED} so CI cannot mistake the stub for a real success. The owning track is
 * responsible for replacing the body of {@link #call()} with the real lifecycle.
 */
@Command(
        name = "run",
        mixinStandardHelpOptions = true,
        description = "Run a full mock client session: authenticate, queue, play, teardown.")
public final class RunCommand implements Callable<Integer> {

    /** Picocli auto-injects the root command so the stub can read the populated config. */
    @ParentCommand private MockClientCli parent;

    /** Picocli auto-injects the active {@link CommandSpec} for scoped error reporting. */
    @Spec private CommandSpec spec;

    /**
     * Validate the config, set up logging, log a TODO line, and exit with {@link
     * ExitCodes#NOT_IMPLEMENTED}. The real session loop is owned by a sibling track.
     *
     * @return {@link ExitCodes#NOT_IMPLEMENTED}
     */
    @Override
    public Integer call() {
        MockClientConfig config = parent.toValidatedConfig(spec);
        MockClientCli.applyLoggingProperties(config);
        LoggingSetup.configure(MockClientCli.COMPONENT_NAME);

        Logger log = LoggerFactory.getLogger(RunCommand.class);
        log.info("TODO: 'run' not implemented yet");
        return ExitCodes.NOT_IMPLEMENTED;
    }
}
