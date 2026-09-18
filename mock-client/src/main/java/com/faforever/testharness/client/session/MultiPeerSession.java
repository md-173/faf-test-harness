package com.faforever.testharness.client.session;

import ch.qos.logback.classic.Level;
import com.faforever.testharness.client.config.GameHostConfig;
import com.faforever.testharness.client.config.GameJoinConfig;
import com.faforever.testharness.client.config.MockClientConfig;
import com.faforever.testharness.client.lobby.AuthenticationException;
import com.faforever.testharness.client.lobby.GameConfig;
import com.faforever.testharness.client.lobby.SessionState;
import com.faforever.testharness.client.lobby.TokenSource;
import com.faforever.testharness.client.lobby.TokenSources;
import com.faforever.testharness.client.state.ClientState;
import java.io.IOException;
import java.net.DatagramSocket;
import java.net.ServerSocket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

/**
 * One multi-peer session (WBS-4.2.1): a host and N-1 joiners, each a Mock Client with its own lobby
 * account, port set, real {@code faf-ice-adapter} and mock-game, completing a host/join through the
 * live lobby until every adapter reports every other peer connected. The {@code session} command
 * and {@code MultiPeerSessionLiveTest} both run a session through this class.
 *
 * <p><b>In-process clients.</b> The clients share this JVM; their adapters and games are separate
 * processes. They never touch each other in-process except for one value: the host's game uid,
 * taken from {@link com.faforever.testharness.client.state.MockClientLifecycle#gameLaunched()} and
 * given to each joiner as its join target. Every other exchange crosses the lobby, exactly as
 * separate machines would. Sharing the JVM means sharing its fate: an OOM, a stuck lock or a stray
 * {@code System.exit} ends every peer, and any peer failing fails the session by design.
 *
 * <p><b>The verdict</b> has two parts, both required.
 *
 * <ul>
 *   <li><b>Full mesh:</b> the adapter's {@code onConnected(localId, remoteId, connected)}
 *       notification (json-rpc-spec.md §5), keeping the latest verdict per remote id, since
 *       verdicts arrive in no fixed order once there are more than two peers. {@code
 *       onIceConnectionStateChanged} is deliberately not waited on: {@code completed} is
 *       unreachable in adapter 3.3.14.
 *   <li><b>Game traffic (WBS-4.3.2):</b> every game has received every other game's datagrams, with
 *       an advancing sequence ({@link TrafficEvidence}). {@code onConnected} is the adapter's own
 *       claim; this is the games' independent proof that the adapter forwards what they send, so an
 *       adapter that connects but drops game packets fails the session. It needs the games and the
 *       client at INFO or finer, which the constructor enforces.
 * </ul>
 *
 * <p><b>One known cause of a slow traffic pass.</b> If an adapter re-announces a peer at a
 * <em>different</em> relay port, that peer's send sequence restarts at zero (WBS-3.2.2.5 installs a
 * fresh counter per registration), while the receiving side only ever raises its highest-seen
 * sequence. The advancing check then makes no progress until the restarted stream climbs past the
 * old high: bounded, about a second per ten datagrams already sent, but it can eat most of {@link
 * #TRAFFIC_TIMEOUT}. WBS-4.3.4 will hit the same shape deliberately when a peer rejoins.
 *
 * <p><b>Auto-launch is off on every peer by default</b> ({@code mockGameLaunchDelaySeconds = -1},
 * WBS-4.3.1). faf-server accepts a {@code game_join} only while the game is in {@code
 * GameState.LOBBY} and leaves that state the moment the host reports {@code GameState Launching},
 * so a host on a timer would make itself unjoinable while a joiner is still booting two JVMs. The
 * peer links are established during the lobby phase, so nothing is lost.
 *
 * <p>The host alone can be given a delay, through the three-argument constructor, for a caller that
 * needs the session to leave its lobby phase (WBS-4.3.4): no other lever exists, since only the
 * host reporting {@code Launching} moves the server's game to LIVE. Joiners never auto-launch
 * whatever is asked, and the delay is floored by {@link #minHostLaunchDelaySeconds(int)}, which
 * grows with the peer count, so the reasoning above still holds.
 *
 * <p><b>The host uses {@code friends} visibility</b> (WBS-4.3.3). faf-server's {@code
 * command_game_join} checks foes, lobby state, init mode and password but never visibility, which
 * only filters the game list, so joiners still join by uid, and strangers in the test lobby cannot
 * see and join the game.
 *
 * <p><b>Join order.</b> Joiners start one at a time, each after the previous reached {@code
 * JOINING}, so join order, and with it faf-server's {@code ConnectToPeer} offer directions, is the
 * order of the configs given.
 *
 * <p><b>Every wait is bounded and named</b>: a missed checkpoint throws {@link CheckpointFailure}
 * with the peer, the stage, the limit that ran out and what had been seen by then. Each wait is
 * bound by its own budget and by {@link #SESSION_DEADLINE}, whichever is sooner.
 *
 * <p><b>Labels.</b> Each peer runs under an instance label, A to Z in join order. Its components
 * capture the label from the thread that builds them ({@code InstanceLabel}), so every peer is
 * built, started and shut down inside its own labelled scope here. Do not set {@code INSTANCE_NAME}
 * for a JVM running a session: it would become the fallback label for every line.
 *
 * <p><b>Shutdown.</b> {@link #close()} may be called from another thread, such as a shutdown hook,
 * while {@link #run()} is in progress. Each peer is built and started under the same monitor {@link
 * #close()} holds, so a peer is either torn down by it or refused with the {@code shutdown} stage.
 * A session is single use.
 */
