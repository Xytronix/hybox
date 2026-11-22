package io.github.xytronix.hybox.core.trigger;

import java.time.Clock;
import java.time.Instant;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import io.github.xytronix.hybox.core.incident.Severity;
import io.github.xytronix.hybox.core.text.Units;

/**
 * Applies cooldown and debounce policies to trigger events.
 */
public final class TriggerEngine {
    private final Clock clock;
    private final TriggerPolicy policy;
    private final Map<String, Instant> lastAcceptedByKey = new HashMap<>();
    private Instant lastAcceptedAt;

    public TriggerEngine(Clock clock, TriggerPolicy policy) {
        this.clock = Objects.requireNonNull(clock, "clock");
        this.policy = Objects.requireNonNull(policy, "policy");
    }

    public TriggerResult evaluate(TriggerEvent event) {
        Objects.requireNonNull(event, "event");
        Instant now = event.at() == null ? clock.instant() : event.at();

        boolean profileCompletion = event.kind() == TriggerKind.MANUAL && event.attrs().containsKey("profileStartMs");
        if (!profileCompletion && lastAcceptedAt != null && !policy.cooldownExemptKinds().contains(event.kind())) {
            Instant cooldownUntil = lastAcceptedAt.plus(policy.cooldown());
            if (now.isBefore(cooldownUntil)) {
                return new TriggerResult(TriggerDecision.COOLDOWN, Severity.INFO,
                    "Capture skipped. Another capture ran less than " + Units.elapsed(policy.cooldown().toMillis()) + " ago.");
            }
        }

        String key = event.kind() + "|" + event.scope();
        Instant lastForKey = lastAcceptedByKey.get(key);
        if (!profileCompletion && lastForKey != null) {
            Instant debounceUntil = lastForKey.plus(policy.debounce());
            if (now.isBefore(debounceUntil)) {
                return new TriggerResult(TriggerDecision.DEBOUNCE, Severity.INFO,
                    "Capture skipped. The same trigger fired less than " + Units.elapsed(policy.debounce().toMillis()) + " ago.");
            }
        }

        TriggerResult accepted = decideAccepted(event);
        lastAcceptedAt = lastAcceptedAt != null && lastAcceptedAt.isAfter(now) ? lastAcceptedAt : now;
        lastAcceptedByKey.merge(key, now, (previous, current) -> previous.isAfter(current) ? previous : current);
        return accepted;
    }

    private TriggerResult decideAccepted(TriggerEvent event) {
        if (event.kind() == TriggerKind.MANUAL) {
            String reason = event.attrs().get("reason");
            String headline = reason == null || reason.isBlank()
                ? "Manual capture"
                : "Manual capture: " + reason;
            return new TriggerResult(TriggerDecision.ACCEPT, Severity.INFO, headline);
        }
        if (event.kind() == TriggerKind.HEARTBEAT_STALL) {
            long stallMs = parseLong(event.attrs().get("stallMs"));
            Severity severity = Severity.INFO;
            if (stallMs >= policy.stallCriticalMs()) {
                severity = Severity.CRITICAL;
            } else if (stallMs >= policy.stallDegradedMs()) {
                severity = Severity.DEGRADED;
            }
            String headline = "World " + event.scope() + " stopped responding for " + Units.elapsed(stallMs);
            return new TriggerResult(TriggerDecision.ACCEPT, severity, headline);
        }
        if (event.kind() == TriggerKind.WORLD_FAILURE) {
            String error = event.attrs().get("error");
            String headline = error == null || error.isBlank()
                ? "World " + event.scope() + " failed"
                : "World " + event.scope() + " failed: " + error;
            return new TriggerResult(TriggerDecision.ACCEPT, Severity.CRITICAL, headline);
        }
        if (event.kind() == TriggerKind.TICK_DEGRADED) {
            long tickAvgMs = parseLong(event.attrs().get("tickAvgMs"));
            Severity severity = Severity.INFO;
            if (tickAvgMs >= policy.tickAvgCriticalMs()) {
                severity = Severity.CRITICAL;
            } else if (tickAvgMs >= policy.tickAvgDegradedMs()) {
                severity = Severity.DEGRADED;
            }
            String headline = "World " + event.scope() + " is slow: " + tickAvgMs + " ms per tick";
            return new TriggerResult(TriggerDecision.ACCEPT, severity, headline);
        }
        if (event.kind() == TriggerKind.DEADLOCK) {
            long threads = parseLong(event.attrs().get("threads"));
            return new TriggerResult(TriggerDecision.ACCEPT, Severity.CRITICAL,
                Units.count(threads, "thread") + (threads == 1 ? " is" : " are") + " deadlocked");
        }
        if (event.kind() == TriggerKind.HEAP_PRESSURE) {
            long usedPct = parseLong(event.attrs().get("usedPct"));
            Severity severity = usedPct >= HEAP_CRITICAL_PCT ? Severity.CRITICAL : Severity.DEGRADED;
            return new TriggerResult(TriggerDecision.ACCEPT, severity,
                "Heap " + usedPct + "% full after GC");
        }
        if (event.kind() == TriggerKind.GC_PRESSURE) {
            long gcPct = parseLong(event.attrs().get("gcPct"));
            return new TriggerResult(TriggerDecision.ACCEPT, Severity.DEGRADED,
                gcPct + "% of the time spent in GC pauses");
        }
        if (event.kind() == TriggerKind.CPU_SATURATION) {
            long cpuPct = parseLong(event.attrs().get("cpuPct"));
            return new TriggerResult(TriggerDecision.ACCEPT, Severity.DEGRADED,
                "Server CPU at " + cpuPct + "%");
        }
        if (event.kind() == TriggerKind.NET_SATURATION) {
            String direction = event.attrs().get("direction");
            long mbps = parseLong(event.attrs().get("mbps"));
            String traffic = "in".equals(direction) ? "Inbound traffic" : "out".equals(direction)
                ? "Outbound traffic" : "Network traffic";
            return new TriggerResult(TriggerDecision.ACCEPT, Severity.DEGRADED,
                traffic + " at " + mbps + " Mbit/s");
        }
        if (event.kind() == TriggerKind.PLAYER_DROP) {
            long before = parseLong(event.attrs().get("before"));
            long after = parseLong(event.attrs().get("after"));
            return new TriggerResult(TriggerDecision.ACCEPT, Severity.DEGRADED,
                "Players dropped from " + before + " to " + after);
        }
        if (event.kind() == TriggerKind.LOG_ERROR) {
            String error = event.attrs().get("error");
            String headline = error == null || error.isBlank()
                ? "Server error logged"
                : "Server error: " + error;
            return new TriggerResult(TriggerDecision.ACCEPT, Severity.DEGRADED, headline);
        }
        String kind = event.kind().name().replace('_', ' ').toLowerCase(Locale.ROOT);
        return new TriggerResult(TriggerDecision.ACCEPT, Severity.INFO,
            Character.toUpperCase(kind.charAt(0)) + kind.substring(1));
    }

    private static final long HEAP_CRITICAL_PCT = 97;

    private static long parseLong(String value) {
        if (value == null) {
            return 0L;
        }
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException e) {
            return 0L;
        }
    }
}
