package com.faforever.testharness.client.lobby;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

public class GameLaunchHandlerTest {
    private final ObjectMapper mapper = new ObjectMapper();

    /**
     * What the handler did with one frame (#457): the config it passed on, or the reason it
     * rejected the frame. Exactly one of the two is set.
     *
     * @param config the config the sink received, or {@code null}
     * @param rejection the reason the rejection sink received, or {@code null}
     */
    private record Handled(GameConfig config, String rejection) {}

    private Handled handle(String json) throws Exception {
        AtomicReference<GameConfig> config = new AtomicReference<>();
        AtomicReference<String> rejection = new AtomicReference<>();
        new GameLaunchHandler(mapper, config::set, rejection::set).onMessage(mapper.readTree(json));
        return new Handled(config.get(), rejection.get());
    }

    private GameConfig accepted(String json) throws Exception {
        Handled handled = handle(json);
        Assertions.assertNull(handled.rejection(), "a valid frame must not be rejected");
        Assertions.assertNotNull(handled.config());
        return handled.config();
    }

    private String rejected(String json) throws Exception {
        Handled handled = handle(json);
        Assertions.assertNull(handled.config(), "a rejected frame must not be launched");
        Assertions.assertNotNull(handled.rejection());
        return handled.rejection();
    }

    @Test
    public void validCustomProducesConfig() throws Exception {
        String json =
                "{"
                        + "\"uid\": 42,"
                        + "\"mod\": \"faf\","
                        + "\"name\": \"Test Game\","
                        + "\"game_type\": \"custom\","
                        + "\"rating_type\": \"global\","
                        + "\"args\": [\"/numgames\", 5]"
                        + "}";

        GameConfig cfg = accepted(json);

        Assertions.assertEquals(42, cfg.uid());
        Assertions.assertEquals("faf", cfg.mod());
        Assertions.assertEquals("Test Game", cfg.name());
        Assertions.assertEquals("custom", cfg.gameType());
        Assertions.assertEquals(2, cfg.args().size());
        Assertions.assertEquals("/numgames", cfg.args().get(0));
        Assertions.assertEquals("5", cfg.args().get(1));
    }

    @Test
    public void validMatchmakerProducesConfig() throws Exception {
        String json =
                "{"
                        + "\"uid\": 7,"
                        + "\"mod\": \"ladder1v1\","
                        + "\"name\": \"MM\","
                        + "\"game_type\": \"matchmaker\","
                        + "\"rating_type\": \"ladder_1v1\","
                        + "\"mapname\": \"island_map\","
                        + "\"team\": 1,"
                        + "\"faction\": 2,"
                        + "\"map_position\": 3,"
                        + "\"expected_players\": 2"
                        + "}";

        GameConfig cfg = accepted(json);

        Assertions.assertEquals(7, cfg.uid());
        Assertions.assertEquals("island_map", cfg.mapname());
        Assertions.assertEquals(Integer.valueOf(2), cfg.faction());
    }

    @Test
    public void invalidMapnameIsRejected() throws Exception {
        String json =
                "{"
                        + "\"uid\": 1,"
                        + "\"mod\": \"faf\","
                        + "\"name\": \"Bad Map\","
                        + "\"game_type\": \"matchmaker\","
                        + "\"rating_type\": \"global\","
                        + "\"mapname\": \"weird;rm -rf\","
                        + "\"team\": 0,"
                        + "\"faction\": 1,"
                        + "\"map_position\": 0,"
                        + "\"expected_players\": 2"
                        + "}";

        Assertions.assertEquals(
                "game_launch.mapname invalid for matchmaker: weird;rm -rf", rejected(json));
    }

    @Test
    public void invalidArgsAreRejected() throws Exception {
        String json =
                "{"
                        + "\"uid\": 2,"
                        + "\"mod\": \"faf\","
                        + "\"name\": \"Bad Args\","
                        + "\"game_type\": \"custom\","
                        + "\"rating_type\": \"global\","
                        + "\"args\": [\"--danger\"]"
                        + "}";

        Assertions.assertEquals(
                "game_launch.args contains disallowed leading '-': --danger", rejected(json));
    }

    @Test
    public void validInitModeIsAccepted() throws Exception {
        String json =
                "{"
                        + "\"uid\": 5,"
                        + "\"mod\": \"faf\","
                        + "\"name\": \"Init Mode\","
                        + "\"game_type\": \"custom\","
                        + "\"rating_type\": \"global\","
                        + "\"init_mode\": 1,"
                        + "\"args\": [\"/numgames\", 1]"
                        + "}";

        Assertions.assertEquals(Integer.valueOf(1), accepted(json).initMode());
    }