public final class MultiPeerSession implements AutoCloseable {

    /** The fewest peers a session can run: one host and one joiner. */
    public static final int MIN_PEERS = 2;

    /** The most peers a session can run, one per instance label A to Z. */
    public static final int MAX_PEERS = 26;

    /** Map the host advertises; any real map folder name works. */
    static final String HOST_MAP = "scmp_007";

    /** Featured mod the host advertises. */
    static final String HOST_MOD = "faf";

    /** Host visibility; see the class javadoc. */
    static final String HOST_VISIBILITY = "friends";

    /** {@code mockGameLaunchDelaySeconds} value that disables auto-launch. */
    static final int LAUNCH_DISABLED = -1;

    /**
     * The shortest host launch delay a caller may ask for at {@link #MIN_PEERS}, before the
     * per-joiner allowance below. The host's timer starts when its own game enters HOSTING and
     * every joiner's bring-up has to finish inside it, so this is comfortably above the 20 to 40 s
     * an observed two-peer session takes and leaves room for a slower runner.
     */
    static final Duration MIN_HOST_LAUNCH_DELAY = Duration.ofSeconds(90);

    /**
     * Added to {@link #MIN_HOST_LAUNCH_DELAY} for each joiner beyond the first. Joiners start
     * serially, each only once the previous reached JOINING, so the bring-up the host has to stay
     * joinable through grows with the peer count; a floor calibrated at two peers would otherwise
     * bless a delay that leaves later joiners refused with {@code game_not_ready}.
     */
    static final Duration PER_JOINER_LAUNCH_ALLOWANCE = Duration.ofSeconds(30);

    /**
     * Budget for the whole session, from the first login to the full mesh. Every named wait draws
     * from it as well as from its own budget, and a failure says which ran out. Observed sessions
     * take 20 to 40 s at two to four peers; this is headroom for slower ICE negotiation on a CI
     * runner (#343, #366), not a target. Teardown is not part of it.
     */
    static final Duration SESSION_DEADLINE = Duration.ofSeconds(420);

    /**
     * Budget for every direction of game traffic to be proven once the mesh is up, shared by all of
     * them: every pair starts sending as soon as its link is up, so they are proven together.
     * Generous against a 1 s progress interval; headroom, not a measurement.
     */
    static final Duration TRAFFIC_TIMEOUT = Duration.ofSeconds(60);

    /** Budget for one client's connect, auth handshake and welcome, including the Hydra hop. */
    private static final Duration SESSION_TIMEOUT = Duration.ofSeconds(90);

    /**
     * Budget from a client reaching IDLE, where it sends {@code game_host} or {@code game_join}, to
     * both of its subprocesses being up: the server's {@code game_launch}, the adapter JVM starting
     * and binding, its setup RPCs, and the game JVM starting.
     */
    private static final Duration GAME_LAUNCH_TIMEOUT = Duration.ofSeconds(90);

    /**
     * Budget for a launched client to take up its role: the GPGNet handshake plus the server's
     * {@code HostGame} for the host, and the same plus {@code JoinGame} for a joiner.
     */
    private static final Duration ROLE_TIMEOUT = Duration.ofSeconds(90);

    /**
     * Budget for ICE to complete on every link once the last joiner is in, shared by the whole
     * mesh. Processes on one host negotiate over host candidates, which is fast; this is headroom
     * for the lobby relay hop.
     */
    private static final Duration PEER_CONNECTED_TIMEOUT = Duration.ofSeconds(90);

    /** Budget for a requested shutdown to drive a peer to TERMINATED and run its teardown. */
    private static final Duration TEARDOWN_TIMEOUT = Duration.ofSeconds(30);

    /**
     * Budget for every subprocess to disappear from this JVM's descendants. Polled rather than
     * sampled: teardown returns once the processes are reaped, but the OS can hold the handles a
     * moment longer.
     */
    private static final Duration NO_ORPHANS_TIMEOUT = Duration.ofSeconds(20);

    /** Poll slice for every bounded wait built on a repeated probe. */
    private static final Duration POLL_SLICE = Duration.ofMillis(250);

    /**
     * The session's start marker, which belongs to no peer and so carries no label, and teardown
     * warnings, each logged under its peer's label.
     */
    private static final Logger LOG = LoggerFactory.getLogger(MultiPeerSession.class);

    /** One validated base config per peer, host first; each carries that peer's account. */
    private final List<MockClientConfig> bases;

    /** One token source per peer, in the same order, resolved before anything starts. */
    private final List<TokenSource> tokens;

    /** What each game has received from each other game, captured while the session runs. */
    private final TrafficEvidence traffic = new TrafficEvidence();

    /** Title the host advertises. */
    private final String hostTitle;

    /** Seconds the host's game waits before launching, or {@link #LAUNCH_DISABLED} for never. */
    private final int hostLaunchDelaySeconds;

    /**
     * Every peer built so far, host first. Written by {@link #run()} and read by {@link #close()},
     * possibly on another thread.
     */
    private final List<SessionPeer> peers = new CopyOnWriteArrayList<>();

