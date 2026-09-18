package io.github.xytronix.hybox.hytale;

import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.function.Supplier;
import io.github.xytronix.hybox.core.capture.CapturePipeline;
import io.github.xytronix.hybox.core.jfr.JfrRepository;

final class JfrSessionRecovery {
    private final Clock clock;
    private final System.Logger logger;
    private final Path rollingFile;
    private final Path recoverDir;
    private final Path sessionPointer;
    private final Path repositoryBase;
    private volatile Path currentSessionDir;

    JfrSessionRecovery(Clock clock, System.Logger logger, Path rollingFile, Path recoverDir, Path sessionPointer) {
        this(clock, logger, rollingFile, recoverDir, sessionPointer,
            JfrRepository.repositoryBase(ManagementFactory.getRuntimeMXBean().getInputArguments(),
                Path.of(System.getProperty("java.io.tmpdir", "."))));
    }

    JfrSessionRecovery(Clock clock, System.Logger logger, Path rollingFile, Path recoverDir, Path sessionPointer,
                       Path repositoryBase) {
        this.clock = Objects.requireNonNull(clock, "clock");
        this.logger = Objects.requireNonNull(logger, "logger");
        this.rollingFile = Objects.requireNonNull(rollingFile, "rollingFile");
        this.recoverDir = Objects.requireNonNull(recoverDir, "recoverDir");
        this.sessionPointer = Objects.requireNonNull(sessionPointer, "sessionPointer");
        this.repositoryBase = Objects.requireNonNull(repositoryBase, "repositoryBase").toAbsolutePath().normalize();
    }

    CompletableFuture<Void> recoverOrphanedRecordings(boolean snapshotsEnabled, Supplier<CapturePipeline> pipeline,
                                   ExecutorService worker) {
        List<Path> previousSessions = readSessionPointers();
        locateCurrentSession();
        persistSessions(previousSessions);
        return CompletableFuture.runAsync(() -> {
            CapturePipeline capture = pipeline.get();
            capture.recoverTemporaryRecordings();
            if (!capture.isEnabled() || Thread.currentThread().isInterrupted()) {
                return;
            }
            List<Path> retained = new ArrayList<>();
            for (Path previous : previousSessions) {
                if (previous.equals(currentSessionDir) || !Files.exists(previous, LinkOption.NOFOLLOW_LINKS)) {
                    continue;
                }
                if (Thread.currentThread().isInterrupted() || !safeInactiveRepository(previous)) {
                    retained.add(previous);
                    continue;
                }
                if (!harvestPreviousRepository(previous.toString(), () -> capture) && hasRepositoryEvidence(previous)) {
                    retained.add(previous);
                }
            }
            persistSessions(retained);
            if (Thread.currentThread().isInterrupted()) {
                return;
            }
            if (snapshotsEnabled) {
                try {
                    if (Files.isRegularFile(rollingFile, LinkOption.NOFOLLOW_LINKS)) {
                        Files.createDirectories(recoverDir);
                        Path staged = recoverDir.resolve("rolling-" + java.util.UUID.randomUUID() + ".jfr");
                        Files.move(rollingFile, staged);
                    }
                } catch (Exception e) {
                    logger.log(System.Logger.Level.WARNING, "Failed to stage an orphaned recording for recovery.", e);
                    throw new java.util.concurrent.CompletionException(e);
                }
            }
            try {
                capture.recoverOrphans(recoverDir);
            } catch (Exception e) {
                logger.log(System.Logger.Level.WARNING, "Recovery scan failed.", e);
            }
            recheckStorageBudgets(pipeline.get());
        }, worker);
    }

    void recheckStorageBudgets(CapturePipeline pipeline) {
        pipeline.checkRecoveryStorage(readSessionPointers().stream().filter(this::safeInactiveRepository).toList(), recoverDir);
    }

    private boolean hasRepositoryEvidence(Path repository) {
        try {
            return !JfrRepository.chunkFiles(repository).isEmpty();
        } catch (Exception e) {
            logger.log(System.Logger.Level.WARNING, "Cannot inspect retained repository " + repository + ".", e);
            return true;
        }
    }

    private List<Path> readSessionPointers() {
        try {
            if (Files.isRegularFile(sessionPointer, LinkOption.NOFOLLOW_LINKS)) {
                LinkedHashSet<Path> sessions = new LinkedHashSet<>();
                for (String line : Files.readAllLines(sessionPointer)) {
                    if (!line.isBlank()) {
                        Path path = Path.of(line.trim()).toAbsolutePath().normalize();
                        if (repositoryBase.equals(path.getParent()) && !Files.isSymbolicLink(path)) {
                            sessions.add(path);
                        } else {
                            logger.log(System.Logger.Level.WARNING, "Ignoring unsafe JFR repository pointer " + path + ".");
                        }
                    }
                }
                return new ArrayList<>(sessions);
            }
        } catch (Exception e) {
            logger.log(System.Logger.Level.WARNING, "Failed to read the JFR repository pointer.", e);
        }
        return List.of();
    }

