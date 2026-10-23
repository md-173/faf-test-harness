package com.faforever.testharness.shared.process;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.lang.ProcessBuilder.Redirect;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.CodeSource;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Layer 2 of subprocess-orchestration-spec §7.3 wherever {@link ParentDeathSignal} has no prefix:
 * macOS, Windows, and a Linux host without a usable setpriv (#508). A JVM killed there runs no
 * shutdown hook and the kernel signals none of its children, so a separate process ends them.
 *
 * <p>The first child {@link #watch} is given starts the reaper, a JVM running {@link #main}. Its
 * stdin is a pipe from this JVM, which the OS closes however this JVM ends, so the reaper reads end
 * of file only once this JVM is gone. It then ends every child it was sent as {@link
 * SubprocessManager#terminate} would: {@link ProcessHandle#destroy()} (SIGTERM, or TerminateProcess
 * on Windows), up to {@link #GRACE}, then {@link ProcessHandle#destroyForcibly()}.
 *
 * <p>Each child is sent as its PID and start time, and the reaper keeps a handle only to a process
 * with that PID that started then, so it never ends one that took the PID of a child that had
 * exited. From then on the handle itself refuses a reused PID. The two JVMs can disagree on a start
 * time by whole seconds, though: on Linux the JDK dates a process from the boot time each JVM reads
 * once, which moves whenever the wall clock is stepped. So the first line is the reaper's own start
 * time as this JVM sees it, and the reaper shifts every later line by the difference from its own
 * view.
 */
final class OrphanReaper {

    /**
     * Longest the reaper waits for its children to exit before forcing them: both launchers' grace.
     */
    private static final Duration GRACE = Duration.ofSeconds(5);

    /** How often the reaper checks, during {@link #GRACE}, whether its children have exited. */
    private static final Duration POLL = Duration.ofMillis(50);

    /**
     * Environment variables a JVM reads options from before {@code main}, removed from the
     * reaper's. It needs none, and one could stop it: a {@code -cp} there overrides {@code
     * CLASSPATH}, and a debugger waiting to attach would park it.
     */
    private static final List<String> JVM_OPTION_VARIABLES =
            List.of("JAVA_TOOL_OPTIONS", "JDK_JAVA_OPTIONS", "_JAVA_OPTIONS");

    private OrphanReaper() {}

    /**
     * Hands {@code child} to this JVM's reaper, starting the reaper on the first call. Does nothing
     * where {@link ParentDeathSignal#prefix()} is not empty, for a child that has already exited,
     * and after the reaper could not be started or written to, which logs one WARN. Never throws.
     *
     * @param child a child this JVM has just started
     */
    static void watch(final Process child) {
        if (ParentDeathSignal.prefix().isEmpty()) {
            Link.send(child);
        }
    }

    /**
     * The reaper process. Reads the header and the children's lines until end of file, then ends
     * the children that are still running. Uses JDK classes only: its classpath holds this class's
     * code source alone, without slf4j.
     *
     * @param args ignored
     */
    public static void main(final String[] args) {
        List<ProcessHandle> children = new ArrayList<>();
        try (BufferedReader in =
                new BufferedReader(new InputStreamReader(System.in, StandardCharsets.US_ASCII))) {
            String header = in.readLine();
            Optional<Instant> self = ProcessHandle.current().info().startInstant();
            if (header != null && self.isPresent()) {
                long offset = self.get().toEpochMilli() - Long.parseLong(header);
                String line;
                while ((line = in.readLine()) != null) {
                    child(line, offset).ifPresent(children::add);
                }
            }
        } catch (IOException | NumberFormatException e) {
            // A failed read means the harness JVM is gone as surely as end of file does, and a
            // garbled header leaves no line that could be trusted.
        }
        end(children);
    }

    /**
     * Parses one {@code <pid> <start>} line.
     *
     * @param line the line, start time in epoch milliseconds as the harness JVM saw it
     * @param offset what to add to that start time to get this JVM's view of it
     * @return a handle to the process, if one with that PID started at that time
     */
    private static Optional<ProcessHandle> child(final String line, final long offset) {
        String[] fields = line.split(" ");
        if (fields.length != 2) {
            return Optional.empty();
        }
        try {
            long pid = Long.parseLong(fields[0]);
            long started = Long.parseLong(fields[1]) + offset;
            return ProcessHandle.of(pid)
                    .filter(
                            handle ->
                                    handle.info()
                                            .startInstant()
                                            .map(start -> start.toEpochMilli() == started)
                                            .orElse(false));
        } catch (NumberFormatException e) {
            return Optional.empty();
        }
    }

    /**
     * Ends {@code children}: {@link ProcessHandle#destroy()}, up to {@link #GRACE} for them to
     * exit, then {@link ProcessHandle#destroyForcibly()} on any still running.
     *
     * @param children the processes to end
     */
    private static void end(final List<ProcessHandle> children) {
        children.forEach(ProcessHandle::destroy);
        long deadline = System.nanoTime() + GRACE.toNanos();
        while (children.stream().anyMatch(ProcessHandle::isAlive) && System.nanoTime() < deadline) {
            try {
                Thread.sleep(POLL.toMillis());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        children.stream().filter(ProcessHandle::isAlive).forEach(ProcessHandle::destroyForcibly);
    }

    /**
     * This JVM's end of the pipe, in a class of its own so the reaper's JVM, which never uses it,
     * never loads slf4j.
     */
    private static final class Link {

        /** Diagnostic logger, for the one line saying the reaper is not running. */
        private static final Logger LOG = LoggerFactory.getLogger(OrphanReaper.class);

        /**
         * The reaper, once started. Held for the life of the JVM: its stdin is the pipe, and a
         * stream nothing references could be closed by a cleaner, which the reaper would read as
         * this JVM's death.
         */
        private static Process reaper;

        /** Set once the reaper could not be started or written to; later calls do nothing. */
        private static boolean failed;

        private Link() {}

        /**
         * Sends {@code child}'s line, starting the reaper first if this is the first child.
         *
         * @param child the child to send
         */
        static synchronized void send(final Process child) {
            if (failed) {
                return;
            }
            Optional<Instant> started = child.toHandle().info().startInstant();
            if (started.isEmpty()) {
                return;
            }
            try {
                if (reaper == null) {
                    reaper = start();
                }
                write(reaper, child.pid() + " " + started.get().toEpochMilli());
            } catch (IOException | RuntimeException e) {
                failed = true;
                LOG.warn(
                        "Could not hand pid {} to the orphan reaper ({}); a hard kill of this JVM"
                                + " will leave its children running",
                        child.pid(),
                        e.toString());
            }
        }

        /**
         * Starts the reaper and writes its header.
         *
         * @return the started reaper
         * @throws IOException if it cannot be started, has already exited, or cannot be written to
         */
        private static Process start() throws IOException {
            ProcessBuilder pb =
                    new ProcessBuilder(javaBinary(), OrphanReaper.class.getName())
                            .redirectOutput(Redirect.DISCARD)
                            .redirectError(Redirect.DISCARD);
            Map<String, String> environment = pb.environment();
            // Not -cp: a path in the argv could hold an adapter's or game's file name, and the
            // session's teardown counts any descendant whose command line does as a survivor.
            environment.put("CLASSPATH", codeSource());
            JVM_OPTION_VARIABLES.forEach(environment::remove);
            Process started = pb.start();
            try {
                Instant reaperStart =
                        started.toHandle()
                                .info()
                                .startInstant()
                                .orElseThrow(() -> new IOException("the reaper exited at once"));
                write(started, Long.toString(reaperStart.toEpochMilli()));
                return started;
            } catch (IOException e) {
                started.destroyForcibly();
                throw e;
            }
        }

        /**
         * Writes one line to the reaper and flushes it.
         *
         * @param to the reaper
         * @param line the line, without its newline
         * @throws IOException if the reaper's stdin is closed
         */
        private static void write(final Process to, final String line) throws IOException {
            to.getOutputStream().write((line + "\n").getBytes(StandardCharsets.US_ASCII));
            to.getOutputStream().flush();
        }

        /**
         * Resolves the {@code java} executable as the launchers do (spec §2.2): the one running
         * this JVM, or {@code ${java.home}/bin/java} if the OS withholds its path.
         *
         * @return a path to a {@code java} binary
         */
        private static String javaBinary() {
            return ProcessHandle.current()
                    .info()
                    .command()
                    .orElse(System.getProperty("java.home") + "/bin/java");
        }

        /**
         * The jar or directory this class was loaded from, the reaper's whole classpath.
         *
         * @return its path
         * @throws IOException if the class has no code source with a file path
         */
        private static String codeSource() throws IOException {
            CodeSource source = OrphanReaper.class.getProtectionDomain().getCodeSource();
            if (source == null) {
                throw new IOException("no code source for " + OrphanReaper.class.getName());
            }
            try {
                return Path.of(source.getLocation().toURI()).toString();
            } catch (URISyntaxException e) {
                throw new IOException("unusable code source " + source.getLocation(), e);
            }
        }
    }
}