    /** Whether {@link #run()} has been called. Guarded by {@code this}. */
    private boolean started;

    /** Whether {@link #close()} has been called. Guarded by {@code this}. */
    private boolean closed;

    /** {@link System#nanoTime()} at which {@link #SESSION_DEADLINE} runs out. */
    private long deadline;

    /**
     * Prepares a session without starting anything: checks the peer count, that no two peers share
     * a refresh-token file, that every token file can be read, that the adapter, game and {@code
     * faf-uid} binaries exist, and that the log level lets game traffic be seen. A second login
     * with the same account signs the first out, which would otherwise surface much later as an
     * unexplained lobby disconnect.
     *
     * @param peerBases one validated config per peer, host first and joiners in join order. Each
     *     supplies the peer's account and the settings every peer shares; its ports, launch delay
     *     and host, join and queue intent are replaced by the session
     * @param hostTitle the title the host advertises
     * @throws IllegalArgumentException if the count is outside {@value #MIN_PEERS} to {@value
     *     #MAX_PEERS}, a token file cannot be read, two peers share one, a binary is missing, or
     *     the log level is above INFO
     */
    public MultiPeerSession(final List<MockClientConfig> peerBases, final String hostTitle) {
        this(peerBases, hostTitle, LAUNCH_DISABLED);
    }

    /**
     * As {@link #MultiPeerSession(List, String)}, with the host allowed to launch its match on a
     * timer (WBS-4.3.4).
     *
     * <p>Only a caller that needs the session to leave its lobby phase should pass a delay: a
     * post-launch scenario cannot be reached any other way, because faf-server's {@code
     * handle_game_state} moves a game to LIVE only on the <em>host</em> reporting {@code GameState
     * Launching}. Joiners never auto-launch, whatever is passed here, so each joiner's own match
     * timer cannot end its session on a schedule the caller did not choose.
     *
     * <p>The delay is floored at {@link #MIN_HOST_LAUNCH_DELAY} rather than trusted, because the
     * invariant it relaxes is load-bearing: the host must stay joinable until every joiner is in,
     * and a host that launches first makes itself unjoinable, which surfaces as a joiner's {@code
     * game_join} being refused with {@code game_not_ready}.
     *
     * @param peerBases one validated config per peer, as above
     * @param hostTitle the title the host advertises
     * @param hostLaunchDelaySeconds seconds the host's game waits before launching, or {@link
     *     #LAUNCH_DISABLED} for the default of never
     * @throws IllegalArgumentException for the reasons above, or if the delay is anything other
     *     than {@link #LAUNCH_DISABLED} below the floor for this peer count
     */
    public MultiPeerSession(
            final List<MockClientConfig> peerBases,
            final String hostTitle,
            final int hostLaunchDelaySeconds) {
        if (hostLaunchDelaySeconds != LAUNCH_DISABLED
                && hostLaunchDelaySeconds < minHostLaunchDelaySeconds(peerBases.size())) {
            throw new IllegalArgumentException(
                    "a host launch delay must be at least "
                            + minHostLaunchDelaySeconds(peerBases.size())
                            + "s at "
                            + peerBases.size()
                            + " peers so the game stays joinable through bring-up, got "
                            + hostLaunchDelaySeconds
                            + "s");
        }
        if (peerBases.size() < MIN_PEERS || peerBases.size() > MAX_PEERS) {
            throw new IllegalArgumentException(
                    "a session needs "
                            + MIN_PEERS
                            + " to "
                            + MAX_PEERS
                            + " peers, got "
                            + peerBases.size());
        }
        this.bases = List.copyOf(peerBases);
        this.hostTitle = hostTitle;
        this.hostLaunchDelaySeconds = hostLaunchDelaySeconds;
        List<TokenSource> resolved = new ArrayList<>();
        Map<Path, String> owners = new HashMap<>();
        for (int i = 0; i < bases.size(); i++) {
            String label = labelFor(i);
            MockClientConfig base = bases.get(i);
            Path file = base.oauthRefreshTokenFile();
            try {
                resolved.add(TokenSources.fromConfig(base));
                file = file.toRealPath();
            } catch (AuthenticationException | IOException e) {
                throw new IllegalArgumentException(
                        "peer " + label + ": cannot read refresh-token file " + file, e);
            }
            String previous = owners.putIfAbsent(file, label);
            if (previous != null) {
                throw new IllegalArgumentException(
                        "peer "
                                + label
                                + ": refresh-token file "
                                + file
                                + " is also peer "
                                + previous
                                + "'s; every peer needs its own account");
            }
        }
        this.tokens = List.copyOf(resolved);
        for (MockClientConfig base : bases) {
            // The traffic checkpoint reads the games' INFO progress lines, which neither the games
            // nor this JVM emit above INFO. Logback reads an unrecognised level as DEBUG.
            if (!Level.INFO.isGreaterOrEqual(Level.toLevel(base.logLevel(), Level.DEBUG))) {
                throw new IllegalArgumentException(
                        "--log-level must be INFO or finer for a session, which reads game traffic"
                                + " from the games' INFO lines; got "
                                + base.logLevel());
            }
        }
        if (!TrafficEvidence.capturable()) {
            throw new IllegalArgumentException(
                    "this JVM does not log subprocess output at INFO, so game traffic cannot be"
                            + " seen; set LOG_LEVEL to INFO or finer");
        }
        // Checked here rather than at launch: the host would otherwise log in, rotating its
        // refresh token, before a wrong path surfaced.
        for (MockClientConfig base : bases) {
            requireBinary("faf-ice-adapter", base.iceAdapterBinaryPath());
            requireBinary("mock-game", base.mockGameBinaryPath());
            base.uidBinaryPath().ifPresent(uid -> requireBinary("faf-uid", uid));
        }
    }

