package io.github.xytronix.hybox.core.health;

import java.util.List;

@FunctionalInterface
public interface WorldStatsProvider {
    Worlds worlds();

    record Worlds(int targetTps, int maxViewRadius, int onlinePlayers, List<HealthSnapshot.World> worlds,
                  HealthSnapshot.Radius allPlayers) {
        public Worlds {
            worlds = worlds == null ? List.of() : List.copyOf(worlds);
        }
    }

    static WorldStatsProvider none() {
        return () -> new Worlds(0, -1, -1, List.of(), null);
    }
}
