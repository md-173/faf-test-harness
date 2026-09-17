package com.faforever.testharness.client.cli;

import com.faforever.testharness.client.config.MockClientCli;
import com.faforever.testharness.client.config.MockClientConfig;
import com.faforever.testharness.client.config.VersionProvider;
import com.faforever.testharness.client.session.CheckpointFailure;
import com.faforever.testharness.client.session.MultiPeerSession;
import com.faforever.testharness.shared.logging.LoggingSetup;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
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
 * {@code session} subcommand (WBS-4.2.1): run a multi-peer session through the live lobby from the
 * release jars and pass or fail on its own. One host and {@code --peers - 1} joiners, each with its
 * own account, adapter and game, host and join a game until every adapter reports every other peer
 * connected and every game has received every other game's traffic, so an adapter that connects but
 * does not forward game packets fails the run. The orchestration is {@link MultiPeerSession},
 * shared with {@code MultiPeerSessionLiveTest}; this class is the CLI shell around it: flags in,
 * exit code out.
 *
 * <p>The clients run in this JVM, and their adapters and games as subprocesses. Sharing the JVM
 * means sharing its fate: an OOM or a stuck lock ends every peer, and any peer failing fails the
 * session.
 *
 * <p>Each peer's credential is one file: a refresh-token file ({@code --peer-refresh-token-file},
 * exchanged at Hydra and rewritten on rotation) or a pre-signed access-token file ({@code
 * --peer-access-token-file}, sent as-is and never renewed). Every peer uses the same channel. When
 * both lists are configured, the one at the higher layer wins, as for the root credential options
 * (harness-runbook.md §3); both at one layer is a usage error.
 *
 * <p>The inherited options supply what every peer shares (lobby, OAuth endpoints, {@code faf-uid},
 * adapter and game binaries, log level). The session sets each peer's credential, adapter ports,
 * auto-launch off, and the host or join intent itself. The root {@code --oauth-refresh-token-file}
 * and {@code --oauth-access-token-file} are therefore ignored entirely, and {@code
 * --ice-adapter-*-port}, {@code --mock-game-launch-delay-seconds}, {@code --host-*}, {@code
 * --target-game-id}, {@code --game-join-password} and {@code --queue-*} are not used, though they
 * are still validated. The run logs which credential list it used and where that came from, so a CI
 * that set both can see which one won.
 *
 * <p>Exit codes: {@link ExitCodes#OK} on a full mesh with two-way game traffic between every pair
 * and no adapter or game left running; {@link ExitCodes#USAGE} for a bad invocation, including no
 * credential list, both lists at one layer, fewer credential files than peers, two peers on one
 * file or (on access tokens) one account, a refresh-token path that is not a regular file, an
 * unreadable or empty file, a missing binary or a {@code --log-level} above INFO (the traffic check
 * reads INFO lines), all refused before any process starts; {@link ExitCodes#RUNTIME} when a
 * checkpoint fails (logged as {@code session: FAIL <peer>: <stage>: <detail>}) or a subprocess
 * survives teardown, which is then killed.
 */
@Command(
        name = "session",
        mixinStandardHelpOptions = true,
        versionProvider = VersionProvider.class,
        exitCodeOnExecutionException = ExitCodes.RUNTIME,
        description =
                "Run a multi-peer session through the live lobby: one host and --peers - 1 "
                        + "joiners, each with its own account, adapter and game. Exits 0 when "
                        + "every adapter reports every other peer connected and every game has "
                        + "received every other game's traffic; needs --log-level INFO or finer. "
                        + "Give each peer one credential file with --peer-refresh-token-file or "
                        + "--peer-access-token-file. Sets each peer's credential, adapter ports, "
                        + "launch delay and host or join intent itself: the root "
                        + "--oauth-refresh-token-file and --oauth-access-token-file are ignored, "
                        + "and the --ice-adapter-*-port, --mock-game-launch-delay-seconds, "
                        + "--host-*, --target-game-id, --game-join-password and --queue-* options "
                        + "are not used, though they are still validated.")
public final class SessionCommand implements Callable<Integer> {

    /** The refresh-token channel's option, shared with the layer lookup so a typo cannot pass. */
    static final String PEER_REFRESH_TOKEN_FLAG = "--peer-refresh-token-file";

    /** The access-token channel's option, shared with the layer lookup so a typo cannot pass. */
    static final String PEER_ACCESS_TOKEN_FLAG = "--peer-access-token-file";

    /** Picocli auto-injects the root command so the subcommand can read the populated config. */
    @ParentCommand private MockClientCli parent;

    /** Picocli auto-injects the active {@link CommandSpec} for scoped error reporting. */
    @Spec private CommandSpec spec;

    /**
     * Number of peers, host included. The upper bound is the instance labels A to Z, not a tested
     * limit: runs are verified at 2 to 4 peers, and {@code MultiPeerSession}'s fixed session
     * deadline will be sized by #87's ceiling step.
     */
    @Option(
            names = "--peers",
            defaultValue = "" + MultiPeerSession.MIN_PEERS,
            description =
                    "Number of peers, host included, "
                            + MultiPeerSession.MIN_PEERS
                            + ".."
                            + MultiPeerSession.MAX_PEERS
                            + " (default: ${DEFAULT-VALUE}). Verified at 2 to 4 peers; the 420 s "
                            + "session deadline does not yet grow with this, so a larger session "
                            + "can time out on it.")
    private int peers;

    /** One refresh-token file per peer, host first; extras are unused. */
    @Option(
            names = PEER_REFRESH_TOKEN_FLAG,
            split = ",",
            description =
                    "A peer's refresh-token file, one per peer and host first. Repeat the flag "
                            + "or separate paths with commas. Each file must belong to its own "
                            + "account and is rewritten in place when Hydra rotates the token. "
                            + "Files beyond --peers are unused.")
    private List<Path> peerRefreshTokenFiles = new ArrayList<>();

    /** One access-token file per peer, host first; extras are unused. */
    @Option(
            names = PEER_ACCESS_TOKEN_FLAG,
            split = ",",
            description =
                    "A peer's pre-signed access-token file, one per peer and host first, instead "
                            + "of --peer-refresh-token-file. Repeat the flag or separate paths "
                            + "with commas. Each token must belong to its own account; it is sent "
                            + "as-is, never renewed or rewritten, and the lobby rejects it once "
                            + "expired. Files beyond --peers are unused.")
    private List<Path> peerAccessTokenFiles = new ArrayList<>();

    /**
     * Validates the invocation, runs the session, tears it down, and maps the verdict to an exit
     * code.
     *
     * @return {@link ExitCodes#OK} on a full mesh with two-way game traffic and nothing left
     *     running, otherwise {@link ExitCodes#RUNTIME}
     * @throws ParameterException for a bad invocation; picocli exits {@link ExitCodes#USAGE}
     */
    @Override
    public Integer call() {
        String instanceName = System.getProperty(LoggingSetup.INSTANCE_NAME_ENV);
        if (instanceName == null) {
            instanceName = System.getenv(LoggingSetup.INSTANCE_NAME_ENV);
        }
        if (instanceName != null && !instanceName.isBlank()) {
            // It would become the fallback label for every line, attributing any unlabelled line
            // to one peer.
            throw new ParameterException(
                    spec.commandLine(),
                    LoggingSetup.INSTANCE_NAME_ENV
                            + " must not be set for session, which labels each peer itself");
        }
        if (peers < MultiPeerSession.MIN_PEERS || peers > MultiPeerSession.MAX_PEERS) {
            throw new ParameterException(
                    spec.commandLine(),
                    "--peers must be between "
                            + MultiPeerSession.MIN_PEERS
                            + " and "
                            + MultiPeerSession.MAX_PEERS
                            + "; got "
                            + peers);
        }
        boolean useAccessTokens = chooseAccessTokenChannel();
        String flag = useAccessTokens ? PEER_ACCESS_TOKEN_FLAG : PEER_REFRESH_TOKEN_FLAG;
        List<Path> files = useAccessTokens ? peerAccessTokenFiles : peerRefreshTokenFiles;
        if (files.size() < peers) {
            throw new ParameterException(
                    spec.commandLine(),
                    "--peers is "
                            + peers
                            + " but "
                            + files.size()
                            + " "
                            + flag
                            + " given; every peer needs its own account");
        }
        List<MockClientConfig> bases = new ArrayList<>();
        for (Path file : files.subList(0, peers)) {
            bases.add(
                    useAccessTokens
                            ? parent.toValidatedConfigWithAccessToken(spec, file)
                            : parent.toValidatedConfig(spec, file));
        }
        String title = "faf-test-harness session " + UUID.randomUUID();
        MultiPeerSession session;
        try {
            session = new MultiPeerSession(bases, title);
        } catch (IllegalArgumentException e) {
            throw new ParameterException(spec.commandLine(), e.getMessage(), e);
        }

        Logger log = LoggerFactory.getLogger(SessionCommand.class);
        // Which list won matters to a CI mid-switch: the other one may hold secrets it thinks are
        // in
        // use, and a refresh run spends its tokens while passing.
        log.info(
                "session: credentials from {} ({})",
                flag,
                MockClientCli.layerDescription(spec, flag));
        // Ctrl-C or SIGTERM: tear every peer down before the JVM exits. close() is idempotent and
        // synchronized, so this and the teardown below never both run a peer's teardown.
        Runtime.getRuntime().addShutdownHook(new Thread(session::close, "mc-session-shutdown"));

        boolean passed = false;
        boolean cleanTeardown = false;
        try {
            session.run();
            passed = true;
        } catch (CheckpointFailure f) {
            log.error("session: FAIL {}", f.getMessage());
            log.debug("checkpoint failure", f);
        } catch (InterruptedException e) {
            log.error("session: FAIL interrupted");
            Thread.currentThread().interrupt();
        } finally {
            cleanTeardown = tearDown(session, log);
        }
        if (!passed || !cleanTeardown) {
            return ExitCodes.RUNTIME;
        }
        // Only after teardown, so a consumer reading the log never sees PASS for a run that left
        // a subprocess behind.
        log.info(
                "session: PASS - {} peers, full mesh and two-way game traffic, "
                        + "nothing left running",
                peers);
        return ExitCodes.OK;
    }

    /**
     * Picks the credential channel every peer uses. With only one list configured that list wins;
     * with both, the one at the higher layer wins, as for the root credential options.
     *
     * @return {@code true} for access-token files, {@code false} for refresh-token files
     * @throws ParameterException if no list is configured, or both are at the same layer
     */
    private boolean chooseAccessTokenChannel() {
        boolean refresh = !peerRefreshTokenFiles.isEmpty();
        boolean access = !peerAccessTokenFiles.isEmpty();
        if (!refresh && !access) {
            throw new ParameterException(
                    spec.commandLine(),
                    "session needs one credential file per peer: give "
                            + PEER_REFRESH_TOKEN_FLAG
                            + " or "
                            + PEER_ACCESS_TOKEN_FLAG
                            + " (the root --oauth-refresh-token-file and --oauth-access-token-file "
                            + "are not used by session)");
        }
        if (refresh && access) {
            return MockClientCli.higherLayer(spec, PEER_ACCESS_TOKEN_FLAG, PEER_REFRESH_TOKEN_FLAG)
                    .map(PEER_ACCESS_TOKEN_FLAG::equals)
                    .orElseThrow(
                            () ->
                                    new ParameterException(
                                            spec.commandLine(),
                                            PEER_REFRESH_TOKEN_FLAG
                                                    + " and "
                                                    + PEER_ACCESS_TOKEN_FLAG
                                                    + " are both set "
                                                    + MockClientCli.layerDescription(
                                                            spec, PEER_ACCESS_TOKEN_FLAG)
                                                    + "; supply one"));
        }
        return access;
    }

    /**
     * Closes the session and kills any adapter or game still running afterwards. The interrupt flag
     * is cleared for the bounded waits and restored once the sweep is done.
     *
     * @param session the session to tear down
     * @param log where a survivor is reported
     * @return {@code true} if nothing survived
     */
    private static boolean tearDown(final MultiPeerSession session, final Logger log) {
        boolean interrupted = Thread.interrupted();
        try {
            session.close();
            List<ProcessHandle> survivors = session.survivingSubprocesses();
            if (survivors.isEmpty()) {
                return true;
            }
            List<String> described = new ArrayList<>();
            for (ProcessHandle survivor : survivors) {
                described.add(session.describe(survivor));
                survivor.destroyForcibly();
            }
            log.error("session: FAIL teardown: killed subprocesses that survived: {}", described);
            return false;
        } catch (InterruptedException e) {
            interrupted = true;
            log.error("session: FAIL teardown: interrupted during the survivor sweep");
            return false;
        } finally {
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }
}
