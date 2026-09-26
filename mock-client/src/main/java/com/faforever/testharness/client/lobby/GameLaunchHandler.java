package com.faforever.testharness.client.lobby;

import com.faforever.testharness.client.lobby.GameLaunchValidation.Accepted;
import com.faforever.testharness.client.lobby.GameLaunchValidation.Rejected;
import com.faforever.testharness.client.lobby.message.GameLaunchMessage;
import com.faforever.testharness.shared.logging.Failures;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.cfg.CoercionAction;
import com.fasterxml.jackson.databind.cfg.CoercionInputShape;
import com.fasterxml.jackson.databind.type.LogicalType;
import java.util.function.Consumer;

/**
 * Decodes a {@code game_launch} frame, validates it, and hands the result to one of two sinks: the
 * {@link GameConfig} to launch, or the one-line reason the frame cannot be used (WBS-3.1.1.6-fix,
 * #457). It logs neither, since the lifecycle names a rejection in the line that ends the session.
 */
public final class GameLaunchHandler implements LobbyMessageHandler {

    /** Jackson mapper used to decode incoming frames, holding each field to its JSON type. */
    private final ObjectMapper mapper;

    /** Consumer that receives validated {@link GameConfig} objects. */
    private final Consumer<GameConfig> sink;

    /** Consumer that receives the reason a frame cannot be used. */
    private final Consumer<String> rejected;

    /**
     * Construct a handler.
     *
     * @param mapper Jackson mapper used to convert frames; the handler decodes with a strict copy
     * @param sink consumer that will receive validated GameConfig objects
     * @param rejected consumer that will receive the one-line reason a frame cannot be used
     */
    public GameLaunchHandler(
            ObjectMapper mapper, Consumer<GameConfig> sink, Consumer<String> rejected) {
        this.mapper = strict(mapper);
        this.sink = sink;
        this.rejected = rejected;
    }

    /**
     * A copy of {@code mapper} that reads each field only from its own JSON type (#474). The spec
     * holds every {@code game_launch} field to its type (lobby-protocol-spec section 5 step 3),
     * while Jackson's defaults coerce: {@code "uid":1.5} became uid 1, {@code "uid":"8"} uid 8 and
     * {@code "mod":5} mod "5". The real client accepts all three, so this is the spec's rule rather
     * than the client's. {@code args} and {@code game_options} are read as raw JSON, which no
     * coercion rule reaches, so faf-server's integer in {@code args} still decodes.
     *
     * @param mapper the mapper to copy
     * @return the copy, refusing coercion into integer and string fields
     */
    private static ObjectMapper strict(final ObjectMapper mapper) {
        ObjectMapper strict = mapper.copy();
        strict.coercionConfigFor(LogicalType.Integer)
                .setCoercion(CoercionInputShape.Float, CoercionAction.Fail)
                .setCoercion(CoercionInputShape.String, CoercionAction.Fail)
                .setCoercion(CoercionInputShape.EmptyString, CoercionAction.Fail);
        strict.coercionConfigFor(LogicalType.Textual)
                .setCoercion(CoercionInputShape.Integer, CoercionAction.Fail)
                .setCoercion(CoercionInputShape.Float, CoercionAction.Fail)
                .setCoercion(CoercionInputShape.Boolean, CoercionAction.Fail);
        return strict;
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
        String message = withoutHint(json.getOriginalMessage());
        // Only a top-level field can have the wrong type: args and game_options are read as raw
        // JSON, so a path never goes deeper than one field.
        return json.getPath().isEmpty()
                ? message
                : "game_launch." + json.getPath().get(0).getFieldName() + ": " + message;
    }

    /**
     * Jackson's message without the advice it appends to a refused coercion, such as {@code (but
     * could if coercion was enabled using `CoercionConfig`)} (#474). The advice is about this
     * class's settings, and an operator could read it as a switch to turn on. It is the last
     * bracket, after any value the message quotes.
     *
     * @param message Jackson's original message
     * @return the message up to the advice, or all of it when there is none
     */
    private static String withoutHint(final String message) {
        int hint = message == null ? -1 : message.lastIndexOf(" (but ");
        return hint >= 0 && message.endsWith(")") ? message.substring(0, hint) : message;
    }
}
