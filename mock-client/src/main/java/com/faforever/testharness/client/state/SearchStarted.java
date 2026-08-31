package com.faforever.testharness.client.state;

import com.faforever.testharness.shared.statemachine.Event;
import com.fasterxml.jackson.databind.JsonNode;

/**
 * Lobby confirms a matchmaker search has started, via {@code search_info} reporting state {@code
 * "start"} (WBS-3.1.1.9).
 *
 * @param command the {@code search_info} frame received.
 */
/*package-private*/ record SearchStarted(JsonNode command) implements Event {}
