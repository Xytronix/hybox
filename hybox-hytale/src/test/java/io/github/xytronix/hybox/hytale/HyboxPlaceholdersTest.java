package io.github.xytronix.hybox.hytale;

import static org.junit.jupiter.api.Assertions.*;

import io.github.xytronix.hybox.core.metrics.HealthGauges;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import org.junit.jupiter.api.Test;

class HyboxPlaceholdersTest {
    private static final long GIB = 1024L * 1024 * 1024;
    private static final ZoneId ZONE = ZoneId.of("Europe/Berlin");
    private static final Instant NOW = Instant.parse("2026-11-19T12:30:00Z");

    private static final class FakeSource implements HyboxPlaceholders.Source {
        HytaleTelemetrySampler.Status status;
        List<HealthGauges.World> worlds = List.of();
        long[] times = new long[0];

        @Override
        public HytaleTelemetrySampler.Status status() {
            return status;
        }

        @Override
        public List<HealthGauges.World> worlds() {
            return worlds;
        }

        @Override
        public long[] incidentTimes() {
            return times;
        }
    }

    private static HealthGauges.World world(String name, double tps, double mspt) {
        return new HealthGauges.World(name, tps, mspt, 0, 0, -1, 0, 0, 0);
    }

    @Test
    void waitsForTheFirstSample() {
        var placeholders = new HyboxPlaceholders(new FakeSource(), Clock.fixed(NOW, ZONE));
        assertEquals("Waiting", placeholders.apply("tps"));
        assertEquals("Waiting", placeholders.apply("mspt"));
        assertEquals("Waiting", placeholders.apply("status"));
        assertEquals("Waiting", placeholders.apply("heap"));
        assertEquals("none", placeholders.apply("last_incident"));
        assertEquals("0", placeholders.apply("incidents_today"));
    }

    @Test
    void formatsLikeTheDashboard() {
        var source = new FakeSource();
        var placeholders = new HyboxPlaceholders(source, Clock.fixed(NOW, ZONE));
        source.status = new HytaleTelemetrySampler.Status(29.96, 12.34, 10, 3 * GIB, 4 * GIB, 30);
        source.worlds = List.of(world("default", 29.96, 12.34), world("Arena_2", 14, 71.43));
        assertEquals("30.0", placeholders.apply("tps"));
        assertEquals("12.3", placeholders.apply("mspt"));
        assertEquals("OK", placeholders.apply("status"));
        assertEquals("75%", placeholders.apply("heap"));
        assertEquals("14.0", placeholders.apply("tps_arena_2"));
        assertEquals("71.4", placeholders.apply("mspt_Arena_2"));
        assertNull(placeholders.apply("tps_nether"));
        assertNull(placeholders.apply("players"));
        assertNull(placeholders.apply(""));

        source.status = new HytaleTelemetrySampler.Status(24, 41.7, 10, GIB, 4 * GIB, 30);
        assertEquals("Degraded", placeholders.apply("status"));
        source.status = new HytaleTelemetrySampler.Status(14, 71.4, 10, GIB, 4 * GIB, 30);
        assertEquals("Critical", placeholders.apply("status"));
        assertEquals("14.0", placeholders.apply("tps"));
    }

    @Test
    void countsIncidentsSinceLocalMidnight() {
        var source = new FakeSource();
        var placeholders = new HyboxPlaceholders(source, Clock.fixed(NOW, ZONE));
        source.times = new long[] {
            Instant.parse("2026-11-18T22:59:00Z").toEpochMilli(),
            Instant.parse("2026-11-18T23:00:00Z").toEpochMilli(),
            Instant.parse("2026-11-19T12:18:00Z").toEpochMilli()
        };
        assertEquals("2", placeholders.apply("incidents_today"));
        assertEquals("12 min ago", placeholders.apply("last_incident"));
    }
}