    /**
     * Runs the session: the host through welcome, {@code game_launch} and HOSTING, then each joiner
     * in turn through welcome, {@code game_launch} and JOINING, then the full mesh, then game
     * traffic. Returns normally only when every peer's adapter reports every other peer connected
     * and every game has received every other game's datagrams.
     *
     * @throws CheckpointFailure naming the peer and the stage, if any checkpoint does not pass
     * @throws InterruptedException if any bounded wait is interrupted
     * @throws IllegalStateException if the session was already run
     */
    public void run() throws InterruptedException {
        synchronized (this) {
            if (started) {
                throw new IllegalStateException("a session can only run once");
            }
            started = true;
            // Before any game starts, so no progress line is missed, and under the monitor, so a
            // concurrent close() either detaches it or runs first and leaves it unattached.
            if (!closed) {
                traffic.attach();
            }
        }
        deadline = System.nanoTime() + SESSION_DEADLINE.toNanos();
        LOG.info("session: {} peers, host title '{}'", bases.size(), hostTitle);

        // A hosts. Reaching IDLE sends game_host; the server answers game_launch, which is what
        // spawns A's adapter and game and completes gameLaunched with the uid.
        Started host = startPeer(0, null);
        GameConfig hosted;
        try (MDC.MDCCloseable ignored = host.peer().labelled()) {
            host.peer().identity(await(host.welcome(), SESSION_TIMEOUT, host.peer(), "welcome"));
            hosted =
                    await(
                            host.peer().lifecycle().gameLaunched(),
                            GAME_LAUNCH_TIMEOUT,
                            host.peer(),
                            "game_launch");
            await(host.role(), ROLE_TIMEOUT, host.peer(), "HOSTING");
        }

        // Only now is the game joinable: the server marks it hosted when A's game reports Lobby.
        for (int i = 1; i < bases.size(); i++) {
            Started joiner = startPeer(i, hosted.uid());
            try (MDC.MDCCloseable ignored = joiner.peer().labelled()) {
                joiner.peer()
                        .identity(
                                await(joiner.welcome(), SESSION_TIMEOUT, joiner.peer(), "welcome"));
                checkDistinctAccount(joiner.peer());
                await(
                        joiner.peer().lifecycle().gameLaunched(),
                        GAME_LAUNCH_TIMEOUT,
                        joiner.peer(),
                        "game_launch");
                await(joiner.role(), ROLE_TIMEOUT, joiner.peer(), "JOINING");
            }
        }

        awaitFullMesh();
        awaitTraffic();
    }

    /**
     * Every peer built so far, host first and joiners in join order.
     *
     * @return an unmodifiable snapshot
     */
    public List<SessionPeer> peers() {
        return List.copyOf(peers);
    }

    /**
     * Shuts every peer down, joiners first in reverse join order and the host last, and waits for
     * each teardown. One throwing shutdown never stops the others, so every adapter and game gets
     * its teardown. Idempotent; a {@link #run()} still in progress on another thread starts no
     * further peer.
     *
     * <p>Call it with the thread's interrupt flag clear: an interrupt cuts every bounded teardown
     * wait short. The flag is left as it was found.
     */
    @Override
    public synchronized void close() {
        if (closed) {
            return;
        }
        closed = true;
        traffic.detach();
        for (int i = peers.size() - 1; i >= 0; i--) {
            SessionPeer peer = peers.get(i);
            try (MDC.MDCCloseable ignored = peer.labelled()) {
                shutdown(peer);
            } catch (RuntimeException e) {
                try (MDC.MDCCloseable ignored = peer.labelled()) {
                    LOG.warn("{} shutdown threw", peer.name(), e);
                }
            }
        }
    }

    /**
     * The "pgrep-clean" sweep: once the session is closed, no peer's adapter or game may still be
     * running under this JVM. Polls up to {@link #NO_ORPHANS_TIMEOUT} for them to disappear.
     *
     * @return the processes still running after that, or empty
     * @throws InterruptedException if the wait is interrupted
     */
    public List<ProcessHandle> survivingSubprocesses() throws InterruptedException {
        Set<String> needles = new HashSet<>();
        for (SessionPeer peer : peers) {
            needles.add(peer.config().iceAdapterBinaryPath().getFileName().toString());
            needles.add(peer.config().mockGameBinaryPath().getFileName().toString());
        }
        if (needles.isEmpty()) {
            return List.of();
        }
        long waitUntil = System.nanoTime() + NO_ORPHANS_TIMEOUT.toNanos();
        while (true) {
            List<ProcessHandle> survivors =
                    ProcessHandle.current()
                            .descendants()
                            .filter(ProcessHandle::isAlive)
                            .filter(handle -> runsAnyOf(handle, needles))
                            .toList();
            // Checked once more after the last pause, so a process that exits in the final slice
            // is not reported.
            if (survivors.isEmpty() || System.nanoTime() >= waitUntil) {
                return survivors;
            }
            pollPause(waitUntil);
        }
    }

