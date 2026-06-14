package com.faforever.testharness.client.lobby.message;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;

/**
 * Inbound {@code game_launch} payload (lobby-protocol-spec.md §5, §10.3) — the server's trigger for
 * the Mock Client to start the {@code mock-game} and {@code faf-ice-adapter} subprocesses.
 *
 * <p>The payload has two shapes — custom and matchmaker — that share a required common header and
 * differ in optional fields. Both shapes are represented by a single record with matchmaker-only
 * fields as nullable boxed types; the consumer (3.1.1.6) inspects {@code gameType} to decide which
 * fields to require.
 *
 * <p><b>Validation scope at this layer.</b> Per the issue (3.1.1.5), validation here is type and
 * required-field correctness only. The deeper argument-injection / unexpected-path / type-confusion
 * checks described in spec §5 step 3 are 3.1.1.6's responsibility — they belong adjacent to the
 * {@code ProcessBuilder} call where the values cross the trust boundary into a launched process.
 *
 * <p>{@code args} is heterogeneous on the wire ({@code ["/numgames", 5]} — strings and ints
 * interleaved), so it is exposed as {@code List<JsonNode>} rather than a typed list.
 *
 * <p>Required fields are boxed types with a canonical-constructor presence check, so a frame that
 * omits one decodes to {@code null} and is dropped by the dispatcher rather than silently
 * defaulting (a primitive {@code int uid} would mask a missing game id as {@code 0}). {@code
 * initMode} is the exception: it is boxed but <em>not</em> required, because the spec marks it
 * deprecated ("infer from {@code game_type}") and a server may legitimately omit it.
 *
 * @param uid game ID; required
 * @param mod featured mod name (e.g. {@code "faf"}, {@code "ladder1v1"}); required
 * @param name game display name; required
 * @param initMode 0 = normal lobby, 1 = auto lobby (deprecated — infer from {@code gameType});
 *     {@code null} if the server omitted it
 * @param gameType {@code "coop"} / {@code "custom"} / {@code "matchmaker"}; required
 * @param ratingType e.g. {@code "global"}, {@code "ladder_1v1"}; required
 * @param args optional extra CLI args passed to the game executable
 * @param mapname matchmaker only — map folder name
 * @param team matchmaker only — team assignment
 * @param faction matchmaker only — 1=UEF, 2=Aeon, 3=Cybran, 4=Seraphim
 * @param mapPosition matchmaker only — start spot on the map
 * @param expectedPlayers matchmaker only — expected player count
 * @param mapPoolMapVersionId matchmaker only — map pool version reference
 * @param gameOptions matchmaker only — additional game options, opaque to this layer
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record GameLaunchMessage(
        Integer uid,
        String mod,
        String name,
        @JsonProperty("init_mode") Integer initMode,
        @JsonProperty("game_type") String gameType,
        @JsonProperty("rating_type") String ratingType,
        List<JsonNode> args,
        String mapname,
        Integer team,
        Integer faction,
        @JsonProperty("map_position") Integer mapPosition,
        @JsonProperty("expected_players") Integer expectedPlayers,
        @JsonProperty("map_pool_map_version_id") Integer mapPoolMapVersionId,
        @JsonProperty("game_options") JsonNode gameOptions) {

    /**
     * Compact canonical constructor — rejects a frame missing any required header field. This is
     * structural (presence) validation only; the value-level checks in spec §5 step 3 are 3.1.1.6's
     * responsibility (see the class javadoc).
     */
    public GameLaunchMessage {
        if (uid == null) {
            throw new IllegalArgumentException("game_launch.uid is required");
        }
        if (mod == null || mod.isBlank()) {
            throw new IllegalArgumentException("game_launch.mod is required");
        }
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("game_launch.name is required");
        }
        if (gameType == null || gameType.isBlank()) {
            throw new IllegalArgumentException("game_launch.game_type is required");
        }
        if (ratingType == null || ratingType.isBlank()) {
            throw new IllegalArgumentException("game_launch.rating_type is required");
        }
    }
}
