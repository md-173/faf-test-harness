package com.faforever.testharness.client.cli;

import java.io.PrintWriter;
import picocli.CommandLine;
import picocli.CommandLine.IExitCodeExceptionMapper;
import picocli.CommandLine.IParameterExceptionHandler;
import picocli.CommandLine.ParameterException;
import picocli.CommandLine.UnmatchedArgumentException;

/**
 * Renders a command-line parse error as a single line ahead of picocli's usage block, so no argv
 * can forge the {@code Usage:} boundary for anything reading stderr (#307).
 *
 * <p>Picocli's default handler prints {@code ex.getMessage()} verbatim, and that message quotes the
 * offending argument. An argument is caller-controlled and may contain a newline, so {@code
 * mock-client $'--bogus\nUsage: FORGED'} printed a second line reading {@code Usage: FORGED} above
 * the real usage block. {@code LayeredDefaultProvider.oneLine} already kept the construction-time
 * {@code --config} diagnostics on one line (#284); this closes the same hole on the far side of
 * {@link CommandLine#execute(String...)}, where every other parse error is reported.
 *
 * <p>Apart from the escaping this does what picocli's {@code DefaultParameterExceptionHandler}
 * does, in the same order: the message in the error colour, then either the "did you mean"
 * suggestions for an unmatched argument or the failing command's usage, then the exit code from the
 * command's {@link IExitCodeExceptionMapper} if one is set, else its {@code
 * exitCodeOnInvalidInput}, which is {@link ExitCodes#USAGE} throughout this tree. The suggestions
 * need no escaping: they are drawn from the command's own option and subcommand names. The one
 * thing not carried over is the stack trace picocli prints under {@code -Dpicocli.trace=DEBUG},
 * because picocli's trace stream is not reachable from outside the library.
 *
 * <p>Installed on the root command by {@code ConfigLoader.newCommandLine}. Picocli consults the
 * root's handler whichever subcommand the error came from, and {@code ex.getCommandLine()} is that
 * subcommand, so the usage printed is still the one for the command the user got wrong.
 */
public final class ParameterExceptionHandler implements IParameterExceptionHandler {

    /**
     * Prints the escaped parse error and the usage help, and returns the usage exit code.
     *
     * @param ex the parse error; its command line is the subcommand that rejected the input
     * @param args the raw arguments, unused
     * @return the mapped exit code, {@link ExitCodes#USAGE} unless a mapper says otherwise
     */
    @Override
    public int handleParseException(final ParameterException ex, final String[] args) {
        CommandLine cmd = ex.getCommandLine();
        PrintWriter err = cmd.getErr();
        err.println(
                cmd.getColorScheme()
                        .errorText(
                                ExecutionExceptionHandler.oneLine(
                                        String.valueOf(ex.getMessage()))));
        if (!UnmatchedArgumentException.printSuggestions(ex, err)) {
            cmd.usage(err, cmd.getColorScheme());
        }
        err.flush();
        IExitCodeExceptionMapper mapper = cmd.getExitCodeExceptionMapper();
        return mapper != null
                ? mapper.getExitCode(ex)
                : cmd.getCommandSpec().exitCodeOnInvalidInput();
    }
}
