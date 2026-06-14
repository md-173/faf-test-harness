package com.faforever.testharness.client.lobby;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

/** Unit tests for {@link JsonRequire}. */
final class JsonRequireTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void stringFieldReturnsValue() throws Exception {
        assertEquals("hi", JsonRequire.stringField(MAPPER.readTree("{\"x\":\"hi\"}"), "x"));
    }

    @Test
    void stringFieldRejectsMissing() throws Exception {
        assertThrows(
                IllegalArgumentException.class,
                () -> JsonRequire.stringField(MAPPER.readTree("{}"), "x"));
    }

    @Test
    void stringFieldRejectsNull() throws Exception {
        assertThrows(
                IllegalArgumentException.class,
                () -> JsonRequire.stringField(MAPPER.readTree("{\"x\":null}"), "x"));
    }

    @Test
    void stringFieldRejectsWrongType() throws Exception {
        assertThrows(
                IllegalArgumentException.class,
                () -> JsonRequire.stringField(MAPPER.readTree("{\"x\":42}"), "x"));
    }

    @Test
    void longFieldReturnsValue() throws Exception {
        assertEquals(42L, JsonRequire.longField(MAPPER.readTree("{\"x\":42}"), "x"));
    }

    @Test
    void longFieldRejectsMissing() throws Exception {
        // The silent-`0` bug guard: a missing required integer must throw, not return 0.
        assertThrows(
                IllegalArgumentException.class,
                () -> JsonRequire.longField(MAPPER.readTree("{}"), "x"));
    }

    @Test
    void longFieldRejectsString() throws Exception {
        assertThrows(
                IllegalArgumentException.class,
                () -> JsonRequire.longField(MAPPER.readTree("{\"x\":\"42\"}"), "x"));
    }

    @Test
    void intFieldReturnsValue() throws Exception {
        assertEquals(42, JsonRequire.intField(MAPPER.readTree("{\"x\":42}"), "x"));
    }

    @Test
    void intFieldRejectsMissing() throws Exception {
        assertThrows(
                IllegalArgumentException.class,
                () -> JsonRequire.intField(MAPPER.readTree("{}"), "x"));
    }
}
