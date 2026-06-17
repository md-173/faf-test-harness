package com.faforever.testharness.client.ice;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import com.faforever.testharness.client.config.ConfigLoader;
import com.faforever.testharness.client.config.MockClientConfig;
import com.faforever.testharness.client.process.IceAdapterLauncher;
import com.faforever.testharness.shared.process.SubprocessManager;
import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import java.net.ServerSocket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;
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
 * steps 1–2 (adapter launch → TCP connect → readiness {@code status} poll, §6). Everything past
 * readiness ({@code setIceServers}, ICE candidate relay, GPGNet forwarding) is downstream and out
 * of scope.
 *
 * <p><b>Gating.</b> Mirrors {@link
 * com.faforever.testharness.client.lobby.LobbyConnectionLiveSmokeTest}: an {@link EnabledIf} probe
 * self-skips (does not fail) when no real adapter JAR is resolvable. The binary is taken from the
 * {@code FAF_ICE_ADAPTER_JAR} environment variable if set, otherwise the default {@code
 * faf-ice-adapter.jar} on the configured path (CWD or repo root). For how to provision that JAR,
 * see {@code documentation/operations/ice-adapter-setup.md} (R74) — the provisioning steps are
 * deliberately not duplicated here.
 *
 * <p>Each run allocates a fresh free {@code --rpc-port} so concurrent harness runs don't collide,
 * and always tears the adapter down via {@link SubprocessManager#terminate()} in a {@code finally}
 * — no zombie process survives, including on failure.
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
     * A SIGTERM-ed JVM exits with 128 + SIGTERM(15); 128 + SIGKILL(9) = 137 would mean grace blown.
     */
    private static final int EXIT_SIGTERM = 128 + 15;

    @Test
    @EnabledIf("adapterJarAvailable")
    void spawnsRealAdapterConnectsRunsReadinessStatusThenExitsCleanly() throws Exception {
        Path binary = resolveAdapterBinary();
        int rpcPort = freePort();
        MockClientConfig config = configFor(binary, rpcPort);

        SubprocessManager adapter = new IceAdapterLauncher(config).start();
        IceAdapterConnection conn =
                new IceAdapterConnection(rpcPort, CONNECT_ATTEMPTS, RETRY_DELAY, CALL_TIMEOUT);
        try {
            // Boot step 2: TCP connect (with retry while the adapter JVM is still binding).
            conn.connect().get(30, TimeUnit.SECONDS);

            // Readiness: the `status` RPC round-trips against the real binary (spec §6/§9). The
            // real
            // adapter returns `result` as a (double-encoded) JSON string, so a non-null result node
            // is enough to prove the request/response path works end-to-end.
            JsonNode status = conn.call("status").get(CALL_TIMEOUT.toSeconds(), TimeUnit.SECONDS);
            assertFalse(status == null || status.isNull(), "adapter `status` returned no result");
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

    /** Allocate a free loopback port for the adapter's {@code --rpc-port}. */
    private static int freePort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }

    /**
     * Build a {@link MockClientConfig} pointing the launcher at {@code binary} on the allocated
     * {@code rpcPort}. The OAuth fields are required by config validation but unused here — the
     * adapter is only spawned, never authenticated (see ice-adapter-setup.md).
     */
    private static MockClientConfig configFor(final Path binary, final int rpcPort) {
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
                        "--ice-adapter-rpc-port=" + rpcPort);
        return ConfigLoader.load(args.toArray(new String[0]), java.util.Map.of()).orElseThrow();
    }
}
