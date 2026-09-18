package io.github.xytronix.hybox.core.retention;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RecordingRetentionTest {
    private final Instant now = Instant.parse("2026-09-18T00:00:00Z");
    private final RetentionManager manager = new RetentionManager(Clock.fixed(now, ZoneOffset.UTC),
        System.getLogger("recording-retention-test"), FileDeleter.defaultDeleter());

    @Test
    void stagingPrunesExpiredFailuresButProtectsPendingAndNewest(@TempDir Path root) throws Exception {
        Path failed = Files.createDirectories(root.resolve("failed"));
        Path old = recording(failed.resolve("old.jfr"), 3, 20);
        Path newest = recording(failed.resolve("newest.jfr"), 3, 10);
        Path pending = recording(Files.createDirectories(root.resolve("pending")).resolve("pending.jfr"), 10, 30);
        Path active = recording(root.resolve("active.jfr"), 10, 30);
        assertFalse(manager.enforceStaging(root, policy(4, Duration.ofDays(7), 100, Duration.ofDays(7))));
        assertFalse(Files.exists(old));
        assertTrue(Files.exists(newest));
        assertTrue(Files.exists(pending));
        assertTrue(Files.exists(active));
    }

    @Test
    void stagingByteLimitPrunesOldestFailuresFirst(@TempDir Path root) throws Exception {
        Path failed = Files.createDirectories(root.resolve("failed"));
        Path old = recording(failed.resolve("old.jfr"), 4, 2);
        Path newest = recording(failed.resolve("newest.jfr"), 4, 1);
        assertTrue(manager.enforceStaging(root, policy(6, Duration.ofDays(7), 100, Duration.ofDays(7))));
        assertFalse(Files.exists(old));
        assertTrue(Files.exists(newest));
    }

    @Test
    void retainedRepositoryPressureDoesNotDeleteRecoverableChunks(@TempDir Path root) throws Exception {
        Path chunk = recording(root.resolve("chunk.jfr"), 8, 20);
        assertFalse(manager.checkRepositoryBudget(List.of(root), policy(100, Duration.ofDays(7), 4, Duration.ofDays(7))));
        assertEquals(8, Files.size(chunk));
        assertFalse(manager.checkRepositoryBudget(List.of(root), policy(100, Duration.ofDays(7), 100, Duration.ofDays(7))));
        assertTrue(manager.checkRepositoryBudget(List.of(root), policy(100, Duration.ofDays(7), 100, Duration.ofDays(30))));
    }

    @Test
    void stagingNeverTraversesSymlinkedQuarantine(@TempDir Path root) throws Exception {
        Path external = Files.createDirectories(root.resolve("external"));
        Path evidence = recording(external.resolve("evidence.jfr"), 10, 30);
        Path staging = Files.createDirectories(root.resolve("staging"));
        Files.createSymbolicLink(staging.resolve("failed"), external);
        assertFalse(manager.enforceStaging(staging, policy(1, Duration.ZERO, 100, Duration.ofDays(7))));
        assertTrue(Files.exists(evidence));
    }

    @Test
    void stagingRootsShareOneByteBudget(@TempDir Path root) throws Exception {
        Path temp = Files.createDirectories(root.resolve("temp"));
        Path recover = Files.createDirectories(root.resolve("recover"));
        Path first = recording(temp.resolve("pending.jfr"), 1, 0);
        Path second = recording(recover.resolve("pending.jfr"), 1, 0);
        assertFalse(manager.enforceStaging(temp, policy(2, Duration.ofDays(7), 100, Duration.ofDays(7)), recover));
        assertEquals(1, Files.size(first));
        assertEquals(1, Files.size(second));
    }

    @Test
    void sharedBudgetPrunesOldestFailureAcrossRoots(@TempDir Path root) throws Exception {
        Path temp = Files.createDirectories(root.resolve("temp"));
        Path recover = Files.createDirectories(root.resolve("recover"));
        Path tempFailed = Files.createDirectories(temp.resolve("failed"));
        Path recoverFailed = Files.createDirectories(recover.resolve("failed"));
        Path oldest = recording(tempFailed.resolve("oldest.jfr"), 2, 3);
        Path tempNewest = recording(tempFailed.resolve("newest.jfr"), 2, 1);
        Path recoverNewest = recording(recoverFailed.resolve("newest.jfr"), 2, 2);
        assertTrue(manager.enforceStaging(temp, policy(5, Duration.ofDays(7), 100, Duration.ofDays(7)), recover));
        assertFalse(Files.exists(oldest));
        assertTrue(Files.exists(tempNewest));
        assertTrue(Files.exists(recoverNewest));
    }

    private Path recording(Path path, int size, int ageDays) throws Exception {
        Files.write(path, new byte[size]);
        Files.setLastModifiedTime(path, FileTime.from(now.minus(Duration.ofDays(ageDays))));
        return path;
    }

    private RetentionPolicy policy(long stagingBytes, Duration stagingAge, long repositoryBytes, Duration repositoryAge) {
        return new RetentionPolicy(0, 0, null, 5, stagingBytes, stagingAge, repositoryBytes, repositoryAge);
    }
}
