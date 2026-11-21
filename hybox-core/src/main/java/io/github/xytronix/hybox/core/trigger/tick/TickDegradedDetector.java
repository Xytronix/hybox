package io.github.xytronix.hybox.core.trigger.tick;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import io.github.xytronix.hybox.core.trigger.TriggerEvent;
import io.github.xytronix.hybox.core.trigger.TriggerKind;
import io.github.xytronix.hybox.core.trigger.TriggerPolicy;

public final class TickDegradedDetector {

    private static final double REARM_FACTOR = 0.9;

    public record Tick(double avgMs, double targetTps) {
    }

    @FunctionalInterface
    public interface Source {
        Map<String, Tick> tickAverageMillis();
    }

    private final Clock clock;
    private final Source source;
    private final TriggerPolicy policy;
    private final Map<String, Boolean> inDegraded = new ConcurrentHashMap<>();

    public TickDegradedDetector(Clock clock, Source source, TriggerPolicy policy) {
        this.clock = Objects.requireNonNull(clock, "clock");
        this.source = Objects.requireNonNull(source, "source");
        this.policy = Objects.requireNonNull(policy, "policy");
    }

    public List<TriggerEvent> check() {
        Instant now = clock.instant();
        Map<String, Tick> ticks = source.tickAverageMillis();
        inDegraded.keySet().retainAll(ticks.keySet());
        List<TriggerEvent> events = new ArrayList<>();
        for (Map.Entry<String, Tick> entry : ticks.entrySet()) {
            String scope = entry.getKey();
            Tick tick = entry.getValue();
            if (scope == null || scope.isBlank() || tick == null || !Double.isFinite(tick.avgMs())) {
                continue;
            }
            double targetTps = Double.isFinite(tick.targetTps()) && tick.targetTps() > 0
                ? tick.targetTps()
                : TriggerPolicy.DEFAULT_TARGET_TPS;
            double threshold = policy.tickDegradedMs(targetTps);
            boolean wasDegraded = inDegraded.getOrDefault(scope, false);
            if (!wasDegraded && tick.avgMs() >= threshold) {
                inDegraded.put(scope, true);
                events.add(new TriggerEvent(
                    TriggerKind.TICK_DEGRADED,
                    scope,
                    now,
                    Map.of(
                        "tickAvgMs", Long.toString(Math.round(tick.avgMs())),
                        "targetTps", Long.toString(Math.round(targetTps))
                    )
                ));
            } else if (wasDegraded && tick.avgMs() < threshold * REARM_FACTOR) {
                inDegraded.put(scope, false);
            }
        }
        return events;
    }

    public boolean recovered(String scope) {
        return scope == null || !inDegraded.getOrDefault(scope, false);
    }
}
