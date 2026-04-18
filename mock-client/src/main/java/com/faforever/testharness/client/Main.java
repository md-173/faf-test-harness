package com.faforever.testharness.client;

import com.faforever.testharness.shared.SharedModule;

@SuppressWarnings("checkstyle:hideutilityclassconstructor")
public class Main {
    /**
     * Entry point.
     *
     * @param args command-line arguments
     */
    public static void main(final String[] args) {
        System.out.println("Hello World!");
        System.out.println(SharedModule.getVersion());
    }
}
