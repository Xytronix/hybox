package io.github.xytronix.hybox.core.metrics;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class HealthGaugesTest {

    @Test
    void freshGaugesHaveNoSampleAndNoIncidents() {
        HealthGauges gauges = new HealthGauges();
        assertEquals(List.of(), gauges.sample().worlds());
        assertEquals(-1L, gauges.lastIncidentEpochSeconds());
        assertEquals(0, gauges.incidents().size());
    }

    @Test
    void incrementIncidentCountsPerTriggerAndSeverity() {
        HealthGauges gauges = new HealthGauges();
        gauges.incrementIncident("HEAP_PRESSURE", "critical", 100L);
        gauges.incrementIncident("HEAP_PRESSURE", "critical", 200L);
        gauges.incrementIncident("DEADLOCK", "critical", 300L);

        assertEquals(2L, gauges.incidents().get(new HealthGauges.IncidentKey("HEAP_PRESSURE", "critical")));
        assertEquals(1L, gauges.incidents().get(new HealthGauges.IncidentKey("DEADLOCK", "critical")));
        assertEquals(300L, gauges.lastIncidentEpochSeconds());
    }

    @Test
    void incrementDisconnectCountsByReasonAndDefaultsNull() {
        HealthGauges gauges = new HealthGauges();
        gauges.incrementDisconnect("TIMEOUT");
        gauges.incrementDisconnect("TIMEOUT");
        gauges.incrementDisconnect(null);
        assertEquals(2L, gauges.disconnects().get("TIMEOUT"));
        assertEquals(1L, gauges.disconnects().get("unknown"));
    }

    @Test
    void setHeartbeatsCopiesValues() {
        HealthGauges gauges = new HealthGauges();
        gauges.setHeartbeats(Map.of("overworld", 123L));
        assertEquals(Map.of("overworld", 123L), gauges.heartbeats());
    }

    @Test
    void setPluginMetricsCopiesGaugesAndCounters() {
        HealthGauges gauges = new HealthGauges();
        gauges.setPluginMetrics(Map.of("g", 1.0), Map.of("c", 2.0));
        assertEquals(Map.of("g", 1.0), gauges.pluginGauges());
        assertEquals(Map.of("c", 2.0), gauges.pluginCounters());
    }
}
