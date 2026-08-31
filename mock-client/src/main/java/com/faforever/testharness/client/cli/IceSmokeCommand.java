package com.faforever.testharness.client.cli;

import com.faforever.testharness.client.config.IceAdapterSettings;
import com.faforever.testharness.client.config.MockClientCli;
import com.faforever.testharness.client.process.IceReachabilityCheck;
import com.faforever.testharness.shared.logging.LoggingSetup;
import java.time.Duration;
import java.util.concurrent.Callable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.ParameterException;
import picocli.CommandLine.ParentCommand;
import picocli.CommandLine.Spec;

/**
 * {@code ice-smoke} subcommand (WBS-3.1.4.3): bring up {@code faf-ice-adapter}, prove both of the
 * endpoints a session depends on are serving, tear it down, and exit 0 or non-zero.
 *
 * <p>This is the one command that exercises the harness end to end without a FAF account: no lobby,
 * no OAuth, and nothing the harness itself sends leaves loopback (the adapter subprocess still
 * opens its own telemetry WebSocket, which upstream offers no way to disable). {@code run} needs
 * lobby credentials, and the live lifecycle tests are Gradle tasks with a session-sized budget;
 * this is the cheap precondition you run before paying for either, and the check that separates
 * "the adapter never came up" from "the session logic is wrong".
 *
 * <p>Unlike its sibling diagnostics it does <em>not</em> call {@link
 * MockClientCli#toValidatedConfig}. A localhost reachability check has no lobby leg, so it
 * validates only the adapter settings ({@link MockClientCli#toValidatedAdapterSettings}) and a
 * consumer with no account is not asked to invent OAuth values for fields nothing here reads.
 *
 * <p>The mechanism, the phase-by-phase proof, and the ordering constraints live in {@link
 * IceReachabilityCheck}; this class is the CLI shell around it: flags in, exit code out.
 *
 * <p>Exit codes: {@link ExitCodes#OK} when the adapter is reachable; {@link ExitCodes#RUNTIME} for
 * every negative verdict (binary missing, ports busy, adapter dead, either endpoint not serving);
 * {@link ExitCodes#USAGE} for a bad invocation.
 */
@Command(
        name = "ice-smoke",
        mixinStandardHelpOptions = true,
        description =
                "ICE-adapter reachability check: bring up the adapter, verify its JSON-RPC and "
                        + "GPGNet endpoints are serving, tear it down. Needs no lobby account.")
public final class IceSmokeCommand implements Callable<Integer> {

    /** Total budget for the check when {@code --timeout-seconds} is not given. */
    static final int DEFAULT_TIMEOUT_SECONDS = 20;

    /**
     * Largest budget accepted. An hour is already absurd for a two-second gate; the cap exists so
     * the value stays in a range the phase arithmetic can represent, rather than relying on every
     * downstream conversion to survive an arbitrary one.
     */
    static final int MAX_TIMEOUT_SECONDS = 3600;

    /** Picocli auto-injects the root command so the subcommand can read the populated config. */
    @ParentCommand private MockClientCli parent;

    /** Picocli auto-injects the active {@link CommandSpec} for scoped error reporting. */
    @Spec private CommandSpec spec;

    /**
     * Wall-clock budget for the whole check. The default is failure headroom, not the expected
     * runtime: a healthy adapter is reached in about two seconds, and the check returns as soon as
     * it has its verdict.
     */
    @Option(
            names = "--timeout-seconds",
            defaultValue = "" + DEFAULT_TIMEOUT_SECONDS,
            description =
                    "Budget for the check, in seconds, 1..3600 (default: ${DEFAULT-VALUE}). A "
                            + "reachable adapter answers in about two seconds; this bounds how "
                            + "long an unreachable one is waited for. Tearing the adapter down "
                            + "adds a separately bounded grace outside this budget.")
    private int timeoutSeconds;

    /**
     * Runs the reachability check and maps its verdict to an exit code.
     *
     * @return {@link ExitCodes#OK} when the adapter is reachable, otherwise {@link
     *     ExitCodes#RUNTIME}
     * @throws ParameterException if {@code --timeout-seconds} is outside 1..{@value
     *     #MAX_TIMEOUT_SECONDS}, or an adapter setting is invalid; picocli renders these as usage
     *     errors and exits {@link ExitCodes#USAGE}
     */
    @Override
    public Integer call() {
        if (timeoutSeconds <= 0 || timeoutSeconds > MAX_TIMEOUT_SECONDS) {
            // A non-positive budget would make every phase's wait expire immediately, reporting an
            // unreachable adapter that was never given a chance to answer.
            throw new ParameterException(
                    spec.commandLine(),
                    "--timeout-seconds must be between 1 and "
                            + MAX_TIMEOUT_SECONDS
                            + "; got "
                            + timeoutSeconds);
        }

        IceAdapterSettings settings = parent.toValidatedAdapterSettings(spec);
        MockClientCli.applyLoggingProperties(settings);
        LoggingSetup.configure(MockClientCli.COMPONENT_NAME);
        Logger log = LoggerFactory.getLogger(IceSmokeCommand.class);

        IceReachabilityCheck.Result result =
                new IceReachabilityCheck(settings, Duration.ofSeconds(timeoutSeconds)).run();
        if (result.reachable()) {
            log.info("ice-smoke: PASS - {}", result.detail());
            return ExitCodes.OK;
        }
        // Single-line, log-ready message with no stack trace, as the sibling diagnostics report.
        log.error("ice-smoke: FAIL [{}] {}", result.verdict(), result.detail());
        return ExitCodes.RUNTIME;
    }
}
