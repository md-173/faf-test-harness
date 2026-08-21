package com.faforever.testharness.client.lobby;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;

/**
 * Live smoke tests against the FAF public test environment, tagged {@code integration} so they
 * don't run under the default {@code ./gradlew test} task.
 *
 * <p>Two scenarios, both targeting the canonical {@code wss://ws.faforever.xyz} (served at the host
 * root {@code /}) and both reachability-gated so they self-skip when the FAF test env is
 * unreachable from the current network:
 *
 * <ul>
 *   <li>{@link #connectSucceeds()} — connect and verify the WS upgrade. Self-skips when {@code
 *       ws.faforever.xyz:443} is unreachable (TCP timeout).
 *   <li>{@link #authHandshakeYieldsTerminalReply()} — the full production path: refresh-token →
 *       Hydra → JWT → {@code ask_session} → {@code session} → {@code auth} → {@code welcome} or
 *       {@code authentication_failed}. Additionally gated on a local {@code
 *       .secrets/refresh_token.txt}. Either terminal reply is a pass — both prove the transport
 *       works bidirectionally; only silence/timeout once connected is a failure.
 * </ul>
 *
 * <p>This test mutates {@code .secrets/refresh_token.txt} — Hydra rotates the refresh token on
 * every use, and the spec requires persisting the new one atomically (write-then-rename) before
 * treating the refresh as successful (lobby-protocol-spec.md §2). If you re-run this test back to
 * back without the rotation succeeding, Hydra will return {@code invalid_grant} and you'll need to
 * re-run the manual bootstrap.
 *
 * <p><b>If you get {@code invalid_client} or {@code invalid_grant}</b>, your local refresh token
 * was minted against a client_id that no longer exists (most commonly the legacy {@code faf-client}
 * ID retired in the Hydra 2.x migration). Re-bootstrap per spec §2 step 3: visit Hydra's
 * authorization endpoint in a browser with {@code client_id=95ecec08-29c1-4c48-ae0a-b000ff349cb8},
 * {@code response_type=code}, {@code redirect_uri=http://127.0.0.1}, {@code
 * scope=openid+offline+lobby}, and any state ≥ 8 chars. Log in as a test user (shared password
 * {@code foo}), copy the {@code code=} from the failed 127.0.0.1 redirect, exchange it at {@code
 * https://hydra.faforever.xyz/oauth2/token}, and write the response's {@code refresh_token} to
 * {@code .secrets/refresh_token.txt}, overwriting any old one.
 */
@Tag("integration")
final class LobbyConnectionLiveSmokeTest {

    /**
     * Canonical FAF test-env lobby endpoint, served natively at the host root ({@code /}) by the
     * lobby server (the {@code .com}→{@code .xyz} swap of production {@code
     * wss://ws.faforever.com}; there is no {@code /ws} path — a {@code GET /ws} 404s, that was the
     * retired {@code ws_bridge_rs} path). {@code wss://lobby.faforever.xyz}, read from
     * downlords-faf-client's {@code application-test.yml}, times out from every network tried (WSL,
     * native Windows, curl, an independent cloud egress, and a FAF maintainer) and is wrong in
     * practice — see {@code documentation/research/lobby-protocol-spec.md} §1's 2026-06-18
     * correction. Confirmed empirically by a full live login through this test.
     */
    private static final URI FAF_TEST_LOBBY = URI.create("wss://ws.faforever.xyz");

    /** FAF Hydra test-env token endpoint. */
    private static final URI HYDRA_TOKEN = URI.create("https://hydra.faforever.xyz/oauth2/token");

    /**
     * Seeded "FAF Classic Client (Python)" public OAuth client (spec §2). The legacy {@code
     * faf-client} ID was removed during the Hydra 2.x migration and is no longer registered.
     */
    private static final String FAF_OAUTH_CLIENT_ID = "95ecec08-29c1-4c48-ae0a-b000ff349cb8";

