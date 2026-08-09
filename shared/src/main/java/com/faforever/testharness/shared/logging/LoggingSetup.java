package com.faforever.testharness.shared.logging;

import ch.qos.logback.classic.LoggerContext;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

/**
 * One-call logging initialiser for harness components.
 *
 * <p>Every component (MockClient, MockGame, ICEAdapter subprocess reader) calls {@link
 * #configure(String)} once at startup. This stamps every subsequent log record with the component
 * name, so interleaved output from concurrent processes remains distinguishable.
 *
 * <p><b>Usage:</b>
 *
 * <pre>{@code
 * // In Main.main(), before any logger is created:
 * LoggingSetup.configure("MockClient");
 * Logger log = LoggerFactory.getLogger(MyClass.class);
 * log.info("Started");
 * // Console: [2026-04-17 12:00:00] [MockClient] [INFO ] Started
 * // File:    {"timestamp":"2026-04-17 12:00:00","component":"MockClient",...}
 * }</pre>
 *
 * <p>Log level is read by Logback from the {@value #LOG_LEVEL_ENV} environment variable at
 * config-parse time via {@code ${LOG_LEVEL:-INFO}} in {@code logback.xml} — this class does not
 * apply it programmatically. The default is {@code INFO}.
 *
 * <p>The log file path is read from the {@value #LOG_FILE_ENV} environment variable. The default is
 * {@code logs/test-harness.jsonl}.
 *
 * <p>The optional {@value #INSTANCE_NAME_ENV} environment variable names which instance of a
 * component this process is (WBS-3.1.6.2). Several instances then stay attributable line by line. A
 * named instance also gets its own default log file, {@code logs/<component>-<instance>.jsonl}, so
 * concurrent instances never share one rolling file. An explicit {@value #LOG_FILE_ENV} still wins.
 * When no instance is named nothing changes, neither the default path nor the record shape. See
 * {@code mock-client/README.md} § "Harness log contract".
 */
public final class LoggingSetup {

    /** MDC key written into every log record to identify the source component. */
    public static final String COMPONENT_MDC_KEY = "component";

    /**
     * MDC key written into every log record to identify the instance, when one is configured. It is
     * deliberately separate from {@value #COMPONENT_MDC_KEY}. {@link ProcessOutputLogger} rewrites
     * the component key on every captured subprocess line, which would discard a label folded into
     * it.
     */
    public static final String INSTANCE_MDC_KEY = "instance";

    /** Environment variable controlling the minimum log level for all components. */
    public static final String LOG_LEVEL_ENV = "LOG_LEVEL";

    /** Environment variable controlling the JSONL output file path. */
    public static final String LOG_FILE_ENV = "LOG_FILE";

    /**
     * Environment variable naming this instance of the component. Use a real environment variable
     * rather than a {@code -D} system property. {@code ProcessBuilder} inherits the parent
     * environment, so a spawner's value reaches the mock game this process launches and its own
     * logs self-label with it. A system property is honoured for this JVM only and does not cross a
     * process boundary. The third-party ICE adapter knows nothing of this variable, so its output
     * carries the label only where this process captures it.
     */
    public static final String INSTANCE_NAME_ENV = "INSTANCE_NAME";

    private LoggingSetup() {}

