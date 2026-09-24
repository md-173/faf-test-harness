package com.faforever.testharness.client.process;

import com.faforever.testharness.client.ice.IceAdapterConnection;
import com.faforever.testharness.client.ice.IceRpcException;
import com.faforever.testharness.client.lobby.LobbyConnection;
import com.faforever.testharness.shared.process.SubprocessManager;
import java.net.http.WebSocket;
import java.time.Duration;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.BooleanSupplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Coordinated teardown of a live mock-client session (WBS-3.1.3.2): terminates the game and ICE
 * adapter subprocesses and closes the lobby + adapter connections, so integration tests leave no
 * orphaned processes.
 *
 * <p><b>Order (deterministic):</b> mock-game → the owner's step → ICE adapter → adapter RPC close →
 * lobby close. Subprocesses go first so the adapter is never left relaying for a dead game;
 * connections close last and tolerate the peer already being gone. The owner's step is the
 * session's own work between the two, registered by its lifecycle through {@link
 * #registerAfterGameStep}: it runs once the game is down, while the lobby is still open, which is
 * where the session reports the game's end to the server (WBS-3.1.2.6-fix, #454). The official FAF
 * client does the same things in a slightly different order ({@code GameRunner} in
 * downlords-faf-client stops the adapter once the game exits, then notifies the server); here the
 * report comes before the adapter step, and either way it reaches the lobby before the close. Each
 * step is exception-isolated: a failing step is logged and the sequence continues.
 *
 * <p><b>Adapter step is quit-first (WBS-3.1.2.5):</b> while the RPC connection is still open, a
 * {@code quit} request is sent and briefly awaited — the one real-client behaviour ({@code
 * iceAdapterProxy.quit()}) this teardown previously lacked. That always falls through to the
 * existing SIGTERM→SIGKILL escalation below, which is a no-op once quit has already ended the
 * process, so a quit that never lands still leaves the step (and teardown) bounded.
 *
 * <p><b>Bounded:</b> subprocess termination reuses {@link SubprocessManager#terminate()}'s
 * SIGTERM→grace→SIGKILL escalation (bounded internally by each manager's start-time grace); {@link
 * IceAdapterConnection#close()} is a synchronous socket close; the lobby close is awaited for at
 * most {@link #LOBBY_CLOSE_TIMEOUT}. A hung resource cannot block the sequence indefinitely.
 *
 * <p><b>Idempotent and convergent:</b> the first {@link #run()} wins; later calls (and concurrent
 * ones, which block until the first finishes) are no-ops. The signal hook and the FSM's TERMINATED
 * action (R59b) share one instance, so the on-request and signal paths converge on this single
 * mechanism with no double-termination. The JVM-wide {@code SubprocessRegistry} exit hook remains
 * the independent safety net for when teardown never runs; overlapping with it is safe because
 * {@code terminate} is a no-op on an already-dead process.
 *
 * <p>Handles are registered as they come into existence: the lobby connection exists from startup
 * (constructor), while the adapter RPC connection and the two subprocess handles appear only once a
 * game session is being set up. The game process is registered at launch by the lifecycle
 * (WBS-3.1.2.4); the adapter handles are wired by R38/R59b. {@link #run()} skips whatever was never
 * registered — tearing down an idle, lobby-only session is valid.
 */
public final class SessionTeardown {

    /** Diagnostic logger; teardown handles no credentials. */
    private static final Logger LOG = LoggerFactory.getLogger(SessionTeardown.class);

    /** Bound on the clean lobby WebSocket close. */
    private static final Duration LOBBY_CLOSE_TIMEOUT = Duration.ofSeconds(5);

    /** Bound on the quit RPC's response, before falling through to SIGTERM. */
    private static final Duration ADAPTER_QUIT_RPC_TIMEOUT = Duration.ofSeconds(2);

    /** Bound on awaiting the adapter's own exit once quit has been sent. */
    private static final Duration ADAPTER_QUIT_EXIT_TIMEOUT = Duration.ofSeconds(2);

    /** Lobby connection; present from session startup. */
    private final LobbyConnection lobby;

    /** Raised once a signal has started the JVM's shutdown; see {@link #signalled()}. */
    private final BooleanSupplier signalled;

    /** Adapter JSON-RPC connection; {@code null} until registered. */
    private volatile IceAdapterConnection adapterRpc;

    /** ICE adapter subprocess handle; {@code null} until registered. */
    private volatile SubprocessManager adapterProcess;

    /** Mock game subprocess handle; {@code null} until registered. */
    private volatile SubprocessManager gameProcess;

    /** The owner's step between the game and the adapter; a no-op until one is registered. */
    private volatile Runnable afterGameStep = () -> {};

    /**
     * True once {@link #run()} has executed. Volatile so the lock-free read in {@link
     * #warnIfDone(String)} is guaranteed to see a completed teardown.
     */
    private volatile boolean done;

    /**
     * Creates a teardown for a session whose lobby connection already exists. The remaining handles
     * are registered later, as they come into existence. No signal ever reaches it: for a session
     * whose owner has no signal hook of its own.
     *
     * @param lobby the session's lobby connection; must not be {@code null}
     */
    public SessionTeardown(final LobbyConnection lobby) {
        this(lobby, () -> false);
    }

    /**
     * Creates a teardown that knows when a signal is tearing the JVM down (WBS-3.1.2.8-fix, #438).
     *
     * @param lobby the session's lobby connection; must not be {@code null}
     * @param signalled raised once a signal has started the JVM's shutdown: {@code run}'s {@code
     *     shuttingDown} flag (#442), which its hook raises before it tears down
     */
    public SessionTeardown(final LobbyConnection lobby, final BooleanSupplier signalled) {
        this.lobby = Objects.requireNonNull(lobby, "lobby");
        this.signalled = Objects.requireNonNull(signalled, "signalled");
    }

    /**
     * Registers the adapter JSON-RPC connection for teardown.
     *
     * @param connection the live adapter connection; must not be {@code null}
     */
    public void registerAdapterRpc(final IceAdapterConnection connection) {
        this.adapterRpc = Objects.requireNonNull(connection, "connection");
        warnIfDone("adapter RPC connection");
    }

    /**
     * Registers the ICE adapter subprocess for teardown.
     *
     * @param process the launched adapter's manager; must not be {@code null}
     */
    public void registerAdapterProcess(final SubprocessManager process) {
        this.adapterProcess = Objects.requireNonNull(process, "process");
        warnIfDone("ICE adapter process");
    }

    /**
     * Registers the mock game subprocess for teardown.
     *
     * @param process the launched game's manager; must not be {@code null}
     */
    public void registerGameProcess(final SubprocessManager process) {
        this.gameProcess = Objects.requireNonNull(process, "process");
        warnIfDone("game process");
    }

    /**
     * Registers the owner's step, which {@link #run()} takes once the game is down and before it
     * touches the adapter (WBS-3.1.2.6-fix, #454).
     *
     * <p>It runs on every path into teardown, whoever starts it: the lifecycle's TERMINATED entry
     * hook, the CLI's signal hook, a direct {@link #run()}. That is the point of taking it here
     * rather than in the lifecycle's own hook, since the signal hook reaches teardown without the
     * lifecycle's state machine. It runs at most once, under this instance's lock, and a throw from
     * it is logged and the rest of teardown still runs.
     *
     * @param step what to do between the game and the adapter; must not be {@code null}
     */
    public void registerAfterGameStep(final Runnable step) {
        this.afterGameStep = Objects.requireNonNull(step, "step");
        warnIfDone("step after the game");
    }

    /**
     * Best-effort warning for a handle registered after teardown already ran — it will not be torn
     * down by this instance (the JVM-exit registry hook still covers processes). Lock-free so a
     * registration never blocks; a registration racing {@link #run()} may still miss the warning,
     * which is acceptable.
     *
     * @param label human-readable name of the late registrant for the log line
     */
    private void warnIfDone(final String label) {
        if (done) {
            LOG.warn("{} registered after teardown already ran; it will not be torn down", label);
        }
    }

    /**
     * Whether {@link #run()} has started. Since {@link #done} is set before any subprocess is
     * killed, this is already {@code true} by the time a teardown-initiated exit can be observed —
     * read by both the game's (#211) and the ICE adapter's (#214) exit classification so a SIGTERM
     * this instance sent is not logged as a crash.
     *
     * @return {@code true} once {@link #run()} has been entered
     */
    public boolean hasRun() {
        return done;
    }

    /**
     * Whether a signal started the JVM's shutdown (WBS-3.1.2.8-fix, #438). Read by the verdict
     * check teardown runs through the owner's step: a signal kills the adapter itself (a terminal's
     * SIGINT reaches it directly, and {@code SubprocessRegistry}'s hook terminates it), so an
     * adapter found dead then is the signal's doing, not a finding.
     *
     * @return {@code true} once a signal has started the shutdown
     */
    public boolean signalled() {
        return signalled.getAsBoolean();
    }

    /**
     * The ICE adapter process registered for teardown, if one was (WBS-3.1.2.8-fix, #438). The
     * verdict check reads its exit from here, as the process reaper records it ({@link
     * SubprocessManager#exitCode()}, {@link SubprocessManager#waitFor}), rather than from a future
     * the common pool completes.
     *
     * @return the adapter's manager, or empty if no adapter was launched
     */
    public Optional<SubprocessManager> adapterProcess() {
        return Optional.ofNullable(adapterProcess);
    }

    /**
     * Runs the teardown sequence once: terminate game, run the owner's step, terminate adapter,
     * close the adapter RPC connection, close the lobby connection. Unregistered handles are
     * skipped; a failing step is logged and does not stop the rest. Subsequent (or concurrent)
     * calls are no-ops.
     */
    public synchronized void run() {
        if (done) {
            return;
        }
        done = true;
        LOG.info("tearing down session");
        terminate(gameProcess, "mock-game");
        runAfterGameStep();
        terminateAdapter();
        closeAdapterRpc();
        closeLobby();
        LOG.info("session teardown complete");
    }

    /**
     * Runs the step registered through {@link #registerAfterGameStep}. A throw is a defect in the
     * owner rather than a teardown failure, so it is logged at ERROR with its trace, and the
     * adapter and connections are still torn down.
     */
    private void runAfterGameStep() {
        try {
            afterGameStep.run();
        } catch (RuntimeException e) {
            LOG.error("teardown step after the game threw; continuing", e);
        }
    }

    /**
     * Terminates the ICE adapter, quit-first (WBS-3.1.2.5): {@link #quitAdapterIfOpen()} gives the
     * adapter a bounded chance to shut itself down gracefully via RPC, mirroring the real client's
     * {@code iceAdapterProxy.quit()}. Unconditionally falls through to {@link
     * SubprocessManager#terminate()}'s SIGTERM→SIGKILL escalation, already a no-op once quit has
     * ended the process — a quit that never lands still leaves this step bounded.
     */
    private void terminateAdapter() {
        quitAdapterIfOpen();
        terminate(adapterProcess, "ICE adapter");
    }

    /**
     * Sends {@code quit} over the adapter RPC connection and briefly awaits the process actually
     * exiting, but only while {@link IceAdapterConnection#isOpen()} — no RPC connection means
     * nothing to send on, and this step is skipped exactly as it was before this card. Both the
     * send and the exit-await are bounded, and every failure mode (no response, RPC error, process
     * still alive after quit) is swallowed at DEBUG: {@link #terminateAdapter()}'s SIGTERM fallback
     * covers all of them.
     *
     * <p>An {@link IceRpcException} response is treated as final rather than awaited: it means the
     * adapter received and rejected quit, so it isn't going to exit on its own, and there is
     * nothing left to wait for. Skipping {@link #ADAPTER_QUIT_EXIT_TIMEOUT} in that case is a
     * meaningful speed-up, not just tidiness — see the upstream {@code faf-ice-adapter} tray-icon
     * bug this guards against: {@code IceAdapter.close(int)} calls {@code TrayIcon.close()} with no
     * {@code SystemTray.isSupported()} guard (unlike {@code TrayIcon.create()}, which does check),
     * so on any headless box — this one included, and CI — quit always errors and the adapter never
     * exits from it, leaving SIGTERM as the only path to actually end the process.
     */
    private void quitAdapterIfOpen() {
        IceAdapterConnection connection = adapterRpc;
        SubprocessManager process = adapterProcess;
        if (connection == null || process == null || !connection.isOpen()) {
            return;
        }
        try {
            connection.call("quit").get(ADAPTER_QUIT_RPC_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return;
        } catch (ExecutionException e) {
            if (e.getCause() instanceof IceRpcException) {
                LOG.debug("ICE adapter refused quit: {}", e.getCause().getMessage());
                return;
            }
            LOG.debug("quit RPC to ICE adapter did not complete cleanly: {}", e.getMessage());
        } catch (TimeoutException e) {
            LOG.debug("quit RPC to ICE adapter did not complete cleanly: {}", e.getMessage());
        }
        try {
            // waitFor, not onExit(): see SubprocessManager.waitFor for why a busy common pool must
            // not decide how long this takes.
            if (!process.waitFor(ADAPTER_QUIT_EXIT_TIMEOUT)) {
                LOG.debug("ICE adapter did not exit within {} of quit", ADAPTER_QUIT_EXIT_TIMEOUT);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * Terminates one subprocess via its manager's bounded SIGTERM→SIGKILL path.
     *
     * @param process the subprocess handle, or {@code null} if never registered
     * @param label human-readable name for the failure log line
     */
    private static void terminate(final SubprocessManager process, final String label) {
        if (process == null) {
            return;
        }
        try {
            process.terminate();
        } catch (RuntimeException e) {
            LOG.warn("failed to terminate {}: {}", label, e.getMessage());
        }
    }

    /** Closes the adapter RPC socket; tolerates the adapter already being gone. */
    private void closeAdapterRpc() {
        IceAdapterConnection connection = adapterRpc;
        if (connection == null) {
            return;
        }
        try {
            connection.close();
        } catch (RuntimeException e) {
            LOG.warn("failed to close ICE adapter connection: {}", e.getMessage());
        }
    }

    /** Closes the lobby WebSocket, waiting at most {@link #LOBBY_CLOSE_TIMEOUT}. */
    private void closeLobby() {
        try {
            lobby.close(WebSocket.NORMAL_CLOSURE, "Session teardown")
                    .get(LOBBY_CLOSE_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (ExecutionException | TimeoutException e) {
            LOG.warn("lobby close did not complete cleanly: {}", e.getMessage());
        }
    }
}
