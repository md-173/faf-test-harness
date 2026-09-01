package com.faforever.testharness.game.lifecycle;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.faforever.testharness.game.config.MockGameConfig;
import com.faforever.testharness.game.gpgnet.GpgNetConnection;
import com.faforever.testharness.game.gpgnet.GpgNetFrame;
import com.faforever.testharness.game.gpgnet.ScriptedGpgNetServer;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

public final class LifecyclePeerConnectTest {
    private static final MockGameConfig DEFAULT_CONFIG =
            new MockGameConfig(50000, 50001, 1, "Rhiza", 9001, Map.of(), 0, 0);

    private ScriptedGpgNetServer gpgnet;

    @BeforeEach
    void setup() throws IOException {
        gpgnet = new ScriptedGpgNetServer();
    }

    @AfterEach
    void teardown() {
        gpgnet.stop();
    }

    // Tests ConnectToPeer messages are received correctly from the host side.
    @Test
    void connectToPeer() throws Exception {
        // No delay and match duration so that those don't interfere.
        MockGameLifecycle lifecycle =
                new MockGameLifecycle(
                        DEFAULT_CONFIG, new GpgNetConnection(gpgnet.port()), null, null);

        gpgnet.start();
        gpgnet.awaitClient();
        // Drop frame
        gpgnet.pollReceived(1, TimeUnit.SECONDS);

        gpgnet.sendFrame(new GpgNetFrame("CreateLobby", List.of(0, 50001, "Rhiza", 1, 1)));
        // Drop frame
        gpgnet.pollReceived(1, TimeUnit.SECONDS);

        gpgnet.sendFrame(new GpgNetFrame("HostGame", List.of("scm_007")));
        assertMessage("PlayerOption", DEFAULT_CONFIG.playerId(), "Army", 1);
        assertMessage("PlayerOption", DEFAULT_CONFIG.playerId(), "Team", 1);
        assertMessage("PlayerOption", DEFAULT_CONFIG.playerId(), "StartSpot", 1);
        assertMessage("PlayerOption", DEFAULT_CONFIG.playerId(), "Faction", 1);
        assertMessage("PlayerOption", DEFAULT_CONFIG.playerId(), "Color", 1);
        lifecycle.stateReached(GameState.HOSTING).get(1, TimeUnit.SECONDS);

        gpgnet.sendFrame(new GpgNetFrame("ConnectToPeer", List.of("127.0.0.4:4000", "Smith", 2)));
        assertMessage("PlayerOption", 2, "Army", 2);
        assertMessage("PlayerOption", 2, "Team", 2);
        assertMessage("PlayerOption", 2, "StartSpot", 2);
        assertMessage("PlayerOption", 2, "Faction", 2);
        assertMessage("PlayerOption", 2, "Color", 2);

        lifecycle.launchMatch();
        lifecycle.stateReached(GameState.LIVE).get(1, TimeUnit.SECONDS);

        lifecycle.endMatch();
        lifecycle.stateReached(GameState.ENDED).get(1, TimeUnit.SECONDS);
    }

    // Tests ConnectToPeer messages are received correctly from the joiner side. Currently, these
    // produce no actual
    // side-effect, so we must capture the log.
    @Test
    void joinerConnectToPeer() throws Exception {
        // No delay and match duration so that those don't interfere.
        MockGameLifecycle lifecycle =
                new MockGameLifecycle(
                        DEFAULT_CONFIG, new GpgNetConnection(gpgnet.port()), null, null);
        LoggerContext ctx = (LoggerContext) LoggerFactory.getILoggerFactory();
        Logger root = ctx.getLogger(Logger.ROOT_LOGGER_NAME);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.setContext(ctx);
        appender.start();

        gpgnet.start();
        gpgnet.awaitClient();
        // Drop frame
        gpgnet.pollReceived(1, TimeUnit.SECONDS);

        gpgnet.sendFrame(new GpgNetFrame("CreateLobby", List.of(0, 50001, "Rhiza", 1, 1)));
        // Drop frame
        gpgnet.pollReceived(1, TimeUnit.SECONDS);

        gpgnet.sendFrame(new GpgNetFrame("JoinGame", List.of("127.0.0.1:4000", "Smith", 2)));
        lifecycle.stateReached(GameState.JOINING).get(1, TimeUnit.SECONDS);

        // Start sending logs to list.
        root.addAppender(appender);

        gpgnet.sendFrame(
                new GpgNetFrame("ConnectToPeer", List.of("127.0.0.4:5000", "ProGamer", 3)));

        // We should not receive any PlayerOption messages here (ScriptedGpgNetServer throws an
        // AssertionError when it times out).
        assertThrows(AssertionError.class, () -> gpgnet.pollReceived(1, TimeUnit.SECONDS));

        lifecycle.launchMatch();
        lifecycle.stateReached(GameState.LIVE).get(1, TimeUnit.SECONDS);

        lifecycle.endMatch();
        lifecycle.stateReached(GameState.ENDED).get(1, TimeUnit.SECONDS);

        // Stop sending logs to list.
        appender.stop();
        root.detachAppender(appender);

        Predicate<ILoggingEvent> pred =
                e ->
                        e.getMessage().contains("New peer")
                                && e.getArgumentArray()[2].equals("127.0.0.4:5000");
        assertTrue(appender.list.stream().anyMatch(pred));
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
}
