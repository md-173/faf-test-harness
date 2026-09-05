package com.faforever.testharness.client.state;

import com.faforever.testharness.shared.statemachine.Event;
import com.fasterxml.jackson.databind.JsonNode;

/**
 * Lobby confirms a matchmaker search has stopped, via {@code search_info} reporting state {@code
 * "stop"} (WBS-3.1.1.9).
 *
 * @param command the {@code search_info} frame received.
 */
/*package-private*/ record SearchStopped(JsonNode command) implements Event {}
