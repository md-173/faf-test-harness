package com.faforever.testharness.game.lifecycle;

import static org.junit.jupiter.api.Assertions.assertTrue;

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
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

public final class LifecyclePeerConnectTest {
    private static final MockGameConfig DEFAULT_CONFIG =
            new MockGameConfig(50000, 50001, 1, "Rhiza");

    private ScriptedGpgNetServer gpgnet;

    @BeforeEach
    void setup() throws IOException {
        gpgnet = new ScriptedGpgNetServer();
    }

    @AfterEach
    void teardown() {
        gpgnet.stop();
    }

    // Tests ConnectToPeer messages are received correctly. Currently, these produce no actual
    // side-effect, so we must capture the log.
    @Test
    void connectToPeer() throws Exception {
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

        gpgnet.sendFrame(new GpgNetFrame("CreateLobby", List.of(0, 5000, "Rhiza", 1, 1)));
        // Drop frame
        gpgnet.pollReceived(1, TimeUnit.SECONDS);

        gpgnet.sendFrame(new GpgNetFrame("HostGame", List.of("scm_007")));
        lifecycle.stateReached(GameState.HOSTING).get(1, TimeUnit.SECONDS);

        // Start sending logs to list.
        root.addAppender(appender);

        gpgnet.sendFrame(new GpgNetFrame("ConnectToPeer", List.of("127.0.0.4", "Smith", 2)));
        // Time for ConnectToPeer message to go through.
        Thread.sleep(1000);

        lifecycle.launchMatch();
        lifecycle.stateReached(GameState.LIVE).get(1, TimeUnit.SECONDS);

        lifecycle.endMatch();
        lifecycle.stateReached(GameState.ENDED).get(1, TimeUnit.SECONDS);

        // Stop sending logs to list.
        appender.stop();
        root.detachAppender(appender);

        Predicate<ILoggingEvent> pred =
                e ->
                        e.getMessage().contains("New peer with address")
                                && e.getArgumentArray()[0].equals("127.0.0.4");
        assertTrue(appender.list.stream().anyMatch(pred));
    }
}
