package com.faforever.testharness.game.config;

/**
 * Where a player sits in a matchmaker game: the team, start spot and faction its {@code
 * game_launch} assigned, which the FAF client passes to Forged Alliance as {@code /team}, {@code
 * /startspot} and a faction flag (WBS-3.2.4.3-fix, #534). FA's matchmaker lobby seats every player
 * with these values, and its host reports each seated player's to faf-server as {@code
 * PlayerOption} frames.
 *
 * @param team the team, FA's {@code /team}
 * @param startSpot the start spot on the map, FA's {@code /startspot}, which {@code game_launch}
 *     calls {@code map_position}
 * @param faction the faction, numbered as {@code game_launch} numbers it
 */
public record Seat(int team, int startSpot, int faction) {

    /**
     * FA's own values for a player launched without those arguments, as every custom game is: team
     * -1, start spot -1 and faction 1 ({@code CreateLocalPlayer} in {@code
     * AutolobbyController.lua}).
     */
    public static final Seat DEFAULT = new Seat(-1, -1, 1);
}
