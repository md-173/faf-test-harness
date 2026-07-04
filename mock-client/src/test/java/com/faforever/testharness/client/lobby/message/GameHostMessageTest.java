package com.faforever.testharness.client.lobby.message;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

final class GameHostMessageTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void defaultsCommandToGameHostAndOmitsNullFields() throws JsonProcessingException {
        GameHostMessage message = new GameHostMessage(null, null, null, null, null, null);

        String json = MAPPER.writeValueAsString(message);

        assertEquals("game_host", message.command());
        assertEquals("{\"command\":\"game_host\"}", json);
        assertNull(message.title());
    }
}
