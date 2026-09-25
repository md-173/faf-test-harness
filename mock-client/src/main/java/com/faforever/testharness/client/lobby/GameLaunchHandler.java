package com.faforever.testharness.client.lobby;

import com.faforever.testharness.client.lobby.GameLaunchValidation.Accepted;
import com.faforever.testharness.client.lobby.GameLaunchValidation.Rejected;
import com.faforever.testharness.client.lobby.message.GameLaunchMessage;
import com.faforever.testharness.shared.logging.Failures;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.function.Consumer;

/**
 * Decodes a {@code game_launch} frame, validates it, and hands the result to one of two sinks: the
 * {@link GameConfig} to launch, or the one-line reason the frame cannot be used (WBS-3.1.1.6-fix,
 * #457). It logs neither, since the lifecycle names a rejection in the line that ends the session.
 */
public final class GameLaunchHandler implements LobbyMessageHandler {

    /** Jackson mapper used to decode incoming frames. */
    private final ObjectMapper mapper;

    /** Consumer that receives validated {@link GameConfig} objects. */
    private final Consumer<GameConfig> sink;

    /** Consumer that receives the reason a frame cannot be used. */
    private final Consumer<String> rejected;

    /**
     * Construct a handler.
     *
     * @param mapper Jackson mapper used to convert frames
     * @param sink consumer that will receive validated GameConfig objects
     * @param rejected consumer that will receive the one-line reason a frame cannot be used
     */
    public GameLaunchHandler(
            ObjectMapper mapper, Consumer<GameConfig> sink, Consumer<String> rejected) {
        this.mapper = mapper;
        this.sink = sink;
        this.rejected = rejected;
    }

    @Override
    public void onMessage(JsonNode node) {
        GameLaunchValidation result;
        try {
            result =
                    GameLaunchValidator.validate(
                            mapper.convertValue(node, GameLaunchMessage.class));
        } catch (RuntimeException e) {
            // convertValue's own failure, since validate never throws: a required field is
            // missing, or a value has the wrong type.
            result = new Rejected(decodeFailure(e));
        }
        switch (result) {
            case Accepted accepted -> sink.accept(accepted.config());
            case Rejected rejection -> rejected.accept(rejection.reason());
        }
    }

    /**
     * One line naming why a frame did not decode. {@code convertValue} wraps Jackson's own error,
     * whose message carries its source location on a second line. A missing required field is the
     * record constructor's own message, such as {@code game_launch.uid is required}, and a value of
     * the wrong type is Jackson's original message after the field it was read for.
     *
     * @param failure what {@code convertValue} threw
     * @return the reason, on one line
     */
    private static String decodeFailure(final RuntimeException failure) {
        if (!(failure.getCause() instanceof JsonMappingException json)) {
            return Failures.describe(failure);
        }
        if (json.getCause() instanceof IllegalArgumentException missing) {
            return missing.getMessage();
        }
        // Only a top-level field can have the wrong type: args and game_options are read as raw
        // JSON, so a path never goes deeper than one field.
        return json.getPath().isEmpty()
                ? json.getOriginalMessage()
                : "game_launch."
                        + json.getPath().get(0).getFieldName()
                        + ": "
                        + json.getOriginalMessage();
    }
}
