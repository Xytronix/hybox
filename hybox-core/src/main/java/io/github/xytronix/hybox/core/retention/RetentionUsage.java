package io.github.xytronix.hybox.core.retention;

import java.util.Objects;

public record RetentionUsage(int bundles, long bytes, int expired, long freeBytes, long diskBytes, RetentionPolicy policy) {
    private static final long LOW_DISK_BYTES = 2L * 1024 * 1024 * 1024;
    private static final double NEAR_LIMIT = 0.9;

    public RetentionUsage {
        Objects.requireNonNull(policy, "policy");
    }

    public boolean lowDisk() {
        return freeBytes >= 0 && diskBytes > 0 && (freeBytes < diskBytes * 0.05 || freeBytes < LOW_DISK_BYTES);
    }

    public boolean nearSizeLimit() {
        return policy.maxTotalBytes() > 0 && bytes >= policy.maxTotalBytes() * NEAR_LIMIT;
    }
}
