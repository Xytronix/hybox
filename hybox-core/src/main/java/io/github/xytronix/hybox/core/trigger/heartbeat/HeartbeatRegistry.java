package io.github.xytronix.hybox.core.trigger.heartbeat;

import java.time.Clock;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Stores last heartbeat timestamps per scope.
 */
public final class HeartbeatRegistry {
    private static final long GAP_MILLIS = 500;
    private static final long GAP_MATCH_MILLIS = 250;
    private static final int GAPS_KEPT = 8;

    private final Clock clock;
    private final Map<String, Beat> beats = new ConcurrentHashMap<>();

    public HeartbeatRegistry(Clock clock) {
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public void beat(String scope) {
        Beat beat = holder(scope);
        long now = clock.millis();
        long previous = beat.epochMillis;
        long waitedSince = beat.postedMillis > previous ? beat.postedMillis : previous;
        if (previous >= 0 && now - waitedSince >= GAP_MILLIS) {
            beat.ended(waitedSince, now);
        }
        beat.threadId = Thread.currentThread().threadId();
        beat.epochMillis = now;
    }

    public long resumedAfter(String scope, long waitedSinceMillis) {
        Beat beat = beats.get(scope);
        if (beat == null) {
            return -1;
        }
        long resumed = -1;
        for (long[] gap : beat.gaps) {
            if (Math.abs(gap[0] - waitedSinceMillis) <= GAP_MATCH_MILLIS && (resumed < 0 || gap[1] < resumed)) {
                resumed = gap[1];
            }
        }
        return resumed;
    }

    public void posted(String scope) {
        Beat beat = holder(scope);
        if (beat.postedMillis <= beat.epochMillis) {
            beat.postedMillis = clock.millis();
        }
    }

    private Beat holder(String scope) {
        Objects.requireNonNull(scope, "scope");
        Beat beat = beats.get(scope);
        if (beat == null) {
            if (scope.isBlank()) {
                throw new IllegalArgumentException("scope must be non-blank.");
            }
            beat = beats.computeIfAbsent(scope, ignored -> new Beat());
        }
        return beat;
    }

    public Instant lastBeat(String scope) {
        long millis = lastBeatMillis(scope);
        return millis < 0 ? null : Instant.ofEpochMilli(millis);
    }

    public long lastBeatMillis(String scope) {
        Beat beat = beats.get(scope);
        return beat == null ? -1 : beat.epochMillis;
    }

    public long pendingSinceMillis(String scope) {
        Beat beat = beats.get(scope);
        if (beat == null) {
            return -1;
        }
        long posted = beat.postedMillis;
        return posted > beat.epochMillis ? posted : -1;
    }

    public Long lastBeatThreadId(String scope) {
        Beat beat = beats.get(scope);
        return beat == null || beat.epochMillis < 0 ? null : beat.threadId;
    }

    public Set<String> scopes() {
        return Set.copyOf(beats.keySet());
    }

    public void retain(Set<String> scopes) {
        Objects.requireNonNull(scopes, "scopes");
        beats.keySet().retainAll(scopes);
    }

    public Map<String, Instant> snapshot() {
        Map<String, Instant> out = new HashMap<>();
        beats.forEach((scope, beat) -> {
            long millis = beat.epochMillis;
            if (millis >= 0) {
                out.put(scope, Instant.ofEpochMilli(millis));
            }
        });
        return Map.copyOf(out);
    }

    private static final class Beat {
        private volatile long threadId;
        private volatile long epochMillis = -1;
        private volatile long postedMillis = -1;
        private volatile long[][] gaps = new long[0][];

        private synchronized void ended(long from, long to) {
            int keep = Math.min(gaps.length, GAPS_KEPT - 1);
            long[][] next = new long[keep + 1][];
            System.arraycopy(gaps, gaps.length - keep, next, 0, keep);
            next[keep] = new long[] {from, to};
            gaps = next;
        }
    }
}
