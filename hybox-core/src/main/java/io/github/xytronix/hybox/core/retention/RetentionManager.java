package io.github.xytronix.hybox.core.retention;

import java.io.IOException;
import java.nio.file.FileStore;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Stream;

import io.github.xytronix.hybox.core.text.Units;

/**
 * Enforces on-disk retention for incident bundles.
 */
public final class RetentionManager {
    private static final DateTimeFormatter INCIDENT_TIMESTAMP = new DateTimeFormatterBuilder()
        .appendPattern("yyyyMMdd-HHmmss.SSS")
        .appendOffset("+HHmm", "Z")
        .toFormatter(Locale.ROOT);

    private final Clock clock;
    private final System.Logger logger;
    private final FileDeleter deleter;

    public RetentionManager(Clock clock, System.Logger logger, FileDeleter deleter) {
        this.clock = Objects.requireNonNull(clock, "clock");
        this.logger = Objects.requireNonNull(logger, "logger");
        this.deleter = Objects.requireNonNull(deleter, "deleter");
    }

    public RetentionStats enforce(Path incidentDir, RetentionPolicy policy) {
        Objects.requireNonNull(incidentDir, "incidentDir");
        Objects.requireNonNull(policy, "policy");

        if (!Files.exists(incidentDir)) {
            return new RetentionStats(0, 0, 0L, 0, 0L, 0, List.of());
        }

        List<Path> candidates;
        try {
            candidates = bundleFiles(incidentDir);
        } catch (IOException e) {
            logger.log(System.Logger.Level.WARNING, "Failed to list incident directory " + incidentDir, e);
            return new RetentionStats(0, 0, 0L, 0, 0L, 0, List.of());
        }

        int scanned = candidates.size();
        List<IncidentFile> incidents = readIncidents(candidates);

        List<RetentionStats.Removal> removals = new ArrayList<>();
        int deleteFailures = 0;

        long currentBytes = incidents.stream().mapToLong(IncidentFile::size).sum();
        int currentCount = incidents.size();

        if (incidents.isEmpty()) {
            long finalBytes = recalcBytes(incidentDir);
            int finalCount = recalcCount(incidentDir);
            return new RetentionStats(scanned, 0, 0L, 0, finalBytes, finalCount, List.of());
        }

        IncidentFile newest = incidents.get(incidents.size() - 1);
        Set<Path> failed = new HashSet<>();

        if (policy.maxAge() != null) {
            Instant cutoff = clock.instant().minus(policy.maxAge());
            for (IncidentFile incident : new ArrayList<>(incidents)) {
                if (incident.equals(newest)) {
                    continue;
                }
                if (incident.createdAt().isBefore(cutoff)) {
                    if (attemptDelete(incident, newest, failed)) {
                        incidents.remove(incident);
                        removals.add(removed(incident, RetentionStats.Rule.AGE, policy));
                        currentBytes -= incident.size();
                        currentCount--;
                    } else {
                        deleteFailures++;
                    }
                }
            }
            if (currentCount == 1 && newest.createdAt().isBefore(cutoff)) {
                logger.log(System.Logger.Level.WARNING,
                    "Retention maxAge exceeded but newest incident must be kept.");
            }
        }

        if (policy.maxCount() > 0) {
            while (currentCount > policy.maxCount()) {
                IncidentFile candidate = oldestCandidate(incidents, newest, failed);
                if (candidate == null) {
                    logger.log(System.Logger.Level.WARNING,
                        "Retention maxCount exceeded but no deletable incidents remain.");
                    break;
                }
                if (attemptDelete(candidate, newest, failed)) {
                    incidents.remove(candidate);
                    removals.add(removed(candidate, RetentionStats.Rule.COUNT, policy));
                    currentBytes -= candidate.size();
                    currentCount--;
                } else {
                    deleteFailures++;
                }
            }
        }

        if (policy.maxTotalBytes() > 0) {
            while (currentBytes > policy.maxTotalBytes()) {
                IncidentFile candidate = oldestCandidate(incidents, newest, failed);
                if (candidate == null) {
                    logger.log(System.Logger.Level.WARNING,
                        "Retention maxTotalBytes exceeded but no deletable incidents remain.");
                    break;
                }
                if (attemptDelete(candidate, newest, failed)) {
                    incidents.remove(candidate);
                    removals.add(removed(candidate, RetentionStats.Rule.SIZE, policy));
                    currentBytes -= candidate.size();
                    currentCount--;
                } else {
                    deleteFailures++;
                }
                if (currentCount <= 1 && currentBytes > policy.maxTotalBytes()) {
                    logger.log(System.Logger.Level.WARNING,
                        "Retention would remove the newest incident. Stopping deletions.");
                    break;
                }
            }
        }

        long finalBytes = recalcBytes(incidentDir);
        int finalCount = recalcCount(incidentDir);
        long bytesDeleted = removals.stream().mapToLong(RetentionStats.Removal::bytes).sum();
        RetentionStats stats = new RetentionStats(scanned, removals.size(), bytesDeleted, deleteFailures,
            finalBytes, finalCount, removals);
        if (!removals.isEmpty() || deleteFailures > 0) {
            logger.log(System.Logger.Level.INFO, "Retention removed " + Units.count(removals.size(), "bundle")
                + " (" + stats.removed(RetentionStats.Rule.AGE) + " by age, "
                + stats.removed(RetentionStats.Rule.COUNT) + " by count, "
                + stats.removed(RetentionStats.Rule.SIZE) + " by size) and freed " + Units.bytes(bytesDeleted)
                + (deleteFailures > 0 ? ". " + deleteFailures + " could not be deleted" : "") + ". "
                + Units.count(finalCount, "bundle") + " and " + Units.bytes(finalBytes) + " remain.");
        }
        return stats;
    }

