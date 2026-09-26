package com.faforever.testharness.client.lobby;

import com.faforever.testharness.client.lobby.GameLaunchValidation.Accepted;
import com.faforever.testharness.client.lobby.GameLaunchValidation.Rejected;
import com.faforever.testharness.client.lobby.message.GameLaunchMessage;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Validator implementing the §5 step-3 checks for {@code game_launch} payloads. Returns the reason
 * an invalid frame is refused rather than logging it (WBS-3.1.1.6-fix, #457), so the lifecycle can
 * end the session on it and name the reason in the one line that reports it.
 */
public final class GameLaunchValidator {
    /** Pattern for allowed mod identifiers. */
    private static final Pattern MOD_PATTERN = Pattern.compile("^[A-Za-z0-9._-]+$");

    /** Pattern for allowed map names. */
    private static final Pattern MAP_PATTERN = Pattern.compile("^[A-Za-z0-9._-]+$");

    /** Pattern for allowed textual args that are not slash flags. */
    private static final Pattern ARG_TEXT = Pattern.compile("^[A-Za-z0-9._/\\-]+$");

    /** Allowed game type values. */
    private static final Set<String> VALID_GAME_TYPES = Set.of("coop", "custom", "matchmaker");

    /**
     * Allowed faction numeric values. Tracks faf-server's {@code Faction} enum, {@code uef = 1} to
     * {@code nomad = 5}, the same bound {@code GameQueueConfig} uses for {@code --queue-faction}.
     */
    private static final Set<Integer> VALID_FACTIONS = Set.of(1, 2, 3, 4, 5);

    /**
     * Allow-list of recognized slash-prefixed CLI flags.
     *
     * <p>Only known slash flags are permitted here, since the FAF lobby protocol allows CLI
     * arguments from untrusted servers. Expand this list when the spec enumerates additional flags.
     */
    private static final Set<String> ALLOWED_SLASH_ARGS = Set.of("/numgames");

    private GameLaunchValidator() {}

    /**
     * Validate a decoded {@link GameLaunchMessage}.
     *
     * @param msg decoded message (presence-validated by its constructor)
     * @return the validated config, or the one-line reason the frame cannot be used
     */
    public static GameLaunchValidation validate(GameLaunchMessage msg) {
        try {
            if (msg.uid() == null || msg.uid() < 0) {
                return new Rejected("game_launch.uid invalid: " + msg.uid());
            }
            if (msg.mod() == null || !MOD_PATTERN.matcher(msg.mod()).matches()) {
                return new Rejected("game_launch.mod invalid: " + msg.mod());
            }
            if (msg.name() == null || msg.name().isBlank()) {
                return new Rejected("game_launch.name invalid: " + msg.name());
            }
            if (msg.gameType() == null || !VALID_GAME_TYPES.contains(msg.gameType())) {
                return new Rejected("game_launch.game_type invalid: " + msg.gameType());
            }
            if (msg.ratingType() == null || msg.ratingType().isBlank()) {
                return new Rejected("game_launch.rating_type invalid: " + msg.ratingType());
            }
            if (msg.initMode() != null && msg.initMode() != 0 && msg.initMode() != 1) {
                return new Rejected("game_launch.init_mode invalid: " + msg.initMode());
            }

            // Validate args: allow slash flags from a whitelist, plain tokens, and integers, the
            // type of faf-server's /numgames count (#474), so a float is refused, not passed on.
            List<String> sanitizedArgs = new ArrayList<>();

            if (msg.args() != null) {
                for (JsonNode node : msg.args()) {
                    if (node == null || node.isNull()) {
                        return new Rejected("game_launch.args contains null element");
                    }
                    if (node.isTextual()) {
                        String s = node.textValue();
                        if (s.startsWith("-")) {
                            return new Rejected(
                                    "game_launch.args contains disallowed leading '-': " + s);
                        }
                        if (s.startsWith("/")) {
                            if (!ALLOWED_SLASH_ARGS.contains(s)) {
                                return new Rejected(
                                        "game_launch.args contains unknown slash-flag: " + s);
                            }
                            sanitizedArgs.add(s);
                        } else {
                            if (!ARG_TEXT.matcher(s).matches()) {
                                return new Rejected(
                                        "game_launch.args contains disallowed text: " + s);
                            }
                            sanitizedArgs.add(s);
                        }
                    } else if (node.isIntegralNumber()) {
                        sanitizedArgs.add(node.asText());
                    } else {
                        return new Rejected(
                                "game_launch.args contains unsupported element: " + node);
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
                    return new Rejected("game_launch.mapname invalid for matchmaker: " + mapname);
                }
                if (team == null || team < 0) {
                    return new Rejected("game_launch.team invalid for matchmaker: " + team);
                }
                if (faction == null || !VALID_FACTIONS.contains(faction)) {
                    return new Rejected("game_launch.faction invalid for matchmaker: " + faction);
                }
                if (mapPosition == null || mapPosition < 0) {
                    return new Rejected(
                            "game_launch.map_position invalid for matchmaker: " + mapPosition);
                }
                if (expectedPlayers == null || expectedPlayers < 0) {
                    return new Rejected(
                            "game_launch.expected_players invalid for matchmaker: "
                                    + expectedPlayers);
                }
                if (mapPoolMapVersionId != null && mapPoolMapVersionId < 0) {
                    return new Rejected(
                            "game_launch.map_pool_map_version_id invalid for matchmaker: "
                                    + mapPoolMapVersionId);
                }
            }

            JsonNode gameOptionsCopy =
                    msg.gameOptions() == null ? null : msg.gameOptions().deepCopy();

            return new Accepted(
                    new GameConfig(
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
                            gameOptionsCopy));
        } catch (Exception e) {
            return new Rejected("Unexpected validation error: " + e);
        }
    }
}
