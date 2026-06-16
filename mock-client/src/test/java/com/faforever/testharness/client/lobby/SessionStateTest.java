package com.faforever.testharness.client.lobby;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.faforever.testharness.client.lobby.message.WelcomeMessage;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link SessionState} — the app-facing identity distilled from a decoded {@code
 * welcome}. Exercises the {@code welcome.json} spec fixture (full payload), omitted optional fields
 * ({@code clan}/{@code country}/{@code ratings}), and forward-compatible unknown fields (must not
 * throw), per the 3.1.1.3 deliverables.
 */
final class SessionStateTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void distilsFullSpecFixture() throws Exception {
        WelcomeMessage welcome =
                MAPPER.readValue(loadFixture("lobby/inbound/welcome.json"), WelcomeMessage.class);

        SessionState state = SessionState.from(welcome);

        assertEquals(3, state.id());
        assertEquals("test_user", state.login());
        assertEquals("123", state.clan());
        assertEquals("", state.country());
        assertEquals("1970-01-01T00:00:00+00:00", state.currentTime());
        assertTrue(state.ratings().containsKey("global"));
        assertTrue(state.ratings().containsKey("ladder_1v1"));
    }

    @Test
    void toleratesMissingOptionalFields() throws Exception {
        // clan, country and ratings are all absent — required id/login/current_time still present.
        String json = "{\"me\":{\"id\":7,\"login\":\"solo\"},\"current_time\":\"t\"}";
        WelcomeMessage welcome = MAPPER.readValue(json, WelcomeMessage.class);

        SessionState state = SessionState.from(welcome);

        assertEquals(7, state.id());
        assertEquals("solo", state.login());
        assertNull(state.clan());
        assertNull(state.country());
        assertTrue(state.ratings().isEmpty());
    }

    @Test
    void toleratesUnknownExtraFields() throws Exception {
        String json =
                "{\"me\":{\"id\":3,\"login\":\"r\",\"ratings\":{},\"future_me_field\":1},"
                        + "\"current_time\":\"t\",\"id\":3,\"login\":\"r\","
                        + "\"future_field\":\"forward-compat\"}";
        WelcomeMessage welcome = MAPPER.readValue(json, WelcomeMessage.class);

        SessionState state = SessionState.from(welcome);

        assertEquals(3, state.id());
        assertEquals("r", state.login());
    }

    @Test
    void ratingsMapIsImmutable() throws Exception {
        String json = "{\"me\":{\"id\":3,\"login\":\"r\",\"ratings\":{}},\"current_time\":\"t\"}";
        WelcomeMessage welcome = MAPPER.readValue(json, WelcomeMessage.class);

        Map<String, com.fasterxml.jackson.databind.JsonNode> ratings =
                SessionState.from(welcome).ratings();

        assertThrows(
                UnsupportedOperationException.class,
                () -> ratings.put("x", MAPPER.createObjectNode()));
    }

    private static String loadFixture(final String classpathPath) throws Exception {
        Path p =
                Path.of(SessionStateTest.class.getClassLoader().getResource(classpathPath).toURI());
        return Files.readString(p);
    }
}
