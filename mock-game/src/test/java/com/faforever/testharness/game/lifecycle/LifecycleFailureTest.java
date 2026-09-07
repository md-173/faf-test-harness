package com.faforever.testharness.game.lifecycle;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.faforever.testharness.game.config.MockGameConfig;
import com.faforever.testharness.game.gpgnet.GpgNetConnection;
import com.faforever.testharness.game.gpgnet.GpgNetFrame;
import com.faforever.testharness.game.gpgnet.ScriptedGpgNetServer;
import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

public final class LifecycleFailureTest {
    private static final MockGameConfig DEFAULT_CONFIG =
            new MockGameConfig(50000, 50001, 1, "Rhiza", 9001, Map.of(), 0);

    /**
     * A port nothing in this JVM can be listening on, for the two cases that want a connect to
     * fail. Stopping the fixture server and reusing its number does not give that: {@code stop()}
     * closes the {@code ServerSocket}, so the OS reclaims the ephemeral number while {@code port()}
     * keeps reporting it, and any other fixture binding port 0 in this JVM can be handed it. These
     * two are the worst instances of that pattern in the suite — they use the single-argument
     * {@code GpgNetConnection} constructor, so they retry for about two seconds rather than the 3 x
     * 20ms the client-side cases used. Port 1 is below every platform's ephemeral range, so no
     * {@code bind(0)} can ever be assigned it (WBS-3.1.4.1-fix, #287).
     */
    private static final int UNBOUND_PORT = 1;

    private ScriptedGpgNetServer gpgnet;

    @BeforeEach
    void setupServer() throws IOException {
        gpgnet = new ScriptedGpgNetServer();
        gpgnet.start();
    }

    @AfterEach
    void teardownServer() {
        gpgnet.stop();
    }

    @Test
    void timeoutWhenServerUnreachable() throws Exception {
        // Set the timeout to 1 second.
        MockGameLifecycle lifecycle =
                new MockGameLifecycle(
                        DEFAULT_CONFIG,
                        new GpgNetConnection(UNBOUND_PORT),
                        Duration.ofSeconds(1),
                        Duration.ofSeconds(1),
                        Duration.ofSeconds(1));
        lifecycle.start();
        // Wait for 2 seconds, timeout should occur.
        lifecycle.stateReached(GameState.ENDED).get(2, TimeUnit.SECONDS);
        assertEquals(MockGameLifecycle.ExitStatus.SERVER_NOT_CONNECTED, lifecycle.getExitStatus());
    }

    @Test
    void serverDisconnectionOnIdle() throws Exception {
        MockGameLifecycle lifecycle =
                new MockGameLifecycle(
                        DEFAULT_CONFIG,
                        new GpgNetConnection(gpgnet.port()),
                        Duration.ofSeconds(1),
                        Duration.ofSeconds(1));
        lifecycle.start();
        lifecycle.stateReached(GameState.IDLE).get(1, TimeUnit.SECONDS);
        gpgnet.stop();
        lifecycle.stateReached(GameState.ENDED).get(10, TimeUnit.SECONDS);
        assertEquals(
                MockGameLifecycle.ExitStatus.SERVER_CONNECTION_LOST, lifecycle.getExitStatus());
    }

    @Test
    void serverDisconnectionOnLive() throws Exception {
        // No delay and match duration so that those don't intefere.
        MockGameLifecycle lifecycle =
                new MockGameLifecycle(
                        DEFAULT_CONFIG, new GpgNetConnection(gpgnet.port()), null, null);
        lifecycle.start();
        lifecycle.stateReached(GameState.IDLE).get(1, TimeUnit.SECONDS);

        gpgnet.sendFrame(new GpgNetFrame("CreateLobby", List.of(0, 50001, "Rhiza", 1, 1)));
        lifecycle.stateReached(GameState.LOBBY).get(1, TimeUnit.SECONDS);

        gpgnet.sendFrame(new GpgNetFrame("HostGame", List.of("scm_007")));
        lifecycle.stateReached(GameState.HOSTING).get(1, TimeUnit.SECONDS);

        lifecycle.launchMatch();
        lifecycle.stateReached(GameState.LIVE).get(1, TimeUnit.SECONDS);
        gpgnet.stop();
        lifecycle.stateReached(GameState.ENDED).get(10, TimeUnit.SECONDS);
        assertEquals(
                MockGameLifecycle.ExitStatus.SERVER_CONNECTION_LOST, lifecycle.getExitStatus());
    }

    @Test
    void connectionClosedOnLive() throws Exception {
        GpgNetConnection conn = new GpgNetConnection(gpgnet.port());
        // No delay and match duration so that those don't intefere.
        MockGameLifecycle lifecycle = new MockGameLifecycle(DEFAULT_CONFIG, conn, null, null);
        lifecycle.start();
        lifecycle.stateReached(GameState.IDLE).get(1, TimeUnit.SECONDS);

        gpgnet.sendFrame(new GpgNetFrame("CreateLobby", List.of(0, 50001, "Rhiza", 1, 1)));
        lifecycle.stateReached(GameState.LOBBY).get(1, TimeUnit.SECONDS);

        gpgnet.sendFrame(new GpgNetFrame("HostGame", List.of("scm_007")));
        lifecycle.stateReached(GameState.HOSTING).get(1, TimeUnit.SECONDS);

        lifecycle.launchMatch();
        lifecycle.stateReached(GameState.LIVE).get(1, TimeUnit.SECONDS);
        conn.close();
        // A local close does not cause the lifecycle to reach ended.
        assertThrows(
                TimeoutException.class,
                () -> lifecycle.stateReached(GameState.ENDED).get(1, TimeUnit.SECONDS));
    }

    @Test
    void serverDisconnectionOnInitializing() throws Exception {
        // GpgNetConnection.start fails after 10 tries (default) and sends a ServerDisconnected.
        MockGameLifecycle lifecycle =
                new MockGameLifecycle(
                        DEFAULT_CONFIG,
                        new GpgNetConnection(UNBOUND_PORT),
                        Duration.ofSeconds(1),
                        Duration.ofSeconds(1));
        lifecycle.start();
        lifecycle.stateReached(GameState.ENDED).get(3, TimeUnit.SECONDS);
        assertEquals(MockGameLifecycle.ExitStatus.SERVER_NOT_CONNECTED, lifecycle.getExitStatus());
    }

    @Test
    void malformedJoinGameCommand() throws Exception {
        MockGameLifecycle lifecycle =
                new MockGameLifecycle(
                        DEFAULT_CONFIG,
                        new GpgNetConnection(gpgnet.port()),
                        Duration.ofSeconds(1),
                        Duration.ofSeconds(1));
        lifecycle.start();
        lifecycle.stateReached(GameState.IDLE).get(1, TimeUnit.SECONDS);
        gpgnet.sendFrame(new GpgNetFrame("CreateLobby", List.of(0, 5000, "Rhiza", 1, 1)));
        lifecycle.stateReached(GameState.LOBBY).get(1, TimeUnit.SECONDS);
        // JoinGame with no arguments.
        gpgnet.sendFrame(new GpgNetFrame("JoinGame", List.of()));
        // Causes ENDED
        lifecycle.stateReached(GameState.ENDED).get(1, TimeUnit.SECONDS);
        // Generic failure exit status
        assertEquals(MockGameLifecycle.ExitStatus.FAILED, lifecycle.getExitStatus());
    }
}
