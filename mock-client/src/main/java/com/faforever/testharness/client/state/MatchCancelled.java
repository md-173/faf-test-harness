package com.faforever.testharness.client.state;

import com.faforever.testharness.shared.statemachine.Event;
import com.fasterxml.jackson.databind.JsonNode;

/**
 * Lobby cancels a matched game before launch, via {@code match_cancelled} (WBS-3.1.1.9). Carries a
 * nullable game id, per the protocol; this event only drives the SEARCHING to IDLE edge, so the id
 * is not read.
 *
 * @param command the {@code match_cancelled} frame received.
 */
/*package-private*/ record MatchCancelled(JsonNode command) implements Event {}
