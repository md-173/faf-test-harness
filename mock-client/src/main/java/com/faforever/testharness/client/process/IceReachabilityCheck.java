package com.faforever.testharness.client.process;

import com.faforever.testharness.client.config.IceAdapterSettings;
import com.faforever.testharness.client.ice.IceAdapterConnection;
import com.faforever.testharness.client.ice.IceRpcException;
import com.faforever.testharness.shared.process.SubprocessManager;
import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.time.Duration;
import java.util.Objects;
import java.util.OptionalInt;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * One-shot "is a local ICE adapter reachable?" check (WBS-3.1.4.3): bring up {@code
 * faf-ice-adapter}, prove both of the endpoints a session depends on are actually serving, tear it
 * back down, and report a verdict. The mechanism behind the {@code ice-smoke} subcommand.
 *
 * <p>It needs no lobby and no FAF account, and everything it sends itself stays on loopback, which
 * is the point — it is the cheap precondition you run before paying for a full session test, and
 * the check that separates "the adapter never came up" from "the session logic is wrong". (The
 * adapter subprocess is not so contained: it opens a telemetry WebSocket that 3.3.14 offers no way
 * to disable. Nothing in the verdict depends on it.)
 *
 * <p><b>What counts as proof.</b> Five phases, each with its own bounded wait:
 *
 * <ol>
 *   <li><b>port pre-flight</b> — bind the JSON-RPC and GPGNet ports before spawning anything. A
 *       port already in use means a stale or foreign adapter is listening there, and every later
 *       phase would then be probing <em>that</em> process: a pass that says nothing about the
 *       binary under test. Failing here converts a false success into a clear error.
 *   <li><b>launch</b> — {@link IceAdapterLauncher}, which fails fast on a missing binary.
 *   <li><b>RPC connect</b> — a TCP connect with retry while the adapter JVM is still binding.
 *   <li><b>RPC round-trip</b> — {@code setLobbyInitMode("normal")}, boot step 3 of {@code
 *       json-rpc-spec.md} §9, so the verdict covers a request the adapter parses, dispatches, and
 *       answers, not merely an open socket. Deliberately <em>not</em> the {@code status} method
 *       used elsewhere for readiness: upstream marks it {@code @Deprecated(forRemoval = true)}.
 *   <li><b>GPGNet probe</b> — open a plain TCP connection to the GPGNet port and wait for the
 *       adapter to announce it on the RPC channel as {@code onConnectionStateChanged("Connected")}.
 *       That single notification is what makes this a reachability check rather than a port scan:
 *       it can only arrive if the GPGNet server accepted the connection <em>and</em> the RPC
 *       service is wired to it and pushing frames to us.
 * </ol>
 *
 * <p><b>Ordering is load-bearing, not stylistic.</b> The RPC connection must exist before the
 * GPGNet probe. On accept, the adapter's {@code GPGNetClient} constructor calls {@code
 * RPCService.onConnectionStateChanged}, which blocks in {@code getPeerOrWait()} — an unbounded wait
 * for the first JSON-RPC peer (verified against 3.3.14; {@code gpgnet-format-spec.md} §8.1).
 * Probing GPGNet first would park the adapter's accept thread instead of producing a verdict.
 *
 * <p>The probe socket sends no GPGNet frame. It has nothing useful to say, and §8.1 records that a
 * frame sent in the window before the adapter finishes assigning its client kills the adapter's
 * listener thread. Connecting and disconnecting is handled cleanly upstream: the adapter logs the
 * lost connection and carries on serving.
 *
 * <p><b>Bounded.</b> One deadline covers the probing phases and every one of them draws from it, so
 * no wait — including a phase that would otherwise inherit a default — can push them past the
 * caller's budget. The connect phase reserves what the later phases need rather than spending
 * everything, so a slow start is reported as a slow start. Teardown is the one thing outside that
 * deadline: the adapter is always terminated in a {@code finally}, since leaving one alive to
 * honour a budget would poison the next run, and that step carries its own {@link #TEARDOWN_GRACE}.
 * A whole invocation is therefore bounded by the budget plus at most twice that grace.
 *
 * <p>Not thread-safe, and single-use: one instance performs one {@link #run()}.
 */
public final class IceReachabilityCheck {

    /** Diagnostic logger; nothing on this path carries credentials. */
    private static final Logger LOG = LoggerFactory.getLogger(IceReachabilityCheck.class);

    /** The adapter serves both of its TCP endpoints on loopback. */
    private static final String LOOPBACK = "127.0.0.1";

    /** Delay between JSON-RPC connect attempts while the adapter JVM is still binding. */
    private static final Duration RPC_RETRY_DELAY = Duration.ofMillis(200);

    /** Bound on the {@code setLobbyInitMode} round-trip once the socket is open. */
    private static final Duration RPC_CALL_TIMEOUT = Duration.ofSeconds(2);

    /** Bound on the GPGNet TCP connect, which is either immediate or refused. */
    private static final Duration GPGNET_CONNECT_TIMEOUT = Duration.ofSeconds(2);

    /**
     * Bound on the adapter announcing the probe over RPC. Measured at ~20 ms against 3.3.14; the
     * margin is for a loaded CI box, not for a slow adapter.
     */
    private static final Duration GPGNET_CONFIRM_TIMEOUT = Duration.ofSeconds(5);

    /** Notification the adapter emits when something connects to its GPGNet server. */
    private static final String GPGNET_STATE_NOTIFICATION = "onConnectionStateChanged";

    /** Value of that notification's single argument when a client has connected. */
    private static final String GPGNET_CONNECTED_STATE = "Connected";

    /** Lobby init mode for a custom game — the value a non-matchmaker session sends anyway. */
    private static final String LOBBY_INIT_MODE = "normal";

    /**
     * Budget the connect phase leaves behind for the three phases after it. Without this reserve a
     * slow-binding adapter could spend the entire deadline on the connect and leave the later
     * phases a zero-length window each, turning "too slow to start" into a bogus {@code RPC_SILENT}
     * or {@code GPGNET_UNCONFIRMED} — a true failure reported under the wrong name.
     */
    private static final Duration POST_CONNECT_RESERVE =
            RPC_CALL_TIMEOUT.plus(GPGNET_CONNECT_TIMEOUT).plus(GPGNET_CONFIRM_TIMEOUT);

    /**
     * Grace given to the adapter between SIGTERM and SIGKILL during teardown, shorter than the
     * launcher's session default. Teardown is not optional and so cannot live inside the caller's
     * deadline — leaving a stray adapter behind to honour a budget would be worse than overrunning
     * it — but it is still bounded and named here, worst case twice this value. The real adapter
     * exits on SIGTERM in about ten milliseconds; this only bites on one that is already hung,
     * which is exactly when a diagnostic should stop waiting and kill it.
     */
    private static final Duration TEARDOWN_GRACE = Duration.ofSeconds(2);

    /** Adapter settings to launch and probe. */
    private final IceAdapterSettings settings;

    /** Total budget for the whole check, spawn through verdict. */
    private final Duration budget;

    /** Why a check ended the way it did. Every value but {@link #REACHABLE} is a failure. */
    public enum Verdict {
        /** Both endpoints served, and the adapter confirmed the GPGNet connection over RPC. */
        REACHABLE,
        /** A JSON-RPC or GPGNet port was already in use, so nothing this check spawns owns it. */
        PORTS_IN_USE,
        /** The adapter binary is missing, or the process could not be started. */
        LAUNCH_FAILED,
        /** The adapter exited before the check could finish. */
        ADAPTER_EXITED,
        /** Nothing accepted a JSON-RPC connection within the budget. */
        RPC_UNREACHABLE,
        /** The JSON-RPC socket opened but no answer came back to a request on it. */
        RPC_SILENT,
        /** The GPGNet port refused a connection. */
        GPGNET_UNREACHABLE,
        /** The GPGNet port accepted, but the adapter never announced it over RPC. */
        GPGNET_UNCONFIRMED,
        /** The thread running the check was interrupted. */
        INTERRUPTED
    }

    /**
     * The outcome of one check.
     *
     * @param verdict what happened; {@link Verdict#REACHABLE} is the only success
     * @param detail one line naming the phase and the reason, ready to log verbatim
     */
    public record Result(Verdict verdict, String detail) {

        /**
         * Whether this outcome is the success one.
         *
         * @return {@code true} if the adapter was reachable
         */
        public boolean reachable() {
            return verdict == Verdict.REACHABLE;
        }
    }

    /**
     * Creates a check bound to {@code settings}, to complete within {@code budget}.
     *
     * @param settings which adapter to launch, on which ports; must not be {@code null}
     * @param budget total wall-clock budget for the check; must be positive
     * @throws IllegalArgumentException if {@code budget} is not positive
     */
    public IceReachabilityCheck(final IceAdapterSettings settings, final Duration budget) {
        this.settings = Objects.requireNonNull(settings, "settings");
        this.budget = Objects.requireNonNull(budget, "budget");
        if (budget.isZero() || budget.isNegative()) {
            throw new IllegalArgumentException("budget must be positive; got " + budget);
        }
    }

    /**
     * Runs the check: pre-flight, launch, connect, round-trip, probe, tear down.
     *
     * <p>Returns a verdict rather than throwing — an unreachable adapter is this method's normal
     * output, not an exceptional condition. The adapter subprocess is terminated before returning,
     * on every path.
     *
     * @return the outcome, with a one-line detail naming the phase that decided it
     */
    public Result run() {
        long deadline = System.nanoTime() + budget.toNanos();

        Result portsBusy = checkPortsFree();
        if (portsBusy != null) {
            return portsBusy;
        }

        SubprocessManager adapter;
        try {
            adapter = new IceAdapterLauncher(settings).start();
        } catch (IceAdapterLaunchException e) {
            return new Result(Verdict.LAUNCH_FAILED, e.getMessage());
        }

        try {
            return probe(adapter, deadline);
        } finally {
            teardown(adapter);
        }
    }

    /**
     * Phase 1 — the two TCP ports the adapter will bind must be free, so that whatever answers
     * later is the process this check started.
     *
     * @return a {@link Verdict#PORTS_IN_USE} result naming the busy port, or {@code null} if both
     *     are free
     */
    private Result checkPortsFree() {
        for (PortRole role : PortRole.values()) {
            int port = role.portOf(settings);
            try (ServerSocket probe = new ServerSocket(port)) {
                LOG.debug(
                        "port pre-flight: {} port {} is free", role.label(), probe.getLocalPort());
            } catch (IOException e) {
                return new Result(
                        Verdict.PORTS_IN_USE,
                        "port pre-flight: "
                                + role.label()
                                + " port "
                                + port
                                + " is already in use ("
                                + e.getMessage()
                                + "). Another ICE adapter is probably still running; stop it, or "
                                + "pass different ports.");
            }
        }
        return null;
    }

    /**
     * Phases 3 to 5 against an already-launched adapter: connect, round-trip, probe GPGNet.
     *
     * @param adapter the running adapter subprocess
     * @param deadline nanoTime the whole check must finish by
     * @return the outcome
     */
    private Result probe(final SubprocessManager adapter, final long deadline) {
        IceAdapterConnection connection = newConnection(deadline);
        CompletableFuture<String> gpgNetConnected = new CompletableFuture<>();
        // Registered before the probe socket opens: the adapter answers within milliseconds, and a
        // handler installed afterwards could miss the notification it is waiting for.
        connection.registerNotification(
                GPGNET_STATE_NOTIFICATION, node -> completeOnConnected(gpgNetConnected, node));

        try {
            Result connected = connectRpc(adapter, connection, deadline);
            if (connected != null) {
                return connected;
            }
            Result answered = callSetLobbyInitMode(connection, deadline);
            if (answered != null) {
                return answered;
            }
            Result probed = probeGpgNet(adapter, gpgNetConnected, deadline);
            if (probed.reachable() && !adapter.isAlive()) {
                // The pre-flight leaves a millisecond-wide window: if something else claimed a port
                // between the bind test and the launch, our adapter dies on its own failed bind
                // while every probe above succeeds — against the foreign listener. A live process
                // at the finish is what ties the passing verdict to the binary we started.
                return adapterExited(adapter, "verdict");
            }
            return probed;
        } finally {
            connection.close();
        }
    }

    /**
     * Builds the JSON-RPC transport, spreading the remaining budget over connect attempts so a
     * cold-starting adapter is waited for exactly as long as the caller allowed and no longer.
     *
     * @param deadline nanoTime the whole check must finish by
     * @return an unconnected transport aimed at the adapter's JSON-RPC port
     */
    private IceAdapterConnection newConnection(final long deadline) {
        long attempts = connectWindow(deadline).toMillis() / RPC_RETRY_DELAY.toMillis();
        // Clamp in long arithmetic before narrowing: a large enough budget overflows int, and a
        // negative attempt count makes the retry loop run zero times and report a healthy adapter
        // unreachable in milliseconds — a gate failing open while looking closed.
        int boundedAttempts = (int) Math.max(1, Math.min(Integer.MAX_VALUE, attempts));
        return new IceAdapterConnection(
                settings.rpcPort(), boundedAttempts, RPC_RETRY_DELAY, RPC_CALL_TIMEOUT);
    }

    /**
     * How long the connect phase may spend: everything left except what the later phases need. When
     * the whole budget is smaller than that reserve, the remainder is split rather than handed to
     * the connect, so a tight budget still reaches the phases that follow.
     *
     * @param deadline nanoTime the whole check must finish by
     * @return the connect phase's window
     */
    private static Duration connectWindow(final long deadline) {
        Duration left = remaining(deadline);
        Duration afterReserve = left.minus(POST_CONNECT_RESERVE);
        return afterReserve.isNegative() || afterReserve.isZero()
                ? left.dividedBy(2)
                : afterReserve;
    }

    /**
     * Phase 3 — open the JSON-RPC socket, retrying while the adapter JVM binds it.
     *
     * @param adapter the running adapter, consulted to tell "died" apart from "slow"
     * @param connection the transport to connect
     * @param deadline nanoTime the whole check must finish by
     * @return a failure result, or {@code null} once connected
     */
    private Result connectRpc(
            final SubprocessManager adapter,
            final IceAdapterConnection connection,
            final long deadline) {
        Duration window = connectWindow(deadline);
        LOG.info(
                "ice-smoke: connecting to ICE adapter JSON-RPC at {}:{} (within {})",
                LOOPBACK,
                settings.rpcPort(),
                window);
        try {
            connection.connect().get(window.toMillis(), TimeUnit.MILLISECONDS);
            return null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return interrupted("RPC connect");
        } catch (ExecutionException | TimeoutException e) {
            if (!adapter.isAlive()) {
                return adapterExited(adapter, "RPC connect");
            }
            return new Result(
                    Verdict.RPC_UNREACHABLE,
                    "RPC connect: nothing accepted a JSON-RPC connection on "
                            + LOOPBACK
                            + ":"
                            + settings.rpcPort()
                            + " within "
                            + window
                            + " (adapter still running)");
        }
    }

    /**
     * Phase 4 — one real request, so the verdict covers the adapter parsing and answering rather
     * than only accepting a socket.
     *
     * @param connection the connected transport
     * @param deadline nanoTime the whole check must finish by
     * @return a failure result, or {@code null} once the adapter answered
     */
    private Result callSetLobbyInitMode(
            final IceAdapterConnection connection, final long deadline) {
        Duration window = min(remaining(deadline), RPC_CALL_TIMEOUT);
        LOG.info("ice-smoke: RPC round-trip setLobbyInitMode (within {})", window);
        try {
            connection
                    .call("setLobbyInitMode", LOBBY_INIT_MODE)
                    .get(window.toMillis(), TimeUnit.MILLISECONDS);
            return null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return interrupted("RPC round-trip");
        } catch (ExecutionException e) {
            if (e.getCause() instanceof IceRpcException) {
                // An error *response* is still an answer: the adapter read the frame, dispatched
                // it, and replied, which is the whole point of this phase. Refusing the request is
                // the adapter's business — this check only asks whether it is serving.
                LOG.warn(
                        "ice-smoke: adapter refused setLobbyInitMode ({}); the RPC path is alive, "
                                + "which is what this phase proves",
                        e.getCause().getMessage());
                return null;
            }
            return rpcSilent(window, e);
        } catch (TimeoutException e) {
            return rpcSilent(window, e);
        }
    }

    /**
     * The "no answer came back" result for the round-trip phase.
     *
     * @param window how long the request was waited for
     * @param cause what ended the wait
     * @return an {@link Verdict#RPC_SILENT} result
     */
    private static Result rpcSilent(final Duration window, final Exception cause) {
        return new Result(
                Verdict.RPC_SILENT,
                "RPC round-trip: the adapter did not answer setLobbyInitMode within "
                        + window
                        + " ("
                        + rootMessage(cause)
                        + ")");
    }

    /**
     * Phase 5 — connect to the GPGNet port as a game would, and wait for the adapter to announce it
     * back over RPC.
     *
     * @param adapter the running adapter, consulted to tell "died" apart from "silent"
     * @param gpgNetConnected completed by the notification handler when the adapter announces the
     *     connection
     * @param deadline nanoTime the whole check must finish by
     * @return the final outcome
     */
    private Result probeGpgNet(
            final SubprocessManager adapter,
            final CompletableFuture<String> gpgNetConnected,
            final long deadline) {
        Duration connectWindow = min(remaining(deadline), GPGNET_CONNECT_TIMEOUT);
        LOG.info(
                "ice-smoke: probing GPGNet endpoint at {}:{} (within {})",
                LOOPBACK,
                settings.gpgNetPort(),
                connectWindow);
        // Socket.connect reads 0 as "wait forever", so an exhausted budget must still floor at a
        // millisecond — an expired deadline has to fail fast, not block.
        int connectMillis = (int) Math.max(1, connectWindow.toMillis());
        Socket probe = new Socket();
        try {
            try {
                probe.connect(
                        new InetSocketAddress(LOOPBACK, settings.gpgNetPort()), connectMillis);
            } catch (IOException e) {
                if (!adapter.isAlive()) {
                    return adapterExited(adapter, "GPGNet probe");
                }
                return new Result(
                        Verdict.GPGNET_UNREACHABLE,
                        "GPGNet probe: could not connect to "
                                + LOOPBACK
                                + ":"
                                + settings.gpgNetPort()
                                + " within "
                                + connectWindow
                                + " ("
                                + e.getMessage()
                                + ")");
            }
            return awaitGpgNetConfirmation(gpgNetConnected, deadline);
        } finally {
            // Closing tells the adapter the "game" went away; it logs the lost connection and
            // carries on. A failure here cannot change a verdict already decided above.
            try {
                probe.close();
            } catch (IOException e) {
                LOG.debug("ice-smoke: closing the GPGNet probe socket failed: {}", e.getMessage());
            }
        }
    }

    /**
     * The confirming half of phase 5: the adapter telling us, on the RPC channel, that its GPGNet
     * server accepted a client.
     *
     * @param gpgNetConnected future the notification handler completes
     * @param deadline nanoTime the whole check must finish by
     * @return the final outcome
     */
    private Result awaitGpgNetConfirmation(
            final CompletableFuture<String> gpgNetConnected, final long deadline) {
        Duration window = min(remaining(deadline), GPGNET_CONFIRM_TIMEOUT);
        LOG.info("ice-smoke: awaiting adapter's GPGNet connection notice (within {})", window);
        try {
            gpgNetConnected.get(window.toMillis(), TimeUnit.MILLISECONDS);
            LOG.info("ice-smoke: {}", reachableDetail());
            return new Result(Verdict.REACHABLE, reachableDetail());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return interrupted("GPGNet confirmation");
        } catch (ExecutionException | TimeoutException e) {
            return new Result(
                    Verdict.GPGNET_UNCONFIRMED,
                    "GPGNet confirmation: the GPGNet port accepted the probe but the adapter did "
                            + "not report "
                            + GPGNET_STATE_NOTIFICATION
                            + "(\""
                            + GPGNET_CONNECTED_STATE
                            + "\") within "
                            + window
                            + " ("
                            + rootMessage(e)
                            + ")");
        }
    }

    /**
     * Completes {@code gpgNetConnected} when the adapter reports a connected GPGNet client.
     * Malformed notifications are dropped with a warning, matching the log-and-drop convention
     * elsewhere on this channel; a {@code "Disconnected"} report is not the state being waited for.
     *
     * @param gpgNetConnected the future to complete
     * @param notification the full JSON-RPC notification node, as delivered on the reader thread
     */
    private static void completeOnConnected(
            final CompletableFuture<String> gpgNetConnected, final JsonNode notification) {
        JsonNode params = notification.get("params");
        if (params == null || !params.isArray() || params.isEmpty() || !params.get(0).isTextual()) {
            LOG.warn(
                    "dropping malformed {} notification from adapter: {}",
                    GPGNET_STATE_NOTIFICATION,
                    notification);
            return;
        }
        String state = params.get(0).asText();
        if (GPGNET_CONNECTED_STATE.equals(state)) {
            gpgNetConnected.complete(state);
        } else {
            LOG.debug("ice-smoke: ignoring gpgnet link state={}", state);
        }
    }

    /**
     * Terminates the adapter through {@link SubprocessManager}'s bounded SIGTERM to SIGKILL path.
     *
     * <p>No {@code quit} RPC first, unlike a session teardown: on a headless host the adapter's
     * {@code quit} handler always errors before it can exit (its tray-icon close is unguarded), so
     * asking would only spend budget on a request that cannot work here.
     *
     * <p>This runs outside the caller's deadline, on every path, using {@link #TEARDOWN_GRACE}
     * rather than the launcher's longer session grace. Killing the adapter is not something a blown
     * budget may skip — a stray adapter would break the next run's port pre-flight — so the honest
     * bound on a whole invocation is the budget plus twice that grace, and only when the adapter
     * ignores SIGTERM.
     *
     * @param adapter the adapter subprocess to stop
     */
    private static void teardown(final SubprocessManager adapter) {
        adapter.terminate(TEARDOWN_GRACE);
        OptionalInt exitCode = adapter.exitCode();
        if (exitCode.isPresent()) {
            LOG.info("ice-smoke: ICE adapter terminated; exit code {}", exitCode.getAsInt());
        } else {
            LOG.warn("ice-smoke: ICE adapter did not exit after terminate()");
        }
    }

    /**
     * Builds the result for an adapter that died mid-check, naming its exit code when known.
     *
     * @param adapter the dead adapter
     * @param phase the phase that noticed
     * @return an {@link Verdict#ADAPTER_EXITED} result
     */
    private static Result adapterExited(final SubprocessManager adapter, final String phase) {
        OptionalInt exitCode = adapter.exitCode();
        return new Result(
                Verdict.ADAPTER_EXITED,
                phase
                        + ": the ICE adapter exited before the check completed"
                        + (exitCode.isPresent() ? "; exit code " + exitCode.getAsInt() : ""));
    }

    /**
     * Builds the result for an interrupted check.
     *
     * @param phase the phase that was interrupted
     * @return an {@link Verdict#INTERRUPTED} result
     */
    private static Result interrupted(final String phase) {
        return new Result(Verdict.INTERRUPTED, phase + ": interrupted while waiting");
    }

    /**
     * The success line, naming both endpoints so a passing run is still evidence.
     *
     * @return the detail text for a reachable adapter
     */
    private String reachableDetail() {
        return "ICE adapter reachable: JSON-RPC "
                + LOOPBACK
                + ":"
                + settings.rpcPort()
                + " answered, GPGNet "
                + LOOPBACK
                + ":"
                + settings.gpgNetPort()
                + " served the probe";
    }

    /**
     * Time left before the overall deadline, floored at zero so a blown budget produces an
     * immediate, bounded wait rather than a negative one.
     *
     * @param deadline nanoTime the whole check must finish by
     * @return the remaining budget, never negative
     */
    private static Duration remaining(final long deadline) {
        long left = deadline - System.nanoTime();
        return left <= 0 ? Duration.ZERO : Duration.ofNanos(left);
    }

    /**
     * The shorter of two durations.
     *
     * @param a first duration
     * @param b second duration
     * @return whichever is shorter
     */
    private static Duration min(final Duration a, final Duration b) {
        return a.compareTo(b) <= 0 ? a : b;
    }

    /**
     * Message of the underlying cause, for callers that wrap failures in {@link
     * ExecutionException}.
     *
     * @param e the caught exception
     * @return the most specific message available
     */
    private static String rootMessage(final Exception e) {
        Throwable cause = e.getCause() == null ? e : e.getCause();
        String message = cause.getMessage();
        return message == null ? cause.getClass().getSimpleName() : message;
    }

    /** The two TCP ports the adapter binds, so the pre-flight can name the one that is busy. */
    private enum PortRole {
        /** The JSON-RPC server the Mock Client connects to. */
        RPC,
        /** The GPGNet server the game connects to. */
        GPGNET;

        /**
         * The configured port for this role.
         *
         * @param adapterSettings the adapter settings being checked
         * @return the port number
         */
        int portOf(final IceAdapterSettings adapterSettings) {
            return this == RPC ? adapterSettings.rpcPort() : adapterSettings.gpgNetPort();
        }

        /**
         * Human-readable name for log and error lines.
         *
         * @return the role's label
         */
        String label() {
            return this == RPC ? "JSON-RPC" : "GPGNet";
        }
    }
}
