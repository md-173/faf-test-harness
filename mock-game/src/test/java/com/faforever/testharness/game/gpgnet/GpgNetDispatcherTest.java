package com.faforever.testharness.game.gpgnet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

/**
 * Tests for {@link GpgNetDispatcher}: the lifecycle inbound set (gpgnet-format-spec §7.2) routed
 * through the real {@link GpgNetConnection} read loop and {@link ScriptedGpgNetServer}, plus
 * pure-unit routing edge cases.
 */
final class GpgNetDispatcherTest {

    private ScriptedGpgNetServer server;
    private GpgNetConnection conn;
    private GpgNetDispatcher dispatcher;

    @BeforeEach
    void setUp() throws Exception {
        server = new ScriptedGpgNetServer();
        server.start();
        dispatcher = new GpgNetDispatcher();
    }

    @AfterEach
    void tearDown() {
        if (conn != null) {
            conn.close();
        }
        if (server != null) {
            server.stop();
        }
    }

    /** Wire the dispatcher as the transport's frame consumer and connect to the fixture. */
    private void connect() throws Exception {
        conn = new GpgNetConnection(server.port(), 5, Duration.ofMillis(20));
        conn.onFrame(dispatcher);
        conn.connect().get(5, TimeUnit.SECONDS);
        server.awaitClient();
    }

    /** Register a capturing handler for {@code command} and return the captured-frame holder. */
    private AtomicReference<GpgNetFrame> capture(final String command, final CountDownLatch latch) {
        AtomicReference<GpgNetFrame> holder = new AtomicReference<>();
        dispatcher.registerHandler(
                command,
                frame -> {
                    holder.set(frame);
                    latch.countDown();
                });
        return holder;
    }

    @Test
    void routesCreateLobbyWithAllArgs() throws Exception {
        CountDownLatch got = new CountDownLatch(1);
        AtomicReference<GpgNetFrame> holder = capture("CreateLobby", got);
        connect();

        server.sendFrame(GpgNetFrame.of("CreateLobby", 0, 6112, "TestPlayer", 1234, 1));

        assertTrue(got.await(2, TimeUnit.SECONDS), "CreateLobby handler should fire");
        GpgNetFrame frame = holder.get();
        assertEquals(0, frame.intArg(0), "init_mode");
        assertEquals(6112, frame.intArg(1), "port");
        assertEquals("TestPlayer", frame.stringArg(2), "login");
        assertEquals(1234, frame.intArg(3), "player_id");
        assertEquals(1, frame.intArg(4), "nat_traversal_provider");
    }

    @Test
    void routesHostGameMapName() throws Exception {
        CountDownLatch got = new CountDownLatch(1);
        AtomicReference<GpgNetFrame> holder = capture("HostGame", got);
        connect();

        server.sendFrame(GpgNetFrame.of("HostGame", "scmp_007"));

        assertTrue(got.await(2, TimeUnit.SECONDS), "HostGame handler should fire");
        assertEquals("scmp_007", holder.get().stringArg(0), "map_name");
    }

    @Test
    void routesJoinGameWithThreeArgs() throws Exception {
        CountDownLatch got = new CountDownLatch(1);
        AtomicReference<GpgNetFrame> holder = capture("JoinGame", got);
        connect();

        // Local-wire form is 3 args, not FA Lua's 4-arg shape (§7.3).
        server.sendFrame(GpgNetFrame.of("JoinGame", "192.168.1.5:6112", "HostPlayer", 42));

        assertTrue(got.await(2, TimeUnit.SECONDS), "JoinGame handler should fire");
        GpgNetFrame frame = holder.get();
        assertEquals(3, frame.argCount(), "JoinGame is 3 args on the local wire (§7.3)");
        assertEquals("192.168.1.5:6112", frame.stringArg(0), "net_address");
        assertEquals("HostPlayer", frame.stringArg(1), "remote_player_login");
        assertEquals(42, frame.intArg(2), "remote_player_id");
    }

    @Test
    void routesConnectToPeerWithThreeArgs() throws Exception {
        CountDownLatch got = new CountDownLatch(1);
        AtomicReference<GpgNetFrame> holder = capture("ConnectToPeer", got);
        connect();

        server.sendFrame(GpgNetFrame.of("ConnectToPeer", "10.0.0.9:6112", "PeerPlayer", 7));

        assertTrue(got.await(2, TimeUnit.SECONDS), "ConnectToPeer handler should fire");
        GpgNetFrame frame = holder.get();
        assertEquals(3, frame.argCount(), "ConnectToPeer is 3 args on the local wire (§7.3)");
        assertEquals("10.0.0.9:6112", frame.stringArg(0), "net_address");
        assertEquals("PeerPlayer", frame.stringArg(1), "remote_player_login");
        assertEquals(7, frame.intArg(2), "remote_player_id");
    }

