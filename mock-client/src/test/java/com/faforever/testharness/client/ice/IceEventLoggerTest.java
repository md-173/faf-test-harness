package com.faforever.testharness.client.ice;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.faforever.testharness.shared.logging.JsonLineEncoder;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

/**
 * Tests for {@link IceEventLogger} against the in-process {@link ScriptedJsonRpcServer}, so
 * notifications travel the real JSON-RPC path and the handler registration itself is exercised. A
 * handler bound to the wrong method name is the regression most worth catching, and calling the
 * handlers directly would not catch it.
 *
 * <p>Assertions parse records encoded by the real {@link JsonLineEncoder} rather than matching
 * console text, pinning the harness log contract (WBS-3.1.6.2).
 */
final class IceEventLoggerTest {

    /** Parses the encoded JSONL records. */
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** Encodes captured events exactly as the file appender would. */
    private static final JsonLineEncoder ENCODER = new JsonLineEncoder();

    private ScriptedJsonRpcServer server;
    private IceAdapterConnection conn;
    private ListAppender<ILoggingEvent> appender;
    private Logger logger;

    @BeforeEach
    void setUp() throws Exception {
        server = new ScriptedJsonRpcServer();
        server.start();

        LoggerContext context = (LoggerContext) LoggerFactory.getILoggerFactory();
        logger = context.getLogger(IceEventLogger.class);
        appender = new ListAppender<>();
        // Handlers run on the adapter's reader thread, so the default ArrayList would be mutated
        // while the test thread iterates it.
        appender.list = new CopyOnWriteArrayList<>();
        appender.setContext(context);
        appender.start();
        logger.addAppender(appender);
    }

    @AfterEach
    void tearDown() {
        if (appender != null) {
            appender.stop();
            logger.detachAppender(appender);
        }
        if (conn != null) {
            conn.close();
        }
        if (server != null) {
            server.stop();
        }
    }

    /** Connects to the fixture with retry and timeout tuned short, and starts the logger. */
    private void connectAndStart() throws Exception {
        conn =
                new IceAdapterConnection(
                        server.port(), 5, Duration.ofMillis(20), Duration.ofSeconds(2));
        new IceEventLogger(conn).start();
        conn.connect().get(5, TimeUnit.SECONDS);
        server.awaitClient();
    }

    /**
     * Waits for a record whose message starts with {@code prefix} and returns it, parsed from the
     * encoder's own JSONL output.
     *
     * @param prefix the contract prefix to wait for
     * @return the parsed record
     * @throws InterruptedException if the wait is interrupted
     */
    private JsonNode awaitRecord(final String prefix) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() < deadline) {
            for (JsonNode record : records()) {
                if (record.get("message").asText().startsWith(prefix)) {
                    return record;
                }
            }
            Thread.sleep(20);
        }
        throw new AssertionError("no record starting with '" + prefix + "' in " + messages());
    }

    /**
     * Encodes every captured event as JSONL and parses it back.
     *
     * @return one parsed record per captured event
     */
    private List<JsonNode> records() {
        return appender.list.stream()
                .map(event -> new String(ENCODER.encode(event), StandardCharsets.UTF_8))
                .map(IceEventLoggerTest::parse)
                .toList();
    }

    /**
     * Parses one encoded record, failing the test if the encoder did not produce valid JSONL.
     *
     * @param line the encoded record, including its trailing newline
     * @return the parsed JSON object
     */
    private static JsonNode parse(final String line) {
        assertTrue(line.endsWith("\n"), "each record must be one line: " + line);
        try {
            return MAPPER.readTree(line);
        } catch (IOException e) {
            throw new AssertionError("record is not valid JSON: " + line, e);
        }
    }

    /**
     * The {@code message} values of every captured record, in order.
     *
     * @return the messages a harness would read
     */
    private List<String> messages() {
        return records().stream().map(record -> record.get("message").asText()).toList();
    }

    @Test
    void reportsTheGpgNetLinkStateChange() throws Exception {
        connectAndStart();

        server.send(
                "{\"jsonrpc\":\"2.0\",\"method\":\"onConnectionStateChanged\","
                        + "\"params\":[\"Connected\"]}");

        assertEquals(
                "gpgnet link: state=Connected",
                awaitRecord("gpgnet link:").get("message").asText());
    }

    @Test
    void reportsPeerIceStateTransitions() throws Exception {
        connectAndStart();

        server.send(
                "{\"jsonrpc\":\"2.0\",\"method\":\"onIceConnectionStateChanged\","
                        + "\"params\":[1,2,\"checking\"]}");

        assertEquals(
                "peer ice: local=1 remote=2 state=checking",
                awaitRecord("peer ice:").get("message").asText(),
                "the transitions Phase 5 delayed-negotiation tests measure");
    }

    @Test
    void reportsPlayerIdsBeyondIntRange() throws Exception {
        connectAndStart();

        server.send(
                "{\"jsonrpc\":\"2.0\",\"method\":\"onIceConnectionStateChanged\","
                        + "\"params\":[4294967296,2,\"connected\"]}");

        assertEquals(
                "peer ice: local=4294967296 remote=2 state=connected",
                awaitRecord("peer ice:").get("message").asText(),
                "the adapter's RPCService declares these ids as long, not int");
    }

    @Test
    void reportsThePeerConnectedVerdict() throws Exception {
        connectAndStart();

        server.send("{\"jsonrpc\":\"2.0\",\"method\":\"onConnected\",\"params\":[1,2,true]}");

        assertEquals(
                "peer connected: local=1 remote=2 connected=true",
                awaitRecord("peer connected:").get("message").asText());
    }

    @Test
    void dropsMalformedNotificationsWithAWarning() throws Exception {
        connectAndStart();

        server.send(
                "{\"jsonrpc\":\"2.0\",\"method\":\"onConnected\",\"params\":[1]}"
                        + "{\"jsonrpc\":\"2.0\",\"method\":\"onIceConnectionStateChanged\","
                        + "\"params\":[]}"
                        + "{\"jsonrpc\":\"2.0\",\"method\":\"onConnectionStateChanged\","
                        + "\"params\":[7]}");

        JsonNode dropped = awaitRecord("dropping malformed onConnectionStateChanged");
        assertEquals("WARN", dropped.get("level").asText());
        assertEquals(
                3,
                messages().stream().filter(m -> m.startsWith("dropping malformed")).count(),
                "every malformed shape is dropped, not just the last");
        assertTrue(
                messages().stream().noneMatch(m -> m.startsWith("peer ")),
                "a malformed notification must not produce a contract line");
    }

    @Test
    void startIsSingleUse() throws Exception {
        conn =
                new IceAdapterConnection(
                        server.port(), 5, Duration.ofMillis(20), Duration.ofSeconds(2));
        IceEventLogger eventLogger = new IceEventLogger(conn);
        eventLogger.start();

        assertThrows(
                IllegalStateException.class,
                eventLogger::start,
                "a second start would log every notification twice");
    }
}
