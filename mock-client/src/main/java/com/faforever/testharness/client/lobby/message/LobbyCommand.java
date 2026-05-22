package com.faforever.testharness.client.lobby.message;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Binds a {@link InboundMessage} or {@link OutboundMessage} record to its wire {@code command}
 * string. The dispatcher reads this annotation to know which record to decode an incoming frame
 * into; the sender reads it to set the {@code command} field on the outgoing JSON envelope.
 *
 * <p>Every record permitted by {@link InboundMessage} or {@link OutboundMessage} must carry exactly
 * one of these. Construction of {@link com.faforever.testharness.client.lobby.LobbyMessageSender}
 * or registration with {@link com.faforever.testharness.client.lobby.LobbyMessageDispatcher} fails
 * fast if the annotation is missing — a defensive guard against forgetting it on a new record.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
public @interface LobbyCommand {
    /**
     * Wire-protocol {@code command} value (e.g. {@code "ask_session"}, {@code "welcome"}) — see
     * {@code documentation/research/lobby-protocol-spec.md} §10.
     *
     * @return wire command string
     */
    String value();
}
