package com.faforever.testharness.client.config;

import java.util.List;

/**
 * Aggregated configuration error. Carries one {@link Issue} per failing field so the user
 * sees every problem at once instead of fixing them one-at-a-time.
 */
public final class ConfigValidationException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /**
     * A single validation failure.
     *
     * @param field human-readable field name (typically the JSON key)
     * @param reason what is wrong with the value (or that it is missing)
     * @param cliFlag CLI flag the user can set, or {@code null} if not applicable
     * @param envVar environment variable the user can set, or {@code null} if not applicable
     */
    public record Issue(String field, String reason, String cliFlag, String envVar) {}

    private final transient List<Issue> issues;

    public ConfigValidationException(final List<Issue> issues) {
        super(format(issues));
        this.issues = List.copyOf(issues);
    }

    public List<Issue> issues() {
        return issues;
    }

    private static String format(final List<Issue> issues) {
        StringBuilder sb = new StringBuilder("Mock Client configuration is invalid:\n");
        for (Issue issue : issues) {
            sb.append("  - ").append(issue.field()).append(": ").append(issue.reason());
            if (issue.cliFlag() != null || issue.envVar() != null) {
                sb.append(" (set via ");
                if (issue.cliFlag() != null) {
                    sb.append(issue.cliFlag());
                }
                if (issue.cliFlag() != null && issue.envVar() != null) {
                    sb.append(" or ");
                }
                if (issue.envVar() != null) {
                    sb.append(issue.envVar());
                }
                sb.append(')');
            }
            sb.append('\n');
        }
        return sb.toString();
    }
}