    /** Placeholder hardware identifier; spec §3 leaves the exact value to the implementer. */
    private static final String FAF_MOCK_UNIQUE_ID = "00000000-0000-0000-0000-000000000000";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void connectSucceeds() throws Exception {
        assumeTrue(
                lobbyReachable(),
                "FAF test lobby "
                        + FAF_TEST_LOBBY
                        + " unreachable from this network (TCP timeout on :443). Self-skips "
                        + "off-net; runs on a FAF-allowlisted host/VPN.");
        LobbyConnection lobby = new LobbyConnection(FAF_TEST_LOBBY);
        List<LobbyConnection.DisconnectEvent> disconnects = new CopyOnWriteArrayList<>();
        CountDownLatch disconnected = new CountDownLatch(1);
        lobby.onDisconnect(
                event -> {
                    disconnects.add(event);
                    disconnected.countDown();
                });

        try {
            lobby.connect().get(15, TimeUnit.SECONDS);
            boolean fired = disconnected.await(2, TimeUnit.SECONDS);
            assertTrue(!fired, "connected then immediately disconnected: " + disconnects);
        } finally {
            lobby.close().get(5, TimeUnit.SECONDS);
        }
    }

    @Test
    @EnabledIf("hasRefreshToken")
    void authHandshakeYieldsTerminalReply() throws Exception {
        // Probe reachability BEFORE the token exchange so an off-network run skips cleanly
        // without burning (rotating) the refresh token.
        assumeTrue(
                lobbyReachable(),
                "FAF test lobby "
                        + FAF_TEST_LOBBY
                        + " unreachable (TCP timeout on :443 — destination-side allowlist/VPN "
                        + "gate). Run from an allowlisted FAF host.");

        Path refreshFile = findRefreshTokenFile();
        String accessToken = exchangeRefreshTokenForJwt(refreshFile);

        LobbyConnection lobby = new LobbyConnection(FAF_TEST_LOBBY);

        AtomicReference<JsonNode> sessionMsg = new AtomicReference<>();
        AtomicReference<JsonNode> welcomeMsg = new AtomicReference<>();
        AtomicReference<JsonNode> failureMsg = new AtomicReference<>();
        CountDownLatch sessionLatch = new CountDownLatch(1);
        CountDownLatch terminalLatch = new CountDownLatch(1);
        List<LobbyConnection.DisconnectEvent> disconnects = new CopyOnWriteArrayList<>();

        lobby.onDisconnect(disconnects::add);
        lobby.registerHandler(
                "session",
                node -> {
                    sessionMsg.set(node);
                    sessionLatch.countDown();
                });
        lobby.registerHandler(
                "welcome",
                node -> {
                    welcomeMsg.set(node);
                    terminalLatch.countDown();
                });
        lobby.registerHandler(
                "authentication_failed",
                node -> {
                    failureMsg.set(node);
                    terminalLatch.countDown();
                });

        try {
            lobby.connect().get(15, TimeUnit.SECONDS);

            ObjectNode askSession = MAPPER.createObjectNode();
            askSession.put("command", "ask_session");
            askSession.put("version", "0.0.0-mock");
            askSession.put("user_agent", "faf-test-harness");
            lobby.send(askSession).get(5, TimeUnit.SECONDS);

            assertTrue(
                    sessionLatch.await(15, TimeUnit.SECONDS),
                    "no `session` response within 15s. disconnects=" + disconnects);
            long sessionId = sessionMsg.get().get("session").asLong();

            ObjectNode auth = MAPPER.createObjectNode();
            auth.put("command", "auth");
            auth.put("token", accessToken);
            auth.put("unique_id", FAF_MOCK_UNIQUE_ID);
            auth.put("session", sessionId);
            lobby.send(auth).get(5, TimeUnit.SECONDS);

            assertTrue(
                    terminalLatch.await(15, TimeUnit.SECONDS),
                    "no `welcome` or `authentication_failed` within 15s of auth. session="
                            + sessionMsg.get()
                            + ", disconnects="
                            + disconnects);

            JsonNode terminal = welcomeMsg.get() != null ? welcomeMsg.get() : failureMsg.get();
            assertNotNull(terminal);
            // Either terminal is a pass — both prove the transport works end-to-end.
            // Log the outcome so the test runner shows which path was exercised.
            System.out.println("[live smoke] auth handshake terminal: " + terminal);
        } finally {
            lobby.close().get(5, TimeUnit.SECONDS);
        }
    }

