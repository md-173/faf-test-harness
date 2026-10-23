package com.faforever.testharness.shared.logging;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The open check itself, called directly (WBS-2.3.6-fix, #524).
 *
 * <p>{@link UnusableLogFileEndToEndTest} proves what the operator sees, at a child JVM per case.
 * This pins what {@link LoggingSetup#whyUnwritable} decides, and the three things it must do as
 * Logback does: trim the name, create missing directories, and append rather than truncate.
 */
final class WritableLogFileTest {

    @TempDir private Path tempDir;

    @Test
    void aDirectoryIsRejected() {
        assertEquals(
                Optional.of("it is a directory"), LoggingSetup.whyUnwritable(tempDir.toString()));
    }

    /**
     * Logback trims the name before it opens the file, so the check must judge the same path.
     * Untrimmed, this one is a missing file next to the directory, which the check would create.
     */
    @Test
    void theNameIsTrimmedAsLogbackTrimsIt() throws IOException {
        Path directory = Files.createDirectory(tempDir.resolve("a-directory"));

        assertEquals(Optional.of("it is a directory"), LoggingSetup.whyUnwritable(directory + " "));
    }

    @Test
    void aParentThatIsAFileIsRejected() throws IOException {
        Path file = Files.createFile(tempDir.resolve("not-a-directory"));

        assertEquals(
                Optional.of("its directory cannot be created"),
                LoggingSetup.whyUnwritable(file.resolve("run.jsonl").toString()));
    }

    @Test
    void missingDirectoriesAreCreated() {
        Path log = tempDir.resolve("a").resolve("b").resolve("run.jsonl");

        assertEquals(Optional.empty(), LoggingSetup.whyUnwritable(log.toString()));
        assertTrue(Files.isRegularFile(log), "the check creates the file, as Logback would");
    }

    @Test
    void anExistingLogIsAppendedToNotTruncated() throws IOException {
        String earlier = "{\"message\":\"an earlier run\"}\n";
        Path log = Files.writeString(tempDir.resolve("run.jsonl"), earlier);

        assertEquals(Optional.empty(), LoggingSetup.whyUnwritable(log.toString()));
        assertEquals(earlier, Files.readString(log), "the check must not truncate a log");
    }
}
