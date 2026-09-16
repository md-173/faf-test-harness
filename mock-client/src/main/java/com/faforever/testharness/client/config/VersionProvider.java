package com.faforever.testharness.client.config;

import picocli.CommandLine.IVersionProvider;

/**
 * Supplies {@code --version} text from the {@code Implementation-Version} attribute that {@code
 * mock-client/build.gradle} writes into the jar manifest (WBS-3.1.5.2, #383).
 *
 * <p>The version used to be a string literal on {@link MockClientCli}'s {@code @Command}. {@code
 * release.yml} builds with {@code -Pversion}, which renames the jar but cannot reach a constant
 * compiled into a class file, so every release reported the build default instead of the version it
 * shipped as. The manifest is written from {@code project.version} at build time, so it is the one
 * place that override lands.
 *
 * <p>{@link Package#getImplementationVersion()} reads the manifest of the jar this class was loaded
 * from, never another jar on the classpath, so picocli's own manifest cannot be picked up. The same
 * lookup is what Neroxis-Map-Generator and downlords-faf-client use. It returns {@code null} when
 * there is no jar at all: {@code ./gradlew run}, the IDE, and every unit test run from class
 * directories. That route prints {@link #DEVELOPMENT_BUILD} rather than throwing, because an
 * exception out of {@link #getVersion()} would turn {@code --version} into a stack trace.
 */
public final class VersionProvider implements IVersionProvider {

    /** Printed when the classes were not loaded from a jar carrying a version. */
    public static final String DEVELOPMENT_BUILD = "mock-client (development build)";

    @Override
    public String[] getVersion() {
        String version = VersionProvider.class.getPackage().getImplementationVersion();
        return new String[] {version == null ? DEVELOPMENT_BUILD : "mock-client " + version};
    }
}
