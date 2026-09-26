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
            case "lines" -> {
                for (int i = 1; i < args.length; i++) {
                    String arg = args[i];
                    if (arg.startsWith("err:")) {
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
