package com.students;
import com.students.SharedModule;

@SuppressWarnings("checkstyle:hideutilityclassconstructor")
public class Main {
    /**
     * Entry Point for mock game.
     *
     * @param args command-line arguments
     */
    public static void main(final String[] args) {
        System.out.println("Hello World!");
        System.out.println("Shared module version: " + SharedModule.getVersion());
    }
}
