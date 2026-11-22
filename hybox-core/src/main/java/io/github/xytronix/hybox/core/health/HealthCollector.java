package io.github.xytronix.hybox.core.health;

import java.lang.management.ManagementFactory;
import java.lang.management.MemoryUsage;
import java.nio.file.FileStore;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

public final class HealthCollector {
    private HealthCollector() {
    }

    public static HealthSnapshot.Cpu cpu() {
        return new HealthSnapshot.Cpu(Runtime.getRuntime().availableProcessors());
    }

    public static HealthSnapshot.Memory memory() {
        MemoryUsage heap = ManagementFactory.getMemoryMXBean().getHeapMemoryUsage();
        MemoryUsage nonHeap = ManagementFactory.getMemoryMXBean().getNonHeapMemoryUsage();
        return new HealthSnapshot.Memory(heap.getUsed(), heap.getCommitted(), heap.getMax(),
            nonHeap.getUsed(), nonHeap.getCommitted());
    }

    public static List<HealthSnapshot.Gc> gc() {
        List<HealthSnapshot.Gc> out = new ArrayList<>();
        ManagementFactory.getGarbageCollectorMXBeans().forEach(bean ->
            out.add(new HealthSnapshot.Gc(bean.getName(), bean.getCollectionCount(), bean.getCollectionTime())));
        return out;
    }

    public static HealthSnapshot.Sys system() {
        com.sun.management.OperatingSystemMXBean os = sunOsBean();
        long totalRam = os == null ? -1 : os.getTotalMemorySize();
        long uptime = ManagementFactory.getRuntimeMXBean().getUptime();
        String osName = System.getProperty("os.name") + " " + System.getProperty("os.version")
            + " (" + System.getProperty("os.arch") + ")";
        String jvm = System.getProperty("java.vm.name") + " " + System.getProperty("java.runtime.version");
        return new HealthSnapshot.Sys(osName, jvm, totalRam, uptime);
    }

    public static HealthSnapshot.Disk disk() {
        try {
            FileStore store = Files.getFileStore(Path.of("."));
            long total = store.getTotalSpace();
            long usable = store.getUsableSpace();
            return new HealthSnapshot.Disk(Math.max(0, total - usable), total);
        } catch (Exception e) {
            return new HealthSnapshot.Disk(-1, -1);
        }
    }

    public static HealthSnapshot.Threads threads() {
        Set<Thread> all = Thread.getAllStackTraces().keySet();
        Map<String, Integer> byState = new TreeMap<>();
        for (Thread thread : all) {
            byState.merge(thread.getState().name(), 1, Integer::sum);
        }
        return new HealthSnapshot.Threads(all.size(), new LinkedHashMap<>(byState), deadlocked());
    }

    public static List<String> deadlocked() {
        try {
            java.lang.management.ThreadMXBean tmx = ManagementFactory.getThreadMXBean();
            long[] ids = tmx.findDeadlockedThreads();
            if (ids == null || ids.length == 0) {
                return List.of();
            }
            List<String> out = new ArrayList<>();
            for (java.lang.management.ThreadInfo info : tmx.getThreadInfo(ids)) {
                if (info == null) {
                    continue;
                }
                String owner = info.getLockOwnerName();
                out.add(info.getThreadName() + " (" + info.getThreadState() + ") blocked on " + info.getLockName()
                    + (owner != null ? " held by " + owner : ""));
            }
            return out;
        } catch (Exception e) {
            return List.of();
        }
    }

    private static com.sun.management.OperatingSystemMXBean sunOsBean() {
        java.lang.management.OperatingSystemMXBean bean = ManagementFactory.getOperatingSystemMXBean();
        if (bean instanceof com.sun.management.OperatingSystemMXBean sun) {
            return sun;
        }
        return null;
    }
}
