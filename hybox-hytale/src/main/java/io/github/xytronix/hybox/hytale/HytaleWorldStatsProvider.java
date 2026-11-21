package io.github.xytronix.hybox.hytale;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.hypixel.hytale.metrics.metric.HistoricMetric;
import com.hypixel.hytale.server.core.HytaleServer;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.modules.entity.player.ChunkTracker;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.Universe;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.commands.world.perf.WorldPerfCommand;

import io.github.xytronix.hybox.core.health.HealthSnapshot;
import io.github.xytronix.hybox.core.health.WorldStatsProvider;

final class HytaleWorldStatsProvider implements WorldStatsProvider {
    private static final System.Logger LOGGER = System.getLogger(HytaleWorldStatsProvider.class.getName());

    private final PlayerNameMasker nameMasker;
    private final Map<String, Long> lastTickNanos = new HashMap<>();

    HytaleWorldStatsProvider(PlayerNameMasker nameMasker) {
        this.nameMasker = java.util.Objects.requireNonNull(nameMasker, "nameMasker");
    }

    Map<String, Double> worstTicksSinceLastCall() {
        Map<String, Double> out = new HashMap<>();
        try {
            Map<String, World> worlds = Universe.get().getWorlds();
            lastTickNanos.keySet().retainAll(worlds.keySet());
            long now = System.nanoTime();
            for (Map.Entry<String, World> entry : worlds.entrySet()) {
                if (entry.getKey() == null || entry.getValue() == null) {
                    continue;
                }
                try {
                    HistoricMetric metrics = entry.getValue().getBufferedTickLengthMetricSet();
                    long[] times = metrics.getTimestamps(0);
                    long[] values = metrics.getValues(0);
                    Long previous = lastTickNanos.get(entry.getKey());
                    long since = previous == null ? Long.MIN_VALUE : previous;
                    long newest = since;
                    long worst = -1;
                    for (int i = 0, n = Math.min(times.length, values.length); i < n; i++) {
                        if (times[i] > since && times[i] != Long.MAX_VALUE) {
                            worst = Math.max(worst, values[i]);
                            newest = Math.max(newest, times[i]);
                        }
                    }
                    if (worst < 0 && previous != null) {
                        worst = now - previous;
                    }
                    if (newest != Long.MIN_VALUE) {
                        lastTickNanos.put(entry.getKey(), newest);
                    }
                    if (worst >= 0) {
                        out.put(entry.getKey(), worst / 1_000_000.0);
                    }
                } catch (Exception ignored) {
                }
            }
        } catch (Exception e) {
            LOGGER.log(System.Logger.Level.DEBUG, "Worst tick sampling failed.", e);
        }
        return out;
    }

    @Override
    public Worlds worlds() {
        List<HealthSnapshot.World> out = new ArrayList<>();
        int firstTarget = 0;
        int onlinePlayers = -1;
        int maxViewRadius = maxViewRadius();
        World defaultWorld = null;
        List<HealthSnapshot.PlayerLoad> allLoads = new ArrayList<>();
        List<Integer> allHot = new ArrayList<>();
        try {
            Universe universe = Universe.get();
            onlinePlayers = onlinePlayers(universe);
            defaultWorld = defaultWorld(universe);
            Map<String, World> worlds = universe.getWorlds();
            for (Map.Entry<String, World> entry : worlds.entrySet()) {
                String name = entry.getKey();
                World world = entry.getValue();
                if (name == null || name.isBlank() || world == null) {
                    continue;
                }
                double tps = -1;
                double mspt = -1;
                double[] percentiles = {-1, -1, -1};
                int target = targetTps(world);
                if (target > 0 && firstTarget == 0) {
                    firstTarget = target;
                }
                try {
                    HistoricMetric metrics = world.getBufferedTickLengthMetricSet();
                    double avgDeltaNanos = metrics.getAverage(0);
                    tps = WorldPerfCommand.tpsFromDelta(avgDeltaNanos, world.getTickStepNanos());
                    mspt = avgDeltaNanos / 1_000_000.0;
                    percentiles = msptPercentiles(metrics);
                } catch (Exception ignored) {
                }
                out.add(new HealthSnapshot.World(
                    name, playerCount(world), entityCount(world), chunkCount(world), tps, mspt,
                    playerNames(world), avgPingMs(world),
                    percentiles[0], percentiles[1], percentiles[2],
                    chunksGeneratedTotal(world), chunksLoadedTotal(world), target, radius(world, allLoads, allHot)));
            }
        } catch (Exception e) {
            LOGGER.log(System.Logger.Level.WARNING, "World stats collection failed.", e);
        }
        out.sort((a, b) -> a.name().compareToIgnoreCase(b.name()));
        int defaultTarget = defaultWorld == null ? 0 : targetTps(defaultWorld);
        return new Worlds(defaultTarget > 0 ? defaultTarget : firstTarget, maxViewRadius, onlinePlayers, out,
            HealthSnapshot.Radius.of(allLoads, allHot));
    }

