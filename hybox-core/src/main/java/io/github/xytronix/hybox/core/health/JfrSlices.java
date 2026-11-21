package io.github.xytronix.hybox.core.health;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.UnaryOperator;

final class JfrSlices {
    static final long MIN_WINDOW_MS = 5000;
    static final int MIN_SAMPLES = 20;
    private static final int TOP = 3;
    private static final String NO_OWNER = "";
    private static final String UNKNOWN_THREAD = "(unknown thread)";

    private long[] times = new long[1024];
    private int[] frames = new int[1024];
    private int[] threads = new int[1024];
    private int size;
    private final Map<String, Map<String, Integer>> frameIds = new HashMap<>();
    private final List<String> methods = new ArrayList<>();
    private final List<String> owners = new ArrayList<>();
    private final Map<String, Integer> threadIds = new HashMap<>();
    private final List<String> threadNames = new ArrayList<>();

    void add(long t, String method, String owner, String thread) {
        if (t <= 0 || method == null) {
            return;
        }
        if (size == times.length) {
            int grown = size * 2;
            times = Arrays.copyOf(times, grown);
            frames = Arrays.copyOf(frames, grown);
            threads = Arrays.copyOf(threads, grown);
        }
        Map<String, Integer> byMethod = frameIds.computeIfAbsent(owner == null ? NO_OWNER : owner,
            k -> new HashMap<>());
        Integer frame = byMethod.get(method);
        if (frame == null) {
            frame = methods.size();
            byMethod.put(method, frame);
            methods.add(method);
            owners.add(owner);
        }
        String name = thread == null || thread.isBlank() ? UNKNOWN_THREAD : thread;
        Integer threadId = threadIds.get(name);
        if (threadId == null) {
            threadId = threadNames.size();
            threadIds.put(name, threadId);
            threadNames.add(name);
        }
        times[size] = t;
        frames[size] = frame;
        threads[size] = threadId;
        size++;
    }

    JfrSamples samples(UnaryOperator<String> resolve) {
        List<String> resolved = new ArrayList<>(owners.size());
        for (String owner : owners) {
            resolved.add(owner == null ? null : resolve.apply(owner));
        }
        return new JfrSamples(Arrays.copyOf(times, size), Arrays.copyOf(frames, size), Arrays.copyOf(threads, size),
            List.copyOf(methods), resolved, List.copyOf(threadNames));
    }

    JfrTimeline.Slices build(long lo, long hi, int buckets, UnaryOperator<String> resolve) {
        if (size == 0 || hi <= lo || buckets <= 0) {
            return null;
        }
        double bucketMs = (hi - lo + 1) / (double) buckets;
        double windowMs = Math.max(bucketMs, MIN_WINDOW_MS);
        int[] start = new int[buckets + 1];
        int[] bucketOf = new int[size];
        for (int i = 0; i < size; i++) {
            int b = (int) Math.max(0, Math.min(buckets - 1, (times[i] - lo) * buckets / (hi - lo + 1)));
            bucketOf[i] = b;
            start[b + 1]++;
        }
        for (int b = 0; b < buckets; b++) {
            start[b + 1] += start[b];
        }
        int[] order = new int[size];
        int[] fill = Arrays.copyOf(start, buckets);
        for (int i = 0; i < size; i++) {
            order[fill[bucketOf[i]]++] = i;
        }
        int reach = (int) Math.ceil(windowMs / 2 / bucketMs);
        int[] frameCount = new int[methods.size()];
        int[] threadCount = new int[threadNames.size()];
        int[] touchedFrames = new int[methods.size()];
        int[] touchedThreads = new int[threadNames.size()];
        Map<Integer, Integer> keptFrames = new HashMap<>();
        Map<Integer, Integer> keptThreads = new HashMap<>();
        List<JfrTimeline.SliceFrame> outFrames = new ArrayList<>();
        List<String> outThreads = new ArrayList<>();
        int[][] out = new int[buckets][];
        for (int b = 0; b < buckets; b++) {
            double center = lo + (b + 0.5) * bucketMs;
            double from = center - windowMs / 2;
            double to = center + windowMs / 2;
            int n = 0;
            int nf = 0;
            int nt = 0;
            for (int k = Math.max(0, b - reach); k <= Math.min(buckets - 1, b + reach); k++) {
                for (int j = start[k]; j < start[k + 1]; j++) {
                    int s = order[j];
                    if (times[s] < from || times[s] >= to) {
                        continue;
                    }
                    n++;
                    if (frameCount[frames[s]]++ == 0) {
                        touchedFrames[nf++] = frames[s];
                    }
                    if (threadCount[threads[s]]++ == 0) {
                        touchedThreads[nt++] = threads[s];
                    }
                }
            }
            if (n < MIN_SAMPLES) {
                out[b] = new int[] {n};
            } else {
                int[] top = top(touchedFrames, nf, frameCount);
                int thread = top(touchedThreads, nt, threadCount)[0];
                int[] row = new int[3 + 2 * top.length];
                row[0] = n;
                row[1] = keptThreads.computeIfAbsent(thread, id -> {
                    outThreads.add(threadNames.get(id));
                    return outThreads.size() - 1;
                });
                row[2] = threadCount[thread];
                for (int r = 0; r < top.length; r++) {
                    row[3 + 2 * r] = keptFrames.computeIfAbsent(top[r], id -> {
                        String owner = owners.get(id);
                        outFrames.add(new JfrTimeline.SliceFrame(methods.get(id),
                            owner == null ? null : resolve.apply(owner)));
                        return outFrames.size() - 1;
                    });
                    row[4 + 2 * r] = frameCount[top[r]];
                }
                out[b] = row;
            }
            for (int i = 0; i < nf; i++) {
                frameCount[touchedFrames[i]] = 0;
            }
            for (int i = 0; i < nt; i++) {
                threadCount[touchedThreads[i]] = 0;
            }
        }
        return new JfrTimeline.Slices(Math.round(windowMs / 100.0) / 10.0, MIN_SAMPLES, List.copyOf(outFrames),
            List.copyOf(outThreads), out);
    }

    private static int[] top(int[] ids, int n, int[] counts) {
        int[] best = new int[Math.min(TOP, n)];
        int filled = 0;
        for (int i = 0; i < n; i++) {
            int id = ids[i];
            int at = filled;
            while (at > 0 && better(id, best[at - 1], counts)) {
                at--;
            }
            if (at >= best.length) {
                continue;
            }
            int last = Math.min(filled, best.length - 1);
            System.arraycopy(best, at, best, at + 1, last - at);
            best[at] = id;
            filled = Math.min(best.length, filled + 1);
        }
        return best;
    }

    private static boolean better(int a, int b, int[] counts) {
        return counts[a] > counts[b] || (counts[a] == counts[b] && a < b);
    }
}
