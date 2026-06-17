package com.faforever.testharness.client.lobby;

import com.faforever.testharness.client.lobby.message.GameLaunchMessage;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Decodes a {@code game_launch} frame, validates it, and emits a {@link GameConfig} to a sink. */
public final class GameLaunchHandler implements LobbyMessageHandler {
    /** SLF4J logger for this handler. */
    private static final Logger LOG = LoggerFactory.getLogger(GameLaunchHandler.class);

    /** Jackson mapper used to decode incoming frames. */
    private final ObjectMapper mapper;

    /** Consumer that receives validated {@link GameConfig} objects. */
    private final Consumer<GameConfig> sink;

    /**
     * Construct a handler.
     *
     * @param mapper Jackson mapper used to convert frames
     * @param sink consumer that will receive validated GameConfig objects
     */
    public GameLaunchHandler(ObjectMapper mapper, Consumer<GameConfig> sink) {
        this.mapper = mapper;
        this.sink = sink;
    }

    @Override
    public void onMessage(JsonNode node) {
        GameLaunchMessage msg;
        try {
            msg = mapper.convertValue(node, GameLaunchMessage.class);
        } catch (RuntimeException e) {
            LOG.warn("Failed to decode game_launch frame: {}", e.getMessage());
            return;
        }

        GameConfig cfg = GameLaunchValidator.validate(msg);
        if (cfg != null) {
            sink.accept(cfg);
        }
    }
}
