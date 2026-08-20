package com.faforever.testharness.game.lifecycle;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.fail;

import com.faforever.testharness.game.config.MockGameConfig;
import com.faforever.testharness.game.gpgnet.GpgNetConnection;
import com.faforever.testharness.game.gpgnet.GpgNetFrame;
import com.faforever.testharness.game.gpgnet.ScriptedGpgNetServer;
import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

public final class LifecycleSetupTest {

    private static final MockGameConfig DEFAULT_CONFIG =
            new MockGameConfig(50000, 50001, 1, "Rhiza", 9001);
    private ScriptedGpgNetServer gpgnet;
    private MockGameLifecycle lifecycle;

    @BeforeEach
    void setup() throws IOException {
        gpgnet = new ScriptedGpgNetServer();
        lifecycle =
                new MockGameLifecycle(
                        DEFAULT_CONFIG,
                        new GpgNetConnection(gpgnet.port()),
                        Duration.ofSeconds(1),
                        Duration.ofSeconds(1));
    }

    @AfterEach
    void teardown() {
        gpgnet.stop();
    }

    @Test
    // Tests initial gpgnet connection causes a GameState("Idle") and following CreateLobby causes a
    // GameState("Lobby"), with similar internal state.
    void gpgnetSetup() throws Exception {
        assertEquals(GameState.INITIALIZING, lifecycle.getState());
        gpgnet.start();

        gpgnet.awaitClient();
        assertMessage("GameState", "Idle");
        lifecycle.stateReached(GameState.IDLE).get(1, TimeUnit.SECONDS);

        gpgnet.sendFrame(new GpgNetFrame("CreateLobby", List.of(0, 5000, "Rhiza", 1, 1)));
        assertMessage("GameState", "Lobby");
        lifecycle.stateReached(GameState.LOBBY).get(1, TimeUnit.SECONDS);
    }

    @Test
    void hostBranch() throws Exception {
        gpgnet.start();
        gpgnet.awaitClient();
        // Drop frame
        gpgnet.pollReceived(1, TimeUnit.SECONDS);

        gpgnet.sendFrame(new GpgNetFrame("CreateLobby", List.of(0, 5000, "Rhiza", 1, 1)));
        // Drop frame
        gpgnet.pollReceived(1, TimeUnit.SECONDS);

        gpgnet.sendFrame(new GpgNetFrame("HostGame", List.of("scm_007")));
        assertMessage("PlayerOption", DEFAULT_CONFIG.playerId(), "Army", 1);
        assertMessage("PlayerOption", DEFAULT_CONFIG.playerId(), "Team", 1);
        assertMessage("PlayerOption", DEFAULT_CONFIG.playerId(), "StartSpot", 1);
        assertMessage("PlayerOption", DEFAULT_CONFIG.playerId(), "Faction", 1);
        assertMessage("PlayerOption", DEFAULT_CONFIG.playerId(), "Color", 1);
        lifecycle.stateReached(GameState.HOSTING).get(1, TimeUnit.SECONDS);

        lifecycle.launchMatch();
        assertMessage("GameState", "Launching");
        lifecycle.stateReached(GameState.LIVE).get(1, TimeUnit.SECONDS);

        lifecycle.endMatch();

        assertMessage("GameResult", 1, "victory 10");
        assertMessageCommand("JsonStats");
        assertMessage("GameEnded");
        assertMessage("GameState", "Ended");

        lifecycle.stateReached(GameState.ENDED).get(1, TimeUnit.SECONDS);
    }

    @Test
    void joinBranch() throws Exception {
        gpgnet.start();
        gpgnet.awaitClient();
        // Drop frame
        gpgnet.pollReceived(1, TimeUnit.SECONDS);

        gpgnet.sendFrame(new GpgNetFrame("CreateLobby", List.of(0, 5000, "Rhiza", 1, 1)));
        // Drop frame
        gpgnet.pollReceived(1, TimeUnit.SECONDS);

        gpgnet.sendFrame(new GpgNetFrame("JoinGame", List.of("127.0.0.1:4000", "Smith", 2)));
        lifecycle.stateReached(GameState.JOINING).get(1, TimeUnit.SECONDS);

        lifecycle.launchMatch();
        assertMessage("GameState", "Launching");
        lifecycle.stateReached(GameState.LIVE).get(1, TimeUnit.SECONDS);

        lifecycle.endMatch();

        // Two GameResult messages as a joiner always has the host as a peer.
        assertMessage("GameResult", 1, "victory 10");
        assertMessage("GameResult", 2, "defeat -10");
        assertMessageCommand("JsonStats");
        assertMessage("GameEnded");
        assertMessage("GameState", "Ended");

        lifecycle.stateReached(GameState.ENDED).get(1, TimeUnit.SECONDS);
    }

