package io.github.xytronix.hybox.core.trigger.heartbeat;

import java.time.Clock;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import io.github.xytronix.hybox.core.trigger.TriggerEvent;
import io.github.xytronix.hybox.core.trigger.TriggerKind;

/**
 * Emits heartbeat stall trigger events on transition into a stalled state.
 */
public final class HeartbeatStallDetector {
    private final Clock clock;
    private final HeartbeatRegistry registry;
    private final long degradedMs;
    private final Map<String, State> states = new HashMap<>();

    public HeartbeatStallDetector(Clock clock, HeartbeatRegistry registry, long degradedMs) {
        this.clock = Objects.requireNonNull(clock, "clock");
        this.registry = Objects.requireNonNull(registry, "registry");
        if (degradedMs <= 0) {
            throw new IllegalArgumentException("degradedMs must be > 0.");
        }
        this.degradedMs = degradedMs;
    }

    public List<TriggerEvent> check() {
        long now = clock.millis();
        Set<String> scopes = registry.scopes();
        states.keySet().retainAll(scopes);
        List<TriggerEvent> events = List.of();
        for (String scope : scopes) {
            long last = registry.lastBeatMillis(scope);
            if (last < 0) {
                continue;
            }
            State state = states.get(scope);
            if (state == null) {
                state = new State();
                states.put(scope, state);
            }
            if (last > state.lastSeen) {
                state.lastSeen = last;
                state.stalled = false;
            }
            long pending = registry.pendingSinceMillis(scope);
            long stallMs = now - (pending >= 0 ? pending : last);
            boolean stalled = stallMs >= degradedMs;
            if (stalled && !state.stalled) {
                state.stalled = true;
                Map<String, String> attrs = new LinkedHashMap<>();
                attrs.put("stallMs", Long.toString(stallMs));
                Long threadId = registry.lastBeatThreadId(scope);
                if (threadId != null) {
                    attrs.put("threadId", Long.toString(threadId));
                }
                if (events.isEmpty()) {
                    events = new ArrayList<>();
                }
                events.add(new TriggerEvent(TriggerKind.HEARTBEAT_STALL, scope, clock.instant(), attrs));
            } else if (!stalled && state.stalled) {
                state.stalled = false;
            }
        }
        return events;
    }

    private static final class State {
        private long lastSeen = Long.MIN_VALUE;
        private boolean stalled;
    }
}
