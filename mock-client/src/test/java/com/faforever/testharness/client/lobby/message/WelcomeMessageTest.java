package com.faforever.testharness.client.lobby.message;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link WelcomeMessage}. Decodes the spec-fixture JSON and verifies the
 * canonical-constructor presence checks fire for every required field.
 */
final class WelcomeMessageTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void decodesSpecFixture() throws Exception {
        WelcomeMessage welcome =
                MAPPER.readValue(loadFixture("lobby/inbound/welcome.json"), WelcomeMessage.class);

        assertEquals(3, welcome.id());
        assertEquals("Rhiza", welcome.login());
        assertEquals("1970-01-01T00:00:00+00:00", welcome.currentTime());
        assertNotNull(welcome.me());
        assertEquals("123", welcome.me().clan());
        assertTrue(welcome.me().ratings().containsKey("global"));
        assertTrue(welcome.me().ratings().containsKey("ladder_1v1"));
    }

    @Test
    void rejectsMissingId() throws Exception {
        // Missing required primitive — boxed Integer decodes to null, constructor throws.
        String json =
                "{\"me\":{\"id\":3,\"login\":\"r\",\"ratings\":{}},"
                        + "\"current_time\":\"t\",\"login\":\"r\"}";
        assertThrows(
                com.fasterxml.jackson.databind.exc.ValueInstantiationException.class,
                () -> MAPPER.readValue(json, WelcomeMessage.class));
    }

    @Test
    void rejectsMissingMe() throws Exception {
        String json = "{\"current_time\":\"t\",\"id\":3,\"login\":\"r\"}";
        assertThrows(
                com.fasterxml.jackson.databind.exc.ValueInstantiationException.class,
                () -> MAPPER.readValue(json, WelcomeMessage.class));
    }

    @Test
    void rejectsBlankLogin() throws Exception {
        String json =
                "{\"me\":{\"id\":3,\"login\":\"r\",\"ratings\":{}},"
                        + "\"current_time\":\"t\",\"id\":3,\"login\":\"\"}";
        assertThrows(
                com.fasterxml.jackson.databind.exc.ValueInstantiationException.class,
                () -> MAPPER.readValue(json, WelcomeMessage.class));
    }

    @Test
    void ignoresUnknownFields() throws Exception {
        String json =
                "{\"me\":{\"id\":3,\"login\":\"r\",\"ratings\":{}},"
                        + "\"current_time\":\"t\",\"id\":3,\"login\":\"r\","
                        + "\"future_field\":\"forward-compat\"}";
        WelcomeMessage welcome = MAPPER.readValue(json, WelcomeMessage.class);
        assertEquals("r", welcome.login());
    }

    private static String loadFixture(final String classpathPath) throws Exception {
        Path p =
                Path.of(
                        WelcomeMessageTest.class
                                .getClassLoader()
                                .getResource(classpathPath)
                                .toURI());
        return Files.readString(p);
    }
}
