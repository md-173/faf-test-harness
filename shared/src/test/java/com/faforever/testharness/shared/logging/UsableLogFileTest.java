package com.faforever.testharness.shared.logging;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * The log-path guard itself, called directly (WBS-2.3.6-fix, #305).
 *
 * <p>{@link UnusableLogFileEndToEndTest} proves the operator-visible behaviour, but each of its
 * cases costs a child JVM. This pins the boundary {@link LoggingSetup#isUsableLogFile} draws in
 * microseconds, which is what makes it affordable to hold the shape of the rejection rather than
 * just its consequence.
 *
 * <p>The two rejections come from <em>different</em> failure sites inside Logback, which is the
 * point of having both here: {@code a%.jsonl} is a malformed conversion specifier to the pattern
 * parser, while {@code logs/%d{yyyy}/app.jsonl} converts to a regex perfectly well and then dies
 * starting the rolling policy with {@code Unknown periodicity type}. A guard that checks only the
 * regex conversion accepts the second and leaves #305 open for it.
 */
final class UsableLogFileTest {

    /** Paths Logback cannot build a working rollover policy from. */
    @ParameterizedTest
    @ValueSource(
            strings = {
                "a[b.jsonl",
                "a{b.jsonl",
                "a(b.jsonl",
                "a%.jsonl",
                "${LOG_FILE}",
                "logs/%d{yyyy}/app.jsonl"
            })
    void anUnusablePathIsRejected(final String path) {
        assertFalse(
                LoggingSetup.isUsableLogFile(path),
                "Logback cannot use this path, so the guard must catch it: " + path);
    }

    /**
     * Paths that must survive. {@code run-%d{yyyy-MM-dd}.jsonl} is the near miss worth pinning: it
     * carries a date token like the rejected case above, but one whose periodicity Logback can
     * resolve, so the guard must not reject {@code %d} on sight.
     */
    @ParameterizedTest
    @ValueSource(
            strings = {
                "logs/mockclient.jsonl",
                "../rel.jsonl",
                "/tmp/absolute.jsonl",
                "logs/nested/dir/app.jsonl",
                "a b.jsonl",
                "a%b.jsonl",
                "a%X{k}b.jsonl",
                "run-%d{yyyy-MM-dd}.jsonl"
            })
    void aUsablePathIsLeftAlone(final String path) {
        assertTrue(
                LoggingSetup.isUsableLogFile(path),
                "Logback logs to this path today, so the guard must not reject it: " + path);
    }
}