    /**
     * Describes a surviving subprocess for a failure message, naming the peer it belonged to. Both
     * the adapter and the game are launched with {@code --gpgnet-port}, and each peer's is its own.
     *
     * @param process a process from {@link #survivingSubprocesses()}
     * @return the owning peer's name and the command line, or the command line alone
     */
    public String describe(final ProcessHandle process) {
        String line = process.info().commandLine().orElse("pid " + process.pid());
        return ownerOf(line, peers).map(owner -> owner + " " + line).orElse(line);
    }

    /**
     * The peer whose GPGNet port a command line was launched with.
     *
     * @param commandLine a subprocess command line
     * @param candidates the session's peers
     * @return the peer's name, or empty if none matches
     */
    static Optional<String> ownerOf(final String commandLine, final List<SessionPeer> candidates) {
        List<String> args = List.of(commandLine.split("\\s+"));
        int flag = args.indexOf("--gpgnet-port");
        if (flag < 0 || flag + 1 >= args.size()) {
            return Optional.empty();
        }
        String port = args.get(flag + 1);
        return candidates.stream()
                .filter(peer -> port.equals(Integer.toString(peer.config().iceAdapterGpgNetPort())))
                .map(SessionPeer::name)
                .findFirst();
    }

    /**
     * Whether a process's command line mentions any of the given binary names.
     *
     * @param process the process
     * @param needles binary file names
     * @return {@code true} if its command line contains one
     */
    private static boolean runsAnyOf(final ProcessHandle process, final Set<String> needles) {
        return process.info()
                .commandLine()
                .filter(line -> needles.stream().anyMatch(line::contains))
                .isPresent();
    }

    /**
     * One started peer and the futures taken before it started.
     *
     * @param peer the peer
     * @param welcome its welcome identity
     * @param role completes when it reaches HOSTING or JOINING
     */
    private record Started(
            SessionPeer peer,
            CompletableFuture<SessionState> welcome,
            CompletableFuture<Void> role) {}

    /**
     * Builds, registers and starts one peer under the session monitor, so a concurrent {@link
     * #close()} either tears it down or makes it refuse to start. Ports are allocated here, after
     * the earlier peers' adapters hold theirs.
     *
     * @param index the peer's position, 0 for the host
     * @param hostUid the host's game uid for a joiner, {@code null} for the host
     * @return the started peer
     * @throws CheckpointFailure if the session was closed, or ports cannot be allocated or repeat
     */
    private synchronized Started startPeer(final int index, final Integer hostUid) {
        String label = labelFor(index);
        String role = hostUid == null ? "host" : "joiner";
        String name = label + "(" + role + ")";
        if (closed) {
            throw new CheckpointFailure(
                    name, "shutdown", "the session was closed before this peer started");
        }
        AdapterPorts ports;
        try {
            ports = freeAdapterPorts();
        } catch (IOException e) {
            throw new CheckpointFailure(name, "ports", "could not allocate a free port set", e);
        }
        MockClientConfig config =
                hostUid == null
                        ? hostConfig(bases.get(index), ports, hostTitle, hostLaunchDelaySeconds)
                        : joinConfig(bases.get(index), ports, hostUid);
        SessionPeer peer = new SessionPeer(label, role, config);
        peers.add(peer);
        checkDistinctPorts(peer);
        // Taken before the events that can reach them. StateMachine.stateReached only
        // short-circuits while the state is still current, so a future asked for after the FSM
        // has been through and left that state can never complete.
        CompletableFuture<Void> reached =
                peer.lifecycle()
                        .stateReached(hostUid == null ? ClientState.HOSTING : ClientState.JOINING);
        try (MDC.MDCCloseable ignored = peer.labelled()) {
            return new Started(peer, peer.lifecycle().start(tokens.get(index)), reached);
        }
    }

    /**
     * Refuses a joiner the lobby authenticated as an account an earlier peer already uses: two
     * different token files can still belong to one account.
     *
     * @param joiner the joiner that just received its welcome
     * @throws CheckpointFailure if its id matches an earlier peer's
     */
    private void checkDistinctAccount(final SessionPeer joiner) {
        for (SessionPeer other : peers) {
            if (other != joiner
                    && other.identity() != null
                    && other.identity().id() == joiner.identity().id()) {
                throw new CheckpointFailure(
                        joiner.name(),
                        "welcome",
                        "logged in as id "
                                + joiner.identity().id()
                                + ", the same account as "
                                + other.name()
                                + "; every peer needs its own account");
            }
        }
    }

    /**
     * Refuses a peer whose adapter ports repeat an earlier peer's, before it starts (WBS-4.3.3).
     * Each port set is allocated after the earlier peers' adapters hold theirs, so a repeat means
     * the OS handed out a bound port. Ports taken by an unrelated process in the gap before the
     * adapter binds are not visible here; those surface as a later checkpoint timing out.
     *
     * @param peer the peer just built
     * @throws CheckpointFailure if a port repeats
     */
    private void checkDistinctPorts(final SessionPeer peer) {
        for (SessionPeer other : peers) {
            if (other == peer) {
                continue;
            }
            for (String port : portsOf(peer)) {
                if (portsOf(other).contains(port)) {
                    throw new CheckpointFailure(
                            peer.name(), "ports", port + " was already given to " + other.name());
                }
            }
        }
    }

