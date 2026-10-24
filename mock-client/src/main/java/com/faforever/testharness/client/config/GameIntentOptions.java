package com.faforever.testharness.client.config;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import picocli.CommandLine.Option;
import picocli.CommandLine.ScopeType;

/**
 * The options that say what the session does once it reaches IDLE: host a custom game, join one, or
 * queue for a matchmaker game. Mixed into {@link MockClientCli}'s root command; each set builds one
 * of the {@link GameHostConfig}, {@link GameJoinConfig} and {@link GameQueueConfig} components of
 * {@link MockClientConfig}, empty when its options are unset. {@link MockClientConfig} rejects a
 * configuration with more than one.
 *
 * <p>Each field is named for its option in camel case, because the config-file key is the field
 * name.
 */
final class GameIntentOptions {

    /** ID of an existing game to join; when set the FSM sends {@code game_join} from IDLE. */
    @Option(
            names = "--target-game-id",
            scope = ScopeType.INHERIT,
            description =
                    "ID of an existing game to join. When set, the mock client sends game_join "
                            + "for this ID once it reaches the IDLE lobby state.")
    private Integer targetGameId;

    /** Optional password sent alongside {@code game_join} for password-protected games. */
    @Option(
            names = "--game-join-password",
            scope = ScopeType.INHERIT,
            description = "Optional password sent alongside game_join for a protected game.")
    private String gameJoinPassword;

    /**
     * Title advertised in the {@code game_host} request (lobby-protocol-spec §4.1 / §10.2). No
     * default: set together with {@code --host-map}, {@code --host-mod}, and {@code
     * --host-visibility} to host a game on IDLE; omit all four to not host.
     */
    @Option(
            names = "--host-title",
            scope = ScopeType.INHERIT,
            description =
                    "Hosted game title advertised to the lobby. Set together with --host-map, "
                            + "--host-mod, and --host-visibility to host a game on IDLE; omit "
                            + "all four to not host.")
    private String hostTitle;

    /**
     * Map folder name sent in the {@code game_host} request. No default; see {@code --host-title}.
     */
    @Option(
            names = "--host-map",
            scope = ScopeType.INHERIT,
            description = "Map folder name for the hosted game. See --host-title.")
    private String hostMap;

    /**
     * Featured-mod technical name sent in the {@code game_host} request. No default; see {@code
     * --host-title}.
     */
    @Option(
            names = "--host-mod",
            scope = ScopeType.INHERIT,
            description = "Featured-mod technical name for the hosted game. See --host-title.")
    private String hostMod;

    /**
     * Visibility ({@code public}/{@code friends}) sent in the {@code game_host} request. No
     * default; see {@code --host-title}.
     */
    @Option(
            names = "--host-visibility",
            scope = ScopeType.INHERIT,
            description =
                    "Visibility for the hosted game, \"public\" or \"friends\". See "
                            + "--host-title.")
    private String hostVisibility;

    /**
     * Minimum displayed rating for joining the hosted game. No default; see {@code --host-title}.
     */
    @Option(
            names = "--host-rating-min",
            scope = ScopeType.INHERIT,
            description = "Minimum displayed rating for joining the hosted game.")
    private Double hostRatingMin;

    /**
     * Maximum displayed rating for joining the hosted game. No default; see {@code --host-title}.
     */
    @Option(
            names = "--host-rating-max",
            scope = ScopeType.INHERIT,
            description = "Maximum displayed rating for joining the hosted game.")
    private Double hostRatingMax;

    /** Whether the server should enforce {@code --host-rating-min}/{@code --host-rating-max}. */
    @Option(
            names = "--host-enforce-rating-range",
            scope = ScopeType.INHERIT,
            defaultValue = "false",
            description =
                    "Whether to enforce --host-rating-min/--host-rating-max for the hosted game "
                            + "(default: ${DEFAULT-VALUE}).")
    private boolean hostEnforceRatingRange;

    /** A set of key-value pair game options to be sent by the host game. */
    @Option(
            names = "--host-game-option",
            arity = "0..*",
            scope = ScopeType.INHERIT,
            description = "A set of key-value pair game options to be sent by the host game.")
    private Map<String, String> hostGameOption = new HashMap<>();

    /**
     * Matchmaker queue name sent in the {@code game_matchmaking} request. No default: set to queue
     * on reaching IDLE; omit to not queue.
     */
    @Option(
            names = "--queue-name",
            scope = ScopeType.INHERIT,
            description =
                    "Matchmaker queue to search, e.g. \"ladder1v1\". When set, the mock client "
                            + "sends game_matchmaking start for this queue once it reaches the "
                            + "IDLE lobby state.")
    private String queueName;

    /** Faction sent alongside {@code game_matchmaking} state {@code start}. No default. */
    @Option(
            names = "--queue-faction",
            scope = ScopeType.INHERIT,
            description =
                    "Faction to search with (1=UEF, 2=Aeon, 3=Cybran, 4=Seraphim, 5=Nomad). Sent "
                            + "only on game_matchmaking start. See --queue-name.")
    private Integer queueFaction;

    /**
     * Builds the join config from {@code --target-game-id} and {@code --game-join-password}, or
     * empty if no target game was set.
     *
     * @return the join config, or {@link Optional#empty()} if the operator did not request joining
     */
    Optional<GameJoinConfig> buildJoinConfig() {
        if (targetGameId == null) {
            return Optional.empty();
        }
        return Optional.of(new GameJoinConfig(targetGameId, Optional.ofNullable(gameJoinPassword)));
    }

    /**
     * Builds the host config from the four {@code --host-*} options, or empty if none of them was
     * set. A partial set (e.g. only {@code --host-title}) is rejected by {@link GameHostConfig}'s
     * compact constructor, which names the specific missing option.
     *
     * @return the host config, or {@link Optional#empty()} if the operator did not request hosting
     */
    Optional<GameHostConfig> buildHostConfig() {
        if (hostTitle == null && hostMap == null && hostMod == null && hostVisibility == null) {
            return Optional.empty();
        }
        return Optional.of(
                new GameHostConfig(
                        hostTitle,
                        hostMap,
                        hostMod,
                        hostVisibility,
                        Optional.ofNullable(hostRatingMin),
                        Optional.ofNullable(hostRatingMax),
                        hostEnforceRatingRange,
                        hostGameOption));
    }

    /**
     * Builds the queue config from {@code --queue-name} and {@code --queue-faction}, or empty if
     * neither was set. Triggers on <em>either</em> option, the same shape {@link
     * #buildHostConfig()} uses, so a partial set is rejected by {@link GameQueueConfig}'s compact
     * constructor naming the missing option rather than silently starting a session that never
     * queues (#304 review). Gating on {@code queueName} alone would also make that record's
     * "--queue-name must not be blank" message unreachable from this path.
     *
     * @return the queue config, or {@link Optional#empty()} if the operator did not request
     *     queueing
     */
    Optional<GameQueueConfig> buildQueueConfig() {
        if (queueName == null && queueFaction == null) {
            return Optional.empty();
        }
        return Optional.of(new GameQueueConfig(queueName, Optional.ofNullable(queueFaction)));
    }
}
