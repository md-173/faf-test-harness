package com.faforever.testharness.client.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.faforever.testharness.shared.logging.LoggingSetup;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Pins that {@code --log-level} actually reaches Logback, which is what makes the stack trace
 * {@link ExecutionExceptionHandler} suppresses recoverable at {@code DEBUG} — the third acceptance
 * criterion of the exit-code work, and the half of it no in-process test can reach.
 *
 * <p>Logback substitutes {@code ${LOG_LEVEL:-INFO}} in {@code logback.xml} when the first logger in
 * the process is created, and every subcommand sets that property later, from inside {@code
 * call()}. Anything that creates a logger while the {@link
 * com.faforever.testharness.client.config.ConfigLoader#newCommandLine} tree is being built
 * therefore pins the process at {@code INFO} and turns {@code --log-level} into a silent no-op — a
 * regression with no visible symptom other than missing records, which is exactly the kind a test
 * suite has to catch rather than a reviewer.
 *
 * <p>Follows {@code InstanceLabelEndToEndTest}: real child JVMs, assertions read the JSONL each
 * child wrote.
 */
final class LogLevelFlagEndToEndTest {

    /** Parses the JSONL the child wrote. */
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** How long a child gets to run the entry point, log one record, and flush. */
    private static final int CHILD_TIMEOUT_SECONDS = 30;

    @TempDir private Path tempDir;

    @Test
    void logLevelDebugEnablesDebugRecords() throws Exception {
        List<JsonNode> records = runChild("--log-level=DEBUG");

        assertTrue(
                records.stream().anyMatch(LogLevelFlagEndToEndTest::isDebugMarker),
                "--log-level=DEBUG produced no DEBUG record, so the flag never reached Logback. "
                        + "Something on the CommandLine-construction path created a logger before "
                        + "the subcommand applied the level. Records: "
                        + records);
    }

    @Test
    void defaultLevelSuppressesDebugRecords() throws Exception {
        // The control. Without it the test above would still pass if DEBUG were on unconditionally,
        // which would prove nothing about the flag.
        List<JsonNode> records = runChild();

        assertFalse(
                records.stream().anyMatch(LogLevelFlagEndToEndTest::isDebugMarker),
                "DEBUG records appeared without the flag. Records: " + records);
        assertFalse(records.isEmpty(), "the child wrote no records at all, so it never ran");
    }

    /**
     * Whether {@code record} is the marker {@link LogLevelFlagChild} emits at {@code DEBUG}.
     *
     * @param record one parsed JSONL record
     * @return {@code true} if this is the child's DEBUG marker
     */
    private static boolean isDebugMarker(final JsonNode record) {
        return "DEBUG".equals(record.path("level").asText())
                && LogLevelFlagChild.DEBUG_MARKER.equals(record.path("message").asText());
    }

    /**
     * Runs {@link LogLevelFlagChild} in a child JVM against a fast-failing {@code launch-ice}
     * invocation, and returns the records it wrote.
     *
     * <p>{@code launch-ice} with an absent binary is used because it applies the logging properties
     * and then fails before any subprocess, socket or timer exists — so the child is quick and has
     * nothing to flake on.
     *
     * @param extraArgs arguments appended to the invocation, e.g. the log-level flag
     * @return the parsed records, in order
     * @throws IOException if the child cannot be started or its output cannot be read
     * @throws InterruptedException if the wait for the child is interrupted
     */
    private List<JsonNode> runChild(final String... extraArgs)
            throws IOException, InterruptedException {
        Path logFile = tempDir.resolve("child.jsonl");
        String absentBinary = tempDir.resolve("no-such-faf-ice-adapter").toString();
        String javaBin =
                ProcessHandle.current()
                        .info()
                        .command()
                        .orElse(System.getProperty("java.home") + "/bin/java");

        List<String> command =
                new ArrayList<>(
                        List.of(
                                javaBin,
                                "-cp",
                                System.getProperty("java.class.path"),
                                LogLevelFlagChild.class.getName()));
        command.addAll(
                List.of(CliTestFixtures.withSubcommandAndIceBinary("launch-ice", absentBinary)));
        command.addAll(List.of(extraArgs));

        ProcessBuilder pb = new ProcessBuilder(command);
        pb.directory(tempDir.toFile());
        // The ambient environment wins over the config file and the built-in default, so a
        // developer or CI runner exporting either of these would otherwise steer the child.
        pb.environment().remove(LoggingSetup.LOG_LEVEL_ENV);
        pb.environment().remove(LoggingSetup.INSTANCE_NAME_ENV);
        pb.environment().put(LoggingSetup.LOG_FILE_ENV, logFile.toString());
        pb.redirectErrorStream(true);

        Process child = pb.start();
        String console = new String(child.getInputStream().readAllBytes());
        assertTrue(
                child.waitFor(CHILD_TIMEOUT_SECONDS, TimeUnit.SECONDS),
                "child JVM did not exit in time");
        assertEquals(
                ExitCodes.RUNTIME,
                child.exitValue(),
                "child did not reach the expected failure. console output: " + console);

        assertTrue(Files.exists(logFile), "child wrote no log file at " + logFile);
        List<JsonNode> records = new ArrayList<>();
        for (String line : Files.readAllLines(logFile)) {
            records.add(MAPPER.readTree(line));
        }
        return records;
    }
}
