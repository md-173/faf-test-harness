package com.faforever.testharness.client.process;

import com.faforever.testharness.client.ice.IceAdapterConnection;
import com.faforever.testharness.client.lobby.LobbyConnection;
import com.faforever.testharness.shared.process.SubprocessManager;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Coordinated teardown of a live mock-client session (WBS-3.1.3.2): terminates the game and ICE
 * adapter subprocesses and closes the lobby + adapter connections, so integration tests leave no
 * orphaned processes.
 *
 * <p><b>Order (deterministic):</b> mock-game → ICE adapter → adapter RPC close → lobby close.
 * Subprocesses go first so the adapter is never left relaying for a dead game; connections close
 * last and tolerate the peer already being gone. Each step is exception-isolated — a failing step
 * is logged and the sequence continues.
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
 * game session is being set up (register methods, wired by R59b). {@link #run()} skips whatever was
 * never registered — tearing down an idle, lobby-only session is valid.
 */
public final class SessionTeardown {

    /** Diagnostic logger; teardown handles no credentials. */
    private static final Logger LOG = LoggerFactory.getLogger(SessionTeardown.class);

    /** Bound on the clean lobby WebSocket close. */
    private static final Duration LOBBY_CLOSE_TIMEOUT = Duration.ofSeconds(5);

    /** Lobby connection; present from session startup. */
    private final LobbyConnection lobby;

    /** Adapter JSON-RPC connection; {@code null} until registered. */
    private volatile IceAdapterConnection adapterRpc;

    /** ICE adapter subprocess handle; {@code null} until registered. */
    private volatile SubprocessManager adapterProcess;

    /** Mock game subprocess handle; {@code null} until registered. */
    private volatile SubprocessManager gameProcess;

    /** True once {@link #run()} has executed; guarded by the {@code run()} monitor. */
    private boolean done;

    /**
     * Creates a teardown for a session whose lobby connection already exists. The remaining handles
     * are registered later, as they come into existence.
     *
     * @param lobby the session's lobby connection; must not be {@code null}
     */
    public SessionTeardown(final LobbyConnection lobby) {
        this.lobby = Objects.requireNonNull(lobby, "lobby");
    }

    /**
     * Registers the adapter JSON-RPC connection for teardown.
     *
     * @param connection the live adapter connection; must not be {@code null}
     */
    public void registerAdapterRpc(final IceAdapterConnection connection) {
        this.adapterRpc = Objects.requireNonNull(connection, "connection");
    }

    /**
     * Registers the ICE adapter subprocess for teardown.
     *
     * @param process the launched adapter's manager; must not be {@code null}
     */
    public void registerAdapterProcess(final SubprocessManager process) {
        this.adapterProcess = Objects.requireNonNull(process, "process");
    }

    /**
     * Registers the mock game subprocess for teardown.
     *
     * @param process the launched game's manager; must not be {@code null}
     */
    public void registerGameProcess(final SubprocessManager process) {
        this.gameProcess = Objects.requireNonNull(process, "process");
    }

    /**
     * Runs the teardown sequence once: terminate game, terminate adapter, close the adapter RPC
     * connection, close the lobby connection. Unregistered handles are skipped; a failing step is
     * logged and does not stop the rest. Subsequent (or concurrent) calls are no-ops.
     */
    public synchronized void run() {
        if (done) {
            return;
        }
        done = true;
        LOG.info("tearing down session");
        terminate(gameProcess, "mock-game");
        terminate(adapterProcess, "ICE adapter");
        closeAdapterRpc();
        closeLobby();
        LOG.info("session teardown complete");
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
            lobby.close().get(LOBBY_CLOSE_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (ExecutionException | TimeoutException e) {
            LOG.warn("lobby close did not complete cleanly: {}", e.getMessage());
        }
    }
}
