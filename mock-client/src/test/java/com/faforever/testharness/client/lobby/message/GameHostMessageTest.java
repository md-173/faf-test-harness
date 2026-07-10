package com.faforever.testharness.client.lobby.message;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

final class GameHostMessageTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void commandIsAlwaysGameHostAndNullPasswordIsOmitted() throws JsonProcessingException {
        GameHostMessage message =
                new GameHostMessage("Test game", "public", "faf", "scmp_007", null);

        JsonNode json = MAPPER.valueToTree(message);

        assertEquals("game_host", message.command());
        assertEquals("game_host", json.get("command").asText());
        assertFalse(json.has("password"), "null password should be omitted, not sent as null");
    }

    @Test
    void rejectsBlankTitle() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new GameHostMessage(" ", "public", "faf", "scmp_007", null));
    }

    @Test
    void rejectsBlankVisibility() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new GameHostMessage("Test game", null, "faf", "scmp_007", null));
    }

    @Test
    void rejectsBlankMod() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new GameHostMessage("Test game", "public", null, "scmp_007", null));
    }

    @Test
    void rejectsBlankMapname() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new GameHostMessage("Test game", "public", "faf", "", null));
    }
}
