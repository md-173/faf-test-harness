package com.faforever.testharness.game.lifecycle;

/** States that the mock game lifecycle can be in. */
public enum GameState {
    /** Initiating a GPGNet connection to the server (through the ICE adapter). */
    INITIALIZING,
    /** Connection established with server, waiting to create a lobby. */
    IDLE,
    /** Waiting on instructions from the server. */
    LOBBY,
    /** Hosting a game, waiting for players to join and setting up game options. */
    HOSTING,
    /** Joining a game hosted by a peer. */
    JOINING,
    /** Game simulation running, active communication between peers. */
    LIVE,
    /** The game simulation has ended. The result of the game is sent to the server. */
    ENDED;
}
