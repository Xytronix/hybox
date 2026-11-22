package io.github.xytronix.hybox.core.health;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.IntFunction;
import java.util.function.UnaryOperator;

import jdk.jfr.consumer.RecordedClassLoader;
import jdk.jfr.consumer.RecordedFrame;
import jdk.jfr.consumer.RecordedMethod;
import jdk.jfr.consumer.RecordedStackTrace;

import io.github.xytronix.hybox.core.health.JfrTimeline.ModCpu;
import io.github.xytronix.hybox.core.health.JfrTimeline.ModShare;
import io.github.xytronix.hybox.core.health.JfrTimeline.ThreadShare;

final class JfrModAttribution {
    private static final String HYTALE_PREFIX = "com.hypixel.";
    private static final String HYBOX_PREFIX = "io.github.xytronix.hybox.";
    private static final String CAPTURE_THREAD = "hybox-capture";
    private static final String[] LIBRARY_PREFIXES = {
        "java.", "jdk.", "sun.", "javax.", "com.sun.", "io.netty.", "com.mojang.", "it.unimi.", "org.joml", "org.lwjgl",
        "com.google.", "kotlin.", "scala.", "org.spongepowered.", "org.bson", "org.mongodb", "org.slf4j", "org.apache.",
        "com.github.luben.zstd.", "org.jline.", "org.objectweb.asm.", "com.llamalad7.mixinextras.", "net.fabricmc.",
        "org.bouncycastle.", "io.sentry.", "com.nimbusds.", "ch.randelshofer.", "joptsimple.", "org.checkerframework.",
        "org.w3c.", "org.xml.", "org.ietf.", "org.jcp.xml."
    };
    private static final String LABEL_MARK = "@";
    private static final String JAR_MARK = "#";
    private static final String AMBIGUOUS = "";
    private static final int MIN_JAR_PREFIX = 4;
    private static final int THREAD_SHARES = 3;

    record Owners(String nearest, String nearestMethod, String self, String selfMethod, List<String> present,
                  List<String> presentMethods) {
        static final Owners NONE = new Owners(null, null, null, null, List.of(), List.of());
    }

    private static final class Tally {
        long samples;
        long self;
        long total;
        final Map<String, long[]> methods = new HashMap<>();
        final Map<String, long[]> threads = new HashMap<>();
        final Map<String, long[]> selfMethods = new HashMap<>();
        final Map<String, long[]> selfThreads = new HashMap<>();
        final Map<String, long[]> totalMethods = new HashMap<>();
    }

    private final Map<String, Map<Owners, long[]>> cpu = new HashMap<>();
    private final Map<String, Map<Owners, long[]>> alloc = new HashMap<>();
    private final Set<String> loaderIds = new HashSet<>();
    private final Set<String> packages = new HashSet<>();
    private final Map<String, String> packageLoaders = new HashMap<>();
    private final Map<String, String> resolved = new HashMap<>();
    private final Set<String> hyboxIds = new HashSet<>();
    private final Map<Owners, Owners> captureOwners = new HashMap<>();

