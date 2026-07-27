package com.faforever.testharness.game.net;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

/** Round-trip tests for the {@link GameDatagram} wire payload. */
final class GameDatagramTest {

    @Test
    void encodesToFixedSize() {
        assertEquals(20, GameDatagram.BYTES, "int32 + int64 + int64");
        assertEquals(GameDatagram.BYTES, new GameDatagram(1, 2, 3).toBytes().length);
    }

    @Test
    void roundTrips() {
        GameDatagram original = new GameDatagram(330072, 9876543210L, 1_700_000_000_000L);
        byte[] bytes = original.toBytes();

        assertEquals(original, GameDatagram.parse(bytes, bytes.length));
    }

    @Test
    void parseRejectsTruncatedInput() {
        assertThrows(IllegalArgumentException.class, () -> GameDatagram.parse(new byte[19], 19));
    }
}
