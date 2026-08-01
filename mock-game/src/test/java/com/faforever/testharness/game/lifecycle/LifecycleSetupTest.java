package com.faforever.testharness.game.lifecycle;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.faforever.testharness.game.gpgnet.GpgNetConnection;
import com.faforever.testharness.game.gpgnet.GpgNetFrame;
import com.faforever.testharness.game.gpgnet.ScriptedGpgNetServer;
import java.io.IOException;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

public final class LifecycleSetupTest {

    private ScriptedGpgNetServer gpgnet;

    @BeforeEach
    void setupServer() throws IOException {
        gpgnet = new ScriptedGpgNetServer();
    }

    @AfterEach
    void teardownServer() {
        gpgnet.stop();
    }

    @Test
    // Tests initial gpgnet connection causes a GameState("Idle") and following CreateLobby causes a
    // GameState("Lobby"), with similar internal state.
    void gpgnetSetup() throws Exception {
        MockGameLifecycle lifecycle = new MockGameLifecycle(new GpgNetConnection(gpgnet.port()));
        assertEquals(GameState.INITIALIZING, lifecycle.getState());
        gpgnet.start();

        gpgnet.awaitClient();
        GpgNetFrame received = gpgnet.pollReceived(1, TimeUnit.SECONDS);
        assertEquals(GameState.IDLE, lifecycle.getState());
        assertEquals("GameState", received.command());
        assertEquals("Idle", received.args().get(0));

        gpgnet.sendFrame(new GpgNetFrame("CreateLobby", List.of(0, 5000, "Rhiza", 1, 1)));
        received = gpgnet.pollReceived(1, TimeUnit.SECONDS);
        assertEquals(GameState.LOBBY, lifecycle.getState());
        assertEquals("GameState", received.command());
        assertEquals("Lobby", received.args().get(0));
    }

    @Test
    void hostBranch() throws Exception {
        MockGameLifecycle lifecycle = new MockGameLifecycle(new GpgNetConnection(gpgnet.port()));
        gpgnet.start();
        gpgnet.awaitClient();
        // Drop frame
        gpgnet.pollReceived(1, TimeUnit.SECONDS);

        gpgnet.sendFrame(new GpgNetFrame("CreateLobby", List.of(0, 5000, "Rhiza", 1, 1)));
        // Drop frame
        gpgnet.pollReceived(1, TimeUnit.SECONDS);

        gpgnet.sendFrame(new GpgNetFrame("HostGame", List.of("scm_007")));
        lifecycle.stateReached(GameState.HOSTING).get(1, TimeUnit.SECONDS);

        lifecycle.launchMatch();
        GpgNetFrame received = gpgnet.pollReceived(1, TimeUnit.SECONDS);
        assertEquals(GameState.LIVE, lifecycle.getState());
        assertEquals("GameState", received.command());
        assertEquals("Launching", received.args().get(0));

        lifecycle.endMatch();
        received = gpgnet.pollReceived(1, TimeUnit.SECONDS);
        assertEquals("GameState", received.command());
        assertEquals("Ended", received.args().get(0));

        received = gpgnet.pollReceived(1, TimeUnit.SECONDS);
        assertEquals("GameResult", received.command());

        received = gpgnet.pollReceived(1, TimeUnit.SECONDS);
        assertEquals("JsonStats", received.command());

        received = gpgnet.pollReceived(1, TimeUnit.SECONDS);
        assertEquals("GameEnded", received.command());
        assertEquals(GameState.ENDED, lifecycle.getState());
    }

    @Test
    void joinBranch() throws Exception {
        MockGameLifecycle lifecycle = new MockGameLifecycle(new GpgNetConnection(gpgnet.port()));
        gpgnet.start();
        gpgnet.awaitClient();
        // Drop frame
        gpgnet.pollReceived(1, TimeUnit.SECONDS);

        gpgnet.sendFrame(new GpgNetFrame("CreateLobby", List.of(0, 5000, "Rhiza", 1, 1)));
        // Drop frame
        gpgnet.pollReceived(1, TimeUnit.SECONDS);

        gpgnet.sendFrame(new GpgNetFrame("JoinGame", List.of("127.0.0.1", "Smith", 2)));
        lifecycle.stateReached(GameState.JOINING).get(1, TimeUnit.SECONDS);

        lifecycle.launchMatch();
        lifecycle.stateReached(GameState.LIVE).get(1, TimeUnit.SECONDS);

        lifecycle.endMatch();
        GpgNetFrame received = gpgnet.pollReceived(1, TimeUnit.SECONDS);
        assertEquals("GameState", received.command());
        assertEquals("Ended", received.args().get(0));

        received = gpgnet.pollReceived(1, TimeUnit.SECONDS);
        assertEquals("GameResult", received.command());

        received = gpgnet.pollReceived(1, TimeUnit.SECONDS);
        assertEquals("JsonStats", received.command());

        received = gpgnet.pollReceived(1, TimeUnit.SECONDS);
        assertEquals("GameEnded", received.command());
        assertEquals(GameState.ENDED, lifecycle.getState());
    }
}
