package com.faforever.testharness.shared.process;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

/** Shared helpers for SubprocessManager tests. */
final class TestSupport {

    private TestSupport() {}

    /**
     * Builds a ProcessBuilder that re-invokes the current JVM running {@link TestChild} with the
     * given arguments. Cross-platform — never invokes a shell.
     */
    static ProcessBuilder testChild(String... testChildArgs) {
        return forMain(TestChild.class, testChildArgs);
    }

    /**
     * Builds a ProcessBuilder for the fastest-exiting native child available: the {@code true}
     * utility, resolved from {@code PATH}.
     *
     * <p>Not {@link #testChild} — that re-invokes this JVM, and a JVM takes long enough to start
     * that it cannot trigger a race between a child's exit and its registration. This one exits
     * essentially immediately, which is the whole point of the caller.
     *
     * <p>Resolved rather than hardcoded (#227). {@code /bin/true} exists on Linux but not on macOS,
     * where the binary lives at {@code /usr/bin/true} and {@code true} is otherwise a shell
     * builtin, so a hardcoded path failed with {@code Cannot run program "/bin/true": error=2} on
     * every Mac. Searching {@code PATH} covers both, and distributions that put it somewhere else
     * again.
     *
     * @return a builder for a child that exits 0 immediately
     * @throws IllegalStateException if no {@code true} executable is on {@code PATH}
     */
    static ProcessBuilder fastExitingNativeChild() {
        Path binary = findOnPath("true").orElseThrow(TestSupport::noTrueOnPath);
        return new ProcessBuilder(binary.toString());
    }

    /**
     * The failure for a userland with no {@code true} on {@code PATH}. Names the assumption and
     * quotes the PATH searched, because "error=2" told nobody anything for the two years the
     * hardcoded path survived.
     *
     * @return the exception to throw
     */
    private static IllegalStateException noTrueOnPath() {
        return new IllegalStateException(
                "no `true` executable on PATH; this test needs a fast-exiting native binary "
                        + "and assumes a POSIX userland. PATH="
                        + System.getenv("PATH"));
    }

    /**
     * Finds an executable by name on {@code PATH}.
     *
     * @param name the executable's file name
     * @return the first match, or empty if there is none
     */
    private static Optional<Path> findOnPath(final String name) {
        String path = System.getenv("PATH");
        if (path == null) {
            return Optional.empty();
        }
        for (String entry : path.split(File.pathSeparator)) {
            if (entry.isBlank()) {
                continue;
            }
            Path candidate = Path.of(entry).resolve(name);
            if (Files.isExecutable(candidate) && !Files.isDirectory(candidate)) {
                return Optional.of(candidate);
            }
        }
        return Optional.empty();
    }

    /** Builds a ProcessBuilder that re-invokes the current JVM running {@code mainClass}. */
    static ProcessBuilder forMain(Class<?> mainClass, String... mainArgs) {
        // Mirror spec §2.2: command() can be empty on locked-down platforms; fall back to
        // ${java.home}/bin/java so the spec's canonical pattern is the one example everywhere.
        String javaBin =
                ProcessHandle.current()
                        .info()
                        .command()
                        .orElse(System.getProperty("java.home") + "/bin/java");
        String classpath = System.getProperty("java.class.path");
        List<String> cmd = new ArrayList<>();
        cmd.add(javaBin);
        cmd.add("-cp");
        cmd.add(classpath);
        cmd.add(mainClass.getName());
        Collections.addAll(cmd, mainArgs);
        return new ProcessBuilder(cmd);
    }
}