    public RetentionUsage usage(Path incidentDir, RetentionPolicy policy) {
        Objects.requireNonNull(incidentDir, "incidentDir");
        Objects.requireNonNull(policy, "policy");
        List<IncidentFile> incidents = List.of();
        if (Files.exists(incidentDir)) {
            try {
                incidents = readIncidents(bundleFiles(incidentDir));
            } catch (IOException e) {
                logger.log(System.Logger.Level.WARNING, "Failed to list incident directory " + incidentDir, e);
            }
        }
        int expired = 0;
        if (policy.maxAge() != null && !policy.maxAge().isZero()) {
            Instant cutoff = clock.instant().minus(policy.maxAge());
            for (int i = 0; i < incidents.size() - 1; i++) {
                if (incidents.get(i).createdAt().isBefore(cutoff)) {
                    expired++;
                }
            }
        }
        long free = -1;
        long total = -1;
        Path existing = incidentDir.toAbsolutePath();
        while (existing != null && !Files.exists(existing)) {
            existing = existing.getParent();
        }
        if (existing != null) {
            try {
                FileStore store = Files.getFileStore(existing);
                free = store.getUsableSpace();
                total = store.getTotalSpace();
            } catch (IOException e) {
                logger.log(System.Logger.Level.WARNING, "Failed to read free disk space for " + existing, e);
            }
        }
        return new RetentionUsage(incidents.size(), incidents.stream().mapToLong(IncidentFile::size).sum(),
            expired, free, total, policy);
    }

