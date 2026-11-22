package io.github.xytronix.hybox.core.retention;

import static io.github.xytronix.hybox.core.text.Units.bytes;
import static io.github.xytronix.hybox.core.text.Units.count;
import static io.github.xytronix.hybox.core.text.Units.duration;

import java.util.ArrayList;
import java.util.List;

public final class RetentionText {
    private RetentionText() {
    }

    public static String rule(RetentionStats.Rule rule, RetentionPolicy policy) {
        return switch (rule) {
            case AGE -> "older than " + duration(policy.maxAge());
            case COUNT -> "to keep the newest " + policy.maxCount();
            case SIZE -> "to stay under " + bytes(policy.maxTotalBytes());
        };
    }

    public static String removalLine(RetentionStats stats, RetentionPolicy policy) {
        List<String> parts = new ArrayList<>(3);
        RetentionStats.Rule only = null;
        for (RetentionStats.Rule rule : RetentionStats.Rule.values()) {
            int removed = stats.removed(rule);
            if (removed > 0) {
                parts.add(removed + " " + rule(rule, policy));
                only = parts.size() == 1 ? rule : null;
            }
        }
        String line = null;
        if (parts.size() == 1) {
            line = "Removed " + count(stats.removals().size(), "bundle") + " " + rule(only, policy) + ".";
        } else if (!parts.isEmpty()) {
            line = "Removed " + count(stats.removals().size(), "bundle") + ": " + String.join(", ", parts) + ".";
        }
        if (stats.deleteFailures() > 0) {
            String failure = "Could not remove " + count(stats.deleteFailures(), "bundle") + ". The server log has the reason.";
            line = line == null ? failure : line + " " + failure;
        }
        return line;
    }

    public static String limits(RetentionPolicy policy) {
        List<String> parts = new ArrayList<>(3);
        parts.add(policy.maxCount() > 0 ? "up to " + count(policy.maxCount(), "bundle") : "any number of bundles");
        parts.add(policy.maxTotalBytes() > 0 ? "up to " + bytes(policy.maxTotalBytes()) : "no size limit");
        parts.add(policy.maxAge() == null || policy.maxAge().isZero() ? "no age limit"
            : "for " + duration(policy.maxAge()));
        return "Keeps " + String.join(", ", parts);
    }

    public static String usage(RetentionUsage usage) {
        RetentionPolicy policy = usage.policy();
        List<String> parts = new ArrayList<>(5);
        parts.add(policy.maxCount() > 0 ? usage.bundles() + " of " + count(policy.maxCount(), "bundle")
            : count(usage.bundles(), "bundle"));
        parts.add(policy.maxTotalBytes() > 0 ? bytes(usage.bytes()) + " of " + bytes(policy.maxTotalBytes())
            : bytes(usage.bytes()));
        if (policy.maxAge() != null && !policy.maxAge().isZero()) {
            parts.add("kept for " + duration(policy.maxAge()));
        }
        if (usage.freeBytes() >= 0) {
            parts.add(bytes(usage.freeBytes()) + " free on disk");
        }
        return String.join(" · ", parts);
    }

    public static String expired(RetentionUsage usage) {
        if (usage.expired() == 0) {
            return null;
        }
        return count(usage.expired(), "bundle") + " older than " + duration(usage.policy().maxAge())
            + (usage.expired() == 1 ? " is" : " are") + " removed after the next capture.";
    }
}
