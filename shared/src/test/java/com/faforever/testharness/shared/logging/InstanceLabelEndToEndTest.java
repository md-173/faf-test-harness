package com.faforever.testharness.shared.logging;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
 * End-to-end cover for the multi-instance convention documented in {@code mock-client/README.md}.
 * Real child JVMs are launched with {@value LoggingSetup#INSTANCE_NAME_ENV} in their environment,
 * and the assertions read the JSONL file each child wrote.
 *
 * <p>This is the path the acceptance criteria are written against and the only form the docs
 * recommend, because a real environment variable is inherited by subprocesses while a {@code -D}
 * system property is not. It cannot be exercised in-process, since a JVM cannot set its own
 * environment.
 */
final class InstanceLabelEndToEndTest {

    /** Parses the JSONL each child wrote. */
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** How long a child gets to start, log one record, and flush. */
    private static final int CHILD_TIMEOUT_SECONDS = 30;

    /**
     * Runs {@link InstanceLabelChild} in a child JVM and returns the records it wrote.
     *
     * @param logFile the JSONL path handed to the child via {@value LoggingSetup#LOG_FILE_ENV}, or
     *     {@code null} to let the child pick its own default
     * @param instanceName the value of {@value LoggingSetup#INSTANCE_NAME_ENV}, or {@code null} to
     *     leave it unset
     * @param workingDir directory the child runs in, so a defaulted path stays inside the temp dir
     * @return the parsed records, in order
     * @throws IOException if the child cannot be started or its output cannot be read
     * @throws InterruptedException if the wait for the child is interrupted
     */
    private static List<JsonNode> runChild(
            final Path logFile, final String instanceName, final Path workingDir)
            throws IOException, InterruptedException {
        String javaBin =
                ProcessHandle.current()
                        .info()
                        .command()
                        .orElse(System.getProperty("java.home") + "/bin/java");
        ProcessBuilder pb =
                new ProcessBuilder(
                        javaBin,
                        "-cp",
                        System.getProperty("java.class.path"),
                        InstanceLabelChild.class.getName(),
                        "MockClient");
        pb.directory(workingDir.toFile());
        pb.environment().remove(LoggingSetup.INSTANCE_NAME_ENV);
        pb.environment().remove(LoggingSetup.LOG_FILE_ENV);
        if (instanceName != null) {
            pb.environment().put(LoggingSetup.INSTANCE_NAME_ENV, instanceName);
        }
        if (logFile != null) {
            pb.environment().put(LoggingSetup.LOG_FILE_ENV, logFile.toString());
        }
        pb.redirectErrorStream(true);
        Process child = pb.start();
        String console = new String(child.getInputStream().readAllBytes());
        assertTrue(
                child.waitFor(CHILD_TIMEOUT_SECONDS, TimeUnit.SECONDS),
                "child JVM did not exit in time");
        assertEquals(0, child.exitValue(), "child JVM failed. console output: " + console);

        Path written = logFile != null ? logFile : defaultLogFile(workingDir, instanceName);
        assertTrue(Files.exists(written), "child wrote no log file at " + written);
        List<JsonNode> records = new ArrayList<>();
        for (String line : Files.readAllLines(written)) {
            records.add(MAPPER.readTree(line));
        }
        return records;
    }

    /**
     * The path {@link LoggingSetup#configure} defaults to for a component named {@code MockClient}.
     *
     * @param workingDir the child's working directory
     * @param instanceName the instance label, or {@code null} when unset
     * @return the expected default JSONL path
     */
    private static Path defaultLogFile(final Path workingDir, final String instanceName) {
        String suffix = instanceName == null ? "" : "-" + instanceName;
        return workingDir.resolve("logs").resolve("mockclient" + suffix + ".jsonl");
    }

    @Test
    void environmentVariableReachesTheInstanceField(@TempDir final Path dir) throws Exception {
        List<JsonNode> records = runChild(dir.resolve("peer-a.jsonl"), "peer-a", dir);

        assertEquals(1, records.size(), "the child logs exactly one record");
        assertEquals("peer-a", records.get(0).get("instance").asText());
        assertEquals("MockClient", records.get(0).get("component").asText());
        assertEquals("state entry: CONNECTING", records.get(0).get("message").asText());
    }

    @Test
    void twoInstancesAreAttributableLineByLine(@TempDir final Path dir) throws Exception {
        List<JsonNode> peerA = runChild(dir.resolve("a.jsonl"), "peer-a", dir);
        List<JsonNode> peerB = runChild(dir.resolve("b.jsonl"), "peer-b", dir);

        // The acceptance criterion: merge both streams and every record still names its origin.
        List<JsonNode> merged = new ArrayList<>(peerA);
        merged.addAll(peerB);
        assertEquals(
                List.of("peer-a", "peer-b"),
                merged.stream().map(record -> record.get("instance").asText()).toList(),
                "every record in a merged view identifies which instance emitted it");
    }

    @Test
    void unsetEnvironmentVariableLeavesRecordsUnchanged(@TempDir final Path dir) throws Exception {
        List<JsonNode> records = runChild(dir.resolve("plain.jsonl"), null, dir);

        assertFalse(
                records.get(0).has("instance"),
                "a single-instance run keeps the record shape it had before WBS-3.1.6.2");
    }

    @Test
    void namedInstanceGetsItsOwnDefaultLogFile(@TempDir final Path dir) throws Exception {
        // No LOG_FILE: the child picks its own path, which must not collide with another
        // instance's. This is what keeps two mock games out of one shared file.
        List<JsonNode> records = runChild(null, "peer-a", dir);

        assertEquals("peer-a", records.get(0).get("instance").asText());
        assertTrue(
                Files.exists(dir.resolve("logs").resolve("mockclient-peer-a.jsonl")),
                "a named instance defaults to logs/<component>-<instance>.jsonl");
        assertFalse(
                Files.exists(dir.resolve("logs").resolve("mockclient.jsonl")),
                "and must not fall back to the shared per-component default");
    }
}
