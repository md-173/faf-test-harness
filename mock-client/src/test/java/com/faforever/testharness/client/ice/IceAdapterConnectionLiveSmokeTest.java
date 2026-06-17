package com.faforever.testharness.client.ice;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import com.faforever.testharness.client.config.ConfigLoader;
import com.faforever.testharness.client.config.MockClientConfig;
import com.faforever.testharness.client.process.IceAdapterLauncher;
import com.faforever.testharness.shared.process.SubprocessManager;
import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import java.net.DatagramSocket;
import java.net.ServerSocket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledIf;

/**
 * Live smoke test proving the Mock Client can talk to the <em>real</em> {@code faf-ice-adapter} JAR
 * end-to-end (WBS 3.1.4.x). Tagged {@code integration} so it runs under {@code ./gradlew
 * integrationTest}, not the default {@code ./gradlew test}.
 *
 * <p>The 3.1.4.1 unit tests ({@link IceAdapterConnectionTest}) exercise {@link
 * IceAdapterConnection} against an in-process {@link ScriptedJsonRpcServer} — that proves framing,
 * correlation, and disconnect mechanics, but not that we can actually launch and connect to the
 * real binary. This covers exactly the gap: {@code documentation/research/json-rpc-spec.md} §9 boot
 * steps 1–2 (adapter launch → TCP connect) plus the §6 {@code status} health round-trip. Everything
 * past readiness ({@code setIceServers}, ICE candidate relay, GPGNet forwarding) is downstream and
 * out of scope.
 *
 * <p><b>Gating.</b> Mirrors {@link
 * com.faforever.testharness.client.lobby.LobbyConnectionLiveSmokeTest}: an {@link EnabledIf} probe
 * self-skips (does not fail) when no real adapter JAR is resolvable. The binary is taken from the
 * {@code FAF_ICE_ADAPTER_JAR} environment variable if set, otherwise the default {@code
 * faf-ice-adapter.jar} on the configured path (CWD or repo root). For how to provision that JAR,
 * see {@code documentation/operations/ice-adapter-setup.md} (R74) — the provisioning steps are
 * deliberately not duplicated here.
 *
 * <p>Each run allocates fresh free ports for all three adapter sockets — {@code --rpc-port} and
 * {@code --gpgnet-port} (TCP) and {@code --lobby-port} (UDP) — so concurrent harness runs don't
 * collide. (The adapter binds GPGNet before RPC, so leaving those two on their fixed defaults would
 * let a second run fail to bind and exit before RPC is ever reachable.) The adapter is always torn
 * down via {@link SubprocessManager#terminate()} in a {@code finally} — no zombie process survives,
 * including on failure.
 */
@Tag("integration")
@Timeout(value = 60, unit = TimeUnit.SECONDS)
final class IceAdapterConnectionLiveSmokeTest {

    /**
     * Environment override for the adapter JAR, consistent with R74's documented setup ({@code
     * documentation/operations/ice-adapter-setup.md}).
     */
    private static final String ADAPTER_JAR_ENV = "FAF_ICE_ADAPTER_JAR";

    /**
     * The real adapter JVM is far slower to bind its RPC port than the in-process fixture, so widen
     * the connect-retry window well past {@link IceAdapterConnection}'s 2 s default: 100 attempts ×
     * 200 ms ≈ 20 s of cold-start headroom.
     */
    private static final int CONNECT_ATTEMPTS = 100;

    private static final Duration RETRY_DELAY = Duration.ofMillis(200);
    private static final Duration CALL_TIMEOUT = Duration.ofSeconds(5);

    /**
     * Overall budget for {@code status} to start answering once {@link
     * IceAdapterConnection#connect()} returns. {@code connect()} only proves the TCP port is open;
     * the adapter may accept the socket a moment before its JSON-RPC handlers are registered, so
     * {@code status} is polled across this window rather than called once.
     */
    private static final Duration READINESS_TIMEOUT = Duration.ofSeconds(10);

    /** Delay between {@code status} readiness attempts. */
    private static final Duration READINESS_RETRY_DELAY = Duration.ofMillis(200);

