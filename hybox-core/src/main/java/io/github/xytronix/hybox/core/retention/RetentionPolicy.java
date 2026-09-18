package io.github.xytronix.hybox.core.retention;

import java.time.Duration;

/**
 * Defines retention limits for stored incident bundles.
 */
public record RetentionPolicy(
    int maxCount,
    long maxTotalBytes,
    Duration maxAge,
    int failedRecordingMaxCount,
    long stagingMaxTotalBytes,
    Duration stagingMaxAge,
    long repositoryMaxTotalBytes,
    Duration repositoryMaxAge
) {
    public static final int DEFAULT_FAILED_RECORDING_MAX_COUNT = 5;
    public static final long DEFAULT_STAGING_MAX_TOTAL_BYTES = 512L * 1024 * 1024;
    public static final Duration DEFAULT_STAGING_MAX_AGE = Duration.ofDays(7);
    public static final long DEFAULT_REPOSITORY_MAX_TOTAL_BYTES = 1024L * 1024 * 1024;
    public static final Duration DEFAULT_REPOSITORY_MAX_AGE = Duration.ofDays(7);

    public RetentionPolicy {
        if (maxCount < 0) {
            throw new IllegalArgumentException("maxCount must be >= 0.");
        }
        if (maxTotalBytes < 0) {
            throw new IllegalArgumentException("maxTotalBytes must be >= 0.");
        }
        if (maxAge != null && maxAge.isNegative()) {
            throw new IllegalArgumentException("maxAge must be non-negative.");
        }
        if (failedRecordingMaxCount < 0) {
            throw new IllegalArgumentException("failedRecordingMaxCount must be >= 0.");
        }
        if (stagingMaxTotalBytes < 0 || repositoryMaxTotalBytes < 0) {
            throw new IllegalArgumentException("Recording byte limits must be >= 0.");
        }
        if ((stagingMaxAge != null && stagingMaxAge.isNegative())
            || (repositoryMaxAge != null && repositoryMaxAge.isNegative())) {
            throw new IllegalArgumentException("Recording age limits must be non-negative.");
        }
    }

    public RetentionPolicy(int maxCount, long maxTotalBytes, Duration maxAge, int failedRecordingMaxCount) {
        this(maxCount, maxTotalBytes, maxAge, failedRecordingMaxCount,
            DEFAULT_STAGING_MAX_TOTAL_BYTES, DEFAULT_STAGING_MAX_AGE,
            DEFAULT_REPOSITORY_MAX_TOTAL_BYTES, DEFAULT_REPOSITORY_MAX_AGE);
    }

    public RetentionPolicy(int maxCount, long maxTotalBytes, Duration maxAge) {
        this(maxCount, maxTotalBytes, maxAge, DEFAULT_FAILED_RECORDING_MAX_COUNT);
    }
}
