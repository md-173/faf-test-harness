package com.faforever.testharness.game.gpgnet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

/**
 * Tests for {@link GpgNetSender}: each builder emits the exact frame (command + ordered args) per
 * gpgnet-format-spec §7.1, asserted against a capturing {@link GpgNetFrameSink}, plus one
 * end-to-end path through the real {@link GpgNetConnection} to the wire.
 */
final class GpgNetSenderTest {

    private final List<GpgNetFrame> sent = new ArrayList<>();
    private final GpgNetSender sender = new GpgNetSender(sent::add);

    @Test
    void gameStateEmitsFrame() throws IOException {
        sender.gameState("Lobby");
        assertEquals(List.of(GpgNetFrame.of("GameState", "Lobby")), sent);
    }

    @Test
    void gameStateAcceptsAllFourValidStates() throws IOException {
        sender.gameState("Idle");
        sender.gameState("Lobby");
        sender.gameState("Launching");
        sender.gameState("Ended");
        assertEquals(
                List.of(
                        GpgNetFrame.of("GameState", "Idle"),
                        GpgNetFrame.of("GameState", "Lobby"),
                        GpgNetFrame.of("GameState", "Launching"),
                        GpgNetFrame.of("GameState", "Ended")),
                sent);
    }

    @Test
    void gameStateRejectsInvalidState() {
        assertThrows(IllegalArgumentException.class, () -> sender.gameState("Hosted"));
        assertEquals(List.of(), sent, "no frame should be sent for an invalid state");
    }

    @Test
    void gameOptionEmitsFrame() throws IOException {
        sender.gameOption("Victory", "demoralization");
        assertEquals(List.of(GpgNetFrame.of("GameOption", "Victory", "demoralization")), sent);
    }

    @Test
    void playerOptionEmitsFrameWithIntPlayerId() throws IOException {
        sender.playerOption(4, "Faction", "1");
        assertEquals(List.of(GpgNetFrame.of("PlayerOption", 4, "Faction", "1")), sent);
    }

    @Test
    void gameResultEmitsFrame() throws IOException {
        sender.gameResult(1, "victory 10");
        assertEquals(List.of(GpgNetFrame.of("GameResult", 1, "victory 10")), sent);
    }

    @Test
    void jsonStatsEmitsFrame() throws IOException {
        sender.jsonStats("{\"units\":42}");
        assertEquals(List.of(GpgNetFrame.of("JsonStats", "{\"units\":42}")), sent);
    }

    @Test
    void gameEndedEmitsNoArgFrame() throws IOException {
        sender.gameEnded();
        assertEquals(List.of(GpgNetFrame.of("GameEnded")), sent);
        assertEquals(0, sent.get(0).argCount(), "GameEnded takes no args");
    }

    @Test
    void gameModsActivatedShape() throws IOException {
        // "activated" + count (int).
        sender.gameMods("activated", 0);
        assertEquals(List.of(GpgNetFrame.of("GameMods", "activated", 0)), sent);
    }

    @Test
    void gameModsUidsShape() throws IOException {
        // "uids" + a space-separated uid string.
        sender.gameMods("uids", "1-2-3 4-5-6");
        assertEquals(List.of(GpgNetFrame.of("GameMods", "uids", "1-2-3 4-5-6")), sent);
    }

    @Test
    void sinkIoExceptionPropagates() {
        GpgNetSender throwingSender =
                new GpgNetSender(
                        frame -> {
                            throw new IOException("socket down");
                        });
        assertThrows(IOException.class, () -> throwingSender.gameEnded());
    }

    @Test
    void sendsThroughRealTransportToWire() throws Exception {
        ScriptedGpgNetServer server = new ScriptedGpgNetServer();
        server.start();
        GpgNetConnection conn = new GpgNetConnection(server.port(), 5, Duration.ofMillis(20));
        try {
            conn.connect().get(5, TimeUnit.SECONDS);
            server.awaitClient();
            GpgNetSender wireSender = new GpgNetSender(conn);

            wireSender.gameState("Launching");
            wireSender.gameResult(1, "victory 10");

            assertEquals(
                    GpgNetFrame.of("GameState", "Launching"),
                    server.pollReceived(2, TimeUnit.SECONDS));
            assertEquals(
                    GpgNetFrame.of("GameResult", 1, "victory 10"),
                    server.pollReceived(2, TimeUnit.SECONDS));
        } finally {
            conn.close();
            server.stop();
        }
    }
}
