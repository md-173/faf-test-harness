package com.faforever.testharness.client.lobby;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

final class GameLaunchWiringTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private ScriptedWebSocketServer server;
    private LobbyConnection lobby;

    @BeforeEach
    void setUp() throws Exception {
        server = new ScriptedWebSocketServer();
        server.startAndAwait();
    }

    @AfterEach
    void tearDown() throws Exception {
        if (lobby != null) {
            try {
                lobby.close().get(2, TimeUnit.SECONDS);
            } catch (Exception ignored) {
            }
        }
        server.stop(1000);
    }

    @Test
    void handlerRegisteredOnConnectionReceivesGameConfig() throws Exception {
        AtomicReference<GameConfig> sink = new AtomicReference<>();
        CountDownLatch fired = new CountDownLatch(1);

        lobby = new LobbyConnection(server.uri());
        GameLaunchHandler handler =
                new GameLaunchHandler(
                        MAPPER,
                        cfg -> {
                            sink.set(cfg);
                            fired.countDown();
                        });
        lobby.registerHandler("game_launch", handler);

        lobby.connect().get(5, TimeUnit.SECONDS);
        server.awaitFirstClient();

        server.broadcastText(
                "{"
                        + "\"command\":\"game_launch\","
                        + "\"uid\":11,"
                        + "\"mod\":\"faf\","
                        + "\"name\":\"Wired\","
                        + "\"game_type\":\"custom\","
                        + "\"rating_type\":\"global\","
                        + "\"args\":[\"/numgames\",1]"
                        + "}\n");

        assertTrue(fired.await(3, TimeUnit.SECONDS), "handler sink never fired");
        GameConfig cfg = sink.get();
        assertEquals(11, cfg.uid());
        assertEquals("faf", cfg.mod());
        assertEquals("Wired", cfg.name());
        assertEquals("custom", cfg.gameType());
        assertEquals("global", cfg.ratingType());
        assertEquals(2, cfg.args().size());
        assertEquals("/numgames", cfg.args().get(0));
        assertEquals("1", cfg.args().get(1));
    }
}