    /**
     * One peer's adapter ports, named by protocol so a TCP and a UDP port with one number differ.
     *
     * @param peer the peer
     * @return its RPC, GPGNet and lobby ports
     */
    private static List<String> portsOf(final SessionPeer peer) {
        MockClientConfig c = peer.config();
        return List.of(
                "tcp " + c.iceAdapterRpcPort(),
                "tcp " + c.iceAdapterGpgNetPort(),
                "udp " + c.iceAdapterLobbyPort());
    }

    /**
     * Waits until every peer's adapter reports every other peer connected: two directed links per
     * pair. A verdict about an id outside the session is kept in the failure report but neither
     * satisfies nor fails the wait.
     *
     * @throws InterruptedException if the wait is interrupted
     * @throws CheckpointFailure if the mesh is not complete in time
     */
    private void awaitFullMesh() throws InterruptedException {
        String budget = budgetFor(PEER_CONNECTED_TIMEOUT);
        long waitUntil = System.nanoTime() + boundedNanos(PEER_CONNECTED_TIMEOUT);
        while (true) {
            boolean complete = true;
            for (SessionPeer peer : peers) {
                peer.drainVerdicts();
                complete &= missingLinks(peer).isEmpty();
            }
            if (complete) {
                return;
            }
            List<String> terminated = terminatedPeers();
            if (!terminated.isEmpty()) {
                // A terminated peer can never complete its links, so waiting out the budget would
                // only hide what happened.
                throw new CheckpointFailure(
                        String.join(",", terminated),
                        "full mesh",
                        "session ended (TERMINATED) before every link was up; its log lines say"
                                + " why");
            }
            if (System.nanoTime() >= waitUntil) {
                break;
            }
            pollPause(waitUntil);
        }
        List<String> incomplete = new ArrayList<>();
        StringBuilder report = new StringBuilder();
        for (SessionPeer peer : peers) {
            List<String> missing = missingLinks(peer).stream().map(SessionPeer::name).toList();
            if (!missing.isEmpty()) {
                incomplete.add(peer.name());
            }
            report.append(' ')
                    .append(peer.name())
                    .append(" missing ")
                    .append(missing)
                    .append(", seen ")
                    .append(peer.observed())
                    .append(';');
        }
        throw new CheckpointFailure(
                String.join(",", incomplete),
                "full mesh",
                "not every link up within " + budget + ":" + report);
    }

    /**
     * Waits until every game has proven it receives every other game's traffic.
     *
     * @throws InterruptedException if the wait is interrupted
     * @throws CheckpointFailure if a direction is not proven in time, or a peer's session ends
     */
    private void awaitTraffic() throws InterruptedException {
        Map<Long, String> names = new LinkedHashMap<>();
        for (SessionPeer peer : peers) {
            names.put((long) peer.identity().id(), peer.name());
        }
        awaitTraffic(
                traffic,
                names,
                System.nanoTime() + boundedNanos(TRAFFIC_TIMEOUT),
                budgetFor(TRAFFIC_TIMEOUT),
                this::terminatedPeers);
    }

    /**
     * The traffic wait itself, separated from the session so its failure can be tested without a
     * lobby. Polls every direction between the given players under one deadline.
     *
     * @param evidence the captured progress lines
     * @param names each player's id mapped to its peer name, in join order
     * @param waitUntil the {@link System#nanoTime()} deadline
     * @param budget the limit the deadline represents, for the failure message
     * @param terminated the names of peers whose session has ended
     * @throws InterruptedException if the wait is interrupted
     * @throws CheckpointFailure naming every receiver still missing a direction
     */
    static void awaitTraffic(
            final TrafficEvidence evidence,
            final Map<Long, String> names,
            final long waitUntil,
            final String budget,
            final Supplier<List<String>> terminated)
            throws InterruptedException {
        while (true) {
            List<String> receivers = new ArrayList<>();
            StringBuilder report = new StringBuilder();
            for (Map.Entry<Long, String> receiver : names.entrySet()) {
                for (Map.Entry<Long, String> sender : names.entrySet()) {
                    if (receiver.getKey().equals(sender.getKey())
                            || evidence.proven(receiver.getKey(), sender.getKey())) {
                        continue;
                    }
                    if (!receivers.contains(receiver.getValue())) {
                        receivers.add(receiver.getValue());
                    }
                    report.append(' ')
                            .append(receiver.getValue())
                            .append(" from ")
                            .append(sender.getValue())
                            .append(": ")
                            .append(evidence.seen(receiver.getKey(), sender.getKey()))
                            .append(';');
                }
            }
            if (receivers.isEmpty()) {
                return;
            }
            List<String> ended = terminated.get();
            if (!ended.isEmpty()) {
                throw new CheckpointFailure(
                        String.join(",", ended),
                        "traffic",
                        "session ended (TERMINATED) before every game received traffic; its log"
                                + " lines say why. Still waiting:"
                                + report);
            }
            if (System.nanoTime() >= waitUntil) {
                throw new CheckpointFailure(
                        String.join(",", receivers),
                        "traffic",
                        "no two-way game traffic within "
                                + budget
                                + " (wanted "
                                + TrafficEvidence.MIN_PROGRESS_SAMPLES
                                + " progress lines per direction reaching "
                                + TrafficEvidence.MIN_DATAGRAMS
                                + " datagrams with an advancing sequence):"
                                + report);
            }
            pollPause(waitUntil);
        }
    }

