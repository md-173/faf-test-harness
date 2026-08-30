package com.faforever.testharness.client.config;

import com.faforever.testharness.client.cli.ExecutionExceptionHandler;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;
import picocli.CommandLine;
import picocli.CommandLine.ParseResult;

/**
 * Loads {@link MockClientConfig} from CLI flags, environment variables, and an optional JSON config
 * file. Precedence (lowest to highest): {@code @Option} built-in defaults, config file, environment
 * variables, CLI flags. Any validation failure surfaces as a {@link CommandLine.ParameterException}
 * listing every issue picocli detected.
 *
 * <p>This class exposes two layers:
 *
 * <ul>
 *   <li>{@link #newCommandLine(String[], Map)} — builds the {@link CommandLine} with the {@link
 *       LayeredDefaultProvider} and the {@link ExecutionExceptionHandler} attached. Used by {@code
 *       Main} to drive {@link CommandLine#execute(String...)}, and by tests that want to exercise
 *       the subcommand tree.
 *   <li>{@link #load(String[], Map)} — parses {@code args} and returns a validated config (or
 *       {@link Optional#empty()} on {@code --help}/{@code --version}). The headless test seam used
 *       by all existing {@code ConfigLoader*Test} classes — its contract is stable and must not
 *       change.
 * </ul>
 *
 * <p>If {@code --help} or {@code --version} is supplied, the corresponding usage / version text is
 * printed to picocli's configured output stream (defaults to {@link System#out}) and {@link
 * #load(String[], Map)} returns an empty {@link Optional} so the caller can exit cleanly with
 * status {@code 0}.
 */
public final class ConfigLoader {

    /** Pre-parsed flag identifying the config file layer. */
    public static final String CONFIG_FLAG = "--config";

    private ConfigLoader() {}

    /**
     * Production entry point. Reads {@code System.getenv()} once and passes the rest through to
     * {@link #load(String[], Map)}.
     *
     * @param args raw command-line arguments
     * @return validated {@link MockClientConfig}, or empty if {@code --help} / {@code --version}
     *     was requested
     * @throws CommandLine.ParameterException if any required field is missing or any value is
     *     malformed
     */
    public static Optional<MockClientConfig> load(final String[] args) {
        return load(args, System.getenv());
    }

    /**
     * Test seam. Caller supplies the env map explicitly; nothing is read from the JVM environment.
     *
     * @param args raw command-line arguments
     * @param env environment map (caller-supplied to keep the loader pure)
     * @return validated {@link MockClientConfig}, or empty if {@code --help} / {@code --version}
     *     was requested
     */
    public static Optional<MockClientConfig> load(
            final String[] args, final Map<String, String> env) {
        CommandLine commandLine = newCommandLine(args, env);
        ParseResult result = commandLine.parseArgs(args);

        if (result.isUsageHelpRequested()) {
            commandLine.usage(commandLine.getOut());
            return Optional.empty();
        }
        if (result.isVersionHelpRequested()) {
            commandLine.printVersionHelp(commandLine.getOut());
            return Optional.empty();
        }

        MockClientCli cli = commandLine.getCommand();
        return Optional.of(cli.toValidatedConfig(commandLine.getCommandSpec()));
    }

    /**
     * Build a fresh {@link CommandLine} around a fresh {@link MockClientCli} root, with the layered
     * env+file default-value provider attached. The returned instance is suitable both for {@link
     * CommandLine#parseArgs(String...)} (the headless path used by {@link #load}) and for {@link
     * CommandLine#execute(String...)} (the production path used by {@code Main}). Each call returns
     * a new instance — picocli command instances carry parsed state.
     *
     * @param args raw command-line arguments (used only for the {@code --config} pre-parse)
     * @param env environment map consulted by the default-value provider
     * @return a configured {@link CommandLine} ready for {@code parseArgs} or {@code execute}
     * @throws CommandLine.ParameterException if the {@code --config} file is supplied but cannot be
     *     read or parsed
     */
    public static CommandLine newCommandLine(final String[] args, final Map<String, String> env) {
        MockClientCli cli = new MockClientCli();
        CommandLine commandLine = new CommandLine(cli);

        // Installed here rather than in Main so every caller of this factory — Main, and the
        // exit-code tests that drive execute() directly — observes the same failure shape. It is
        // inert on the parseArgs path used by load().
        commandLine.setExecutionExceptionHandler(new ExecutionExceptionHandler());

        try {
            Path configFile = preParseConfigFlag(args);
            commandLine.setDefaultValueProvider(new LayeredDefaultProvider(env, configFile));
        } catch (IllegalArgumentException e) {
            throw new CommandLine.ParameterException(commandLine, e.getMessage(), e);
        }

        return commandLine;
    }

    /**
     * Picocli's {@link picocli.CommandLine.IDefaultValueProvider} is consulted per-option during
     * parsing, so we need the config-file path resolved <em>before</em> picocli starts. Walk {@code
     * args} once and pull it out. The pre-parser ignores subcommand boundaries, so {@code --config}
     * may appear before or after a subcommand name on the command line.
     *
     * @param args raw command-line arguments
     * @return the path supplied to {@code --config}, or {@code null} if absent
     */
    private static Path preParseConfigFlag(final String[] args) {
        if (args == null) {
            return null;
        }
        for (int i = 0; i < args.length; i++) {
            String token = args[i];
            if (token != null) {
                if (token.equals(CONFIG_FLAG) && i + 1 < args.length) {
                    return toPath(args[i + 1]);
                }
                if (token.startsWith(CONFIG_FLAG + "=")) {
                    return toPath(token.substring(CONFIG_FLAG.length() + 1));
                }
            }
        }
        return null;
    }

    /**
     * Converts a {@code --config} value to a {@link Path}, turning a syntactically invalid one into
     * the {@link IllegalArgumentException} the caller already maps to a usage error. {@link
     * java.nio.file.InvalidPathException} is itself an {@code IllegalArgumentException}, but its
     * message names neither the flag nor the whole value, and on Windows the reserved characters
     * {@code < > : " | ? *} make this reachable from an ordinary shell.
     *
     * @param value the raw {@code --config} argument
     * @return the parsed path
     * @throws IllegalArgumentException if {@code value} is not a valid path on this platform
     */
    private static Path toPath(final String value) {
        try {
            return Path.of(value);
        } catch (InvalidPathException e) {
            throw new IllegalArgumentException(
                    "invalid "
                            + CONFIG_FLAG
                            + " path: "
                            + LayeredDefaultProvider.oneLine(value)
                            + " ("
                            + e.getReason()
                            + ")",
                    e);
        }
    }
}
