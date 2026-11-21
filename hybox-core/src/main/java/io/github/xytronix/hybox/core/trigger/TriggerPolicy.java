package io.github.xytronix.hybox.core.trigger;

import java.time.Duration;
import java.util.Objects;
import java.util.Set;

/**
 * Defines trigger cooldowns and stall thresholds.
 */
public record TriggerPolicy(
    Duration cooldown,
    Duration debounce,
    long stallDegradedMs,
    long stallCriticalMs,
    long tickAvgDegradedMs,
    long tickAvgCriticalMs,
    DetectorPolicy detectors,
    Set<TriggerKind> cooldownExemptKinds
) {
    public static final Set<TriggerKind> DEFAULT_COOLDOWN_EXEMPT_KINDS =
        Set.of(TriggerKind.WORLD_FAILURE, TriggerKind.DEADLOCK, TriggerKind.HEARTBEAT_STALL);
    public static final double TICK_DEGRADED_BUDGET_FACTOR = 3.0;
    public static final double TICK_CRITICAL_BUDGET_FACTOR = 7.5;
    public static final double DEFAULT_TARGET_TPS = 30.0;

    public TriggerPolicy {
        Objects.requireNonNull(cooldown, "cooldown");
        Objects.requireNonNull(debounce, "debounce");
        detectors = detectors == null ? DetectorPolicy.defaults() : detectors;
        cooldownExemptKinds = cooldownExemptKinds == null
            ? DEFAULT_COOLDOWN_EXEMPT_KINDS
            : Set.copyOf(cooldownExemptKinds);
        if (cooldown.isNegative()) {
            throw new IllegalArgumentException("cooldown must be non-negative.");
        }
        if (debounce.isNegative()) {
            throw new IllegalArgumentException("debounce must be non-negative.");
        }
        if (stallDegradedMs <= 0 || stallCriticalMs <= 0) {
            throw new IllegalArgumentException("stall thresholds must be > 0.");
        }
        if (stallCriticalMs < stallDegradedMs) {
            throw new IllegalArgumentException("stallCriticalMs must be >= stallDegradedMs.");
        }
        if (tickAvgDegradedMs < 0 || tickAvgCriticalMs < 0) {
            throw new IllegalArgumentException("tick average thresholds must be >= 0.");
        }
        if (tickAvgDegradedMs > 0 && tickAvgCriticalMs > 0 && tickAvgCriticalMs < tickAvgDegradedMs) {
            throw new IllegalArgumentException("tickAvgCriticalMs must be >= tickAvgDegradedMs.");
        }
    }

    public double tickDegradedMs(double targetTps) {
        return tickAvgDegradedMs > 0 ? tickAvgDegradedMs : budgetMultiple(TICK_DEGRADED_BUDGET_FACTOR, targetTps);
    }

    public double tickCriticalMs(double targetTps) {
        double critical = tickAvgCriticalMs > 0
            ? tickAvgCriticalMs
            : budgetMultiple(TICK_CRITICAL_BUDGET_FACTOR, targetTps);
        return Math.max(critical, tickDegradedMs(targetTps));
    }

    private static double budgetMultiple(double factor, double targetTps) {
        double tps = Double.isFinite(targetTps) && targetTps > 0 ? targetTps : DEFAULT_TARGET_TPS;
        return factor * 1000.0 / tps;
    }

    public TriggerPolicy(
        Duration cooldown,
        Duration debounce,
        long stallDegradedMs,
        long stallCriticalMs,
        long tickAvgDegradedMs,
        long tickAvgCriticalMs
    ) {
        this(cooldown, debounce, stallDegradedMs, stallCriticalMs,
            tickAvgDegradedMs, tickAvgCriticalMs, DetectorPolicy.defaults(), DEFAULT_COOLDOWN_EXEMPT_KINDS);
    }
}