    static boolean isLibrary(String type) {
        for (String prefix : LIBRARY_PREFIXES) {
            if (type.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }

    static String packageId(String type) {
        String clean = JfrTimelineParser.cleanType(type);
        int first = clean.indexOf('.');
        if (first < 0) {
            return clean;
        }
        int second = clean.indexOf('.', first + 1);
        if (second < 0) {
            return clean;
        }
        int third = clean.indexOf('.', second + 1);
        return third < 0 ? clean : clean.substring(0, third);
    }

    static boolean mixinCode(String methodName, String label) {
        return FrameLabels.mixinCodeMod(label) != null || FrameLabels.mixinJar(methodName) != null;
    }

    static boolean captureThread(String thread) {
        return thread != null && thread.startsWith(CAPTURE_THREAD);
    }

    Owners capture(Owners owners) {
        return owners == Owners.NONE ? owners : captureOwners.computeIfAbsent(owners, o -> new Owners(captured(o.nearest()),
            o.nearestMethod(), captured(o.self()), o.selfMethod(), o.present().stream().map(this::captured).toList(),
            o.presentMethods()));
    }

    private String captured(String raw) {
        return raw != null && hyboxIds.contains(raw) ? JfrTimeline.CAPTURE_MOD : raw;
    }

    static String loaderId(RecordedFrame frame) {
        try {
            RecordedClassLoader loader = frame.getMethod().getType().getClassLoader();
            String name = loader == null ? null : loader.getName();
            if (name == null || !name.endsWith(")")) {
                return null;
            }
            int open = name.indexOf('(');
            if (open <= 0 || !(name.startsWith("ThirdParty(") || name.startsWith("BuiltinPlugin("))) {
                return null;
            }
            String id = name.substring(open + 1, name.length() - 1).trim();
            return id.isBlank() ? null : id;
        } catch (Exception e) {
            return null;
        }
    }

    Owners owners(RecordedStackTrace stack) {
        List<RecordedFrame> frames = stack.getFrames();
        int size = frames.size();
        String[] types = new String[size];
        String[] names = new String[size];
        for (int i = 0; i < size; i++) {
            RecordedFrame frame = frames.get(i);
            RecordedMethod method = frame.isJavaFrame() ? frame.getMethod() : null;
            if (method != null) {
                types[i] = method.getType().getName();
                names[i] = method.getName();
            }
        }
        return owners(types, names, i -> FrameLabels.label(JfrTimelineParser.cleanType(types[i]), names[i]),
            i -> loaderId(frames.get(i)));
    }

    Owners owners(String[] types, String[] names, IntFunction<String> labelOf, IntFunction<String> loaderOf) {
        String nearest = null;
        String nearestMethod = null;
        String self = null;
        String selfMethod = null;
        boolean selfFound = false;
        List<String> present = null;
        List<String> presentMethods = null;
        for (int i = 0; i < types.length; i++) {
            String type = types[i];
            if (type == null) {
                continue;
            }
            boolean library = isLibrary(type);
            String id;
            if (library || type.startsWith(HYTALE_PREFIX)) {
                id = mixinId(names[i], labelOf.apply(i));
                if (!selfFound && (id != null || !library)) {
                    selfFound = true;
                    self = id;
                    selfMethod = id == null ? null : method(type, names[i]);
                }
            } else {
                id = observe(type, loaderOf.apply(i));
                if (nearest == null) {
                    nearest = id;
                    nearestMethod = method(type, names[i]);
                }
                if (!selfFound) {
                    selfFound = true;
                    self = id;
                    selfMethod = method(type, names[i]);
                }
            }
            if (id != null) {
                if (present == null) {
                    present = new ArrayList<>(4);
                    presentMethods = new ArrayList<>(4);
                }
                if (!present.contains(id)) {
                    present.add(id);
                    presentMethods.add(method(type, names[i]));
                }
            }
        }
        return present == null ? Owners.NONE
            : new Owners(nearest, nearestMethod, self, selfMethod, List.copyOf(present), List.copyOf(presentMethods));
    }

    void cpu(Owners owners, String thread) {
        if (owners != Owners.NONE) {
            cpu.computeIfAbsent(thread, k -> new HashMap<>()).computeIfAbsent(owners, k -> new long[1])[0]++;
        }
    }

    void alloc(Owners owners, String world, long weight) {
        if (owners != Owners.NONE && weight > 0) {
            alloc.computeIfAbsent(world, k -> new HashMap<>()).computeIfAbsent(owners, k -> new long[1])[0] += weight;
        }
    }

    List<ModCpu> cpu(int limit) {
        Map<String, Tally> mods = new HashMap<>();
        cpu.forEach((thread, counts) -> counts.forEach((owners, count) -> add(mods, owners, count[0], thread, true)));
        List<ModCpu> out = new ArrayList<>();
        for (Map.Entry<String, Tally> en : ranked(mods, limit)) {
            Tally t = en.getValue();
            out.add(new ModCpu(en.getKey(), t.samples, dominant(t.methods), shares(t.threads),
                t.self, t.total, dominant(t.selfMethods), shares(t.selfThreads), dominant(t.totalMethods)));
        }
        return out;
    }

    Map<String, Map<String, ModShare>> cpuBy(UnaryOperator<String> group, int limit) {
        return grouped(cpu, group, limit);
    }

    Map<String, ModShare> alloc(int limit) {
        Map<String, Tally> mods = new HashMap<>();
        alloc.forEach((world, counts) -> counts.forEach((owners, bytes) -> add(mods, owners, bytes[0], null, false)));
        return shares(mods, limit);
    }

    Map<String, Map<String, ModShare>> allocByWorld(int limit) {
        return grouped(alloc, UnaryOperator.identity(), limit);
    }

    private Map<String, Map<String, ModShare>> grouped(Map<String, Map<Owners, long[]>> source,
                                                       UnaryOperator<String> group, int limit) {
        Map<String, Map<String, Tally>> groups = new LinkedHashMap<>();
        source.forEach((key, counts) -> {
            Map<String, Tally> into = groups.computeIfAbsent(group.apply(key), k -> new HashMap<>());
            counts.forEach((owners, count) -> add(into, owners, count[0], null, false));
        });
        Map<String, Map<String, ModShare>> out = new LinkedHashMap<>();
        groups.forEach((key, mods) -> out.put(key, shares(mods, limit)));
        return out;
    }

    private void add(Map<String, Tally> into, Owners owners, long count, String thread, boolean detail) {
        String nearest = resolve(owners.nearest());
        if (nearest != null) {
            Tally t = into.computeIfAbsent(nearest, k -> new Tally());
            t.samples += count;
            if (detail) {
                bump(t.methods, owners.nearestMethod(), count);
                bump(t.threads, thread, count);
            }
        }
        String self = resolve(owners.self());
        if (self != null) {
            Tally t = into.computeIfAbsent(self, k -> new Tally());
            t.self += count;
            if (detail) {
                bump(t.selfMethods, owners.selfMethod(), count);
                bump(t.selfThreads, thread, count);
            }
        }
        List<String> present = owners.present();
        for (int i = 0; i < present.size(); i++) {
            String id = resolve(present.get(i));
            boolean counted = false;
            for (int j = 0; j < i && !counted; j++) {
                counted = id.equals(resolve(present.get(j)));
            }
            if (!counted) {
                Tally t = into.computeIfAbsent(id, k -> new Tally());
                t.total += count;
                if (detail) {
                    bump(t.totalMethods, owners.presentMethods().get(i), count);
                }
            }
        }
    }

    private String resolve(String raw) {
        if (raw == null) {
            return null;
        }
        String id = resolved.get(raw);
        if (id == null) {
            String name = raw.substring(1);
            id = raw.startsWith(LABEL_MARK) ? mixinOwner(name, false)
                : raw.startsWith(JAR_MARK) ? mixinOwner(name, true) : packageOwner(raw);
            resolved.put(raw, id);
        }
        return id;
    }

    private String packageOwner(String raw) {
        if (raw.indexOf(':') >= 0) {
            return raw;
        }
        String loader = packageLoaders.get(raw);
        return loader == null || loader.equals(AMBIGUOUS) ? raw : loader;
    }

    private String mixinOwner(String name, boolean jar) {
        String key = letters(name);
        String loader = closest(loaderIds, key, jar, ':');
        if (loader != null) {
            return loader.equals(AMBIGUOUS) ? name : loader;
        }
        String pkg = closest(packages, key, jar, '.');
        return pkg == null || pkg.equals(AMBIGUOUS) ? name : packageOwner(pkg);
    }

    private static String closest(Set<String> ids, String key, boolean prefix, char separator) {
        String best = null;
        int length = -1;
        for (String id : ids) {
            String candidate = letters(id.substring(id.lastIndexOf(separator) + 1));
            boolean match = prefix ? candidate.length() >= MIN_JAR_PREFIX && key.startsWith(candidate)
                : !candidate.isEmpty() && key.equals(candidate);
            if (match && candidate.length() > length) {
                best = id;
                length = candidate.length();
            } else if (match && candidate.length() == length) {
                best = AMBIGUOUS;
            }
        }
        return best;
    }

    private static String mixinId(String methodName, String label) {
        String mod = FrameLabels.mixinCodeMod(label);
        if (mod != null) {
            return LABEL_MARK + mod;
        }
        String jar = FrameLabels.mixinJar(methodName);
        return jar == null ? null : JAR_MARK + jar;
    }

    private String observe(String type, String loader) {
        String pkg = packageId(type);
        String id = pkg;
        if (loader == null) {
            packages.add(pkg);
        } else {
            loaderIds.add(loader);
            packageLoaders.merge(pkg, loader, (a, b) -> a.equals(b) ? a : AMBIGUOUS);
            id = loader;
        }
        if (type.startsWith(HYBOX_PREFIX)) {
            hyboxIds.add(id);
        }
        return id;
    }

    private static String letters(String name) {
        StringBuilder out = new StringBuilder(name.length());
        for (int i = 0; i < name.length(); i++) {
            char c = Character.toLowerCase(name.charAt(i));
            if (c >= 'a' && c <= 'z') {
                out.append(c);
            }
        }
        return out.toString();
    }

    private static String method(String type, String name) {
        return JfrTimelineParser.cleanType(type) + "." + name;
    }

    private static void bump(Map<String, long[]> map, String key, long count) {
        if (key != null) {
            map.computeIfAbsent(key, k -> new long[1])[0] += count;
        }
    }

    private static String dominant(Map<String, long[]> counts) {
        String best = null;
        long max = -1;
        for (Map.Entry<String, long[]> en : counts.entrySet()) {
            long n = en.getValue()[0];
            if (n > max || (n == max && en.getKey().compareTo(best) < 0)) {
                best = en.getKey();
                max = n;
            }
        }
        return best;
    }

    private static List<ThreadShare> shares(Map<String, long[]> threads) {
        return threads.entrySet().stream()
            .sorted(Comparator.<Map.Entry<String, long[]>>comparingLong(en -> -en.getValue()[0])
                .thenComparing(Map.Entry::getKey))
            .limit(THREAD_SHARES)
            .map(en -> new ThreadShare(en.getKey(), en.getValue()[0]))
            .toList();
    }

    private static Map<String, ModShare> shares(Map<String, Tally> mods, int limit) {
        Map<String, ModShare> out = new LinkedHashMap<>();
        for (Map.Entry<String, Tally> en : ranked(mods, limit)) {
            Tally t = en.getValue();
            out.put(en.getKey(), new ModShare(t.samples, t.self, t.total));
        }
        return out;
    }

    private static List<Map.Entry<String, Tally>> ranked(Map<String, Tally> mods, int limit) {
        List<Map.Entry<String, Tally>> top = mods.entrySet().stream()
            .sorted(Comparator.<Map.Entry<String, Tally>>comparingLong(en -> -en.getValue().total)
                .thenComparing(Map.Entry::getKey))
            .limit(limit)
            .toList();
        return top.stream()
            .sorted(Comparator.<Map.Entry<String, Tally>>comparingLong(en -> -en.getValue().samples)
                .thenComparingLong(en -> -en.getValue().total)
                .thenComparing(Map.Entry::getKey))
            .toList();
    }
}
