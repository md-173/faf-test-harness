package com.faforever.testharness.shared.process;

/** Tiny configurable child program used by SubprocessManager tests. */
public final class TestChild {

    private TestChild() {}

    public static void main(String[] args) throws InterruptedException {
        if (args.length == 0) {
            System.exit(0);
        }
        String mode = args[0];
        switch (mode) {
            case "exit" -> System.exit(Integer.parseInt(args[1]));
            case "sleep" -> Thread.sleep(Long.parseLong(args[1]));
            case "print" -> System.out.println(args[1]);
            case "env" -> System.out.println(System.getenv(args[1]));
            case "hook" -> {
                // Prints args[1] and waits. A SIGTERM runs the shutdown hook, which prints args[2]
                // on the way out, as mock-game logs its totals.
                Runtime.getRuntime().addShutdownHook(new Thread(() -> System.out.println(args[2])));
                System.out.println(args[1]);
                Thread.sleep(60_000);
            }
            case "lines" -> {
                // Each arg is a line to print, to stderr if it starts "err:", or a pause of
                // "sleep:<ms>" between lines.
                for (int i = 1; i < args.length; i++) {
                    String arg = args[i];
                    if (arg.startsWith("sleep:")) {
                        Thread.sleep(Long.parseLong(arg.substring("sleep:".length())));
                    } else if (arg.startsWith("err:")) {
                        System.err.println(arg.substring("err:".length()));
                    } else {
                        System.out.println(arg);
                    }
                }
            }
            default -> {
                System.err.println("unknown mode: " + mode);
                System.exit(2);
            }
        }
    }
}
