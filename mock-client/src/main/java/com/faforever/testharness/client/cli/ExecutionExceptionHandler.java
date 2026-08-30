package com.faforever.testharness.client.cli;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import picocli.CommandLine;
import picocli.CommandLine.IExecutionExceptionHandler;
import picocli.CommandLine.ParseResult;

/**
 * Renders an exception that escaped a subcommand's {@code call()} as a single-line diagnostic and
 * exits {@link ExitCodes#RUNTIME}, closing the last route out of the exit-code scheme documented in
 * {@code mock-client/README.md}.
 *
 * <p>Without this handler picocli applies its own default. {@link CommandLine#execute(String...)}
 * wraps the throwable in a {@code CommandLine.ExecutionException}, the built-in handler rethrows
 * it, and the surrounding {@code catch} calls {@code handleUnhandled}, which prints the whole stack
 * trace to picocli's error writer and returns the <em>leaf subcommand's</em> {@code
 * exitCodeOnExecutionException} — defaulting to {@code ExitCode.SOFTWARE}, which is {@code 1}. The
 * user gets a Java stack trace and an exit code that appears nowhere in the README's table.
 *
 * <p>Note that the leaf's spec is what picocli reads there, not the root's:
 *
 * <pre>{@code
 * } catch (ExecutionException ex) {
 *     try {
 *         return getExecutionExceptionHandler()
 *                 .handleExecutionException(cause, ex.getCommandLine(), …);
 *     } catch (Exception ex2) {
 *         return handleUnhandled(ex2, ex.getCommandLine(),
 *                 ex.getCommandLine().getCommandSpec().exitCodeOnExecutionException());
 *     }
 * }
 * }</pre>
 *
 * <p>So annotating only the root with {@code exitCodeOnExecutionException} would not move the exit
 * code at all. Every {@code @Command} in the tree carries it as a backstop for the two routes that
 * reach {@code handleUnhandled} without consulting this handler (this handler itself throwing, and
 * a non-{@code ParameterException} escaping {@code parseArgs}), but the annotation alone cannot
 * suppress the stack trace — only a handler can, because {@code handleUnhandled} prints before it
 * maps the code.
 *
 * <p>Three sinks, deliberately different:
 *
 * <ul>
 *   <li><b>stderr</b> — one line, no trace. This is the CLI contract: a consumer that only reads
 *       the exit code and stderr sees a usable message.
 *   <li><b>the log at its normal level</b> — the same summary at {@code ERROR}, with no throwable
 *       attached. An automated harness observes a client through its log records alone ({@code
 *       mock-client/README.md} § "Harness log contract"), and stderr is not one of the two capture
 *       channels that section names. The throwable is withheld here because Logback's console
 *       appender writes to <em>stdout</em>, so attaching it would print to the terminal exactly the
 *       trace this class exists to suppress.
 *   <li><b>the log at DEBUG</b> — the full throwable. {@code JsonLineEncoder} writes an attached
 *       throwable as a single escaped {@code exception} field, so the stack trace is recoverable
 *       from the JSONL file without ever splitting a record across lines.
 * </ul>
 */
public final class ExecutionExceptionHandler implements IExecutionExceptionHandler {

    /** Appended to the stderr line so the suppressed trace is discoverable without the README. */
    static final String DEBUG_HINT = "re-run with --log-level DEBUG for the stack trace";

    /** Logger for the two records this handler emits. */
    private static final Logger LOG = LoggerFactory.getLogger(ExecutionExceptionHandler.class);

    /**
     * Reports {@code ex} on stderr and in the log, then maps it to {@link ExitCodes#RUNTIME}.
     *
     * @param ex the exception thrown by the subcommand's {@code call()}
     * @param commandLine the command that threw — the leaf subcommand, not the root
     * @param parseResult the parse result, unused
     * @return {@link ExitCodes#RUNTIME}
     */
    @Override
    public int handleExecutionException(
            final Exception ex, final CommandLine commandLine, final ParseResult parseResult) {
        String command = commandLine.getCommandSpec().qualifiedName();
        // toString() over getMessage(): the message is null for plenty of runtime exceptions, and
        // the type name alone is more use to a reader than the word "null".
        String summary = command + " failed: " + oneLine(ex.toString());

        LOG.error(summary);
        LOG.debug("unhandled exception in {}", command, ex);

        commandLine.getErr().println(summary + " (" + DEBUG_HINT + ")");
        commandLine.getErr().flush();
        return ExitCodes.RUNTIME;
    }

    /**
     * Escapes line breaks so the diagnostic stays on one line. An exception message is arbitrary
     * text and may span lines — a parser splitting stderr on newlines must not see one failure as
     * several, and a message containing {@code "\nUsage:"} must not be able to forge picocli's
     * usage-block boundary. Mirrors {@code LayeredDefaultProvider.oneLine}, which does the same for
     * the construction-time diagnostics in the sibling package.
     *
     * @param text the raw text to interpolate
     * @return the same text with every line terminator replaced by a literal {@code \n}
     */
    private static String oneLine(final String text) {
        return text.replaceAll("\\R", "\\\\n");
    }
}
