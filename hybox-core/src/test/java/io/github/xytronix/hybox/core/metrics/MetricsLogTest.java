package io.github.xytronix.hybox.core.metrics;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MetricsLogTest {

    @Test
    void formatRow_emitsAllColumns() {
        String row = MetricsLog.formatRow(Instant.parse("2026-06-10T20:30:00Z"),
            45.5, 19.8, 12, 1234L, 5678L, 2.5, 12.5, 3.2, 9L, new MetricsLog.Load(20, 32, 8, 6, 4800));
        assertEquals("2026-06-10T20:30:00Z,45.50,19.80,12,1234,5678,2.50,12.50,3.20,9,20.00,32,8.00,6.00,4800", row);
    }

    @Test
    void formatRow_leavesUnknownGaugesBlank() {
        String row = MetricsLog.formatRow(Instant.parse("2026-06-10T20:30:00Z"),
            -1, -1, -1, -1, -1, -1, -1, -1, -1L, MetricsLog.Load.NONE);
        assertEquals("2026-06-10T20:30:00Z,,,,,,,,,,,,,,", row);
    }

    @Test
    void readsOldRowsWithoutTheNewColumnsNextToNewRows(@TempDir Path dir) throws Exception {
        Instant t = Instant.parse("2026-06-10T20:30:00Z");
        Files.writeString(dir.resolve("health-20260610.csv"),
            "timestamp,tickAvgMs,tps,players,heapUsedBytes,rssBytes,gcPct,cpuPct,allocMbPerSec,queuedPackets\n"
                + "2026-06-10T20:30:00Z,45.50,19.80,12,1234,,2.50,12.50,,9\n"
                + MetricsLog.formatRow(t.plusSeconds(10), 50, 20, 3, 1, 1, 0, 0, 0, 0,
                    new MetricsLog.Load(20, 32, 8, 6, 4800)) + "\n");

        MetricsLog.Trend trend = new MetricsLog(dir).read(t.plusSeconds(60), 1);

        assertEquals(2, trend.size());
        assertEquals(45.5, trend.tickAvgMs()[0]);
        assertEquals(9, trend.queuedPackets()[0]);
        assertTrue(Double.isNaN(trend.rssBytes()[0]));
        assertTrue(Double.isNaN(trend.targetTps()[0]));
        assertTrue(Double.isNaN(trend.maxViewRadius()[0]));
        assertTrue(Double.isNaN(trend.loadedSections()[0]));
        assertEquals(20, trend.targetTps()[1]);
        assertEquals(32, trend.maxViewRadius()[1]);
        assertEquals(8, trend.viewRadiusMedian()[1]);
        assertEquals(6, trend.hotRadiusMedian()[1]);
        assertEquals(4800, trend.loadedSections()[1]);
    }

    @Test
    void append_writesHeaderOncePerDayFile(@TempDir Path dir) throws Exception {
        MetricsLog log = new MetricsLog(dir);
        Instant t = Instant.parse("2026-06-10T20:30:00Z");
        log.append(t, row(t), 7);
        log.append(t.plusSeconds(10), row(t.plusSeconds(10)), 7);

        Path file = dir.resolve("health-20260610.csv");
        List<String> lines = Files.readAllLines(file);
        assertEquals(MetricsLog.HEADER, lines.get(0));
        assertEquals(3, lines.size());
    }

    @Test
    void append_rollsToNewFilePerUtcDay(@TempDir Path dir) throws Exception {
        MetricsLog log = new MetricsLog(dir);
        Instant day1 = Instant.parse("2026-06-10T23:59:00Z");
        Instant day2 = Instant.parse("2026-06-11T00:01:00Z");
        log.append(day1, row(day1), 7);
        log.append(day2, row(day2), 7);

        assertTrue(Files.exists(dir.resolve("health-20260610.csv")));
        assertTrue(Files.exists(dir.resolve("health-20260611.csv")));
    }

    @Test
    void append_prunesFilesOlderThanRetention(@TempDir Path dir) throws Exception {
        MetricsLog log = new MetricsLog(dir);
        Instant old = Instant.parse("2026-06-01T12:00:00Z");
        log.append(old, row(old), 7);
        assertTrue(Files.exists(dir.resolve("health-20260601.csv")));

        Instant now = old.plus(Duration.ofDays(10));
        log.append(now, row(now), 7);

        assertFalse(Files.exists(dir.resolve("health-20260601.csv")));
        assertTrue(Files.exists(dir.resolve("health-20260611.csv")));
    }

    private static String row(Instant at) {
        return MetricsLog.formatRow(at, 50, 20, 1, 1, 1, 0, 0, 0, 0, MetricsLog.Load.NONE);
    }
}
