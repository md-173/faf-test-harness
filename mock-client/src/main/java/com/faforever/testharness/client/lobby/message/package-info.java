/**
 * Typed record types for the FAF lobby payloads complex enough to warrant one.
 *
 * <ul>
 *   <li>{@link com.faforever.testharness.client.lobby.message.WelcomeMessage} — server's
 *       authenticated-user reply; nested {@code Me} block + ratings map (consumed by 3.1.1.3
 *       welcome state sync).
 *   <li>{@link com.faforever.testharness.client.lobby.message.GameLaunchMessage} — server's
 *       game-launch trigger; 14 fields across custom and matchmaker shapes (consumed by R24 game
 *       configuration handling).
 * </ul>
 *
 * <p>Both records are presence-validated in their canonical constructors. Required primitives are
 * boxed so an omitted field decodes to {@code null} and throws, rather than silently defaulting to
 * {@code 0}.
 *
 * <p>Consumers decode directly with Jackson:
 *
 * <pre>{@code
 * mapper.treeToValue(node, WelcomeMessage.class);
 * }</pre>
 *
 * <p>Smaller payloads (ask_session, session, auth, authentication_failed, the matchmaker /
 * game_host / game_join shapes) are read straight off the {@link
 * com.fasterxml.jackson.databind.JsonNode} using {@link
 * com.faforever.testharness.client.lobby.JsonRequire}, or written by hand with {@code
 * ObjectMapper.createObjectNode()}. Adding a record for those would be more boilerplate than the
 * field-access saves.
 *
 * <p>Wire schemas live in {@code documentation/research/lobby-protocol-spec.md} §3 / §5 / §10.
 */
package com.faforever.testharness.client.lobby.message;