    private void locateCurrentSession() {
        try {
            currentSessionDir = JfrRepository.sessionDirForPid(repositoryBase, ProcessHandle.current().pid()).orElse(null);
        } catch (Exception e) {
            logger.log(System.Logger.Level.WARNING, "Failed to locate the active JFR repository.", e);
        }
    }

    private void persistSessions(List<Path> retained) {
        try {
            LinkedHashSet<Path> sessions = new LinkedHashSet<>(retained);
            if (currentSessionDir != null) {
                sessions.add(currentSessionDir);
            }
            Files.createDirectories(sessionPointer.getParent());
            Path temporary = Files.createTempFile(sessionPointer.getParent(), "jfr-session-", ".tmp");
            try {
                Files.write(temporary, sessions.stream().map(Path::toString).toList());
                try {
                    Files.move(temporary, sessionPointer, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
                } catch (java.nio.file.AtomicMoveNotSupportedException e) {
                    Files.move(temporary, sessionPointer, StandardCopyOption.REPLACE_EXISTING);
                }
            } finally {
                Files.deleteIfExists(temporary);
            }
        } catch (Exception e) {
            logger.log(System.Logger.Level.WARNING, "Failed to record the JFR repository locations.", e);
        }
    }

    private boolean safeInactiveRepository(Path directory) {
        Path path = directory.toAbsolutePath().normalize();
        if (!repositoryBase.equals(path.getParent()) || path.equals(currentSessionDir)
            || !Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) {
            return false;
        }
        String name = path.getFileName().toString();
        if (!name.matches("\\d{4}_\\d{2}_\\d{2}_\\d{2}_\\d{2}_\\d{2}_\\d+")) {
            return false;
        }
        try {
            long pid = Long.parseLong(name.substring(name.lastIndexOf('_') + 1));
            return pid > 0 && ProcessHandle.of(pid).isEmpty();
        } catch (IllegalArgumentException | SecurityException e) {
            return false;
        }
    }

    private boolean harvestPreviousRepository(String previousSession, Supplier<CapturePipeline> pipeline) {
        if (previousSession == null || previousSession.isEmpty()) {
            return false;
        }
        Path previousDir = Path.of(previousSession).toAbsolutePath().normalize();
        if (!safeInactiveRepository(previousDir) || Thread.currentThread().isInterrupted()) {
            return false;
        }
        List<Path> chunks;
        try {
            chunks = JfrRepository.chunkFiles(previousDir);
        } catch (Exception e) {
            return false;
        }
        if (chunks.isEmpty()) {
            return false;
        }
        Path merged = recoverDir.resolve("harvested-" + java.util.UUID.randomUUID() + ".jfr");
        boolean recovered = false;
        try {
            if (JfrRepository.merge(chunks, merged)) {
                recovered = pipeline.get().recoverFromRecording(merged, lastModifiedOrNow(previousDir)).isPresent();
                if (recovered) {
                    logger.log(System.Logger.Level.INFO,
                        "Recovered an incident bundle from " + chunks.size() + " JFR repository chunk(s) of a crashed run.");
                }
            }
        } catch (Exception e) {
            logger.log(System.Logger.Level.WARNING, "Failed to harvest the previous JFR repository.", e);
        } finally {
            if (recovered && safeInactiveRepository(previousDir)) {
                try {
                    Files.deleteIfExists(merged);
                } catch (Exception ignored) {
                }
                boolean deleted = true;
                for (Path chunk : chunks) {
                    try {
                        Files.deleteIfExists(chunk);
                    } catch (Exception e) {
                        deleted = false;
                        logger.log(System.Logger.Level.WARNING, "Failed to delete recovered chunk " + chunk + ".", e);
                    }
                }
                recovered = deleted;
                try {
                    Files.delete(previousDir);
                } catch (java.nio.file.DirectoryNotEmptyException ignored) {
                } catch (java.nio.file.NoSuchFileException ignored) {
                } catch (Exception e) {
                    logger.log(System.Logger.Level.WARNING, "Failed to clean up harvested repository " + previousDir + ".", e);
                }
            }
        }
        return recovered;
    }

    private Instant lastModifiedOrNow(Path path) {
        try {
            return Files.getLastModifiedTime(path, LinkOption.NOFOLLOW_LINKS).toInstant();
        } catch (Exception e) {
            return clock.instant();
        }
    }
}
