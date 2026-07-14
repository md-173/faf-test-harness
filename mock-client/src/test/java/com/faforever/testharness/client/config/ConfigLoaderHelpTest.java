package com.faforever.testharness.client.config;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import picocli.CommandLine;

/**
 * Verifies that {@code --help} and {@code --version} short-circuit cleanly: no {@link
 * CommandLine.MissingParameterException} is thrown, the loader returns an empty {@link Optional},
 * and the printed help text mentions every required flag so users have a working reference.
 *
 * <p>Regression guard for the bug where {@code parseArgs} (without {@code execute}) would not react
 * to {@code --help} and the loader would crash on missing required fields instead of printing
 * usage.
 */
final class ConfigLoaderHelpTest {

    private PrintStream originalStdout;
    private ByteArrayOutputStream captured;

    @BeforeEach
    void redirectStdout() {
        originalStdout = System.out;
        captured = new ByteArrayOutputStream();
        System.setOut(new PrintStream(captured, true, StandardCharsets.UTF_8));
    }

    @AfterEach
    void restoreStdout() {
        System.setOut(originalStdout);
    }

    @Test
    void helpFlagReturnsEmptyOptional() {
        Optional<MockClientConfig> result;
        try {
            result = ConfigLoader.load(new String[] {"--help"}, Map.of());
        } catch (CommandLine.MissingParameterException e) {
            fail(
                    "--help should short-circuit before required-field validation, but "
                            + "loader threw: "
                            + e.getMessage());
            return;
        }

        assertTrue(
                result.isEmpty(),
                "--help should make the loader return Optional.empty() so Main can exit 0.");
    }

    @Test
    void shortHelpFlagReturnsEmptyOptional() {
        Optional<MockClientConfig> result;
        try {
            result = ConfigLoader.load(new String[] {"-h"}, Map.of());
        } catch (CommandLine.MissingParameterException e) {
            fail("-h should short-circuit just like --help, but loader threw: " + e.getMessage());
            return;
        }

        assertTrue(result.isEmpty(), "-h should return Optional.empty().");
    }

    @Test
    void versionFlagReturnsEmptyOptional() {
        Optional<MockClientConfig> result;
        try {
            result = ConfigLoader.load(new String[] {"--version"}, Map.of());
        } catch (CommandLine.MissingParameterException e) {
            fail(
                    "--version should short-circuit before required-field validation, but "
                            + "loader threw: "
                            + e.getMessage());
            return;
        }

        assertTrue(result.isEmpty(), "--version should return Optional.empty().");
    }

    @Test
    void helpOutputMentionsEveryRequiredFlag() {
        ConfigLoader.load(new String[] {"--help"}, Map.of());

        String output = captured.toString(StandardCharsets.UTF_8);

        assertTrue(
                output.contains("Usage:"),
                "Help output should contain a 'Usage:' header. Got: " + output);
        assertTrue(
                output.contains("--lobby-websocket-url"),
                "Help output should mention --lobby-websocket-url.");
        assertTrue(
                output.contains("--oauth-token-url"),
                "Help output should mention --oauth-token-url.");
        assertTrue(
                output.contains("--oauth-auth-endpoint"),
                "Help output should mention --oauth-auth-endpoint.");
        assertTrue(
                output.contains("--oauth-redirect-uri"),
                "Help output should mention --oauth-redirect-uri.");
        assertTrue(output.contains("--oauth-scopes"), "Help output should mention --oauth-scopes.");
        assertTrue(
                output.contains("--oauth-client-id"),
                "Help output should mention --oauth-client-id.");
        assertTrue(
                output.contains("--oauth-refresh-token-file"),
                "Help output should mention --oauth-refresh-token-file.");
        assertTrue(output.contains("--unique-id"), "Help output should mention --unique-id.");
        assertTrue(
                output.contains("--ice-adapter-binary-path"),
                "Help output should mention --ice-adapter-binary-path.");
        assertTrue(
                output.contains("--mock-game-binary-path"),
                "Help output should mention --mock-game-binary-path.");
        assertFalse(
                output.contains("Missing required options"),
                "Help output should NOT contain a missing-required-options error. "
                        + "If it does, --help isn't short-circuiting. Got: "
                        + output);
    }
}
