package com.faforever.testharness.client.process;

/**
 * Thrown when {@link MockGameLauncher} cannot bring up the {@code mock-game} subprocess.
 *
 * <p>Carries a clear, single-line {@linkplain #getMessage() message} suitable for logging at ERROR
 * without a stack trace — see the acceptance criteria of WBS-3.1.2.3 ("Missing or invalid binary
 * path produces a clear, single-line error and non-zero exit"). The two cases it covers are "binary
 * not found" (the configured path does not point at a regular file) and "binary failed to start"
 * ({@link ProcessBuilder#start()} raised an {@link java.io.IOException}).
 */
public final class MockGameLaunchException extends Exception {

    private static final long serialVersionUID = 1L;

    /**
     * Creates an exception with a single-line, log-ready message.
     *
     * @param message the single-line description of the failure
     */
    public MockGameLaunchException(final String message) {
        super(message);
    }

    /**
     * Creates an exception with a single-line message and an underlying cause. The cause is
     * preserved for debugging but callers are expected to log only {@link #getMessage()}.
     *
     * @param message the single-line description of the failure
     * @param cause the underlying failure
     */
    public MockGameLaunchException(final String message, final Throwable cause) {
        super(message, cause);
    }
}
