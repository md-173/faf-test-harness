/**
 * Typed record types for the FAF lobby payloads complex enough to warrant one.
 *
 * <ul>
 *   <li>{@link com.faforever.testharness.client.lobby.message.WelcomeMessage} — server's
 *       authenticated-user reply; nested {@code Me} block + ratings map (consumed by 3.1.1.3
 *       welcome state sync).
 *   <li>{@link com.faforever.testharness.client.lobby.message.GameLaunchMessage} — server's
 *       game-launch trigger; 14 fields across custom and matchmaker shapes (consumed by 3.1.1.6
 *       game configuration handling).
 *   <li>{@link com.faforever.testharness.client.lobby.message.GameHostMessage} — outbound {@code
 *       game_host} request sent by {@link com.faforever.testharness.client.lobby.GameHostSender} to
 *       start the custom-game host flow (3.1.1.7).
 * </ul>
 *
 * <p>The inbound records ({@code WelcomeMessage}, {@code GameLaunchMessage}) are presence-validated
 * in their canonical constructors — required primitives are boxed so an omitted field decodes to
 * {@code null} and throws, rather than silently defaulting to {@code 0}. The outbound {@code
 * GameHostMessage} has no such checks: the sender owns construction and its values are sourced from
 * validated config, so there is nothing to guard against.
 *
 * <p>Inbound consumers decode directly with Jackson:
 *
 * <pre>{@code
 * mapper.treeToValue(node, WelcomeMessage.class);
 * }</pre>
 *
 * <p>Outbound senders encode the same way:
 *
 * <pre>{@code
 * lobby.send(mapper.valueToTree(gameHostMessage));
 * }</pre>
 *
 * <p>Smaller payloads (ask_session, session, auth, authentication_failed, game_join, the matchmaker
 * shapes) are read straight off the {@link com.fasterxml.jackson.databind.JsonNode}, or written by
 * hand with {@code ObjectMapper.createObjectNode()}. Adding a record for those would be more
 * boilerplate than the field-access saves.
 *
 * <p>Wire schemas live in {@code documentation/research/lobby-protocol-spec.md} §3 / §5 / §10.
 */
package com.faforever.testharness.client.lobby.message;
