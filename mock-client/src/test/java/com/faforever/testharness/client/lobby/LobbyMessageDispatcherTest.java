package com.faforever.testharness.client.lobby;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.faforever.testharness.client.lobby.message.AuthenticationFailedMessage;
import com.faforever.testharness.client.lobby.message.GameLaunchMessage;
import com.faforever.testharness.client.lobby.message.InboundMessage;
import com.faforever.testharness.client.lobby.message.SessionMessage;
import com.faforever.testharness.client.lobby.message.WelcomeMessage;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

/**
 * Unit tests for {@link LobbyMessageDispatcher}. Exercises each typed inbound record using a JSON
 * fixture from {@code src/test/resources/lobby/inbound/}, plus malformed-payload and multi-consumer
 * cases, against an in-process {@link ScriptedWebSocketServer}.
 */
final class LobbyMessageDispatcherTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final long AWAIT_SECS = 3;

    private ScriptedWebSocketServer server;
    private LobbyConnection lobby;
    private LobbyMessageDispatcher dispatcher;
    private ListAppender<ILoggingEvent> logAppender;

    @BeforeEach
    void setUp() throws Exception {
        server = new ScriptedWebSocketServer();
        server.startAndAwait();
        lobby = new LobbyConnection(server.uri());
        dispatcher = new LobbyMessageDispatcher(lobby, MAPPER);

        Logger dispatcherLogger = (Logger) LoggerFactory.getLogger(LobbyMessageDispatcher.class);
        logAppender = new ListAppender<>();
        logAppender.start();
        dispatcherLogger.addAppender(logAppender);
        dispatcherLogger.setLevel(Level.DEBUG);
    }

    @AfterEach
    void tearDown() throws Exception {
        if (lobby != null) {
            try {
                lobby.close().get(2, TimeUnit.SECONDS);
            } catch (Exception ignored) {
                // some tests close the underlying socket already
            }
        }
        Logger dispatcherLogger = (Logger) LoggerFactory.getLogger(LobbyMessageDispatcher.class);
        dispatcherLogger.detachAppender(logAppender);
        server.stop(1000);
    }

    @Test
    void decodesSessionFixture() throws Exception {
        SessionMessage decoded = exchange(SessionMessage.class, "lobby/inbound/session.json");
        assertEquals(812469452L, decoded.session());
    }

    @Test
    void decodesWelcomeFixture() throws Exception {
        WelcomeMessage decoded = exchange(WelcomeMessage.class, "lobby/inbound/welcome.json");
        assertEquals(3, decoded.id());
        assertEquals("Rhiza", decoded.login());
        assertEquals("1970-01-01T00:00:00+00:00", decoded.currentTime());
        assertNotNull(decoded.me());
        assertEquals("123", decoded.me().clan());
        assertTrue(decoded.me().ratings().containsKey("global"));
        assertTrue(decoded.me().ratings().containsKey("ladder_1v1"));
    }

    @Test
    void decodesAuthenticationFailedFixture() throws Exception {
        AuthenticationFailedMessage decoded =
                exchange(
                        AuthenticationFailedMessage.class,
                        "lobby/inbound/authentication_failed.json");
        assertTrue(decoded.text().startsWith("Login not found"));
    }

    @Test
    void decodesCustomGameLaunchFixture() throws Exception {
        GameLaunchMessage decoded =
                exchange(GameLaunchMessage.class, "lobby/inbound/game_launch_custom.json");
        assertEquals(42, decoded.uid());
        assertEquals("custom", decoded.gameType());
        assertEquals("faf", decoded.mod());
        assertEquals(0, decoded.initMode());
        assertEquals(2, decoded.args().size());
        assertEquals("/numgames", decoded.args().get(0).asText());
        assertEquals(5, decoded.args().get(1).asInt());
        // Matchmaker-only fields should be null for a custom-game payload.
        assertEquals(null, decoded.team());
        assertEquals(null, decoded.mapname());
    }

    @Test
    void decodesMatchmakerGameLaunchFixture() throws Exception {
        GameLaunchMessage decoded =
                exchange(GameLaunchMessage.class, "lobby/inbound/game_launch_matchmaker.json");
        assertEquals(41956, decoded.uid());
        assertEquals("matchmaker", decoded.gameType());
        assertEquals("ladder1v1", decoded.mod());
        assertEquals(1, decoded.initMode());
        assertEquals("scmp_015", decoded.mapname());
        assertEquals(2, decoded.team());
        assertEquals(1, decoded.faction());
        assertEquals(1, decoded.mapPosition());
        assertEquals(2, decoded.expectedPlayers());
        assertEquals(1, decoded.mapPoolMapVersionId());
    }

    @Test
    void multipleConsumersForSameCommandAllFire() throws Exception {
        lobby.connect().get(5, TimeUnit.SECONDS);
        server.awaitFirstClient();

        List<SessionMessage> consumer1Seen = new CopyOnWriteArrayList<>();
        List<SessionMessage> consumer2Seen = new CopyOnWriteArrayList<>();
        CountDownLatch latch = new CountDownLatch(2);
        dispatcher.register(
                SessionMessage.class,
                msg -> {
                    consumer1Seen.add(msg);
                    latch.countDown();
                });
        dispatcher.register(
                SessionMessage.class,
                msg -> {
                    consumer2Seen.add(msg);
                    latch.countDown();
                });

        server.broadcastText(loadFixture("lobby/inbound/session.json"));

        assertTrue(latch.await(AWAIT_SECS, TimeUnit.SECONDS), "consumers never fired");
        assertEquals(1, consumer1Seen.size());
        assertEquals(1, consumer2Seen.size());
        assertEquals(812469452L, consumer1Seen.get(0).session());
        assertEquals(812469452L, consumer2Seen.get(0).session());
    }

    @Test
    void malformedPayloadIsLoggedAndDropped() throws Exception {
        lobby.connect().get(5, TimeUnit.SECONDS);
        server.awaitFirstClient();

        AtomicReference<SessionMessage> seen = new AtomicReference<>();
        dispatcher.register(SessionMessage.class, seen::set);

        // 'session' is required to be a number; sending a string should make Jackson fail decode.
        server.broadcastText("{\"command\":\"session\",\"session\":\"not-a-number\"}");

        // Give the dispatcher time to process and log.
        Thread.sleep(300);

        assertEquals(null, seen.get(), "consumer must not be invoked on a malformed payload");
        assertTrue(
                logAppender.list.stream()
                        .anyMatch(
                                e ->
                                        e.getLevel() == Level.WARN
                                                && e.getFormattedMessage().contains("session")
                                                && e.getFormattedMessage().contains("malformed")),
                "expected a WARN log mentioning the malformed 'session' frame");
    }

    @Test
    void malformedPayloadDoesNotKillSubsequentFrames() throws Exception {
        lobby.connect().get(5, TimeUnit.SECONDS);
        server.awaitFirstClient();

        AtomicReference<SessionMessage> good = new AtomicReference<>();
        CountDownLatch dispatched = new CountDownLatch(1);
        dispatcher.register(
                SessionMessage.class,
                msg -> {
                    good.set(msg);
                    dispatched.countDown();
                });

        server.broadcastText("{\"command\":\"session\",\"session\":\"bad\"}");
        server.broadcastText("{\"command\":\"session\",\"session\":123}");

        assertTrue(
                dispatched.await(AWAIT_SECS, TimeUnit.SECONDS),
                "good frame after malformed should still dispatch");
        assertEquals(123L, good.get().session());
    }

    @Test
    void commandOfThrowsForClassWithoutAnnotation() {
        // commandOf is the defensive guard new records must satisfy: forgetting @LobbyCommand on
        // a freshly added record should fail loudly the first time the dispatcher (or sender)
        // touches the class, not silently produce garbage frames.
        assertThrows(
                IllegalStateException.class,
                () -> LobbyMessageDispatcher.commandOf(NoAnnotationStub.class));
    }

    @Test
    void consumerExceptionDoesNotBreakOtherConsumers() throws Exception {
        lobby.connect().get(5, TimeUnit.SECONDS);
        server.awaitFirstClient();

        CountDownLatch goodFired = new CountDownLatch(1);
        dispatcher.register(
                SessionMessage.class,
                msg -> {
                    throw new RuntimeException("boom");
                });
        dispatcher.register(SessionMessage.class, msg -> goodFired.countDown());

        server.broadcastText(loadFixture("lobby/inbound/session.json"));

        assertTrue(
                goodFired.await(AWAIT_SECS, TimeUnit.SECONDS),
                "second consumer must still run after first throws");
    }

    /**
     * Connect, register a single consumer for {@code type}, broadcast {@code fixturePath} to the
     * client, wait for the consumer to fire, and return the decoded record.
     */
    private <T extends InboundMessage> T exchange(final Class<T> type, final String fixturePath)
            throws Exception {
        lobby.connect().get(5, TimeUnit.SECONDS);
        server.awaitFirstClient();

        AtomicReference<T> captured = new AtomicReference<>();
        CountDownLatch latch = new CountDownLatch(1);
        dispatcher.register(
                type,
                msg -> {
                    captured.set(msg);
                    latch.countDown();
                });

        server.broadcastText(loadFixture(fixturePath));
        assertTrue(
                latch.await(AWAIT_SECS, TimeUnit.SECONDS),
                "consumer for " + type.getSimpleName() + " never fired");
        return captured.get();
    }

    private static String loadFixture(final String classpathPath) throws Exception {
        Path p =
                Path.of(
                        LobbyMessageDispatcherTest.class
                                .getClassLoader()
                                .getResource(classpathPath)
                                .toURI());
        return Files.readString(p);
    }

    /**
     * Plain class deliberately lacking the {@code @LobbyCommand} annotation, used to assert the
     * defensive-guard behaviour of {@link LobbyMessageDispatcher#commandOf}.
     */
    private static final class NoAnnotationStub {
        private NoAnnotationStub() {}
    }
}
