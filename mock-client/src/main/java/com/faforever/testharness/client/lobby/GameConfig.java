package com.faforever.testharness.client.lobby;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;

/**
 * Immutable validated configuration produced from a {@code game_launch} frame.
 *
 * @param uid game id
 * @param mod featured mod name
 * @param name game display name
 * @param initMode initial mode (nullable)
 * @param gameType game type string
 * @param ratingType rating type string
 * @param args command-line arguments (validated, as strings)
 * @param mapname map folder name (matchmaker only)
 * @param team team assignment (matchmaker only)
 * @param faction faction id (matchmaker only)
 * @param mapPosition start spot on the map (matchmaker only)
 * @param expectedPlayers expected player count (matchmaker only)
 * @param mapPoolMapVersionId map pool version reference (matchmaker only)
 * @param gameOptions opaque game options JSON
 */
public record GameConfig(
        int uid,
        String mod,
        String name,
        Integer initMode,
        String gameType,
        String ratingType,
        List<String> args,
        String mapname,
        Integer team,
        Integer faction,
        Integer mapPosition,
        Integer expectedPlayers,
        Integer mapPoolMapVersionId,
        JsonNode gameOptions) {}