    /**
     * The peers whose session has ended, which can never pass a later checkpoint.
     *
     * @return their names, in join order
     */
    private List<String> terminatedPeers() {
        List<String> terminated = new ArrayList<>();
        for (SessionPeer peer : peers) {
            if (peer.lifecycle().getState() == ClientState.TERMINATED) {
                terminated.add(peer.name());
            }
        }
        return terminated;
    }

    /**
     * The session's other peers that {@code peer}'s adapter does not currently report connected.
     *
     * @param peer the reporting peer
     * @return the peers it is missing, empty when all its links are up
     */
    private List<SessionPeer> missingLinks(final SessionPeer peer) {
        List<SessionPeer> missing = new ArrayList<>();
        for (SessionPeer other : peers) {
            if (other != peer && !peer.reportsConnected(other)) {
                missing.add(other);
            }
        }
        return missing;
    }

    /**
     * Bounded wait on one peer's checkpoint future, bound by the stage budget and the session
     * deadline, failing with the peer, the stage and the limit that ran out. It also fails as soon
     * as the peer's session ends, since a TERMINATED lifecycle (a failed launch, a lobby
     * disconnect) never reaches a later checkpoint. The join refusal is read here because it can
     * only arrive during this wait.
     *
     * @param future the checkpoint
     * @param stageBudget its named budget
     * @param peer the peer the checkpoint is about
     * @param stage what reaching it proves, e.g. {@code welcome}
     * @param <T> the checkpoint's value type
     * @return the future's value
     * @throws InterruptedException if the wait is interrupted
     * @throws CheckpointFailure if the checkpoint times out or fails
     */
    private <T> T await(
            final CompletableFuture<T> future,
            final Duration stageBudget,
            final SessionPeer peer,
            final String stage)
            throws InterruptedException {
        String budget = budgetFor(stageBudget);
        // TERMINATED is terminal, so asking for it now cannot miss it.
        CompletableFuture<Void> ended = peer.lifecycle().stateReached(ClientState.TERMINATED);
        try {
            CompletableFuture.anyOf(future, ended)
                    .get(boundedNanos(stageBudget), TimeUnit.NANOSECONDS);
            if (future.isDone()) {
                return future.get();
            }
            throw new CheckpointFailure(
                    peer.name(),
                    stage,
                    "session ended (TERMINATED) before this checkpoint; its log lines say why"
                            + peer.refusalHint());
        } catch (TimeoutException e) {
            throw new CheckpointFailure(
                    peer.name(), stage, "timed out after " + budget + peer.refusalHint());
        } catch (ExecutionException e) {
            throw new CheckpointFailure(
                    peer.name(), stage, "failed: " + e.getCause(), e.getCause());
        }
    }

    /**
     * The stage budget capped by what is left of the session deadline.
     *
     * @param stageBudget the wait's own budget
     * @return the nanoseconds the wait may take
     */
    private long boundedNanos(final Duration stageBudget) {
        long remaining = Math.max(0, deadline - System.nanoTime());
        return Math.min(stageBudget.toNanos(), remaining);
    }

    /**
     * Names the limit a wait started now is bound by, for its failure message.
     *
     * @param stageBudget the wait's own budget
     * @return the stage budget, or the session deadline when less of it remains
     */
    private String budgetFor(final Duration stageBudget) {
        return deadline - System.nanoTime() < stageBudget.toNanos()
                ? "the remaining session deadline (" + SESSION_DEADLINE + " per session)"
                : stageBudget.toString();
    }

    /**
     * One poll interval, cut short at the deadline so a wait never overruns it.
     *
     * @param waitUntil the wait's {@link System#nanoTime()} deadline
     * @throws InterruptedException if interrupted
     */
    private static void pollPause(final long waitUntil) throws InterruptedException {
        long remaining = waitUntil - System.nanoTime();
        if (remaining > 0) {
            TimeUnit.NANOSECONDS.sleep(Math.min(POLL_SLICE.toNanos(), remaining));
        }
    }

    /**
     * Refuses a binary path that is not a file, as the launchers would at launch.
     *
     * @param what the binary's name, for the message
     * @param path the configured path, resolved against the working directory
     * @throws IllegalArgumentException if it is not a regular file
     */
    private static void requireBinary(final String what, final Path path) {
        if (!Files.isRegularFile(path)) {
            throw new IllegalArgumentException(
                    what + " binary not found: " + path.toAbsolutePath());
        }
    }

