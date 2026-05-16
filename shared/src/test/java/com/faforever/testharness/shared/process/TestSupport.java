package com.faforever.testharness.shared.process;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

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

    /** Builds a ProcessBuilder that re-invokes the current JVM running {@code mainClass}. */
    static ProcessBuilder forMain(Class<?> mainClass, String... mainArgs) {
        String javaBin = ProcessHandle.current().info().command().orElseThrow();
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