    /**
     * A SIGTERM-ed JVM exits with 128 + SIGTERM(15); 128 + SIGKILL(9) = 137 would mean grace blown.
     * POSIX signal semantics — appropriate for this Linux/WSL-targeted harness; a Windows port
     * would need a different expected code (TerminateProcess does not use signal-offset exit
     * codes).
     */
    private static final int EXIT_SIGTERM = 128 + 15;

    @Test
    @EnabledIf("adapterJarAvailable")
    void spawnsRealAdapterConnectsRunsReadinessStatusThenExitsCleanly() throws Exception {
        Path binary = resolveAdapterBinary();
        AdapterPorts ports = freeAdapterPorts();
        int rpcPort = ports.rpc();
        MockClientConfig config = configFor(binary, ports);

        SubprocessManager adapter = new IceAdapterLauncher(config).start();
        IceAdapterConnection conn =
                new IceAdapterConnection(rpcPort, CONNECT_ATTEMPTS, RETRY_DELAY, CALL_TIMEOUT);
        try {
            // Boot step 2: TCP connect (with retry while the adapter JVM is still binding).
            conn.connect().get(30, TimeUnit.SECONDS);

            // Readiness (spec §6 health poll): poll `status` until the adapter's RPC handlers
            // answer — connect() only proves the TCP port is open. A present, non-null result
            // proves the request/response path works end-to-end (the adapter double-encodes the
            // result as a JSON string).
            JsonNode status = awaitStatusReady(conn);
            System.out.println("[live smoke] ICE adapter status: " + status);
        } finally {
            conn.close();
            // Lifecycle is owned by SubprocessManager: SIGTERM, then SIGKILL after the launcher's
            // grace. Always runs, so a failed assertion above still leaves no zombie adapter.
            adapter.terminate();
        }

        // The adapter must have exited within the configured grace — i.e. via SIGTERM (143), not
        // the
        // SIGKILL fallback (137) that terminate() escalates to when grace is exceeded.
        int exitCode = adapter.onExit().get(15, TimeUnit.SECONDS);
        assertFalse(adapter.isAlive(), "adapter should be dead after terminate()");
        assertEquals(
                EXIT_SIGTERM, exitCode, "adapter should exit cleanly on SIGTERM within the grace");
    }

    /**
     * Poll {@code status} until the adapter returns a real result, retrying on a per-call timeout
     * or an RPC error (e.g. the method not being registered yet). This turns the accept-before-
     * handlers-ready race into a self-healing wait. A non-readiness failure — a dropped connection
     * or any other cause — aborts immediately rather than being retried.
     *
     * @param conn the connected adapter transport
     * @return the first present, non-null {@code status} result
     * @throws IllegalStateException if {@code status} never answers within {@link
     *     #READINESS_TIMEOUT}, or the call fails for a reason other than a timeout/RPC error
     */
    private static JsonNode awaitStatusReady(final IceAdapterConnection conn)
            throws InterruptedException {
        long deadline = System.nanoTime() + READINESS_TIMEOUT.toNanos();
        Throwable last = null;
        do {
            try {
                JsonNode status =
                        conn.call("status").get(CALL_TIMEOUT.toSeconds(), TimeUnit.SECONDS);
                if (status != null && !status.isNull()) {
                    return status;
                }
                last = new IllegalStateException("adapter `status` returned no result");
            } catch (TimeoutException e) {
                last = e;
            } catch (ExecutionException e) {
                if (!(e.getCause() instanceof IceRpcException)) {
                    throw new IllegalStateException("adapter `status` call failed", e.getCause());
                }
                last = e.getCause();
            }
            Thread.sleep(READINESS_RETRY_DELAY.toMillis());
        } while (System.nanoTime() < deadline);
        throw new IllegalStateException(
                "adapter `status` never became ready within " + READINESS_TIMEOUT, last);
    }

