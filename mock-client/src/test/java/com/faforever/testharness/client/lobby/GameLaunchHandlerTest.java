package com.faforever.testharness.client.lobby;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

public class GameLaunchHandlerTest {
    private final ObjectMapper mapper = new ObjectMapper();

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

        AtomicReference<GameConfig> sink = new AtomicReference<>();
        GameLaunchHandler handler = new GameLaunchHandler(mapper, sink::set);

        handler.onMessage(mapper.readTree(json));

        GameConfig cfg = sink.get();
        Assertions.assertNotNull(cfg);
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

        AtomicReference<GameConfig> sink = new AtomicReference<>();
        GameLaunchHandler handler = new GameLaunchHandler(mapper, sink::set);

        handler.onMessage(mapper.readTree(json));

        GameConfig cfg = sink.get();
        Assertions.assertNotNull(cfg);
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

        AtomicReference<GameConfig> sink = new AtomicReference<>();
        GameLaunchHandler handler = new GameLaunchHandler(mapper, sink::set);

        handler.onMessage(mapper.readTree(json));

        Assertions.assertNull(sink.get());
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

        AtomicReference<GameConfig> sink = new AtomicReference<>();
        GameLaunchHandler handler = new GameLaunchHandler(mapper, sink::set);

        handler.onMessage(mapper.readTree(json));

        Assertions.assertNull(sink.get());
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

        AtomicReference<GameConfig> sink = new AtomicReference<>();
        GameLaunchHandler handler = new GameLaunchHandler(mapper, sink::set);

        handler.onMessage(mapper.readTree(json));

        GameConfig cfg = sink.get();
        Assertions.assertNotNull(cfg);
        Assertions.assertEquals(Integer.valueOf(1), cfg.initMode());
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

        AtomicReference<GameConfig> sink = new AtomicReference<>();
        GameLaunchHandler handler = new GameLaunchHandler(mapper, sink::set);

        handler.onMessage(mapper.readTree(json));

        Assertions.assertNull(sink.get());
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

        AtomicReference<GameConfig> sink = new AtomicReference<>();
        GameLaunchHandler handler = new GameLaunchHandler(mapper, sink::set);

        handler.onMessage(mapper.readTree(json));

        Assertions.assertNull(sink.get());
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

        AtomicReference<GameConfig> sink = new AtomicReference<>();
        GameLaunchHandler handler = new GameLaunchHandler(mapper, sink::set);

        handler.onMessage(mapper.readTree(json));

        Assertions.assertNull(sink.get());
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

        AtomicReference<GameConfig> sink = new AtomicReference<>();
        GameLaunchHandler handler = new GameLaunchHandler(mapper, sink::set);

        handler.onMessage(mapper.readTree(json));

        Assertions.assertNotNull(sink.get());
        Assertions.assertEquals(Integer.valueOf(0), sink.get().initMode());
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

        AtomicReference<GameConfig> sink = new AtomicReference<>();
        GameLaunchHandler handler = new GameLaunchHandler(mapper, sink::set);

        handler.onMessage(mapper.readTree(json));

        GameConfig cfg = sink.get();

        Assertions.assertNotNull(cfg);
        Assertions.assertNull(cfg.faction());
        Assertions.assertNull(cfg.mapname());
    }

    private GameConfig handle(String json) throws Exception {
        AtomicReference<GameConfig> sink = new AtomicReference<>();
        new GameLaunchHandler(mapper, sink::set).onMessage(mapper.readTree(json));
        return sink.get();
    }

    private static String loadFixture(String path) throws Exception {
        return Files.readString(
                Path.of(GameLaunchHandlerTest.class.getClassLoader().getResource(path).toURI()));
    }

    @Test
    public void matchmakerKeepsAllFields() throws Exception {
        GameConfig cfg = handle(loadFixture("lobby/inbound/game_launch_matchmaker.json"));

        Assertions.assertNotNull(cfg);
        Assertions.assertEquals("scmp_015", cfg.mapname());
        Assertions.assertEquals(Integer.valueOf(1), cfg.faction());
        Assertions.assertEquals(Integer.valueOf(1), cfg.mapPoolMapVersionId());
        Assertions.assertEquals(Integer.valueOf(1), cfg.initMode());
    }

    @Test
    void frameMissingRequiredFieldIsSwallowed() throws Exception {
        String json =
                "{"
                        + "\"mod\":\"faf\","
                        + "\"name\":\"x\","
                        + "\"game_type\":\"custom\","
                        + "\"rating_type\":\"global\""
                        + "}";

        AtomicReference<GameConfig> sink = new AtomicReference<>();
        new GameLaunchHandler(mapper, sink::set)
                .onMessage(mapper.readTree(json));

        Assertions.assertNull(sink.get());
    }
}
