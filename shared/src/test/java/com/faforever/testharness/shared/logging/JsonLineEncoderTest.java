package com.faforever.testharness.shared.logging;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.FileAppender;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.MDC;

/**
 * Tests for {@link JsonLineEncoder}'s record shape, driven through a real {@link FileAppender} into
 * a temporary directory. Assertions parse the written file with Jackson rather than matching
 * console text, so they pin the exact JSONL channel a test harness reads (WBS-3.1.6.2).
 *
 * <p>Rotation is out of scope here. Production uses a {@code RollingFileAppender}, but the contract
 * under test is the shape of one record, not Logback's rolling policy.
 */
final class JsonLineEncoderTest {

    /** Parses the JSONL the encoder produced. */
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /**
     * Builds a started throwaway context. A hand-built context has no MDC adapter and event
     * construction dereferences it, so the global one is installed.
     *
     * @return a started {@link LoggerContext} safe to log through
     */
    private static LoggerContext newContext() {
        LoggerContext context = new LoggerContext();
        context.setMDCAdapter(MDC.getMDCAdapter());
        context.start();
        return context;
    }

    /**
     * Logs the given messages through a real file appender and returns the parsed records. The
     * appender is stopped before the file is read, which flushes and closes it, so the read is
     * deterministic.
     *
     * @param file the JSONL file to write
     * @param component component label to store as a context property
     * @param instance instance label to store as a context property, or {@code null} for none
     * @param messages the messages to log, one record each
     * @return one parsed JSON object per written line
     * @throws IOException if the written file cannot be read back
     */
    private static List<JsonNode> writeAndParse(
            final Path file,
            final String component,
            final String instance,
            final String... messages)
            throws IOException {
        LoggerContext context = newContext();
        context.putProperty(LoggingSetup.COMPONENT_MDC_KEY, component);
        if (instance != null) {
            context.putProperty(LoggingSetup.INSTANCE_MDC_KEY, instance);
        }

        JsonLineEncoder encoder = new JsonLineEncoder();
        encoder.setContext(context);
        encoder.start();

        FileAppender<ILoggingEvent> appender = new FileAppender<>();
        appender.setContext(context);
        appender.setFile(file.toString());
        appender.setEncoder(encoder);
        appender.start();

        Logger logger = context.getLogger(JsonLineEncoderTest.class);
        logger.addAppender(appender);
        for (String message : messages) {
            logger.info(message);
        }

        appender.stop();
        context.stop();

        List<String> lines = Files.readAllLines(file);
        return lines.stream().map(JsonLineEncoderTest::parse).toList();
    }

    /**
     * Parses one JSONL line, rethrowing a parse failure as an assertion-friendly error.
     *
     * @param line the line to parse
     * @return the parsed JSON object
     */
    private static JsonNode parse(final String line) {
        try {
            return MAPPER.readTree(line);
        } catch (IOException e) {
            throw new AssertionError("encoder wrote a line that is not valid JSON: " + line, e);
        }
    }

    @Test
    void writesOneParseableRecordPerLine(@TempDir final Path dir) throws IOException {
        Path file = dir.resolve("harness.jsonl");

        List<JsonNode> records = writeAndParse(file, "MockClient", null, "first", "second");

        assertEquals(2, records.size(), "one JSONL record per log call");
        assertEquals("first", records.get(0).get("message").asText());
        assertEquals("second", records.get(1).get("message").asText());
        assertEquals("MockClient", records.get(0).get("component").asText());
        assertEquals("INFO", records.get(0).get("level").asText());
        assertTrue(records.get(0).hasNonNull("timestamp"), "records carry a timestamp");
    }

    @Test
    void stampsInstanceFieldWhenInstanceIsNamed(@TempDir final Path dir) throws IOException {
        Path file = dir.resolve("peer-a.jsonl");

        List<JsonNode> records = writeAndParse(file, "MockClient", "peer-a", "hello");

        assertEquals("peer-a", records.get(0).get("instance").asText());
    }

    @Test
    void omitsInstanceFieldForSingleInstanceRuns(@TempDir final Path dir) throws IOException {
        Path file = dir.resolve("harness.jsonl");

        List<JsonNode> records = writeAndParse(file, "MockClient", null, "hello");

        assertFalse(
                records.get(0).has("instance"),
                "an unnamed instance must leave the record shape unchanged");
    }

    @Test
    void keepsMultiLineStackTracesOnOneRecord(@TempDir final Path dir) throws IOException {
        Path file = dir.resolve("harness.jsonl");
        LoggerContext context = newContext();
        JsonLineEncoder encoder = new JsonLineEncoder();
        encoder.setContext(context);
        encoder.start();
        FileAppender<ILoggingEvent> appender = new FileAppender<>();
        appender.setContext(context);
        appender.setFile(file.toString());
        appender.setEncoder(encoder);
        appender.start();
        Logger logger = context.getLogger(JsonLineEncoderTest.class);
        logger.addAppender(appender);

        logger.warn("boom", new IllegalStateException("cause text"));

        appender.stop();
        context.stop();

        List<String> lines = Files.readAllLines(file);
        assertEquals(1, lines.size(), "a stack trace must not split the record across lines");
        JsonNode record = parse(lines.get(0));
        assertTrue(
                record.get("exception").asText().contains("IllegalStateException"),
                "the stack trace is escaped into the exception field");
    }
}
