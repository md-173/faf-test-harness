package com.faforever.testharness.client.process;

import java.nio.file.Path;
import java.util.ArrayList;
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
     * Returns the OS-command prefix that invokes {@code binary}, with no extra JVM arguments.
     *
     * @param binary the path to the binary to launch
     * @return an immutable list: {@code [binary]} for a native executable, or {@code [java, "-jar",
     *     binary]} for a {@code .jar} (case-insensitive extension match)
     */
    static List<String> commandPrefix(final Path binary) {
        return commandPrefix(binary, List.of());
    }

    /**
     * Returns the OS-command prefix that invokes {@code binary}, inserting {@code jvmArgs}
     * immediately after the resolved {@code java} token for a {@code .jar} so they reach the child
     * JVM rather than whatever may eventually wrap the launch (e.g. a {@code setpriv}/{@code
     * setsid} prefix per {@code subprocess-orchestration-spec.md} §7.3). For a native binary {@code
     * jvmArgs} do not apply and are ignored.
     *
     * @param binary the path to the binary to launch
     * @param jvmArgs JVM arguments (e.g. {@code -D...}) for a {@code .jar} launch; ignored for
     *     native
     * @return an immutable list: {@code [binary]} for a native executable, or {@code [java,
     *     jvmArgs..., "-jar", binary]} for a {@code .jar}
     */
    static List<String> commandPrefix(final Path binary, final List<String> jvmArgs) {
        if (isJar(binary)) {
            List<String> prefix = new ArrayList<>();
            prefix.add(javaBinary());
            prefix.addAll(jvmArgs);
            prefix.add("-jar");
            prefix.add(binary.toString());
            return List.copyOf(prefix);
        }
        return List.of(binary.toString());
    }

    /**
     * Returns whether {@code binary} is a Java archive that must be launched via {@code java -jar}.
     *
     * @param binary the binary path
     * @return {@code true} if the file name ends in {@code .jar} (case-insensitive)
     */
    static boolean isJar(final Path binary) {
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
