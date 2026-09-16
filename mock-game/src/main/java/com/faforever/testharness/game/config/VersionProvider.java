package com.faforever.testharness.game.config;

import picocli.CommandLine.IVersionProvider;

/**
 * Supplies {@code --version} text from the {@code Implementation-Version} attribute that {@code
 * mock-game/build.gradle} writes into the jar manifest (WBS-3.1.5.2, #383).
 *
 * <p>Written from {@code project.version}, which {@code release.yml}'s {@code -Pversion} sets, so a
 * release jar reports the version it was cut as; a string literal here could not.
 *
 * <p>{@link Package#getImplementationVersion()} reads the manifest of the jar this class was loaded
 * from, never another jar on the classpath. It returns {@code null} when there is no jar: {@code
 * ./gradlew run}, the IDE, and unit tests run from class directories. That route prints {@link
 * #DEVELOPMENT_BUILD} rather than throwing. The Mock Client has its own copy of this class: the
 * lookup has to anchor on a class inside the application's own jar, since {@code shared} is a
 * separate jar with its own manifest on the {@code installDist} classpath, so a common helper would
 * have to take the anchor class as an argument. Six duplicated lines are cheaper than that.
 */
public final class VersionProvider implements IVersionProvider {

    /** Printed when the classes were not loaded from a jar carrying a version. */
    public static final String DEVELOPMENT_BUILD = "mock-game (development build)";

    @Override
    public String[] getVersion() {
        String version = VersionProvider.class.getPackage().getImplementationVersion();
        return new String[] {version == null ? DEVELOPMENT_BUILD : "mock-game " + version};
    }
}