    /**
     * Configures logging for the named component.
     *
     * <p>Sets the SLF4J MDC {@value #COMPONENT_MDC_KEY} key so every subsequent log record is
     * tagged with {@code componentName} and sets the JSONL file output name to {@code
     * logs/<componentName>.jsonl}, or {@code logs/<componentName>-<instance>.jsonl} when an
     * instance is named. Logback picks up {@value #LOG_LEVEL_ENV} and {@value #LOG_FILE_ENV} on its
     * own via {@code ${…}} substitution in {@code logback.xml}.
     *
     * @param componentName label that appears in every log line, e.g. {@code "MockClient"} or
     *     {@code "MockGame"}
     */
    public static void configure(final String componentName) {

        String instanceName = resolveInstanceName();

        // Must run in a static call in every Main class so that ${LOG_FILE} in logback.xml picks
        // it up. An explicit env var or -D overrides this.
        //
        // A named instance gets its own default file. Without this, two concurrently running
        // instances of a component would share one rolling file and contend on rollover, which
        // matters most for the subprocesses a client launches: LOG_FILE is not forwarded to them
        // (see MockGameLauncher), so every mock game would otherwise land in logs/mockgame.jsonl.
        if (System.getenv(LOG_FILE_ENV) == null && System.getProperty(LOG_FILE_ENV) == null) {
            String suffix = instanceName.isEmpty() ? "" : "-" + fileSafe(instanceName);
            System.setProperty(
                    LOG_FILE_ENV, "logs/" + componentName.toLowerCase() + suffix + ".jsonl");
        }

        MDC.put(COMPONENT_MDC_KEY, componentName);

        // MDC is thread-local, so async workers (e.g. WebSocket threads) that never inherit
        // it would otherwise render as [Unknown]. Each JVM runs exactly one component, so
        // store the same label in the LoggerContext property map as a JVM-wide fallback —
        // resolved by ComponentConverter and JsonLineEncoder when the MDC is empty.
        LoggerContext context = (LoggerContext) LoggerFactory.getILoggerFactory();
        context.putProperty(COMPONENT_MDC_KEY, componentName);

        // Same two-place treatment for the instance label (WBS-3.1.6.2). The context property is
        // load-bearing rather than a nicety here: logback 1.5's MDC is a plain ThreadLocal, so the
        // subprocess capture threads in ProcessOutputLogger and the adapter's reader thread never
        // see the value put above, and those are exactly the lines a multi-instance harness needs
        // to attribute.
        if (!instanceName.isEmpty()) {
            MDC.put(INSTANCE_MDC_KEY, instanceName);
            context.putProperty(INSTANCE_MDC_KEY, instanceName);
        }
    }

    /**
     * Reduces a label to characters that are safe in a file name, so an instance name only ever
     * contributes a single path segment. Anything outside letters, digits, dot, underscore and
     * hyphen becomes a hyphen. Only the file name is affected. Log records always carry the label
     * exactly as supplied.
     *
     * @param instanceName the raw label
     * @return the label with unsafe characters replaced
     */
    private static String fileSafe(final String instanceName) {
        return instanceName.replaceAll("[^A-Za-z0-9._-]", "-");
    }

    /**
     * Resolves this process's instance label. A {@code -D} system property wins over the
     * environment variable, matching the precedence Logback itself applies to {@value
     * #LOG_FILE_ENV} and letting a future {@code --instance-name} flag set the property without
     * changing this class.
     *
     * @return the trimmed label, or an empty string when neither source supplies a non-blank value
     */
    static String resolveInstanceName() {
        String fromProperty = System.getProperty(INSTANCE_NAME_ENV);
        if (fromProperty != null && !fromProperty.isBlank()) {
            return fromProperty.trim();
        }
        String fromEnv = System.getenv(INSTANCE_NAME_ENV);
        if (fromEnv != null && !fromEnv.isBlank()) {
            return fromEnv.trim();
        }
        return "";
    }

    /**
     * Flushes and stops the logging subsystem. Stopping the Logback {@link LoggerContext} stops
     * every appender, which flushes and closes their output (e.g. the JSONL file handle), so
     * buffered records are written before the JVM exits.
     *
     * <p>Intended as the <em>last</em> step of a component's shutdown sequence: no further log
     * output is produced afterwards. Idempotent — stopping an already-stopped context is a no-op.
     */
    public static void shutdown() {
        shutdown((LoggerContext) LoggerFactory.getILoggerFactory());
    }

    /**
     * Stops a specific {@link LoggerContext}. Package-private overload so tests can exercise the
     * flush/stop behaviour against a throwaway context without tearing down the JVM-global one.
     *
     * @param context the logger context to flush and stop
     */
    static void shutdown(final LoggerContext context) {
        context.stop();
    }
}
