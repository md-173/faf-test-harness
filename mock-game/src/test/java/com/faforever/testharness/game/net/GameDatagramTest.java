package com.faforever.testharness.game.net;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.faforever.testharness.game.config.Seat;
import org.junit.jupiter.api.Test;

/** Round-trip tests for the {@link GameDatagram} wire payload. */
final class GameDatagramTest {

    @Test
    void encodesToFixedSize() {
        assertEquals(32, GameDatagram.BYTES, "int32 + int64 + int64 + an int32 per seat field");
        assertEquals(GameDatagram.BYTES, new GameDatagram(1, 2, 3, Seat.DEFAULT).toBytes().length);
    }

    @Test
    void roundTrips() {
        // Seat fields that all differ, so one written into another's place cannot pass.
        GameDatagram original =
                new GameDatagram(330072, 9876543210L, 1_700_000_000_000L, new Seat(3, 5, 4));
        byte[] bytes = original.toBytes();

        assertEquals(original, GameDatagram.parse(bytes, bytes.length));
    }

    @Test
    void parseRejectsTruncatedInput() {
        assertThrows(IllegalArgumentException.class, () -> GameDatagram.parse(new byte[31], 31));
    }

    @Test
    void parseRejectsOversizedInput() {
        assertThrows(IllegalArgumentException.class, () -> GameDatagram.parse(new byte[64], 64));
    }
}
