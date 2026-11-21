package io.github.xytronix.hybox.hytale;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.xytronix.hybox.core.health.HealthSnapshot;
import java.util.List;
import org.junit.jupiter.api.Test;

class HytaleTelemetrySamplerTest {
    @Test
    void eachWorldIsComparedWithItsOwnTarget() {
        HealthSnapshot.World slowedOnPurpose = world("arena", 10, 5, 10);
        HealthSnapshot.World struggling = world("default", 24, 40, 30);
        assertTrue(HytaleTelemetrySampler.worse(struggling, slowedOnPurpose, 30));
        assertFalse(HytaleTelemetrySampler.worse(slowedOnPurpose, struggling, 30));
        assertEquals(DashboardModel.Tone.NEUTRAL, DashboardModel.tpsTone(slowedOnPurpose.tps(), 10));
        assertEquals(DashboardModel.Tone.WARN, DashboardModel.tpsTone(struggling.tps(), 30));
    }

    @Test
    void atFullSpeedTheWorldWithMoreOfItsBudgetUsedIsWorse() {
        HealthSnapshot.World busy = world("default", 30, 28, 30);
        HealthSnapshot.World idle = world("lobby", 30, 2, 30);
        assertTrue(HytaleTelemetrySampler.worse(busy, idle, 30));
        assertEquals(20, HytaleTelemetrySampler.target(world("old", 20, 5, 0), 20));
        assertEquals(30, HytaleTelemetrySampler.target(world("old", 20, 5, 0), 0));
    }

    @Test
    void radiusKeepsMedianSumMaxAndTheTopPlayersOnly() {
        List<HealthSnapshot.PlayerLoad> loads = List.of(
            new HealthSnapshot.PlayerLoad("player-1", 6, 6, 900),
            new HealthSnapshot.PlayerLoad("player-2", 32, 8, 30000),
            new HealthSnapshot.PlayerLoad("player-3", 8, 3, 1200),
            new HealthSnapshot.PlayerLoad("player-4", 8, 8, 1100),
            new HealthSnapshot.PlayerLoad("player-5", 10, 8, 1500),
            new HealthSnapshot.PlayerLoad("player-6", 12, 8, 2000));
        HealthSnapshot.Radius radius = HealthSnapshot.Radius.of(loads, List.of(8, 8, 3, 8, 8, 8));
        assertEquals(6, radius.players());
        assertEquals(6, radius.viewMin());
        assertEquals(8, radius.viewMedian());
        assertEquals(32, radius.viewMax());
        assertEquals(36700, radius.sections());
        assertEquals(30000, radius.sectionsMax());
        assertEquals(1, radius.hotLowered());
        assertEquals(3, radius.hotLoweredTo());
        assertEquals(HealthSnapshot.Radius.TOP_PLAYERS, radius.top().size());
        assertEquals("player-2", radius.top().getFirst().name());
        assertFalse(radius.top().stream().anyMatch(load -> load.name().equals("player-1")));
    }

    private static HealthSnapshot.World world(String name, double tps, double mspt, int target) {
        return new HealthSnapshot.World(name, 1, 0, 0, tps, mspt, List.of(), -1, -1, -1, -1, -1, -1, target, null);
    }
}
