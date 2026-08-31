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
import java.util.Map;
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

    @Test
    void failureBeforeTheSubcommandConfiguresLoggingStillHonoursBothFlags() throws Exception {
        // The handler is the first logger in the process on this path, so whatever it finds in the
        // system properties is what Logback pins for good. Left alone it pins INFO and the default
        // path: no DEBUG record to recover the trace from, and the ERROR record in a file the
        // operator never asked for, tagged Unknown.
        Path requested = tempDir.resolve("child.jsonl");
        List<JsonNode> records =
                runChild(
                        EarlyFailureChild.class,
                        false,
                        new String[] {EarlyFailureChild.SUBCOMMAND},
                        "--log-level=DEBUG",
                        "--log-file=" + requested);

        JsonNode error = onlyRecordAt(records, "ERROR");
        assertTrue(
                error.path("message").asText().contains(EarlyFailureChild.MESSAGE),
                "the ERROR record did not describe the failure: " + error);
        assertEquals(
                "MockClient",
                error.path("component").asText(),
                "records are tagged Unknown, so LoggingSetup.configure never ran: " + error);

        JsonNode debug = onlyRecordAt(records, "DEBUG");
        assertTrue(
                debug.has("exception"),
                "the DEBUG record carries no stack trace, so the stderr hint is unactionable: "
                        + debug);
        assertTrue(
                debug.path("exception").asText().contains("IllegalStateException"),
                "the recovered trace is not the throw under test: " + debug);
    }

    @Test
    void environmentLayerReachesLoggingBeforeTheSubcommandStarts() throws Exception {
        // The strategy reads the populated root command rather than the ParseResult, precisely
        // because options resolved from the environment or the config file are never "matched"
        // options. Without this case, switching to parseResult.matchedOptionValue would still pass
        // every other test here while silently stranding anyone who configures the harness by
        // environment — which is how the Docker workspace and the N-client spawner do it.
        Path requested = tempDir.resolve("child.jsonl");
        List<JsonNode> records =
                runChild(
                        EarlyFailureChild.class,
                        false,
                        Map.of(
                                "FAF_MOCK_CLIENT_LOG_LEVEL",
                                "DEBUG",
                                "FAF_MOCK_CLIENT_LOG_FILE",
                                requested.toString()),
                        new String[] {EarlyFailureChild.SUBCOMMAND});

        assertEquals(
                "MockClient",
                onlyRecordAt(records, "ERROR").path("component").asText(),
                "records are tagged Unknown, so LoggingSetup.configure never ran");
        assertTrue(
                onlyRecordAt(records, "DEBUG").has("exception"),
                "FAF_MOCK_CLIENT_LOG_LEVEL did not reach Logback, so the trace is unrecoverable");
    }

    /**
     * Returns the single record at {@code level}, failing if there is not exactly one.
     *
     * @param records every record the child wrote
     * @param level the level to select
     * @return the one record at that level
     */
    private static JsonNode onlyRecordAt(final List<JsonNode> records, final String level) {
        List<JsonNode> matching =
                records.stream().filter(r -> level.equals(r.path("level").asText())).toList();
        assertEquals(1, matching.size(), "expected one " + level + " record, got: " + matching);
        return matching.get(0);
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
        String absentBinary = tempDir.resolve("no-such-faf-ice-adapter").toString();
        return runChild(
                LogLevelFlagChild.class,
                true,
                CliTestFixtures.withSubcommandAndIceBinary("launch-ice", absentBinary),
                extraArgs);
    }

    /**
     * Runs {@code mainClass} in a child JVM and returns the records it wrote to this test's log
     * file.
     *
     * @param mainClass the child program to run
     * @param logFileViaEnv whether to point the child at the log file with the {@code LOG_FILE}
     *     environment variable. Pass {@code false} when the test is about the {@code --log-file}
     *     flag itself: the environment variable reaches Logback whether or not the flag was
     *     honoured, so setting both would mask exactly the failure under test
     * @param baseArgs the subcommand and its required flags
     * @param extraArgs arguments appended after {@code baseArgs}
     * @return the parsed records, in order
     * @throws IOException if the child cannot be started or its output cannot be read
     * @throws InterruptedException if the wait for the child is interrupted
     */
    private List<JsonNode> runChild(
            final Class<?> mainClass,
            final boolean logFileViaEnv,
            final String[] baseArgs,
            final String... extraArgs)
            throws IOException, InterruptedException {
        return runChild(mainClass, logFileViaEnv, Map.of(), baseArgs, extraArgs);
    }

    /**
     * As above, with extra environment variables set on the child.
     *
     * @param mainClass the child program to run
     * @param logFileViaEnv whether to point the child at the log file with {@code LOG_FILE}
     * @param extraEnv variables to set on the child, applied after the ambient scrub below
     * @param baseArgs the subcommand and its required flags
     * @param extraArgs arguments appended after {@code baseArgs}
     * @return the parsed records, in order
     * @throws IOException if the child cannot be started or its output cannot be read
     * @throws InterruptedException if the wait for the child is interrupted
     */
    private List<JsonNode> runChild(
            final Class<?> mainClass,
            final boolean logFileViaEnv,
            final Map<String, String> extraEnv,
            final String[] baseArgs,
            final String... extraArgs)
            throws IOException, InterruptedException {
        Path logFile = tempDir.resolve("child.jsonl");
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
                                mainClass.getName()));
        command.addAll(List.of(baseArgs));
        command.addAll(List.of(extraArgs));

        ProcessBuilder pb = new ProcessBuilder(command);
        pb.directory(tempDir.toFile());
        // The ambient environment wins over the config file and the built-in default, so a
        // developer or CI runner exporting either of these would otherwise steer the child.
        pb.environment().remove(LoggingSetup.LOG_LEVEL_ENV);
        pb.environment().remove(LoggingSetup.INSTANCE_NAME_ENV);
        if (logFileViaEnv) {
            pb.environment().put(LoggingSetup.LOG_FILE_ENV, logFile.toString());
        } else {
            pb.environment().remove(LoggingSetup.LOG_FILE_ENV);
        }
        // The child reads the live environment, so a developer or CI runner with any
        // FAF_MOCK_CLIENT_* variable exported would otherwise be configuring it from outside the
        // test. Scrub the whole namespace, then apply only what this case asked for.
        pb.environment().keySet().removeIf(name -> name.startsWith("FAF_MOCK_CLIENT_"));
        pb.environment().putAll(extraEnv);
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