    private static int targetTps(World world) {
        try {
            long tickStepNanos = world.getTickStepNanos();
            return tickStepNanos > 0 ? (int) Math.round(1_000_000_000.0 / tickStepNanos) : 0;
        } catch (Exception e) {
            return 0;
        }
    }

    private static World defaultWorld(Universe universe) {
        try {
            return universe.getDefaultWorld();
        } catch (Exception e) {
            return null;
        }
    }

    private static int maxViewRadius() {
        try {
            return HytaleServer.get().getConfig().getMaxViewRadius();
        } catch (Exception | LinkageError e) {
            return -1;
        }
    }

    private HealthSnapshot.Radius radius(World world, List<HealthSnapshot.PlayerLoad> allLoads, List<Integer> allHot) {
        try {
            List<HealthSnapshot.PlayerLoad> loads = new ArrayList<>();
            List<Integer> hotSettings = new ArrayList<>();
            for (PlayerRef ref : world.getPlayerRefs()) {
                if (ref == null) {
                    continue;
                }
                int view = -1;
                int hot = -1;
                int sections = -1;
                try {
                    Player player = ref.getComponentConcurrent(Player.getComponentType());
                    if (player != null) {
                        view = player.getViewRadius();
                    }
                } catch (Exception | LinkageError ignored) {
                }
                try {
                    ChunkTracker tracker = ref.getChunkTracker();
                    int setting = tracker.getMaxHotLoadedRadius();
                    hotSettings.add(setting);
                    hot = view >= 0 ? Math.min(setting, view) : setting;
                    sections = tracker.getLoadedSectionsCount();
                } catch (Exception | LinkageError ignored) {
                }
                String username = ref.getUsername();
                String name = username == null || username.isBlank() ? "unknown" : nameMasker.maskName(username);
                loads.add(new HealthSnapshot.PlayerLoad(name, view, hot, sections));
            }
            allLoads.addAll(loads);
            allHot.addAll(hotSettings);
            return HealthSnapshot.Radius.of(loads, hotSettings);
        } catch (Exception | LinkageError e) {
            return null;
        }
    }

    private static int onlinePlayers(Universe universe) {
        try {
            return universe.getPlayerCount();
        } catch (Exception e) {
            return -1;
        }
    }

    private static double[] msptPercentiles(HistoricMetric metrics) {
        try {
            long[] values = metrics.getValues(2);
            if (values == null || values.length == 0) {
                return new double[] {-1, -1, -1};
            }
            java.util.Arrays.sort(values);
            int n = values.length;
            double p50 = values[(int) Math.min(n - 1, Math.floor(n * 0.50))] / 1_000_000.0;
            double p95 = values[(int) Math.min(n - 1, Math.floor(n * 0.95))] / 1_000_000.0;
            return new double[] {p50, p95, values[n - 1] / 1_000_000.0};
        } catch (Throwable t) {
            return new double[] {-1, -1, -1};
        }
    }

    private List<String> playerNames(World world) {
        try {
            List<String> names = new ArrayList<>();
            for (com.hypixel.hytale.server.core.universe.PlayerRef ref : world.getPlayerRefs()) {
                if (ref == null) {
                    continue;
                }
                String username = ref.getUsername();
                if (username != null && !username.isBlank()) {
                    nameMasker.remember(username, ref.getUuid());
                    names.add(nameMasker.maskName(username));
                }
            }
            names.sort(String.CASE_INSENSITIVE_ORDER);
            return names;
        } catch (Throwable t) {
            return List.of();
        }
    }

    private static double avgPingMs(World world) {
        try {
            double totalMicros = 0;
            int count = 0;
            for (com.hypixel.hytale.server.core.universe.PlayerRef ref : world.getPlayerRefs()) {
                if (ref == null) {
                    continue;
                }
                try {
                    double micros = ref.getPacketHandler()
                        .getPingInfo(com.hypixel.hytale.protocol.packets.connection.PongType.Raw)
                        .getPingMetricSet().getAverage(0);
                    if (micros > 0) {
                        totalMicros += micros;
                        count++;
                    }
                } catch (Throwable ignored) {
                }
            }
            return count == 0 ? -1 : (totalMicros / count) / 1000.0;
        } catch (Throwable t) {
            return -1;
        }
    }

    private static int playerCount(World world) {
        try {
            return world.getPlayerCount();
        } catch (Exception e) {
            return -1;
        }
    }

    private static int entityCount(World world) {
        try {
            return world.getEntityStore().getStore().getEntityCount();
        } catch (Exception e) {
            return -1;
        }
    }

    private static int chunkCount(World world) {
        try {
            return world.getChunkStore().getLoadedChunksCount();
        } catch (Exception e) {
            return -1;
        }
    }

    private static long chunksGeneratedTotal(World world) {
        try {
            return world.getChunkStore().getTotalGeneratedChunksCount();
        } catch (Exception e) {
            return -1;
        }
    }

    private static long chunksLoadedTotal(World world) {
        try {
            return world.getChunkStore().getTotalLoadedChunksCount();
        } catch (Exception e) {
            return -1;
        }
    }
}
