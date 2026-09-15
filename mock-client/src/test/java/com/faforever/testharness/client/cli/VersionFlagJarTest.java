package com.faforever.testharness.client.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Pins that the shipped shadow jar reports the version it was built as (WBS-3.1.5.2, #383).
 *
 * <p>Every other {@code --version} test runs from class directories, where there is no manifest and
 * the fallback prints, so none of them would notice the manifest attributes disappearing from
 * {@code build.gradle}. That is how 0.2.0 shipped reporting {@code 1.0-SNAPSHOT}. This test runs
 * the real {@code -all} jar in a child JVM and compares against {@code project.version}, the same
 * property {@code release.yml}'s {@code -Pversion} sets.
 *
 * <p>{@code mock-client/build.gradle} passes both values in. They are required rather than assumed
 * absent-means-skip, so broken wiring fails here instead of turning the test into a silent pass.
 */
final class VersionFlagJarTest {

    /** System property carrying the absolute path of the shadow jar under test. */
    private static final String JAR_PROPERTY = "testharness.shadowJar";

    /** System property carrying {@code project.version}. */
    private static final String VERSION_PROPERTY = "testharness.version";

    /** How long the child gets to start a JVM and print one line. */
    private static final int CHILD_TIMEOUT_SECONDS = 30;

    @TempDir private Path tempDir;

    @Test
    void shadowJarReportsTheProjectVersion() throws Exception {
        String jar = requiredProperty(JAR_PROPERTY);
        String version = requiredProperty(VERSION_PROPERTY);
        assertTrue(Files.isRegularFile(Path.of(jar)), "shadow jar not found: " + jar);

        String javaBin =
                ProcessHandle.current()
                        .info()
                        .command()
                        .orElse(System.getProperty("java.home") + "/bin/java");
        Path stdout = tempDir.resolve("stdout.txt");
        Path stderr = tempDir.resolve("stderr.txt");
        ProcessBuilder pb = new ProcessBuilder(List.of(javaBin, "-jar", jar, "--version"));
        pb.directory(tempDir.toFile());
        // A stale FAF_MOCK_CLIENT_* variable is a usage error before --version is reached.
        pb.environment().keySet().removeIf(name -> name.startsWith("FAF_MOCK_CLIENT_"));
        // Files rather than pipes, so a chatty child cannot fill a buffer and stall past the
        // timeout below.
        pb.redirectOutput(stdout.toFile());
        pb.redirectError(stderr.toFile());

        Process child = pb.start();
        if (!child.waitFor(CHILD_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
            child.destroyForcibly();
            throw new AssertionError(
                    "child JVM did not exit within " + CHILD_TIMEOUT_SECONDS + "s");
        }
        String out = Files.readString(stdout, StandardCharsets.UTF_8).strip();
        String err = Files.readString(stderr, StandardCharsets.UTF_8);

        assertEquals(ExitCodes.OK, child.exitValue(), "stdout: " + out + "\nstderr: " + err);
        assertEquals("mock-client " + version, out, "stderr: " + err);
    }

    private static String requiredProperty(final String name) {
        String value = System.getProperty(name);
        assertNotNull(value, name + " is not set; run this test through Gradle, which supplies it");
        return value;
    }
}