    /** Locate {@code .secrets/refresh_token.txt} relative to either the subproject or repo root. */
    private static Path findRefreshTokenFile() {
        String[] candidates = {".secrets/refresh_token.txt", "../.secrets/refresh_token.txt"};
        for (String candidate : candidates) {
            Path p = Path.of(candidate);
            if (Files.isRegularFile(p)) {
                return p;
            }
        }
        return null;
    }

    /** {@code @EnabledIf} probe — skips the auth test cleanly when no refresh token is present. */
    @SuppressWarnings("unused")
    static boolean hasRefreshToken() {
        Path p = findRefreshTokenFile();
        boolean present = false;
        try {
            present = p != null && Files.size(p) > 0;
        } catch (Exception e) {
            // unreadable file counts as absent
        }
        if (!present) {
            System.out.println(
                    "[live smoke] skipping authHandshakeYieldsTerminalReply: no readable "
                            + ".secrets/refresh_token.txt found. See this class's javadoc to "
                            + "bootstrap one.");
        }
        return present;
    }

    /**
     * True iff the lobby host accepts a TCP connection on its port within a short timeout. Lets the
     * live tests self-skip (not fail) when the FAF test env is unreachable from this network.
     */
    private static boolean lobbyReachable() {
        int port = FAF_TEST_LOBBY.getPort() == -1 ? 443 : FAF_TEST_LOBBY.getPort();
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(FAF_TEST_LOBBY.getHost(), port), 3000);
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    /**
     * Exchange the on-disk refresh token at Hydra for a fresh access-token JWT and persist the
     * rotated refresh token atomically.
     */
    private static String exchangeRefreshTokenForJwt(final Path refreshFile) throws Exception {
        String refreshToken = Files.readString(refreshFile).strip();
        String body =
                "grant_type=refresh_token"
                        + "&refresh_token="
                        + URLEncoder.encode(refreshToken, StandardCharsets.UTF_8)
                        + "&client_id="
                        + URLEncoder.encode(FAF_OAUTH_CLIENT_ID, StandardCharsets.UTF_8);

        HttpClient http = HttpClient.newHttpClient();
        HttpRequest req =
                HttpRequest.newBuilder(HYDRA_TOKEN)
                        .header("Content-Type", "application/x-www-form-urlencoded")
                        .POST(HttpRequest.BodyPublishers.ofString(body))
                        .build();
        HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
        // Skip (not fail) when the local refresh token is no longer valid — that's a credential
        // bootstrap problem, not a transport problem. See class javadoc for the bootstrap steps.
        assumeTrue(
                resp.statusCode() == 200,
                "Hydra rejected the refresh token (status="
                        + resp.statusCode()
                        + "). Re-bootstrap .secrets/refresh_token.txt per the class javadoc. "
                        + "Hydra said: "
                        + resp.body());

        JsonNode tokenResp = MAPPER.readTree(resp.body());
        String accessToken = tokenResp.get("access_token").asText();
        String newRefreshToken = tokenResp.get("refresh_token").asText();

        // Persist the rotated refresh token atomically *before* returning the access token, so a
        // crash between here and the WS auth message doesn't lose the new token. See spec §2:
        // "Hydra rotates the refresh_token on each use — persist the new one atomically".
        Path tmp = refreshFile.resolveSibling(refreshFile.getFileName() + ".tmp");
        Files.writeString(tmp, newRefreshToken);
        try {
            Files.move(
                    tmp,
                    refreshFile,
                    StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE);
        } catch (java.nio.file.AtomicMoveNotSupportedException e) {
            // Fallback for filesystems without atomic-move (e.g. some Windows configs).
            Files.move(tmp, refreshFile, StandardCopyOption.REPLACE_EXISTING);
        }

        return accessToken;
    }
}
