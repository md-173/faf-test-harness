package com.faforever.testharness.client.process;

import java.nio.file.Path;
import java.util.List;
import java.util.Locale;

/**
 * Builds the OS-command prefix that invokes a subprocess launcher's binary correctly: a {@code
 * .jar} path is invoked via {@code java -jar} on the same JRE running the parent (per {@code
 * subprocess-orchestration-spec.md} §2.2); any other path is treated as a directly-executable file.
 *
 * <p>Shared by {@link IceAdapterLauncher} and {@link MockGameLauncher} so the JAR-vs-native
 * detection and {@code java} resolution live in one place.
 */
final class BinaryLaunchCommand {

    private BinaryLaunchCommand() {}

    /**
     * Returns the OS-command prefix that invokes {@code binary}.
     *
     * @param binary the path to the binary to launch
     * @return an immutable list: {@code [binary]} for a native executable, or {@code [java, "-jar",
     *     binary]} for a {@code .jar} (case-insensitive extension match)
     */
    static List<String> commandPrefix(final Path binary) {
        if (isJar(binary)) {
            return List.of(javaBinary(), "-jar", binary.toString());
        }
        return List.of(binary.toString());
    }

    /**
     * Returns whether {@code binary} is a Java archive that must be launched via {@code java -jar}.
     *
     * @param binary the binary path
     * @return {@code true} if the file name ends in {@code .jar} (case-insensitive)
     */
    private static boolean isJar(final Path binary) {
        return binary.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".jar");
    }

    /**
     * Resolves the {@code java} executable, mirroring spec §2.2: prefer the JRE running the parent,
     * fall back to {@code ${java.home}/bin/java} when the OS withholds the command path.
     *
     * @return an absolute path to a {@code java} binary
     */
    private static String javaBinary() {
        return ProcessHandle.current()
                .info()
                .command()
                .orElse(System.getProperty("java.home") + "/bin/java");
    }
}
