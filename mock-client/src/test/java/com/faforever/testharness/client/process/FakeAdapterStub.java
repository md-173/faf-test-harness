package com.faforever.testharness.client.process;

import static org.junit.jupiter.api.Assertions.assertTrue;

import com.faforever.testharness.client.config.IceAdapterSettings;
import java.io.IOException;
import java.net.ServerSocket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Optional;
import java.util.OptionalInt;

/**
 * A generated executable standing in for the {@code faf-ice-adapter} binary, plus the ports it was
 * given. Shared by the {@link IceReachabilityCheck} tests and the {@code ice-smoke} CLI tests.
 *
 * <p>The stub is a shell script that execs {@link FakeIceAdapter} on the test JVM's classpath. The
 * launcher runs any non-{@code .jar} path directly, so the real launch path — argv construction,
 * subprocess spawn, output capture, teardown — is exercised, with only the binary swapped.
 *
 * @param settings adapter settings naming the stub and the ports it will serve
 */
public record FakeAdapterStub(IceAdapterSettings settings) {

    /**
     * Writes a stub for {@code mode} under {@code dir} and allocates its ports.
     *
     * @param dir directory to write the script into, typically a JUnit {@code @TempDir}
     * @param mode which parts of a healthy adapter the fake should imitate
     * @return the stub and its settings
     * @throws IOException if the script cannot be written
     */
    public static FakeAdapterStub create(final Path dir, final FakeIceAdapter.Mode mode)
            throws IOException {
        Path javaBinary = Path.of(System.getProperty("java.home"), "bin", "java");
        Path script = dir.resolve("fake-ice-adapter-" + mode.name().toLowerCase(Locale.ROOT));
        Files.writeString(
                script,
                "#!/bin/sh\n"
                        + "exec \""
                        + javaBinary.toAbsolutePath()
                        + "\" -cp \""
                        + System.getProperty("java.class.path")
                        + "\" "
                        + FakeIceAdapter.class.getName()
                        + " --mode "
                        + mode.name()
                        + " \"$@\"\n");
        assertTrue(script.toFile().setExecutable(true), "could not mark stub executable");
        return new FakeAdapterStub(settingsFor(script, freePorts()));
    }

    /**
     * Adapter settings for {@code binary} on {@code ports}, everything else at its default.
     *
     * @param binary path the launcher should execute
     * @param ports JSON-RPC, GPGNet, and lobby ports, in that order
     * @return the settings
     */
    public static IceAdapterSettings settingsFor(final Path binary, final int[] ports) {
        return new IceAdapterSettings(
                binary,
                ports[0],
                ports[1],
                ports[2],
                0,
                OptionalInt.of(1),
                "mock-client",
                "INFO",
                Optional.empty());
    }

    /**
     * Three distinct free ports (JSON-RPC, GPGNet, lobby), held open together so the OS hands out
     * different numbers, then released before the adapter binds them.
     *
     * <p>That release leaves a TOCTOU window, the same one {@code
     * IceAdapterConnectionLiveSmokeTest} accepts and documents: if something else claimed one of
     * these numbers in the moments after this returns, the check reports {@code PORTS_IN_USE} and
     * the test fails. There is no way to both hold a port and let the subprocess under test bind
     * it, and the OS does not reissue an ephemeral port that quickly, so the window is accepted
     * rather than papered over. It is not the {@code #287} pattern — nothing here asserts that a
     * released port stays unowned.
     *
     * @return the three port numbers
     */
    public static int[] freePorts() {
        try (ServerSocket rpc = new ServerSocket(0);
                ServerSocket gpgNet = new ServerSocket(0);
                ServerSocket lobby = new ServerSocket(0)) {
            return new int[] {rpc.getLocalPort(), gpgNet.getLocalPort(), lobby.getLocalPort()};
        } catch (IOException e) {
            throw new IllegalStateException("could not allocate free ports", e);
        }
    }

    /**
     * The stub script's path, for callers that pass it as {@code --ice-adapter-binary-path}.
     *
     * @return the executable's path
     */
    public Path binaryPath() {
        return settings.binaryPath();
    }

    /**
     * The JSON-RPC port this stub serves.
     *
     * @return the port number
     */
    public int rpcPort() {
        return settings.rpcPort();
    }

    /**
     * The GPGNet port this stub serves.
     *
     * @return the port number
     */
    public int gpgNetPort() {
        return settings.gpgNetPort();
    }

    /**
     * The lobby (UDP) port this stub is configured with.
     *
     * @return the port number
     */
    public int lobbyPort() {
        return settings.lobbyPort();
    }
}
