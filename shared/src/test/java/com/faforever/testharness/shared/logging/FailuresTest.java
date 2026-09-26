package com.faforever.testharness.shared.logging;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.TextNode;
import java.io.IOException;
import java.net.ConnectException;
import java.nio.channels.ClosedChannelException;
import java.time.Duration;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import org.junit.jupiter.api.Test;

/** Tests for {@link Failures}: one readable line per failure, however it was wrapped. */
final class FailuresTest {

    @Test
    void aFailureWithNoMessageIsNamedByItsType() {
        assertEquals("ConnectException", Failures.describe(new ConnectException()));
    }

    @Test
    void itsMessageFollowsItsType() {
        assertEquals(
                "IOException: failed to send ask_session",
                Failures.describe(new IOException("failed to send ask_session")));
    }

    /** The refused connect #455 reproduced: neither exception carries a message. */
    @Test
    void itsRootCauseFollowsIt() {
        Throwable refused = new ConnectException();
        refused.initCause(new ClosedChannelException());

        assertEquals(
                "ConnectException, caused by ClosedChannelException", Failures.describe(refused));
    }

    @Test
    void aCauseChainThatLoopsStillEnds() {
        Exception first = new Exception("first");
        Exception second = new Exception("second");
        first.initCause(second);
        second.initCause(first);

        String line =
                assertTimeoutPreemptively(Duration.ofSeconds(5), () -> Failures.describe(first));

        assertTrue(line.startsWith("Exception: first"), line);
    }

    /**
     * A Jackson error is named once and on one line (#455), although Jackson puts its source
     * location on a second line and {@code convertValue} rethrows it wrapped, with the same
     * message.
     */
    @Test
    void aJacksonErrorIsNamedOnceOnOneLine() {
        IllegalArgumentException failure =
                assertThrows(
                        IllegalArgumentException.class,
                        () -> new ObjectMapper().convertValue(new TextNode("abc"), Integer.class));

        String line = Failures.describe(failure);

        assertTrue(line.startsWith("InvalidFormatException: Cannot deserialize value of"), line);
        assertFalse(line.contains("\n"), line);
        assertFalse(line.contains("caused by"), line);
    }

    @Test
    void unwrapStripsTheWrappersAFutureAdds() {
        IOException cause = new IOException("closed");

        assertSame(cause, Failures.unwrap(new ExecutionException(new CompletionException(cause))));
    }
}
