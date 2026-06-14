package com.faforever.testharness.client.lobby.message;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link GameLaunchMessage}. Decodes the two spec-fixture shapes (custom and
 * matchmaker) and verifies the canonical-constructor presence checks fire for every required header
 * field.
 */
final class GameLaunchMessageTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void decodesCustomFixture() throws Exception {
        GameLaunchMessage launch =
                MAPPER.readValue(
                        loadFixture("lobby/inbound/game_launch_custom.json"),
                        GameLaunchMessage.class);

        assertEquals(42, launch.uid());
        assertEquals("custom", launch.gameType());
        assertEquals("faf", launch.mod());
        assertEquals(0, launch.initMode());
        assertEquals(2, launch.args().size());
        assertEquals("/numgames", launch.args().get(0).asText());
        assertEquals(5, launch.args().get(1).asInt());
        // Matchmaker-only fields should be null for a custom-game payload.
        assertNull(launch.team());
        assertNull(launch.mapname());
    }

    @Test
    void decodesMatchmakerFixture() throws Exception {
        GameLaunchMessage launch =
                MAPPER.readValue(
                        loadFixture("lobby/inbound/game_launch_matchmaker.json"),
                        GameLaunchMessage.class);

        assertEquals(41956, launch.uid());
        assertEquals("matchmaker", launch.gameType());
        assertEquals("ladder1v1", launch.mod());
        assertEquals(1, launch.initMode());
        assertEquals("scmp_015", launch.mapname());
        assertEquals(2, launch.team());
        assertEquals(1, launch.faction());
        assertEquals(1, launch.mapPosition());
        assertEquals(2, launch.expectedPlayers());
        assertEquals(1, launch.mapPoolMapVersionId());
    }

    @Test
    void rejectsMissingUid() throws Exception {
        // The silent-`0` bug guard: a primitive int uid would default to 0, masking a missing game
        // id; boxed Integer makes the missing case observable.
        String json =
                "{\"mod\":\"faf\",\"name\":\"x\",\"game_type\":\"custom\","
                        + "\"rating_type\":\"global\"}";
        assertThrows(
                com.fasterxml.jackson.databind.exc.ValueInstantiationException.class,
                () -> MAPPER.readValue(json, GameLaunchMessage.class));
    }

    @Test
    void rejectsBlankMod() throws Exception {
        String json =
                "{\"uid\":1,\"mod\":\"\",\"name\":\"x\",\"game_type\":\"custom\","
                        + "\"rating_type\":\"global\"}";
        assertThrows(
                com.fasterxml.jackson.databind.exc.ValueInstantiationException.class,
                () -> MAPPER.readValue(json, GameLaunchMessage.class));
    }

    @Test
    void allowsMissingInitMode() throws Exception {
        // initMode is boxed but deliberately not required — the spec marks it deprecated and a
        // server may omit it. Missing should produce null, not throw.
        String json =
                "{\"uid\":1,\"mod\":\"faf\",\"name\":\"x\",\"game_type\":\"custom\","
                        + "\"rating_type\":\"global\"}";
        GameLaunchMessage launch = MAPPER.readValue(json, GameLaunchMessage.class);
        assertNull(launch.initMode());
    }

    private static String loadFixture(final String classpathPath) throws Exception {
        Path p =
                Path.of(
                        GameLaunchMessageTest.class
                                .getClassLoader()
                                .getResource(classpathPath)
                                .toURI());
        return Files.readString(p);
    }
}
