package com.faforever.testharness.shared.logging;

import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.IThrowableProxy;
import ch.qos.logback.classic.spi.ThrowableProxyUtil;
import ch.qos.logback.core.encoder.EncoderBase;
import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonGenerator;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

/**
 * Logback encoder that serialises each log event as a single JSON object followed by a newline
 * (JSON Lines / JSONL format).
 *
 * <p>Every output line is a flat JSON object with the fields:
 *
 * <ul>
 *   <li>{@code timestamp} – wall-clock time, format {@code yyyy-MM-dd HH:mm:ss}
 *   <li>{@code component} – component label resolved by {@link ComponentConverter#resolve} (MDC →
 *       {@code LoggerContext} → {@code "Unknown"}), both populated by {@link
 *       LoggingSetup#configure}
 *   <li>{@code level} – log level string ({@code DEBUG}, {@code INFO}, …)
 *   <li>{@code logger} – fully-qualified logger name
 *   <li>{@code thread} – thread name
 *   <li>{@code message} – formatted log message
 *   <li>{@code exception} – (present only when an exception is attached) full stack trace as a
 *       single escaped string, so multi-line Java stack traces never split a JSONL record across
 *       lines
 * </ul>
 *
 * <p>The encoder is referenced from {@code logback.xml} and requires no additional configuration.
 */
public final class JsonLineEncoder extends EncoderBase<ILoggingEvent> {

    /** Timestamp format used in the JSON {@code timestamp} field. */
    private static final DateTimeFormatter TIMESTAMP_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS").withZone(ZoneId.systemDefault());

    /** Shared, thread-safe factory for creating JSON generators. */
    private static final JsonFactory JSON_FACTORY = new JsonFactory();

    /** Newline bytes appended after every JSON object. */
    private static final byte[] NEWLINE = "\n".getBytes(StandardCharsets.UTF_8);

    /** JSON field name for the wall-clock timestamp. */
    private static final String FIELD_TIMESTAMP = "timestamp";

    /** JSON field name for the source component tag. */
    private static final String FIELD_COMPONENT = "component";

    /** JSON field name for the log level. */
    private static final String FIELD_LEVEL = "level";

    /** JSON field name for the logger class name. */
    private static final String FIELD_LOGGER = "logger";

    /** JSON field name for the thread name. */
    private static final String FIELD_THREAD = "thread";

    /** JSON field name for the formatted log message. */
    private static final String FIELD_MESSAGE = "message";

    /** JSON field name for the optional exception stack trace. */
    private static final String FIELD_EXCEPTION = "exception";

    @Override
    public byte[] headerBytes() {
        return new byte[0];
    }

    @Override
    public byte[] footerBytes() {
        return new byte[0];
    }

    /**
     * Serialises a single log event to a JSONL byte array.
     *
     * <p>Returns an empty array (and logs an internal error) if JSON generation fails.
     *
     * @param event the Logback logging event to encode
     * @return UTF-8 bytes containing one JSON object followed by {@code \n}
     */
    @Override
    public byte[] encode(final ILoggingEvent event) {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try {
            JsonGenerator gen = JSON_FACTORY.createGenerator(baos);
            writeEventFields(gen, event);
            gen.close();
            baos.write(NEWLINE);
        } catch (IOException e) {
            addError("Failed to encode log event to JSONL", e);
            return "{\"level\":\"ERROR\",\"message\":\"JSON encode failed\"}\n"
                    .getBytes(StandardCharsets.UTF_8);
        }
        return baos.toByteArray();
    }

    /**
     * Writes all JSON fields for the given event into the provided generator.
     *
     * @param gen the open Jackson generator to write into
     * @param event the log event supplying field values
     * @throws IOException if the generator encounters an I/O error
     */
    private static void writeEventFields(final JsonGenerator gen, final ILoggingEvent event)
            throws IOException {
        gen.writeStartObject();
        gen.writeStringField(
                FIELD_TIMESTAMP,
                TIMESTAMP_FORMAT.format(Instant.ofEpochMilli(event.getTimeStamp())));
        gen.writeStringField(FIELD_COMPONENT, ComponentConverter.resolve(event));
        gen.writeStringField(FIELD_LEVEL, event.getLevel().toString());
        gen.writeStringField(FIELD_LOGGER, event.getLoggerName());
        gen.writeStringField(FIELD_THREAD, event.getThreadName());
        gen.writeStringField(FIELD_MESSAGE, event.getFormattedMessage());
        writeException(gen, event.getThrowableProxy());
        gen.writeEndObject();
    }

    /**
     * Writes the {@code exception} field when a throwable proxy is present.
     *
     * <p>The entire stack trace, including cause chains, is written as a single escaped string
     * value so the JSONL record remains one line.
     *
     * @param gen the open Jackson generator to write into
     * @param throwable the throwable proxy, or {@code null} if none
     * @throws IOException if the generator encounters an I/O error
     */
    private static void writeException(final JsonGenerator gen, final IThrowableProxy throwable)
            throws IOException {
        if (throwable != null) {
            gen.writeStringField(FIELD_EXCEPTION, ThrowableProxyUtil.asString(throwable));
        }
    }
}