    /** {@code @EnabledIf} probe — skips cleanly (not fails) when no real adapter JAR is present. */
    @SuppressWarnings("unused")
    static boolean adapterJarAvailable() {
        Path binary = findAdapterBinary();
        if (binary == null) {
            System.out.println(
                    "[live smoke] skipping ICE adapter live smoke test: no real faf-ice-adapter "
                            + "JAR found (set "
                            + ADAPTER_JAR_ENV
                            + " or run ./gradlew downloadIceAdapter; see "
                            + "documentation/operations/ice-adapter-setup.md).");
        }
        return binary != null;
    }

    /**
     * Non-null variant for the test body; the binary is guaranteed present once the gate passes.
     */
    private static Path resolveAdapterBinary() {
        Path binary = findAdapterBinary();
        if (binary == null) {
            throw new IllegalStateException("adapter JAR vanished after the @EnabledIf gate");
        }
        return binary;
    }

    /**
     * Resolve the real adapter JAR: the {@code FAF_ICE_ADAPTER_JAR} env override first, then the
     * default {@code faf-ice-adapter.jar} relative to the subproject CWD and the repo root. Returns
     * {@code null} when none is a regular file.
     */
    private static Path findAdapterBinary() {
        String override = System.getenv(ADAPTER_JAR_ENV);
        if (override != null && !override.isBlank()) {
            Path p = Path.of(override);
            return Files.isRegularFile(p) ? p : null;
        }
        for (String candidate : new String[] {"faf-ice-adapter.jar", "../faf-ice-adapter.jar"}) {
            Path p = Path.of(candidate);
            if (Files.isRegularFile(p)) {
                return p;
            }
        }
        return null;
    }

    /**
     * The three adapter listener ports, allocated free per run.
     *
     * @param rpc JSON-RPC port (TCP)
     * @param gpgnet GPGNet port (TCP)
     * @param lobby lobby game-traffic port (UDP)
     */
    private record AdapterPorts(int rpc, int gpgnet, int lobby) {}

    /**
     * Allocate three distinct free ports for the adapter. The two TCP sockets (rpc, gpgnet) are
     * held open simultaneously so the OS hands out distinct numbers; lobby is probed as UDP since
     * the adapter binds {@code --lobby-port} for UDP game traffic. The sockets are closed before
     * the adapter binds them — a benign TOCTOU window, acceptable for a smoke test.
     */
    private static AdapterPorts freeAdapterPorts() throws IOException {
        try (ServerSocket rpc = new ServerSocket(0);
                ServerSocket gpgnet = new ServerSocket(0);
                DatagramSocket lobby = new DatagramSocket(0)) {
            return new AdapterPorts(
                    rpc.getLocalPort(), gpgnet.getLocalPort(), lobby.getLocalPort());
        }
    }

    /**
     * Build a {@link MockClientConfig} pointing the launcher at {@code binary} on the allocated
     * {@code ports}. The OAuth fields are required by config validation but unused here — the
     * adapter is only spawned, never authenticated (see ice-adapter-setup.md).
     */
    private static MockClientConfig configFor(final Path binary, final AdapterPorts ports) {
        List<String> args =
                List.of(
                        "--lobby-websocket-url=wss://lobby.faforever.xyz",
                        "--oauth-token-url=https://hydra.faforever.xyz/oauth2/token",
                        "--oauth-auth-endpoint=https://hydra.faforever.xyz/oauth2/auth",
                        "--oauth-redirect-uri=http://127.0.0.1",
                        "--oauth-scopes=openid offline lobby",
                        "--oauth-client-id=95ecec08-29c1-4c48-ae0a-b000ff349cb8",
                        "--oauth-refresh-token=dummy-unused-by-live-smoke",
                        "--unique-id=00000000-0000-0000-0000-000000000000",
                        "--ice-adapter-binary-path=" + binary.toAbsolutePath(),
                        "--ice-adapter-rpc-port=" + ports.rpc(),
                        "--ice-adapter-gpg-net-port=" + ports.gpgnet(),
                        "--ice-adapter-lobby-port=" + ports.lobby());
        return ConfigLoader.load(args.toArray(new String[0]), Map.of()).orElseThrow();
    }
}
