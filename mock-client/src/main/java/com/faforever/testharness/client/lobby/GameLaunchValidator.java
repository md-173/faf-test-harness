package com.faforever.testharness.client.lobby;

import com.faforever.testharness.client.lobby.message.GameLaunchMessage;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Validator implementing the §5 step-3 checks for {@code game_launch} payloads. Logs WARN and
 * returns {@code null} for invalid frames.
 */
public final class GameLaunchValidator {
    /** SLF4J logger for validation messages. */
    private static final Logger LOG = LoggerFactory.getLogger(GameLaunchValidator.class);

    /** Pattern for allowed mod identifiers. */
    private static final Pattern MOD_PATTERN = Pattern.compile("^[A-Za-z0-9._-]+$");

    /** Pattern for allowed map names. */
    private static final Pattern MAP_PATTERN = Pattern.compile("^[A-Za-z0-9._-]+$");

    /** Pattern for allowed textual args that are not slash flags. */
    private static final Pattern ARG_TEXT = Pattern.compile("^[A-Za-z0-9._/\\-]+$");

    /** Allowed game type values. */
    private static final Set<String> VALID_GAME_TYPES = Set.of("coop", "custom", "matchmaker");

    /** Allowed faction numeric values. */
    private static final Set<Integer> VALID_FACTIONS = Set.of(1, 2, 3, 4);

    /**
     * Allow-list of recognized slash-prefixed CLI flags.
     *
     * <p>Only known slash flags are permitted here, since the FAF lobby protocol allows CLI
     * arguments from untrusted servers. Expand this list when the spec enumerates additional flags.
     */
    private static final Set<String> ALLOWED_SLASH_ARGS = Set.of("/numgames");

    private GameLaunchValidator() {}

    /**
     * Validate a decoded {@link GameLaunchMessage} and return a validated {@link GameConfig} or
     * {@code null} if the message is invalid.
     *
     * @param msg decoded message (presence-validated by its constructor)
     * @return validated GameConfig or null on invalid input
     */
    public static GameConfig validate(GameLaunchMessage msg) {
        try {
            if (msg.uid() == null || msg.uid() < 0) {
                LOG.warn("game_launch.uid invalid: {}", msg.uid());
                return null;
            }
            if (msg.mod() == null || !MOD_PATTERN.matcher(msg.mod()).matches()) {
                LOG.warn("game_launch.mod invalid: {}", msg.mod());
                return null;
            }
            if (msg.name() == null || msg.name().isBlank()) {
                LOG.warn("game_launch.name invalid: {}", msg.name());
                return null;
            }
            if (msg.gameType() == null || !VALID_GAME_TYPES.contains(msg.gameType())) {
                LOG.warn("game_launch.game_type invalid: {}", msg.gameType());
                return null;
            }
            if (msg.ratingType() == null || msg.ratingType().isBlank()) {
                LOG.warn("game_launch.rating_type invalid: {}", msg.ratingType());
                return null;
            }
            if (msg.initMode() != null && msg.initMode() != 0 && msg.initMode() != 1) {
                LOG.warn("game_launch.init_mode invalid: {}", msg.initMode());
                return null;
            }

            // Validate args: allow slash flags from a whitelist, plain tokens, and numbers.
            List<String> sanitizedArgs = new ArrayList<>();

            if (msg.args() != null) {
                for (JsonNode node : msg.args()) {
                    if (node == null || node.isNull()) {
                        LOG.warn("game_launch.args contains null element");
                        return null;
                    }
                    if (node.isTextual()) {
                        String s = node.textValue();
                        if (s.startsWith("-")) {
                            LOG.warn("game_launch.args contains disallowed leading '-': {}", s);
                            return null;
                        }
                        if (s.startsWith("/")) {
                            if (!ALLOWED_SLASH_ARGS.contains(s)) {
                                LOG.warn("game_launch.args contains unknown slash-flag: {}", s);
                                return null;
                            }
                            sanitizedArgs.add(s);
                        } else {
                            if (!ARG_TEXT.matcher(s).matches()) {
                                LOG.warn("game_launch.args contains disallowed text: {}", s);
                                return null;
                            }
                            sanitizedArgs.add(s);
                        }
                    } else if (node.isNumber()) {
                        sanitizedArgs.add(node.asText());
                    } else {
                        LOG.warn("game_launch.args contains unsupported element: {}", node);
                        return null;
                    }
                }
            }

            // Matchmaker-specific checks
            String mapname = null;
            Integer team = null;
            Integer faction = null;
            Integer mapPosition = null;
            Integer expectedPlayers = null;
            Integer mapPoolMapVersionId = null;

            if ("matchmaker".equals(msg.gameType())) {
                mapname = msg.mapname();
                team = msg.team();
                faction = msg.faction();
                mapPosition = msg.mapPosition();
                expectedPlayers = msg.expectedPlayers();
                mapPoolMapVersionId = msg.mapPoolMapVersionId();

                if (mapname == null || !MAP_PATTERN.matcher(mapname).matches()) {
                    LOG.warn("game_launch.mapname invalid for matchmaker: {}", mapname);
                    return null;
                }
                if (team == null || team < 0) {
                    LOG.warn("game_launch.team invalid for matchmaker: {}", team);
                    return null;
                }
                if (faction == null || !VALID_FACTIONS.contains(faction)) {
                    LOG.warn("game_launch.faction invalid for matchmaker: {}", faction);
                    return null;
                }
                if (mapPosition == null || mapPosition < 0) {
                    LOG.warn("game_launch.map_position invalid for matchmaker: {}", mapPosition);
                    return null;
                }
                if (expectedPlayers == null || expectedPlayers < 0) {
                    LOG.warn(
                            "game_launch.expected_players invalid for matchmaker: {}",
                            expectedPlayers);
                    return null;
                }
                if (mapPoolMapVersionId != null && mapPoolMapVersionId < 0) {
                    LOG.warn(
                            "game_launch.map_pool_map_version_id invalid for matchmaker: {}",
                            mapPoolMapVersionId);
                    return null;
                }
            }

            JsonNode gameOptionsCopy =
                    msg.gameOptions() == null ? null : msg.gameOptions().deepCopy();

            return new GameConfig(
                    msg.uid(),
                    msg.mod(),
                    msg.name(),
                    msg.initMode(),
                    msg.gameType(),
                    msg.ratingType(),
                    List.copyOf(sanitizedArgs),
                    mapname,
                    team,
                    faction,
                    mapPosition,
                    expectedPlayers,
                    mapPoolMapVersionId,
                    gameOptionsCopy);
        } catch (Exception e) {
            LOG.warn("Unexpected validation error: {}", e.toString());
            return null;
        }
    }
}