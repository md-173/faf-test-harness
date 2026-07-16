package com.faforever.testharness.game.gpgnet;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.EOFException;
import java.io.IOException;
import java.util.Arrays;
import org.junit.jupiter.api.Test;

/**
 * Byte-exact encode/decode tests for {@link GpgNetCodec} against the worked byte maps in
 * gpgnet-format-spec §6.
 */
final class GpgNetCodecTest {

    /** {@code GameState("Lobby")} — the 27-byte map from §6. */
    private static final byte[] GAME_STATE_LOBBY =
            hex(
                    "09 00 00 00"
                            + "47 61 6d 65 53 74 61 74 65" // "GameState"
                            + "01 00 00 00" // chunk count = 1
                            + "01" // tag = string
                            + "05 00 00 00" // length = 5
                            + "4c 6f 62 62 79"); // "Lobby"

    /** {@code CreateLobby(0, 6112, "TestPlayer", 1234, 1)} — the 54-byte map from §6. */
    private static final byte[] CREATE_LOBBY =
            hex(
                    "0B 00 00 00"
                            + "43 72 65 61 74 65 4c 6f 62 62 79" // "CreateLobby"
                            + "05 00 00 00" // chunk count = 5
                            + "00 00 00 00 00" // int 0
                            + "00 E0 17 00 00" // int 6112
                            + "01 0A 00 00 00 54 65 73 74 50 6c 61 79 65 72" // string "TestPlayer"
                            + "00 D2 04 00 00" // int 1234
                            + "00 01 00 00 00"); // int 1

    @Test
    void encodesGameStateLobbyByteExact() {
        assertArrayEquals(
                GAME_STATE_LOBBY, GpgNetCodec.encode(GpgNetFrame.of("GameState", "Lobby")));
        // The §6 byte map spans offsets 0x00–0x1A = 27 bytes (4 cmd-len + 9 "GameState" + 4
        // chunk-count + 1 tag + 4 str-len + 5 "Lobby").
        assertEquals(27, GAME_STATE_LOBBY.length);
    }

    @Test
    void decodesGameStateLobbyRoundTrip() throws IOException {
        assertEquals(GpgNetFrame.of("GameState", "Lobby"), GpgNetCodec.decode(GAME_STATE_LOBBY));
    }

    @Test
    void encodesCreateLobbyByteExact() {
        byte[] encoded =
                GpgNetCodec.encode(GpgNetFrame.of("CreateLobby", 0, 6112, "TestPlayer", 1234, 1));
        assertArrayEquals(CREATE_LOBBY, encoded);
        // The §6 byte map spans offsets 0x00–0x35 = 54 bytes (19-byte header + four 5-byte int
        // chunks + one 15-byte "TestPlayer" string chunk).
        assertEquals(54, CREATE_LOBBY.length);
    }

    @Test
    void decodesCreateLobbyRoundTrip() throws IOException {
        assertEquals(
                GpgNetFrame.of("CreateLobby", 0, 6112, "TestPlayer", 1234, 1),
                GpgNetCodec.decode(CREATE_LOBBY));
    }

    @Test
    void roundTripsMixedArgsFrame() throws IOException {
        GpgNetFrame original = GpgNetFrame.of("PlayerOption", 42, "Faction", "1");
        assertEquals(original, GpgNetCodec.decode(GpgNetCodec.encode(original)));
    }

    @Test
    void roundTripsNoArgFrame() throws IOException {
        GpgNetFrame original = GpgNetFrame.of("GameEnded");
        byte[] encoded = GpgNetCodec.encode(original);
        // command "GameEnded" (4 + 9) + chunk count 0 (4) = 17 bytes, no chunks.
        assertEquals(17, encoded.length);
        assertEquals(original, GpgNetCodec.decode(encoded));
    }

    @Test
    void writerEmitsTag00ForIntAnd01ForString() {
        // Frame "T" with one int then one string; inspect the tag bytes at known offsets.
        byte[] encoded = GpgNetCodec.encode(GpgNetFrame.of("T", 7, "x"));
        // [0..3] cmd len, [4] "T", [5..8] chunk count, [9] int tag, [10..13] int, [14] string tag.
        assertEquals(0x00, encoded[9] & 0xFF, "int chunk tag is 0x00");
        assertEquals(0x01, encoded[14] & 0xFF, "string chunk tag is 0x01");
    }

    @Test
    void readerTreatsUnknownTagAsString() throws IOException {
        // command "X", one chunk with tag 0x02 (never written, but read as a length-prefixed
        // string per §4.2).
        byte[] bytes = hex("01 00 00 00 58 01 00 00 00 02 03 00 00 00 61 62 63");
        GpgNetFrame frame = GpgNetCodec.decode(bytes);
        assertEquals("X", frame.command());
        assertEquals("abc", frame.stringArg(0));
    }

    @Test
    void stringLengthPrefixIsUtf8ByteLengthNotCharCount() throws IOException {
        // U+00E9 (e-acute) is one UTF-16 char but two UTF-8 bytes. The prefix must be 2 (mirroring
        // the Go writer), not 1 (Java upstream's string.length() defect, §4.4). This test exercises
        // that upstream-defect-avoidance property.
        GpgNetFrame frame = GpgNetFrame.of("S", "é");
        byte[] encoded = GpgNetCodec.encode(frame);
        // command "S" (4 + 1) + chunk count (4) + tag (1) + length prefix (4) + body (2) = 16.
        assertEquals(16, encoded.length);
        assertEquals(2, encoded[10] & 0xFF, "length prefix is the UTF-8 byte count");
        assertEquals(frame, GpgNetCodec.decode(encoded));
    }

    @Test
    void rejectsChunkCountOverMax() {
        // command "X", chunk count = 11 (> MAX_CHUNK_SIZE of 10).
        byte[] bytes = hex("01 00 00 00 58 0B 00 00 00");
        assertThrows(IOException.class, () -> GpgNetCodec.decode(bytes));
    }

    @Test
    void rejectsNegativeChunkCount() {
        // command "X", chunk count = -1 (0xFFFFFFFF) — signed int32 on the wire (§4.4).
        byte[] bytes = hex("01 00 00 00 58 FF FF FF FF");
        assertThrows(IOException.class, () -> GpgNetCodec.decode(bytes));
    }

    @Test
    void truncatedFrameThrowsEof() {
        // The 27-byte GameState("Lobby") map cut short mid-string desyncs unrecoverably (§5.3).
        byte[] truncated = Arrays.copyOf(GAME_STATE_LOBBY, 20);
        assertThrows(EOFException.class, () -> GpgNetCodec.decode(truncated));
    }

    @Test
    void encodeRejectsFrameWithTooManyArgs() {
        // 11 args exceeds the codec's chunk cap; the mock game must never emit such a frame (§4.3).
        Object[] args = new Object[11];
        Arrays.fill(args, 0);
        GpgNetFrame overLong = GpgNetFrame.of("Big", args);
        assertThrows(IllegalArgumentException.class, () -> GpgNetCodec.encode(overLong));
    }

    /** Parse a hex string (spaces ignored) into bytes. */
    private static byte[] hex(final String h) {
        String s = h.replaceAll("\\s", "");
        byte[] out = new byte[s.length() / 2];
        for (int i = 0; i < out.length; i++) {
            out[i] = (byte) Integer.parseInt(s.substring(i * 2, i * 2 + 2), 16);
        }
        return out;
    }
}
