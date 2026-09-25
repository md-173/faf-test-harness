package com.faforever.testharness.client.lobby;

/**
 * What {@link GameLaunchValidator} decided about one {@code game_launch} frame (WBS-3.1.1.6-fix,
 * #457): a config this client can launch, or the reason it cannot. {@link GameLaunchHandler}
 * rejects a frame that fails to decode the same way.
 *
 * <p>A result rather than a WARN and {@code null}: the lifecycle ends the session on a rejection
 * and names its reason in the one line that reports it. A dropped frame used to leave the run
 * waiting for a launch it had already received.
 */
public sealed interface GameLaunchValidation {

    /**
     * A frame this client can launch.
     *
     * @param config the validated config
     */
    record Accepted(GameConfig config) implements GameLaunchValidation {}

    /**
     * A frame this client cannot use.
     *
     * @param reason one line naming the field and why it was refused
     */
    record Rejected(String reason) implements GameLaunchValidation {}
}
