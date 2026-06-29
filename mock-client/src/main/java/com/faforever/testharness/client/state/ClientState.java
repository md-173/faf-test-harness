package com.faforever.testharness.client.state;

/** States that the mock client can be in. */
public enum ClientState {
    /** Connecting to the server. */
    CONNECTING,
    /** Waiting for instructions to join or start a game. */
    IDLE,
    /** Opening game binary, establishing necessary connections. */
    STARTING_GAME,
    /** Hosting a game, waiting for all players to be connected. */
    HOSTING,
    /** Join an existing game. */
    JOINING,
    /** Game simulation running. */
    PLAYING,
    /** Game has ended or some unrecoverable error occured. */
    TERMINATED;
}
