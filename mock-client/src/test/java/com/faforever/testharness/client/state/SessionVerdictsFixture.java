package com.faforever.testharness.client.state;

/**
 * Builds a {@link SessionVerdicts} with chosen verdicts, for tests outside this package. Its
 * constructor and recorders are package-private, which keeps recording to the lifecycle's own
 * classes in production; this reaches them from the same package.
 */
public final class SessionVerdictsFixture {

    private SessionVerdictsFixture() {}

    /**
     * Verdicts as a finished session would carry them.
     *
     * @param launchFailed whether the adapter or game never came up
     * @param adapterLost whether the adapter died unaccounted for
     * @param gameCrashed whether the game died unaccounted for
     * @param sessionFailed whether the session failed after it came up
     * @return the verdicts
     */
    public static SessionVerdicts of(
            final boolean launchFailed,
            final boolean adapterLost,
            final boolean gameCrashed,
            final boolean sessionFailed) {
        SessionVerdicts verdicts = new SessionVerdicts();
        if (launchFailed) {
            verdicts.recordLaunchFailed();
        }
        if (adapterLost) {
            verdicts.recordAdapterLost();
        }
        if (gameCrashed) {
            verdicts.recordGameCrashed();
        }
        if (sessionFailed) {
            verdicts.recordSessionFailed();
        }
        return verdicts;
    }
}
