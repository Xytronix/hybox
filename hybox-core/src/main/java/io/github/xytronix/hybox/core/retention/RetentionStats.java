package io.github.xytronix.hybox.core.retention;

import java.util.List;

/**
 * Metrics from a retention enforcement run.
 */
public record RetentionStats(
    int scanned,
    int deleted,
    long bytesDeleted,
    int deleteFailures,
    long finalBytes,
    int finalCount,
    List<Removal> removals
) {
    public enum Rule {
        AGE,
        COUNT,
        SIZE
    }

    public record Removal(String fileName, Rule rule, long bytes) {
    }

    public RetentionStats {
        removals = List.copyOf(removals);
    }

    public int removed(Rule rule) {
        return (int) removals.stream().filter(removal -> removal.rule() == rule).count();
    }
}
