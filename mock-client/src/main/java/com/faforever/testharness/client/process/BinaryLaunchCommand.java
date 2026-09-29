package com.faforever.testharness.client.process;

import com.faforever.testharness.shared.process.ParentDeathSignal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Builds the OS-command prefix that invokes a subprocess launcher's binary correctly: a {@code
 * .jar} path is invoked via {@code java -jar} on the same JRE running the parent (per {@code
 * subprocess-orchestration-spec.md} §2.2); any other path is treated as a directly-executable file.
 * Either is preceded by {@link ParentDeathSignal#prefix()}, which on Linux has the child sent
 * SIGTERM if the JVM that started it dies without running its shutdown hook (spec §7.3).
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
     *     binary]} for a {@code .jar} (case-insensitive extension match), after the parent-death
     *     prefix
     */
    static List<String> commandPrefix(final Path binary) {
        return commandPrefix(binary, List.of());
    }

    /**
     * Returns the OS-command prefix that invokes {@code binary}, inserting {@code jvmArgs}
     * immediately after the resolved {@code java} token for a {@code .jar} so they reach the child
     * JVM rather than the {@code setpriv} in front of it ({@code subprocess-orchestration-spec.md}
     * §7.3). For a native binary {@code jvmArgs} do not apply and are ignored.
     *
     * @param binary the path to the binary to launch
     * @param jvmArgs JVM arguments (e.g. {@code -D...}) for a {@code .jar} launch; ignored for
     *     native
     * @return an immutable list: {@code [binary]} for a native executable, or {@code [java,
     *     jvmArgs..., "-jar", binary]} for a {@code .jar}, after {@link ParentDeathSignal#prefix()}
     */
    static List<String> commandPrefix(final Path binary, final List<String> jvmArgs) {
        List<String> prefix = new ArrayList<>(ParentDeathSignal.prefix());
        if (isJar(binary)) {
            prefix.add(javaBinary());
            prefix.addAll(jvmArgs);
            prefix.add("-jar");
        }
        prefix.add(binary.toString());
        return List.copyOf(prefix);
    }

    /**
     * Returns whether {@code binary} can be started: a {@code .jar}, which runs through {@code
     * java}, or a file this process may execute. The launchers refuse anything else before the
     * start (#378): behind {@link ParentDeathSignal#prefix()} a file that cannot be executed still
     * starts {@code setpriv}, which then exits 126, so the launch would read as a child that died
     * rather than one that never started.
     *
     * @param binary the resolved binary path
     * @return {@code true} if {@code binary} is a {@code .jar} or executable
     */
    static boolean canExecute(final Path binary) {
        return isJar(binary) || Files.isExecutable(binary);
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