    @Test
    void routesDisconnectFromPeer() throws Exception {
        CountDownLatch got = new CountDownLatch(1);
        AtomicReference<GpgNetFrame> holder = capture("DisconnectFromPeer", got);
        connect();

        server.sendFrame(GpgNetFrame.of("DisconnectFromPeer", 42));

        assertTrue(got.await(2, TimeUnit.SECONDS), "DisconnectFromPeer handler should fire");
        assertEquals(42, holder.get().intArg(0), "remote_player_id");
    }

    @Test
    void routesEachCommandToItsOwnHandlerOnly() throws Exception {
        AtomicInteger hostGameHits = new AtomicInteger();
        CountDownLatch joinGot = new CountDownLatch(1);
        dispatcher.registerHandler("HostGame", frame -> hostGameHits.incrementAndGet());
        AtomicReference<GpgNetFrame> joinHolder = capture("JoinGame", joinGot);
        connect();

        server.sendFrame(GpgNetFrame.of("JoinGame", "1.2.3.4:6112", "H", 1));

        assertTrue(joinGot.await(2, TimeUnit.SECONDS), "JoinGame handler should fire");
        assertEquals("1.2.3.4:6112", joinHolder.get().stringArg(0));
        assertEquals(
                0, hostGameHits.get(), "the HostGame handler must not fire for a JoinGame frame");
    }

    @Test
    void inboundIceMsgIsDroppedNotRouted() throws Exception {
        // No handler for IceMsg (out of scope — the mock game doesn't process ICE). It is dropped;
        // the reader survives to deliver the next, registered frame.
        CountDownLatch hostGot = new CountDownLatch(1);
        AtomicReference<GpgNetFrame> holder = capture("HostGame", hostGot);
        connect();

        server.sendFrame(GpgNetFrame.of("IceMsg", 7, "{\"candidate\":\"x\"}"));
        server.sendFrame(GpgNetFrame.of("HostGame", "scmp_007"));

        assertTrue(hostGot.await(2, TimeUnit.SECONDS), "reader must survive an unhandled IceMsg");
        assertEquals("scmp_007", holder.get().stringArg(0));
    }

    @Test
    void unknownCommandLoggedOnceAndDropped() {
        ListAppender<ILoggingEvent> appender = attachAppender();
        try {
            // Pure-unit: two unhandled frames — logged once, no throw.
            dispatcher.accept(GpgNetFrame.of("Mystery", 1));
            dispatcher.accept(GpgNetFrame.of("Mystery", 2));

            long warnings =
                    appender.list.stream()
                            .filter(e -> e.getLevel() == Level.WARN)
                            .filter(e -> e.getFormattedMessage().contains("Mystery"))
                            .count();
            assertEquals(1, warnings, "unhandled command should be logged exactly once");
        } finally {
            detachAppender(appender);
        }
    }

    @Test
    void throwingHandlerIsCaughtAndDoesNotPropagate() {
        ListAppender<ILoggingEvent> appender = attachAppender();
        try {
            dispatcher.registerHandler(
                    "HostGame",
                    frame -> {
                        throw new RuntimeException("boom");
                    });
            AtomicInteger otherHits = new AtomicInteger();
            dispatcher.registerHandler("JoinGame", frame -> otherHits.incrementAndGet());

            // The throwing handler must not propagate out of accept()...
            dispatcher.accept(GpgNetFrame.of("HostGame", "scmp_007"));
            // ...and a later, different command still routes.
            dispatcher.accept(GpgNetFrame.of("JoinGame", "1.2.3.4:6112", "H", 1));

            assertEquals(1, otherHits.get(), "a throwing handler must not stop later routing");
            assertTrue(
                    appender.list.stream()
                            .anyMatch(
                                    e ->
                                            e.getLevel() == Level.WARN
                                                    && e.getFormattedMessage().contains("HostGame")
                                                    && e.getFormattedMessage().contains("threw")),
                    "a throwing handler should be logged at WARN; events: " + appender.list);
        } finally {
            detachAppender(appender);
        }
    }

    @Test
    void duplicateRegistrationThrows() {
        dispatcher.registerHandler("HostGame", frame -> {});
        assertThrows(
                IllegalArgumentException.class,
                () -> dispatcher.registerHandler("HostGame", frame -> {}));
    }

    private ListAppender<ILoggingEvent> attachAppender() {
        LoggerContext ctx = (LoggerContext) LoggerFactory.getILoggerFactory();
        Logger logger = ctx.getLogger(GpgNetDispatcher.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.setContext(ctx);
        appender.start();
        logger.addAppender(appender);
        return appender;
    }

    private void detachAppender(final ListAppender<ILoggingEvent> appender) {
        LoggerContext ctx = (LoggerContext) LoggerFactory.getILoggerFactory();
        ctx.getLogger(GpgNetDispatcher.class).detachAppender(appender);
        appender.stop();
    }
}
