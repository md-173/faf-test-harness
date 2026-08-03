package com.faforever.testharness.game.lifecycle;

import com.faforever.testharness.game.config.MockGameConfig;
import com.faforever.testharness.game.gpgnet.GpgNetConnection;
import com.faforever.testharness.game.gpgnet.GpgNetFrame;
import com.faforever.testharness.game.gpgnet.ScriptedGpgNetServer;
import java.io.IOException;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

public final class LifecycleFailureTest {
    private static final MockGameConfig DEFAULT_CONFIG =
            new MockGameConfig(50000, 50001, 1, "Rhiza");
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
        // Stop the server now.
        gpgnet.stop();
        // Set the timeout to 1 second.
        MockGameLifecycle lifecycle =
                new MockGameLifecycle(DEFAULT_CONFIG, new GpgNetConnection(gpgnet.port()), 1000);
        // Wait for 2 seconds, timeout should occur.
        lifecycle.stateReached(GameState.ENDED).get(2, TimeUnit.SECONDS);
    }

    @Test
    void serverDisconnection() throws Exception {
        MockGameLifecycle lifecycle =
                new MockGameLifecycle(DEFAULT_CONFIG, new GpgNetConnection(gpgnet.port()));
        lifecycle.stateReached(GameState.IDLE).get(1, TimeUnit.SECONDS);
        gpgnet.stop();
        lifecycle.stateReached(GameState.ENDED).get(10, TimeUnit.SECONDS);
    }

    @Test
    void serverDisconnectionOnInitializing() throws Exception {
        // Stop the server now.
        gpgnet.stop();
        // GpgNetConnection.start fails after 10 tries (default) and sends a ServerDisconnected.
        MockGameLifecycle lifecycle =
                new MockGameLifecycle(DEFAULT_CONFIG, new GpgNetConnection(gpgnet.port()));
        lifecycle.stateReached(GameState.ENDED).get(3, TimeUnit.SECONDS);
    }

    @Test
    void malformedJoinGameCommand() throws Exception {
        MockGameLifecycle lifecycle =
                new MockGameLifecycle(DEFAULT_CONFIG, new GpgNetConnection(gpgnet.port()));
        lifecycle.stateReached(GameState.IDLE).get(1, TimeUnit.SECONDS);
        gpgnet.sendFrame(new GpgNetFrame("CreateLobby", List.of(0, 5000, "Rhiza", 1, 1)));
        lifecycle.stateReached(GameState.LOBBY).get(1, TimeUnit.SECONDS);
        // JoinGame with no arguments.
        gpgnet.sendFrame(new GpgNetFrame("JoinGame", List.of()));
        // Causes ENDED
        lifecycle.stateReached(GameState.ENDED).get(1, TimeUnit.SECONDS);
    }
}
