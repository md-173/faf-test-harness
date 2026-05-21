package com.faforever.testharness.client.process;

/**
 * Thrown when {@link IceAdapterLauncher} cannot bring up the {@code faf-ice-adapter} subprocess.
 *
 * <p>Carries a clear, single-line {@linkplain #getMessage() message} suitable for logging at ERROR
 * without a stack trace — see the acceptance criteria of WBS-3.1.2.2 ("Missing or invalid binary
 * path produces a clear error and non-zero exit, not a stack trace"). The two cases it covers are
 * "binary not found" (the configured path does not point at a regular file) and "binary failed to
 * start" ({@link ProcessBuilder#start()} raised an {@link java.io.IOException}).
 */
public final class IceAdapterLaunchException extends Exception {

    private static final long serialVersionUID = 1L;

    /**
     * Creates an exception with a single-line, log-ready message.
     *
     * @param message the single-line description of the failure
     */
    public IceAdapterLaunchException(final String message) {
        super(message);
    }

    /**
     * Creates an exception with a single-line message and an underlying cause. The cause is
     * preserved for debugging but callers are expected to log only {@link #getMessage()}.
     *
     * @param message the single-line description of the failure
     * @param cause the underlying failure
     */
    public IceAdapterLaunchException(final String message, final Throwable cause) {
        super(message, cause);
    }
}
