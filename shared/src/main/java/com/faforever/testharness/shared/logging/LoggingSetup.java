package com.faforever.testharness.shared.logging;

import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.core.ContextBase;
import ch.qos.logback.core.rolling.RollingFileAppender;
import ch.qos.logback.core.rolling.SizeAndTimeBasedRollingPolicy;
import ch.qos.logback.core.rolling.helper.FileNamePattern;
import ch.qos.logback.core.util.FileSize;
import java.util.regex.Pattern;
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
 * <p><b>A component that configures its own level overrides it</b> (WBS-2.3.6-fix, #306). Logback
 * resolves the system property ahead of the environment variable, so a component that writes {@code
 * LOG_LEVEL} as a system property before the first logger exists decides the level for that
 * process, whatever the environment says. Mock Client does exactly that: {@code
 * MockClientCli.applyLoggingProperties} writes its resolved {@code --log-level} — including the
 * built-in default of {@code INFO} — so {@code LOG_LEVEL=DEBUG mock-client run …} produces no
 * {@code DEBUG} records and {@code --log-level} / {@code FAF_MOCK_CLIENT_LOG_LEVEL} is the knob to
 * reach for. Mock Game has no such flag and honours the variable directly. This is the intended
 * split, not an accident: the harness documents a precedence of {@code --log-level} &gt; {@code
 * FAF_MOCK_CLIENT_LOG_LEVEL} &gt; config file &gt; default, and a bare {@code LOG_LEVEL} is
 * Logback's own channel rather than one of those four. Treat this variable as the level for
 * components that configure nothing themselves.
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

    /**
     * Environment variable controlling the minimum log level, for components that do not configure
     * one themselves. Logback reads the system property of the same name first, so a component
     * writing it — Mock Client, from {@code --log-level} — overrides whatever the environment
     * carries. See this class's javadoc.
     */
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

    /**
     * The suffix {@code logback.xml} appends to {@value #LOG_FILE_ENV} to form the rolling
     * appender's {@code fileNamePattern}. Mirrored here so {@link #isUsableLogFile} validates the
     * pattern Logback will actually build; the two must be changed together.
     */
    private static final String ROLLOVER_SUFFIX = ".%d{yyyy-MM-dd}.%i.gz";

    /** Mirrors {@code logback.xml}'s {@code maxFileSize}; see {@link #isUsableLogFile}. */
    private static final String MAX_FILE_SIZE = "10MB";

    private LoggingSetup() {}

    /**
     * Configures logging for the named component.
     *
     * <p>Sets the SLF4J MDC {@value #COMPONENT_MDC_KEY} key so every subsequent log record is
     * tagged with {@code componentName} and sets the JSONL file output name to {@code
     * logs/<componentName>.jsonl}, or {@code logs/<componentName>-<instance>.jsonl} when an
     * instance is named. Logback picks up {@value #LOG_LEVEL_ENV} and {@value #LOG_FILE_ENV} on its
     * own via {@code ${…}} substitution in {@code logback.xml}, resolving the system property of
     * each name ahead of the environment variable — which is what lets a component's own
     * configuration win. See this class's javadoc.
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
        String defaultLogFile =
                "logs/" + componentName.toLowerCase() + fileSuffix(instanceName) + ".jsonl";
        if (System.getenv(LOG_FILE_ENV) == null && System.getProperty(LOG_FILE_ENV) == null) {
            System.setProperty(LOG_FILE_ENV, defaultLogFile);
        }

        // Before anything touches SLF4J. The next statement initialises Logback, and an unusable
        // path takes the whole logging subsystem down with it (WBS-2.3.6-fix, #305).
        replaceUnusableLogFile(defaultLogFile);

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
     * Replaces the configured log file with {@code fallback} when Logback could not use it,
     * reporting the swap on stderr.
     *
     * <p>{@code ${LOG_FILE}} is interpolated into the rolling appender's {@code fileNamePattern},
     * and Logback converts that pattern into a <em>regular expression</em> to find previous
     * rollovers — splicing the operator's raw path in unescaped. A path holding a regex
     * metacharacter therefore fails {@code LoggerContext} initialisation outright: {@code
     * a[b.jsonl} becomes {@code a[b.jsonl.\d{4}-...} and throws {@code PatternSyntaxException}.
     * {@code [}, {@code {} and {@code (} all do it, and all are legal in a filename on Linux and
     * macOS. {@code ${LOG_FILE}} fails the same way, through self-referential substitution.
     *
     * <p>That conversion is not the only way the path can be fatal — a bare {@code %} is a
     * malformed conversion specifier to the pattern parser, and a path carrying its own {@code
     * %d{...}} takes down the rolling policy instead. {@link #isUsableLogFile} covers all three;
     * the notice below deliberately names the effect and offers the usual characters as examples
     * rather than diagnosing which of the three it was.
     *
     * <p>The consequence was worse than a missing file. Logback failed to configure at all, so the
     * run had no logging of any kind — and since every subcommand reports its own failures through
     * the logger, an operator who mistyped a path was told nothing about why the run then failed.
     * The exit code was unaffected, so this never breached the exit-code table; it breached the
     * single-line-diagnostic contract beside it, exactly when the run was already going wrong.
     *
     * <p>Degrading rather than rejecting is the deliberate choice. A bad log path is not a reason
     * to abandon a run, and the operator needs the command's real diagnostic more than they need
     * their preferred filename. Writing to stderr is not a fallback for the logger being absent —
     * it is the only channel available, because this runs before Logback exists.
     *
     * <p>Validation defers to Logback's own conversion rather than a character blacklist, so it
     * stays correct if that conversion changes and does not reject paths Logback would have
     * accepted. Legitimate paths pass, including relative, parent-relative and absolute ones.
     *
     * @param fallback the default path to use when the configured one is unusable.
     */
    private static void replaceUnusableLogFile(final String fallback) {
        String configured = System.getProperty(LOG_FILE_ENV);
        if (configured == null) {
            configured = System.getenv(LOG_FILE_ENV);
        }
        if (configured == null || isUsableLogFile(configured)) {
            return;
        }
        System.err.println(
                "log file path cannot be used by the log rotator and was ignored: "
                        + configured
                        + " (it cannot be used as a log-rotation pattern; characters such as '[',"
                        + " '{', '(' and '%' are not usable here); logging to "
                        + fallback
                        + " instead");
        System.err.flush();
        System.setProperty(LOG_FILE_ENV, fallback);
    }

    /**
     * Whether {@code path} survives everything Logback does with the rolling appender's {@code
     * fileNamePattern}.
     *
     * <p>There are two independent hard-failure sites and both are exercised here. The first is the
     * pattern-to-regex conversion used to find previous rollovers, which a regex metacharacter in
     * the path breaks. The second is starting the rolling policy, which derives the rollover
     * periodicity from the date token — so a path carrying its own {@code %d{...}} (for example
     * {@code logs/%d{yyyy}/app.jsonl}) converts to a regex fine and then dies with {@code Unknown
     * periodicity type}. Checking only the first left that case failing exactly as it did before
     * this guard existed.
     *
     * <p>{@code setMaxFileSize} is load-bearing rather than cosmetic: it is what builds the
     * size-and-time triggering policy that computes the period. {@code maxHistory} and {@code
     * totalSizeCap} change no verdict and are left out.
     *
     * <p>The verdict is the throw and nothing else. Scanning {@link
     * ch.qos.logback.core.status.StatusManager} for errors instead would reject paths that log
     * perfectly well today, {@code a%b.jsonl} and {@code a%X{k}b.jsonl} among them.
     *
     * <p>The suffix and the max file size mirror {@code logback.xml}'s {@code rollingPolicy}
     * exactly; the two must be changed together. Package-private so a test can assert the boundary
     * directly, which {@link UsableLogFileTest} does.
     *
     * @param path the candidate log file path.
     * @return {@code true} if Logback can build and start a rollover policy from it.
     */
    static boolean isUsableLogFile(final String path) {
        String pattern = path + ROLLOVER_SUFFIX;
        try {
            ContextBase context = new ContextBase();
            Pattern.compile(new FileNamePattern(pattern, context).toRegex());

            RollingFileAppender<Object> parent = new RollingFileAppender<>();
            parent.setContext(context);
            parent.setFile(path);
            SizeAndTimeBasedRollingPolicy<Object> policy = new SizeAndTimeBasedRollingPolicy<>();
            policy.setContext(context);
            policy.setParent(parent);
            policy.setFileNamePattern(pattern);
            policy.setMaxFileSize(FileSize.valueOf(MAX_FILE_SIZE));
            policy.start();
            policy.stop();
            return true;
        } catch (RuntimeException e) {
            return false;
        }
    }

    /**
     * The file-name suffix a named instance contributes, or an empty string when none is named.
     *
     * @param instanceName the resolved instance label.
     * @return {@code "-<label>"}, or {@code ""}.
     */
    private static String fileSuffix(final String instanceName) {
        return instanceName.isEmpty() ? "" : "-" + fileSafe(instanceName);
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