    private static List<Path> bundleFiles(Path incidentDir) throws IOException {
        try (Stream<Path> stream = Files.list(incidentDir)) {
            return stream
                .filter(path -> path.getFileName().toString().endsWith(".zip"))
                .filter(path -> Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS))
                .toList();
        }
    }

    private List<IncidentFile> readIncidents(List<Path> candidates) {
        List<IncidentFile> incidents = new ArrayList<>();
        for (Path path : candidates) {
            try {
                Instant createdAt = resolveCreatedAt(path);
                long size = Files.size(path);
                incidents.add(new IncidentFile(path, createdAt, size));
            } catch (IOException e) {
                logger.log(System.Logger.Level.WARNING,
                    "Failed to read incident bundle metadata for " + path, e);
            }
        }
        incidents.sort(Comparator.comparing(IncidentFile::createdAt)
            .thenComparing(file -> file.path().getFileName().toString()));
        return incidents;
    }

    private RetentionStats.Removal removed(IncidentFile incident, RetentionStats.Rule rule, RetentionPolicy policy) {
        String name = incident.path().getFileName().toString();
        logger.log(System.Logger.Level.INFO, "Retention removed " + name + " (" + Units.bytes(incident.size())
            + "): " + logReason(rule, policy) + ".");
        return new RetentionStats.Removal(name, rule, incident.size());
    }

    private static String logReason(RetentionStats.Rule rule, RetentionPolicy policy) {
        return switch (rule) {
            case AGE -> "age, older than MaxAge " + Units.duration(policy.maxAge());
            case COUNT -> "count, more than MaxCount " + policy.maxCount() + " bundles";
            case SIZE -> "size, bundles over MaxTotalBytes " + Units.bytes(policy.maxTotalBytes());
        };
    }

    public boolean enforceStaging(Path directory, RetentionPolicy policy, Path... additionalDirectories) {
        try {
            Set<Path> directories = new java.util.LinkedHashSet<>();
            directories.add(directory.toAbsolutePath().normalize());
            for (Path additional : additionalDirectories) {
                directories.add(additional.toAbsolutePath().normalize());
            }
            List<IncidentFile> protectedFiles = new ArrayList<>();
            List<IncidentFile> failures = new ArrayList<>();
            Set<IncidentFile> newestFiles = new HashSet<>();
            Comparator<IncidentFile> order = Comparator.comparing(IncidentFile::createdAt)
                .thenComparing(file -> file.path().toString());
            for (Path root : directories) {
                protectedFiles.addAll(recordingFiles(root));
                protectedFiles.addAll(recordingFiles(root.resolve("pending")));
                List<IncidentFile> rootFailures = recordingFiles(root.resolve("failed"));
                rootFailures.stream().max(order).ifPresent(newestFiles::add);
                failures.addAll(rootFailures);
            }
            failures.sort(order);
            long bytes = protectedFiles.stream().mapToLong(IncidentFile::size).sum()
                + failures.stream().mapToLong(IncidentFile::size).sum();
            int count = failures.size();
            Instant cutoff = policy.stagingMaxAge() == null ? Instant.MIN : clock.instant().minus(policy.stagingMaxAge());
            Set<Path> failed = new HashSet<>();
            for (IncidentFile file : failures) {
                if (newestFiles.contains(file)) {
                    continue;
                }
                boolean overCount = count > policy.failedRecordingMaxCount();
                boolean tooOld = file.createdAt().isBefore(cutoff);
                boolean overBytes = policy.stagingMaxTotalBytes() > 0 && bytes > policy.stagingMaxTotalBytes();
                if (overCount || tooOld || overBytes) {
                    if (attemptDelete(file, null, failed)) {
                        logger.log(System.Logger.Level.INFO, "Retention removed failed recording " + file.path().getFileName()
                            + " (" + Units.bytes(file.size()) + "): "
                            + (tooOld ? "age, older than " + Units.duration(policy.stagingMaxAge())
                                : overCount ? "count, more than " + policy.failedRecordingMaxCount() + " failed recordings"
                                : "size, staging over " + Units.bytes(policy.stagingMaxTotalBytes())) + ".");
                        bytes -= file.size();
                        count--;
                    }
                }
            }
            boolean withinBudget = policy.stagingMaxTotalBytes() == 0 || bytes < policy.stagingMaxTotalBytes();
            if (!withinBudget) {
                logger.log(System.Logger.Level.WARNING,
                    "Combined staging byte budget reached at " + directories
                        + ". Keeping pending and newest evidence and refusing new captures until space is free.");
            }
            return withinBudget;
        } catch (IOException | RuntimeException e) {
            logger.log(System.Logger.Level.WARNING, "Cannot safely enforce staging retention at " + directory + ".", e);
            return false;
        }
    }

    public boolean checkRepositoryBudget(List<Path> repositories, RetentionPolicy policy) {
        long bytes = 0;
        boolean expired = false;
        Instant cutoff = policy.repositoryMaxAge() == null ? Instant.MIN : clock.instant().minus(policy.repositoryMaxAge());
        try {
            for (Path repository : repositories) {
                for (IncidentFile file : recordingFiles(repository)) {
                    bytes = Math.addExact(bytes, file.size());
                    expired |= file.createdAt().isBefore(cutoff);
                }
            }
        } catch (IOException | RuntimeException e) {
            logger.log(System.Logger.Level.WARNING, "Cannot safely inspect retained JFR repositories.", e);
            return false;
        }
        boolean withinBudget = !expired && (policy.repositoryMaxTotalBytes() == 0 || bytes < policy.repositoryMaxTotalBytes());
        if (!withinBudget) {
            logger.log(System.Logger.Level.WARNING,
                "Retained JFR repository age or byte budget reached. Keeping unrecovered evidence and refusing new captures.");
        }
        return withinBudget;
    }

    private static List<IncidentFile> recordingFiles(Path directory) throws IOException {
        List<IncidentFile> files = new ArrayList<>();
        if (!Files.exists(directory, LinkOption.NOFOLLOW_LINKS)) {
            return files;
        }
        if (!Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Not a real recording directory: " + directory);
        }
        try (Stream<Path> stream = Files.list(directory)) {
            for (Path file : stream.filter(path -> path.getFileName().toString().endsWith(".jfr"))
                .filter(path -> Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)).toList()) {
                files.add(new IncidentFile(file, Files.getLastModifiedTime(file, LinkOption.NOFOLLOW_LINKS).toInstant(),
                    Files.size(file)));
            }
        }
        return files;
    }

    private boolean attemptDelete(IncidentFile incident, IncidentFile newest, Set<Path> failed) {
        if (incident.equals(newest)) {
            return false;
        }
        if (failed.contains(incident.path())) {
            return false;
        }
        try {
            deleter.delete(incident.path());
            return true;
        } catch (IOException e) {
            failed.add(incident.path());
            logger.log(System.Logger.Level.WARNING, "Failed to delete incident " + incident.path(), e);
            return false;
        }
    }

    private static IncidentFile oldestCandidate(List<IncidentFile> incidents, IncidentFile newest, Set<Path> failed) {
        for (IncidentFile incident : incidents) {
            if (incident.equals(newest)) {
                continue;
            }
            if (failed.contains(incident.path())) {
                continue;
            }
            return incident;
        }
        return null;
    }

    private static long recalcBytes(Path incidentDir) {
        try (Stream<Path> stream = Files.list(incidentDir)) {
            return stream
                .filter(path -> path.getFileName().toString().endsWith(".zip"))
                .filter(path -> Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS))
                .mapToLong(path -> {
                    try {
                        return Files.size(path);
                    } catch (IOException e) {
                        return 0L;
                    }
                })
                .sum();
        } catch (IOException e) {
            return 0L;
        }
    }

    private static int recalcCount(Path incidentDir) {
        try (Stream<Path> stream = Files.list(incidentDir)) {
            return (int) stream
                .filter(path -> path.getFileName().toString().endsWith(".zip"))
                .filter(path -> Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS))
                .count();
        } catch (IOException e) {
            return 0;
        }
    }

    private static Instant resolveCreatedAt(Path path) throws IOException {
        Instant fromId = parseCreatedAtFromName(path.getFileName().toString());
        if (fromId != null) {
            return fromId;
        }
        return Files.getLastModifiedTime(path, LinkOption.NOFOLLOW_LINKS).toInstant();
    }

    private static Instant parseCreatedAtFromName(String fileName) {
        if (!fileName.endsWith(".zip")) {
            return null;
        }
        String baseName = fileName.substring(0, fileName.length() - ".zip".length());
        if (baseName.startsWith("incident-")) {
            baseName = baseName.substring("incident-".length());
        }
        int lastDash = baseName.lastIndexOf('-');
        if (lastDash <= 0) {
            return null;
        }
        String timestamp = baseName.substring(0, lastDash);
        try {
            OffsetDateTime parsed = OffsetDateTime.parse(timestamp, INCIDENT_TIMESTAMP);
            return parsed.toInstant();
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    private record IncidentFile(Path path, Instant createdAt, long size) {
    }
}
