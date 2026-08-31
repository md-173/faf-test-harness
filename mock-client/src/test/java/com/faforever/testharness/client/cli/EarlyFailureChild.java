package com.faforever.testharness.client.cli;

import com.faforever.testharness.client.config.ConfigLoader;
import com.faforever.testharness.shared.logging.LoggingSetup;
import java.util.concurrent.Callable;
import picocli.CommandLine;

/**
 * Child program for {@link LogLevelFlagEndToEndTest}. Runs a subcommand that throws <em>before</em>
 * anything applies the logging properties — the window every real subcommand has between entering
 * {@code call()} and reaching {@code MockClientCli.applyLoggingProperties}.
 *
 * <p>The real subcommands cannot stand in for this. They validate their config on the first line,
 * and {@code toValidatedConfig} converts {@link IllegalArgumentException} into a picocli {@code
 * ParameterException}, which is a usage error rather than an execution exception and never reaches
 * {@link ExecutionExceptionHandler}. Only an unchecked exception of another type lands in the
 * window, and no CLI input produces one today — the surviving {@code Objects.requireNonNull} guards
 * in the config records sit on values built through {@code Optional.ofNullable}. That is precisely
 * why it needs a purpose-built subcommand: the window is real, reachable by any future
 * unanticipated throw, and invisible to every other test.
 *
 * <p>A child JVM rather than an in-process test because Logback resolves {@code ${LOG_LEVEL}} and
 * {@code ${LOG_FILE}} exactly once, when the first logger is created. What this pins is which
 * values were in place at that moment, which only a fresh process can show.
 */
public final class EarlyFailureChild {

    /** Name the throwing subcommand is registered under. */
    public static final String SUBCOMMAND = "early-boom";

    /** Message thrown, so the parent test can tie the records back to this throw. */
    public static final String MESSAGE = "early failure before logging was configured";

    private EarlyFailureChild() {}

    /** Throws immediately — no config validation, no logging setup. */
    @CommandLine.Command(name = SUBCOMMAND)
    static final class EarlyBoom implements Callable<Integer> {

        @Override
        public Integer call() {
            throw new IllegalStateException(MESSAGE);
        }
    }

    /**
     * Entry point.
     *
     * @param args command-line arguments, expected to select {@link #SUBCOMMAND}
     */
    public static void main(final String[] args) {
        // System.getenv(), as the real Main does, so the environment layer of the config is live
        // and a test can drive FAF_MOCK_CLIENT_* through to the same code path.
        CommandLine commandLine = ConfigLoader.newCommandLine(args, System.getenv());
        commandLine.addSubcommand(SUBCOMMAND, new EarlyBoom());
        int exitCode = commandLine.execute(args);
        LoggingSetup.shutdown();
        System.exit(exitCode);
    }
}
