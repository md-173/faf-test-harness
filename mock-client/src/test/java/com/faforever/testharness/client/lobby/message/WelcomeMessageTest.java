package com.faforever.testharness.client.lobby.message;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link WelcomeMessage}. Decodes the spec-fixture JSON and verifies the
 * canonical-constructor presence checks fire for the required fields — {@code me} (the canonical
 * identity source, including {@code me.id} / {@code me.login}) and {@code current_time}.
 */
final class WelcomeMessageTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void decodesSpecFixture() throws Exception {
        WelcomeMessage welcome =
                MAPPER.readValue(loadFixture("lobby/inbound/welcome.json"), WelcomeMessage.class);

        assertNotNull(welcome.me());
        assertEquals(3, welcome.me().id().intValue());
        assertEquals("test_user", welcome.me().login());
        assertEquals("123", welcome.me().clan());
        assertEquals("1970-01-01T00:00:00+00:00", welcome.currentTime());
        assertTrue(welcome.me().ratings().containsKey("global"));
        assertTrue(welcome.me().ratings().containsKey("ladder_1v1"));
    }

    @Test
    void rejectsMissingMe() throws Exception {
        String json = "{\"current_time\":\"t\",\"id\":3,\"login\":\"r\"}";
        assertThrows(
                com.fasterxml.jackson.databind.exc.ValueInstantiationException.class,
                () -> MAPPER.readValue(json, WelcomeMessage.class));
    }

    @Test
    void rejectsMissingMeId() throws Exception {
        // me is the canonical source — omitting me.id must throw (boxed Integer decodes to null).
        String json =
                "{\"me\":{\"login\":\"r\",\"ratings\":{}},"
                        + "\"current_time\":\"t\",\"id\":3,\"login\":\"r\"}";
        assertThrows(
                com.fasterxml.jackson.databind.exc.ValueInstantiationException.class,
                () -> MAPPER.readValue(json, WelcomeMessage.class));
    }

    @Test
    void rejectsBlankMeLogin() throws Exception {
        String json =
                "{\"me\":{\"id\":3,\"login\":\"\",\"ratings\":{}},"
                        + "\"current_time\":\"t\",\"id\":3,\"login\":\"r\"}";
        assertThrows(
                com.fasterxml.jackson.databind.exc.ValueInstantiationException.class,
                () -> MAPPER.readValue(json, WelcomeMessage.class));
    }

    @Test
    void allowsMissingTopLevelIdAndLogin() throws Exception {
        // Top-level id/login are optional legacy duplicates; me is the source of truth.
        String json =
                "{\"me\":{\"id\":3,\"login\":\"test_user\",\"ratings\":{}},\"current_time\":\"t\"}";
        WelcomeMessage welcome = MAPPER.readValue(json, WelcomeMessage.class);
        assertEquals(3, welcome.me().id().intValue());
        assertEquals("test_user", welcome.me().login());
        assertNull(welcome.id());
        assertNull(welcome.login());
    }

    @Test
    void ignoresUnknownFields() throws Exception {
        String json =
                "{\"me\":{\"id\":3,\"login\":\"r\",\"ratings\":{}},"
                        + "\"current_time\":\"t\",\"id\":3,\"login\":\"r\","
                        + "\"future_field\":\"forward-compat\"}";
        WelcomeMessage welcome = MAPPER.readValue(json, WelcomeMessage.class);
        assertEquals("r", welcome.me().login());
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