    @Test
    public void invalidInitModeIsRejected() throws Exception {
        String json =
                "{"
                        + "\"uid\": 6,"
                        + "\"mod\": \"faf\","
                        + "\"name\": \"Bad Init\","
                        + "\"game_type\": \"custom\","
                        + "\"rating_type\": \"global\","
                        + "\"init_mode\": 2,"
                        + "\"args\": [\"/numgames\", 1]"
                        + "}";

        Assertions.assertEquals("game_launch.init_mode invalid: 2", rejected(json));
    }

    @Test
    public void outOfRangeFactionIsRejected() throws Exception {
        String json =
                "{"
                        + "\"uid\": 3,"
                        + "\"mod\": \"faf\","
                        + "\"name\": \"Bad Faction\","
                        + "\"game_type\": \"matchmaker\","
                        + "\"rating_type\": \"global\","
                        + "\"mapname\": \"map\","
                        + "\"team\": 0,"
                        + "\"faction\": 9,"
                        + "\"map_position\": 0,"
                        + "\"expected_players\": 2"
                        + "}";

        Assertions.assertEquals("game_launch.faction invalid for matchmaker: 9", rejected(json));
    }

    /**
     * Nomad (5) is accepted: faf-server's {@code Faction} enum ends at {@code nomad = 5}, {@code
     * --queue-faction} accepts it ({@code GameQueueConfig}), and faf-server sends the searched
     * faction back as {@code game_launch.faction}. Refusing it made {@code run} exit 70 on its own
     * search.
     */
    @Test
    void nomadFactionIsAccepted() throws Exception {
        String json =
                "{"
                        + "\"uid\": 3,"
                        + "\"mod\": \"faf\","
                        + "\"name\": \"Nomad\","
                        + "\"game_type\": \"matchmaker\","
                        + "\"rating_type\": \"ladder_1v1\","
                        + "\"mapname\": \"map\","
                        + "\"team\": 2,"
                        + "\"faction\": 5,"
                        + "\"map_position\": 1,"
                        + "\"expected_players\": 2"
                        + "}";

        Assertions.assertEquals(Integer.valueOf(5), accepted(json).faction());
    }

    @Test
    public void missingMatchmakerFieldIsRejected() throws Exception {
        String json =
                "{"
                        + "\"uid\": 4,"
                        + "\"mod\": \"faf\","
                        + "\"name\": \"Missing Map\","
                        + "\"game_type\": \"matchmaker\","
                        + "\"rating_type\": \"global\""
                        + "}";

        Assertions.assertEquals("game_launch.mapname invalid for matchmaker: null", rejected(json));
    }

    @Test
    public void validInitModeZeroIsAccepted() throws Exception {
        String json =
                "{"
                        + "\"uid\": 8,"
                        + "\"mod\": \"faf\","
                        + "\"name\": \"Init Mode Zero\","
                        + "\"game_type\": \"custom\","
                        + "\"rating_type\": \"global\","
                        + "\"init_mode\": 0,"
                        + "\"args\": [\"/numgames\", 1]"
                        + "}";

        Assertions.assertEquals(Integer.valueOf(0), accepted(json).initMode());
    }

    @Test
    public void customNullsMatchmakerFields() throws Exception {
        String json =
                "{"
                        + "\"uid\": 42,"
                        + "\"mod\": \"faf\","
                        + "\"name\": \"Custom\","
                        + "\"game_type\": \"custom\","
                        + "\"rating_type\": \"global\","
                        + "\"faction\": 99,"
                        + "\"mapname\": \"whatever\""
                        + "}";

        GameConfig cfg = accepted(json);

        Assertions.assertNull(cfg.faction());
        Assertions.assertNull(cfg.mapname());
    }

    private static String loadFixture(String path) throws Exception {
        return Files.readString(
                Path.of(GameLaunchHandlerTest.class.getClassLoader().getResource(path).toURI()));
    }

    @Test
    public void matchmakerKeepsAllFields() throws Exception {
        GameConfig cfg = accepted(loadFixture("lobby/inbound/game_launch_matchmaker.json"));

        Assertions.assertEquals("scmp_015", cfg.mapname());
        Assertions.assertEquals(Integer.valueOf(1), cfg.faction());
        Assertions.assertEquals(Integer.valueOf(1), cfg.mapPoolMapVersionId());
        Assertions.assertEquals(Integer.valueOf(1), cfg.initMode());
    }

