package com.faforever.testharness.client.config;

import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;
import picocli.CommandLine;
import picocli.CommandLine.ParseResult;

/**
 * Loads {@link MockClientConfig} from CLI flags, environment variables, and an optional
 * JSON config file. Precedence (lowest to highest): {@code @Option} built-in defaults,
 * config file, environment variables, CLI flags. Any validation failure surfaces as a
 * {@link CommandLine.ParameterException} listing every issue picocli detected.
 *
 * <p>If {@code --help} or {@code --version} is supplied, the corresponding usage / version
 * text is printed to {@link System#out} and {@link #load} returns an empty {@link Optional}
 * so the caller can exit cleanly with status {@code 0}.
 */
public final class ConfigLoader {

    /** Pre-parsed flag identifying the config file layer. */
    public static final String CONFIG_FLAG = "--config";

    private ConfigLoader() {}

    /**
     * Production entry point. Reads {@code System.getenv()} once and passes the rest
     * through to {@link #load(String[], Map)}.
     *
     * @param args raw command-line arguments
     * @return validated {@link MockClientConfig}, or empty if {@code --help} / {@code
     *     --version} was requested
     * @throws CommandLine.ParameterException if any required field is missing or any
     *     value is malformed
     */
    public static Optional<MockClientConfig> load(final String[] args) {
        return load(args, System.getenv());
    }

    /**
     * Test seam. Caller supplies the env map explicitly; nothing is read from the JVM
     * environment.
     *
     * @param args raw command-line arguments
     * @param env environment map (caller-supplied to keep the loader pure)
     * @return validated {@link MockClientConfig}, or empty if {@code --help} / {@code
     *     --version} was requested
     */
    public static Optional<MockClientConfig> load(
            final String[] args, final Map<String, String> env) {
        Path configFile = preParseConfigFlag(args);
        MockClientCli cli = new MockClientCli();
        CommandLine commandLine =
                new CommandLine(cli)
                        .setDefaultValueProvider(new LayeredDefaultProvider(env, configFile));

        ParseResult result = commandLine.parseArgs(args);

        if (result.isUsageHelpRequested()) {
            commandLine.usage(commandLine.getOut());
            return Optional.empty();
        }
        if (result.isVersionHelpRequested()) {
            commandLine.printVersionHelp(commandLine.getOut());
            return Optional.empty();
        }

        return Optional.of(cli.toConfig());
    }

    /**
     * Picocli's {@link picocli.CommandLine.IDefaultValueProvider} is consulted per-option
     * during parsing, so we need the config-file path resolved <em>before</em> picocli
     * starts. Walk {@code args} once and pull it out.
     */
    private static Path preParseConfigFlag(final String[] args) {
        if (args == null) {
            return null;
        }
        for (int i = 0; i < args.length; i++) {
            String token = args[i];
            if (token != null) {
                if (token.equals(CONFIG_FLAG) && i + 1 < args.length) {
                    return Path.of(args[i + 1]);
                }
                if (token.startsWith(CONFIG_FLAG + "=")) {
                    return Path.of(token.substring(CONFIG_FLAG.length() + 1));
                }
            }
        }
        return null;
    }
}