    @Test
    void delayedStartAndEnd() throws Exception {
        gpgnet.start();
        gpgnet.awaitClient();

        // Drop frame
        gpgnet.pollReceived(1, TimeUnit.SECONDS);
        gpgnet.sendFrame(new GpgNetFrame("CreateLobby", List.of(0, 5000, "Rhiza", 1, 1)));
        // Drop frame
        gpgnet.pollReceived(1, TimeUnit.SECONDS);

        gpgnet.sendFrame(new GpgNetFrame("HostGame", List.of("scm_007")));
        lifecycle.stateReached(GameState.HOSTING).get(1, TimeUnit.SECONDS);

        // No need to send command, should become LIVE within 1 second (2 for error).
        lifecycle.stateReached(GameState.LIVE).get(2, TimeUnit.SECONDS);

        // No need to send command, should become ENDED within 1 second (2 for error).
        lifecycle.stateReached(GameState.ENDED).get(2, TimeUnit.SECONDS);
    }

    @Test
    void cleanShutdown() throws Exception {
        gpgnet.start();
        gpgnet.awaitClient();

        gpgnet.sendFrame(new GpgNetFrame("CreateLobby", List.of(0, 5000, "Rhiza", 1, 1)));

        gpgnet.sendFrame(new GpgNetFrame("HostGame", List.of("scm_007")));
        lifecycle.stateReached(GameState.HOSTING).get(1, TimeUnit.SECONDS);

        lifecycle.launchMatch();
        lifecycle.stateReached(GameState.LIVE).get(1, TimeUnit.SECONDS);

        lifecycle.endMatch();

        lifecycle.stateReached(GameState.ENDED).get(1, TimeUnit.SECONDS);
        assertEquals(MockGameLifecycle.ExitStatus.OK, lifecycle.getExitStatus());
    }

    @Test
    void perArmyGameResult() throws Exception {
        gpgnet.start();
        gpgnet.awaitClient();
        // Drop frame
        gpgnet.pollReceived(1, TimeUnit.SECONDS);

        gpgnet.sendFrame(new GpgNetFrame("CreateLobby", List.of(0, 5000, "Rhiza", 1, 1)));
        // Drop frame
        gpgnet.pollReceived(1, TimeUnit.SECONDS);

        gpgnet.sendFrame(new GpgNetFrame("HostGame", List.of("scm_007")));
        // Drop PlayerOption frames
        gpgnet.pollReceived(1, TimeUnit.SECONDS);
        gpgnet.pollReceived(1, TimeUnit.SECONDS);
        gpgnet.pollReceived(1, TimeUnit.SECONDS);
        gpgnet.pollReceived(1, TimeUnit.SECONDS);
        gpgnet.pollReceived(1, TimeUnit.SECONDS);
        lifecycle.stateReached(GameState.HOSTING).get(1, TimeUnit.SECONDS);

        gpgnet.sendFrame(new GpgNetFrame("ConnectToPeer", List.of("127.0.0.4:4000", "Smith", 2)));
        // Drop PlayerOption frames for new player
        gpgnet.pollReceived(1, TimeUnit.SECONDS);
        gpgnet.pollReceived(1, TimeUnit.SECONDS);
        gpgnet.pollReceived(1, TimeUnit.SECONDS);
        gpgnet.pollReceived(1, TimeUnit.SECONDS);
        gpgnet.pollReceived(1, TimeUnit.SECONDS);
        lifecycle.launchMatch();
        // Drop frame
        gpgnet.pollReceived(1, TimeUnit.SECONDS);
        lifecycle.stateReached(GameState.LIVE).get(1, TimeUnit.SECONDS);

        lifecycle.endMatch();

        // Two GameResults
        assertMessage("GameResult", 1, "victory 10");
        assertMessage("GameResult", 2, "defeat -10");
        assertMessageCommand("JsonStats");
        assertMessage("GameEnded");
        assertMessage("GameState", "Ended");

        lifecycle.stateReached(GameState.ENDED).get(1, TimeUnit.SECONDS);
    }

    private void assertMessage(String expectedCommand, Object... expectedArgs) {
        GpgNetFrame received = null;
        try {
            received = gpgnet.pollReceived(1, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            fail("Did not receive frame", e);
        }
        assertEquals(expectedCommand, received.command());
        assertEquals(
                expectedArgs.length,
                received.args().size(),
                "Received argument count doesn't match expected");
        for (int i = 0; i < expectedArgs.length; i++) {
            assertEquals(expectedArgs[i], received.args().get(i));
        }
    }

    private void assertMessageCommand(String expectedCommand) {
        GpgNetFrame received = null;
        try {
            received = gpgnet.pollReceived(1, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            fail("Did not receive frame", e);
        }
        assertEquals(expectedCommand, received.command());
    }
}