    /** A frame missing a required field fails to decode, and is rejected with the field (#457). */
    @Test
    void aFrameMissingARequiredFieldIsRejected() throws Exception {
        String json =
                "{"
                        + "\"mod\":\"faf\","
                        + "\"name\":\"x\","
                        + "\"game_type\":\"custom\","
                        + "\"rating_type\":\"global\""
                        + "}";

        Assertions.assertEquals("game_launch.uid is required", rejected(json));
    }

    /**
     * A value of the wrong JSON type fails to decode, and its reason names the field on one line
     * (#457). Jackson's own message puts its source location on a second line, which would split
     * the one line that reports the rejection. A value Jackson could coerce is refused rather than
     * coerced (#474), and its reason ends before Jackson's advice on enabling coercion.
     *
     * @param field the field given the wrong type
     * @param value the value, as JSON
     * @param reason the exact reason
     */
    @ParameterizedTest(name = "{0} = {1}")
    @MethodSource("wrongJsonTypes")
    void aValueOfTheWrongJsonTypeIsRefusedNamingItsField(
            final String field, final String value, final String reason) throws Exception {
        ObjectNode frame =
                (ObjectNode)
                        mapper.readTree(
                                "{\"uid\":42,\"mod\":\"faf\",\"name\":\"x\","
                                        + "\"game_type\":\"custom\",\"rating_type\":\"global\","
                                        + "\"args\":[\"/numgames\",5]}");
        frame.set(field, mapper.readTree(value));

        Assertions.assertEquals(reason, rejected(mapper.writeValueAsString(frame)));
    }

    static Stream<Arguments> wrongJsonTypes() {
        return Stream.of(
                Arguments.of(
                        "uid",
                        "\"abc\"",
                        "game_launch.uid: Cannot coerce String value (\"abc\") to"
                                + " `java.lang.Integer` value"),
                Arguments.of(
                        "uid",
                        "1.5",
                        "game_launch.uid: Cannot coerce Floating-point value (1.5) to"
                                + " `java.lang.Integer` value"),
                Arguments.of(
                        "uid",
                        "\"8\"",
                        "game_launch.uid: Cannot coerce String value (\"8\") to"
                                + " `java.lang.Integer` value"),
                Arguments.of(
                        "uid",
                        "\"\"",
                        "game_launch.uid: Cannot coerce empty String (\"\") to"
                                + " `java.lang.Integer` value"),
                Arguments.of(
                        "team",
                        "\"2\"",
                        "game_launch.team: Cannot coerce String value (\"2\") to"
                                + " `java.lang.Integer` value"),
                Arguments.of(
                        "mod",
                        "5",
                        "game_launch.mod: Cannot coerce Integer value (5) to"
                                + " `java.lang.String` value"),
                Arguments.of(
                        "mod",
                        "1.5",
                        "game_launch.mod: Cannot coerce Float value (1.5) to"
                                + " `java.lang.String` value"),
                Arguments.of(
                        "name",
                        "true",
                        "game_launch.name: Cannot coerce Boolean value (true) to"
                                + " `java.lang.String` value"),
                Arguments.of(
                        "mapname",
                        "15",
                        "game_launch.mapname: Cannot coerce Integer value (15) to"
                                + " `java.lang.String` value"));
    }

    /**
     * A matchmaker frame as faf-server sends it still launches under the strict decode (#474). Its
     * {@code args} carry an integer and its {@code game_options} values can be of any type, and
     * both are read as raw JSON, which the coercion rules do not reach.
     */
    @Test
    void aFafServerMatchmakerFrameStillLaunches() throws Exception {
        ObjectNode frame =
                (ObjectNode)
                        mapper.readTree(loadFixture("lobby/inbound/game_launch_matchmaker.json"));
        frame.set(
                "game_options",
                mapper.readTree(
                        "{\"Share\":\"ShareUntilDeath\",\"UnitCap\":1000,\"Cheats\":false}"));

        GameConfig cfg = accepted(mapper.writeValueAsString(frame));

        Assertions.assertEquals(41956, cfg.uid());
        Assertions.assertEquals(List.of("/numgames", "0"), cfg.args());
    }
}
