package com.faforever.testharness.client.lobby;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Live smoke test against the FAF public test environment ({@code wss://lobby.faforever.xyz}).
 * Verifies that the WebSocket upgrade completes against the canonical test-env endpoint named in
 * the WBS-2.2.10 acceptance criteria and in the production desktop client's {@code
 * application-test.yml}.
 *
 * <p>Tagged {@code integration} so it does <em>not</em> run under the default {@code ./gradlew
 * test} task, and {@code @Disabled} by default — empirically the FAF {@code .xyz} test environment
 * is not reachable from typical developer networks (TCP times out on every probed port for {@code
 * lobby.faforever.xyz}; the alternate {@code ws.faforever.xyz/ws} returns HTTP 502 from
 * Cloudflare). The endpoint and assertion are kept here as the contract this transport is built
 * against; remove {@code @Disabled} and run {@code ./gradlew :mock-client:integrationTest} when the
 * FAF test environment is confirmed reachable.
 *
 * <p>Scope: this test asserts only that the WebSocket upgrade succeeds, not a ping/pong round-trip.
 * Empirical findings against {@code wss://ws.faforever.xyz/ws} (the only currently responsive WS
 * endpoint) show the upstream completes the upgrade but stays silent for both {@code ping} and
 * {@code ask_session}, suggesting the bridge requires an authenticated session before any reply.
 * The full ping/pong round-trip the original acceptance criterion asks for is therefore gated on
 * the OAuth refresh-token module (WBS-3.1.5.1.x) landing — re-enable then.
 */
@Tag("integration")
@Disabled(
        "FAF .xyz test environment is not reachable from typical developer networks; remove this "
                + "annotation once the env is confirmed up and run :mock-client:integrationTest "
                + "explicitly. See LobbyConnectionLiveSmokeTest class javadoc for the empirical "
                + "findings that motivated this gate.")
final class LobbyConnectionLiveSmokeTest {

    /**
     * Canonical FAF test-env lobby endpoint. Matches {@code application-test.yml} in the production
     * desktop client and the WBS-2.2.10 acceptance criteria. (Spec §1's transport table lists
     * {@code wss://ws.faforever.xyz/ws}, but that disagrees with §2, with the issue, and with the
     * deployed reference client.)
     */
    private static final URI FAF_TEST_LOBBY = URI.create("wss://lobby.faforever.xyz");

    @Test
    void connectSucceeds() throws Exception {
        LobbyConnection lobby = new LobbyConnection(FAF_TEST_LOBBY);
        List<LobbyConnection.DisconnectEvent> disconnects = new CopyOnWriteArrayList<>();
        CountDownLatch disconnected = new CountDownLatch(1);
        lobby.onDisconnect(
                event -> {
                    disconnects.add(event);
                    disconnected.countDown();
                });

        try {
            // If the WS upgrade completes, the future resolves without an exception. That's the
            // achievable proof the transport works against the real FAF endpoint; full ping/pong
            // can't be verified without auth (see class javadoc).
            lobby.connect().get(15, TimeUnit.SECONDS);

            // Guard against the "upgrade completes then immediately closes" failure mode: assert
            // we're still connected after a short observation window.
            boolean fired = disconnected.await(2, TimeUnit.SECONDS);
            assertTrue(!fired, "connected then immediately disconnected: " + disconnects);
        } finally {
            lobby.close().get(5, TimeUnit.SECONDS);
        }
    }
}
