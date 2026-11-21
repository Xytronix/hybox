package io.github.xytronix.hybox.core.health;

import java.util.List;

public final class JfrSamples {
    public record Top(String method, String owner, int samples, int threadSamples) {}

    private final long[] times;
    private final int[] frames;
    private final int[] threads;
    private final List<String> methods;
    private final List<String> owners;
    private final List<String> threadNames;

    JfrSamples(long[] times, int[] frames, int[] threads, List<String> methods, List<String> owners,
               List<String> threadNames) {
        this.times = times;
        this.frames = frames;
        this.threads = threads;
        this.methods = methods;
        this.owners = owners;
        this.threadNames = threadNames;
    }

    public int count(long fromMs, long toMs) {
        int count = 0;
        for (long time : times) {
            if (time >= fromMs && time <= toMs) {
                count++;
            }
        }
        return count;
    }

    public boolean around(long fromMs, long toMs) {
        boolean before = false;
        boolean after = false;
        for (long time : times) {
            before |= time < fromMs;
            after |= time > toMs;
            if (before && after) {
                return true;
            }
        }
        return false;
    }

    public Top top(String thread, long fromMs, long toMs) {
        int id = thread == null ? -1 : threadNames.indexOf(JfrTimelineParser.normalizeThreadName(thread));
        if (id < 0) {
            return null;
        }
        int[] counts = new int[methods.size()];
        int total = 0;
        int best = -1;
        for (int i = 0; i < times.length; i++) {
            if (threads[i] != id || times[i] < fromMs || times[i] > toMs) {
                continue;
            }
            total++;
            int frame = frames[i];
            counts[frame]++;
            if (best < 0 || counts[frame] > counts[best]) {
                best = frame;
            }
        }
        return best < 0 ? null : new Top(methods.get(best), owners.get(best), counts[best], total);
    }
}
