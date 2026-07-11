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
                new GameHostMessage(
                        "Test game", "public", "faf", "scmp_007", null, null, null, false);

        JsonNode json = MAPPER.valueToTree(message);

        assertEquals("game_host", message.command());
        assertEquals("game_host", json.get("command").asText());
        assertFalse(json.has("password"), "null password should be omitted, not sent as null");
    }

    @Test
    void nullRatingBoundsAreOmittedButEnforceRatingRangeIsAlwaysSent()
            throws JsonProcessingException {
        GameHostMessage message =
                new GameHostMessage(
                        "Test game", "public", "faf", "scmp_007", null, null, null, false);

        JsonNode json = MAPPER.valueToTree(message);

        assertFalse(json.has("rating_min"), "null rating_min should be omitted, not sent as null");
        assertFalse(json.has("rating_max"), "null rating_max should be omitted, not sent as null");
        assertFalse(json.get("enforce_rating_range").asBoolean());
    }

    @Test
    void ratingBoundsAreSentWhenPresent() throws JsonProcessingException {
        GameHostMessage message =
                new GameHostMessage(
                        "Test game", "public", "faf", "scmp_007", null, 800.0, 1500.0, true);

        JsonNode json = MAPPER.valueToTree(message);

        assertEquals(800.0, json.get("rating_min").asDouble());
        assertEquals(1500.0, json.get("rating_max").asDouble());
        assertEquals(true, json.get("enforce_rating_range").asBoolean());
    }

    @Test
    void rejectsBlankTitle() {
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        new GameHostMessage(
                                " ", "public", "faf", "scmp_007", null, null, null, false));
    }

    @Test
    void rejectsBlankVisibility() {
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        new GameHostMessage(
                                "Test game", null, "faf", "scmp_007", null, null, null, false));
    }

    @Test
    void rejectsBlankMod() {
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        new GameHostMessage(
                                "Test game", "public", null, "scmp_007", null, null, null, false));
    }

    @Test
    void rejectsBlankMapname() {
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        new GameHostMessage(
                                "Test game", "public", "faf", "", null, null, null, false));
    }
}