    /**
     * Shuts one peer down and waits for its teardown, under the caller's label scope.
     *
     * @param peer the peer to shut down
     */
    private static void shutdown(final SessionPeer peer) {
        peer.lifecycle().shutdown();
        try {
            peer.lifecycle()
                    .stateReached(ClientState.TERMINATED)
                    .get(TEARDOWN_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException | ExecutionException e) {
            // Not a failure here: the job is to leave nothing running even from a session in a
            // state we did not expect. The survivor sweep decides whether teardown worked.
            LOG.warn("{} did not reach TERMINATED: {}", peer.name(), e.toString());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        // Once-guarded, so a no-op on the ordinary path and the real thing on every other.
        peer.teardown().run();
    }

    /**
     * The shortest host launch delay that keeps a session of this size joinable throughout.
     *
     * @param peerCount how many peers the session runs
     * @return the floor, in seconds
     */
    static long minHostLaunchDelaySeconds(final int peerCount) {
        int joinersBeyondFirst = Math.max(0, peerCount - MIN_PEERS);
        return MIN_HOST_LAUNCH_DELAY
                .plus(PER_JOINER_LAUNCH_ALLOWANCE.multipliedBy(joinersBeyondFirst))
                .toSeconds();
    }

    /**
     * The instance label for a peer position.
     *
     * @param index the peer's position, 0 for the host
     * @return {@code A} for 0, {@code B} for 1, and so on
     */
    static String labelFor(final int index) {
        return String.valueOf((char) ('A' + index));
    }

    /**
     * The hosting peer's config: its base, its own ports, auto-launch off, and a friends-only game.
     *
     * @param base the host's base config
     * @param ports its port set
     * @param title the advertised game title
     * @return the validated config
     */
    static MockClientConfig hostConfig(
            final MockClientConfig base, final AdapterPorts ports, final String title) {
        return hostConfig(base, ports, title, LAUNCH_DISABLED);
    }

    /**
     * As {@link #hostConfig(MockClientConfig, AdapterPorts, String)}, with an explicit auto-launch
     * policy (WBS-4.3.4).
     *
     * @param base the host's base config
     * @param ports its port set
     * @param title the advertised game title
     * @param launchDelaySeconds seconds its game waits before launching, or {@link
     *     #LAUNCH_DISABLED} for never
     * @return the validated config
     */
    static MockClientConfig hostConfig(
            final MockClientConfig base,
            final AdapterPorts ports,
            final String title,
            final int launchDelaySeconds) {
        GameHostConfig host =
                new GameHostConfig(
                        title,
                        HOST_MAP,
                        HOST_MOD,
                        HOST_VISIBILITY,
                        Optional.empty(),
                        Optional.empty(),
                        false,
                        Map.of());
        return peerConfig(base, ports, Optional.of(host), Optional.empty(), launchDelaySeconds);
    }

    /**
     * A joining peer's config: its base, its own ports, auto-launch off, and the host's uid.
     *
     * @param base the joiner's base config
     * @param ports its port set
     * @param targetGameId the uid the host's game was launched under
     * @return the validated config
     */
    static MockClientConfig joinConfig(
            final MockClientConfig base, final AdapterPorts ports, final int targetGameId) {
        return peerConfig(
                base,
                ports,
                Optional.empty(),
                Optional.of(new GameJoinConfig(targetGameId, Optional.empty())),
                LAUNCH_DISABLED);
    }

    /**
     * Copies {@code base}, replacing what the session owns: the three adapter ports, the launch
     * delay, and the host, join and queue intent. Everything else, the account included, is kept.
     *
     * @param base the peer's base config
     * @param ports its port set
     * @param host its host intent, if it hosts
     * @param join its join intent, if it joins
     * @param launchDelaySeconds seconds its game waits before launching, or {@link
     *     #LAUNCH_DISABLED} for never; only a host is ever given anything else
     * @return the validated copy
     */
    private static MockClientConfig peerConfig(
            final MockClientConfig base,
            final AdapterPorts ports,
            final Optional<GameHostConfig> host,
            final Optional<GameJoinConfig> join,
            final int launchDelaySeconds) {
        return new MockClientConfig(
                base.lobbyWebSocketUrl(),
                base.oauthTokenUrl(),
                base.oauthAuthEndpoint(),
                base.oauthRedirectUri(),
                base.oauthScopes(),
                base.oauthClientId(),
                base.oauthRefreshTokenFile(),
                base.oauthAccessTokenFile(),
                base.uniqueId(),
                base.clientVersion(),
                base.userAgent(),
                base.uidBinaryPath(),
                base.iceAdapterBinaryPath(),
                base.mockGameBinaryPath(),
                ports.rpc(),
                ports.gpgnet(),
                ports.lobby(),
                base.iceAdapterGameId(),
                launchDelaySeconds,
                base.logLevel(),
                base.logFile(),
                base.playerIdOverride(),
                base.playerLogin(),
                host,
                join,
                Optional.empty(),
                base.iceRelayDelayMs(),
                base.mockGameUdpDropPercent());
    }

    /**
     * The three adapter listener ports for one peer.
     *
     * @param rpc JSON-RPC port (TCP)
     * @param gpgnet GPGNet port (TCP), shared with mock-game per spec §2.8
     * @param lobby lobby game-traffic port (UDP), shared with mock-game per spec §2.8
     */
    record AdapterPorts(int rpc, int gpgnet, int lobby) {}

    /**
     * Allocates three distinct free ports. The sockets are held open together so the OS hands out
     * distinct numbers, and released before the adapter binds them: a benign gap in which another
     * process could take one, which surfaces as a later checkpoint timing out.
     *
     * @return one peer's port set
     * @throws IOException if a socket cannot be opened
     */
    static AdapterPorts freeAdapterPorts() throws IOException {
        try (ServerSocket rpc = new ServerSocket(0);
                ServerSocket gpgnet = new ServerSocket(0);
                DatagramSocket lobby = new DatagramSocket(0)) {
            return new AdapterPorts(
                    rpc.getLocalPort(), gpgnet.getLocalPort(), lobby.getLocalPort());
        }
    }
}
