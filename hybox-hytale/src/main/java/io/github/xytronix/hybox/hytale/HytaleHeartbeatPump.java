package io.github.xytronix.hybox.hytale;

import com.hypixel.hytale.server.core.universe.Universe;
import com.hypixel.hytale.server.core.universe.world.World;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import io.github.xytronix.hybox.core.env.ThreadDumper;
import io.github.xytronix.hybox.core.trigger.TriggerEvent;
import io.github.xytronix.hybox.core.trigger.TriggerKind;
import io.github.xytronix.hybox.core.trigger.heartbeat.HeartbeatRegistry;

final class HytaleHeartbeatPump {
    private final System.Logger logger;
    private final HeartbeatRegistry heartbeatRegistry;
    private final Map<String, Heartbeat> heartbeats = new ConcurrentHashMap<>();

    HytaleHeartbeatPump(System.Logger logger, HeartbeatRegistry heartbeatRegistry) {
        this.logger = Objects.requireNonNull(logger, "logger");
        this.heartbeatRegistry = Objects.requireNonNull(heartbeatRegistry, "heartbeatRegistry");
    }

    void tick() {
        try {
            tick(Universe.get().getWorlds());
        } catch (Exception e) {
            logger.log(System.Logger.Level.WARNING, "Heartbeat tick failed.", e);
        }
    }

    void tick(Map<String, World> worlds) {
        boolean retired = false;
        for (Iterator<Map.Entry<String, Heartbeat>> it = heartbeats.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<String, Heartbeat> entry = it.next();
            Heartbeat heartbeat = entry.getValue();
            if (worlds.get(entry.getKey()) != heartbeat.world || !heartbeat.world.isAlive()) {
                heartbeat.retire();
                it.remove();
                retired = true;
            }
        }
        if (retired) {
            heartbeatRegistry.retain(heartbeats.keySet());
        }

        for (Map.Entry<String, World> entry : worlds.entrySet()) {
            String scope = entry.getKey();
            World world = entry.getValue();
            if (scope == null || scope.isBlank() || world == null || !world.isAlive()) {
                continue;
            }
            Heartbeat heartbeat = heartbeats.get(scope);
            if (heartbeat == null) {
                heartbeat = new Heartbeat(scope, world);
                Heartbeat raced = heartbeats.putIfAbsent(scope, heartbeat);
                if (raced != null) {
                    heartbeat = raced;
                }
            }
            if (heartbeat.world != world || !heartbeat.pending.compareAndSet(false, true)) {
                continue;
            }

            try {
                heartbeatRegistry.posted(scope);
                world.execute(heartbeat.task);
            } catch (Exception e) {
                heartbeat.pending.set(false);
                if (rejectedByStoppingWorld(e)) {
                    heartbeat.retire();
                    heartbeats.remove(scope, heartbeat);
                    heartbeatRegistry.retain(heartbeats.keySet());
                } else {
                    logger.log(System.Logger.Level.WARNING, "Failed to post heartbeat for " + scope, e);
                }
            }
        }
    }

    private static boolean rejectedByStoppingWorld(Throwable failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof IllegalThreadStateException) {
                return true;
            }
        }
        return false;
    }

    private final class Heartbeat {
        private final String scope;
        private final World world;
        private final AtomicBoolean pending = new AtomicBoolean();
        private final Runnable task = this::run;
        private boolean retired;

        private Heartbeat(String scope, World world) {
            this.scope = scope;
            this.world = world;
        }

        private void run() {
            try {
                beat();
            } catch (Exception e) {
                logger.log(System.Logger.Level.WARNING, "Failed to beat heartbeat for " + scope, e);
            } finally {
                pending.set(false);
            }
        }

        private synchronized void beat() {
            if (!retired) {
                heartbeatRegistry.beat(scope);
            }
        }

        private synchronized void retire() {
            retired = true;
        }
    }

    static List<String> awaitRecovery(
        TriggerEvent event,
        HeartbeatRegistry registry,
        Clock clock,
        Duration maxWait,
        int dumpCount,
        Duration dumpInterval,
        BooleanSupplier recovered,
        BooleanSupplier skipped,
        System.Logger logger
    ) {
        List<String> dumps = new ArrayList<>();
        boolean heartbeat = event.kind() == TriggerKind.HEARTBEAT_STALL;
        String scope = event.scope();
        if (heartbeat && (scope == null || scope.isBlank())) {
            return dumps;
        }
        int maxDumps = Math.max(0, dumpCount);
        if (maxDumps > 0) {
            dumps.add(ThreadDumper.dump());
        }
        if (maxWait == null || maxWait.isZero() || maxWait.isNegative()) {
            return dumps;
        }
        Instant triggerAt = event.at();
        Instant deadline = clock.instant().plus(maxWait);
        boolean spacing = dumpInterval != null && !dumpInterval.isZero() && !dumpInterval.isNegative();
        Instant nextDump = spacing ? clock.instant().plus(dumpInterval) : null;
        while (clock.instant().isBefore(deadline)) {
            if (skipped != null && skipped.getAsBoolean()) {
                logger.log(System.Logger.Level.INFO, "Post-incident wait for " + event.kind() + " skipped by a player.");
                return dumps;
            }
            if (heartbeat) {
                Instant last = registry.lastBeat(scope);
                if (last != null && last.isAfter(triggerAt)) {
                    logger.log(System.Logger.Level.INFO,
                        "Heartbeat for '" + scope + "' resumed. Saving the recording with the recovery.");
                    return dumps;
                }
            } else if (recovered != null && recovered.getAsBoolean()) {
                logger.log(System.Logger.Level.INFO,
                    event.kind() + " for '" + scope + "' recovered. Saving the recording with the recovery.");
                return dumps;
            }
            if (nextDump != null && dumps.size() < maxDumps && !clock.instant().isBefore(nextDump)) {
                dumps.add(ThreadDumper.dump());
                nextDump = clock.instant().plus(dumpInterval);
            }
            try {
                Thread.sleep(250L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return dumps;
            }
        }
        if (heartbeat) {
            logger.log(System.Logger.Level.INFO,
                "Heartbeat for '" + scope + "' did not resume within " + maxWait.toSeconds()
                + "s. Saving the recording now.");
        } else {
            logger.log(System.Logger.Level.INFO,
                "Recorded a " + maxWait.toSeconds() + "s post-incident tail for " + event.kind()
                + ". Saving the recording.");
        }
        return dumps;
    }
}
