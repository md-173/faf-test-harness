package com.faforever.testharness.client.lobby;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.slf4j.LoggerFactory;

/**
 * Unit tests for {@link WelcomeStateSync} — the welcome → {@link SessionState} hydration that
 * chains off the handshake's completion future. Verifies the getter is empty before hydration,
 * populated after, that exactly one "session ready" line is logged (no credential), and that an
 * exceptional welcome future leaves the state unset.
 */
final class WelcomeStateSyncTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private ListAppender<ILoggingEvent> appender;
    private Logger logger;

    @BeforeEach
    void attachAppender() {
        LoggerContext ctx = (LoggerContext) LoggerFactory.getILoggerFactory();
        logger = ctx.getLogger(WelcomeStateSync.class);
        appender = new ListAppender<>();
        appender.setContext(ctx);
        appender.start();
        logger.addAppender(appender);
    }

    @AfterEach
    void detachAppender() {
        if (appender != null) {
            appender.stop();
            logger.detachAppender(appender);
        }
    }

    @Test
    void sessionStateEmptyBeforeWelcome() {
        assertTrue(new WelcomeStateSync().sessionState().isEmpty());
    }

    @Test
    @Timeout(value = 5, unit = TimeUnit.SECONDS)
    void hydratesFromCompletedWelcomeFuture() throws Exception {
        WelcomeStateSync sync = new WelcomeStateSync();
        JsonNode welcome = MAPPER.readTree(loadFixture("lobby/inbound/welcome.json"));

        SessionState returned =
                sync.hydrate(CompletableFuture.completedFuture(welcome)).get(5, TimeUnit.SECONDS);

        assertEquals(3, returned.id());
        assertEquals("test_user", returned.login());
        assertTrue(sync.sessionState().isPresent());
        assertEquals(returned, sync.sessionState().get());
    }

    @Test
    @Timeout(value = 5, unit = TimeUnit.SECONDS)
    void logsExactlyOneSessionReadyLineWithoutCredentials() throws Exception {
        WelcomeStateSync sync = new WelcomeStateSync();
        JsonNode welcome = MAPPER.readTree(loadFixture("lobby/inbound/welcome.json"));

        sync.hydrate(CompletableFuture.completedFuture(welcome)).get(5, TimeUnit.SECONDS);

        List<ILoggingEvent> ready =
                appender.list.stream()
                        .filter(e -> e.getFormattedMessage().startsWith("session ready"))
                        .toList();
        assertEquals(1, ready.size());
        String line = ready.get(0).getFormattedMessage();
        assertTrue(line.contains("id=3"), line);
        assertTrue(line.contains("login=test_user"), line);
        assertFalse(line.toLowerCase().contains("token"), line);
        assertFalse(line.toLowerCase().contains("jwt"), line);
    }

    @Test
    @Timeout(value = 5, unit = TimeUnit.SECONDS)
    void leavesStateUnsetWhenWelcomeFails() {
        WelcomeStateSync sync = new WelcomeStateSync();
        CompletableFuture<JsonNode> failed = new CompletableFuture<>();
        failed.completeExceptionally(new AuthenticationException("auth rejected"));

        assertThrows(ExecutionException.class, () -> sync.hydrate(failed).get(5, TimeUnit.SECONDS));
        assertTrue(sync.sessionState().isEmpty());
        assertTrue(
                appender.list.stream()
                        .noneMatch(e -> e.getFormattedMessage().startsWith("session ready")));
    }

    private static String loadFixture(final String classpathPath) throws Exception {
        Path p =
                Path.of(
                        WelcomeStateSyncTest.class
                                .getClassLoader()
                                .getResource(classpathPath)
                                .toURI());
        return Files.readString(p);
    }
}
