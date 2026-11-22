package io.github.xytronix.hybox.hytale;

import java.io.InputStream;
import java.io.Reader;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;

import io.github.xytronix.hybox.core.health.FrameLabels;
import io.github.xytronix.hybox.core.incident.DiagnosticSection;
import io.github.xytronix.hybox.core.report.MixinSection;

final class HytaleMixins {
    private static final System.Logger LOGGER = System.getLogger(HytaleMixins.class.getName());

    private static final String MIXINS_CLASS = "org.spongepowered.asm.mixin.Mixins";
    private static final String IMIXIN_CONFIG_CLASS = "org.spongepowered.asm.mixin.extensibility.IMixinConfig";
    private static final String HYINIT_VERSION_CLASS = "cc.irori.hyinit.Main";
    private static final String HYXIN_VERSION_CLASS = "com.build_9.hyxin.Constants";
    private static final String SAVED_NOTE = "Saved when the crashed run started. Problems after startup are only in that run's server log.";
    private static final String WRAPPED = "$mixinextras$wrapped$";
    private static final long COUNT_CACHE_MILLIS = 5_000;
    private static final int LABEL_CACHE_LIMIT = 20_000;
    private static final Gson GSON = new GsonBuilder().serializeNulls().setPrettyPrinting().create();

    private static final Pattern MIXIN_ARRAY =
        Pattern.compile("\"(?:mixins|client|server)\"\\s*:\\s*\\[([^\\]]*)\\]");
    private static final Pattern STRING_LITERAL = Pattern.compile("\"((?:[^\"\\\\]|\\\\.)*)\"");
    private static final Pattern JAR_VERSION = Pattern.compile("-\\d[\\w.+-]*$");

    private static volatile long[] cachedCounts = {0, 0};
    private static volatile long cachedCountsAt;

    record ProblemCounts(long errors, long warnings) {}

    private HytaleMixins() {
    }

    static List<DiagnosticSection> appendTo(List<DiagnosticSection> base) {
        try {
            HyinitReader reader = HyinitReader.get();
            Map<String, Object> snapshot = reader.snapshot();
            if (snapshot != null) {
                return with(base, section(snapshot, reader.methodMap(), null));
            }
            return appendLegacy(base, reader.classMissing());
        } catch (Throwable t) {
            LOGGER.log(System.Logger.Level.DEBUG, "Mixin enumeration failed.", t);
            return base;
        }
    }

    static List<DiagnosticSection> appendSaved(List<DiagnosticSection> base, Map<String, Object> saved) {
        if (saved == null) {
            return appendTo(base);
        }
        try {
            return with(base, section(saved, null, SAVED_NOTE));
        } catch (Throwable t) {
            LOGGER.log(System.Logger.Level.DEBUG, "Saved Hyinit snapshot could not be read.", t);
            return appendTo(base);
        }
    }

    private static List<DiagnosticSection> with(List<DiagnosticSection> base, DiagnosticSection section) {
        List<DiagnosticSection> out = new ArrayList<>(base);
        out.add(section);
        return out;
    }

    static DiagnosticSection section(Map<String, Object> snapshot,
                                     Map<String, Map<String, List<Map<String, String>>>> methodMap, String note) {
        Map<String, String> modNames = modNames(snapshot);
        String version = string(snapshot.get("hyinitVersion"));
        MixinSection.Versions versions = new MixinSection.Versions(version, string(snapshot.get("launchMode")),
            string(snapshot.get("mixinVersion")), string(snapshot.get("fabricMixinVersion")),
            string(snapshot.get("mixinExtrasVersion")), number(snapshot.get("classesTransformed")),
            number(snapshot.get("transformNanos")), number(snapshot.get("mixinTargetClasses")),
            number(snapshot.get("bootstrapMillis")), number(snapshot.get("problemsOmitted")));
        Map<String, Long> methodsBySource = methodMap == null ? null : methodsBySource(methodMap);
        List<MixinSection.Source> sources = new ArrayList<>();
        for (Map<?, ?> s : maps(snapshot.get("sources"))) {
            String file = string(s.get("file"));
            sources.add(new MixinSection.Source(file, string(s.get("pluginId")), string(s.get("version")),
                string(s.get("sha256")), strings(s.get("configs")), bool(s.get("hasMain")), bool(s.get("mixinsSkipped")),
                string(s.get("mixinCompatibility")), methodsBySource == null ? null : methodsBySource.getOrDefault(file, 0L)));
        }
        List<MixinSection.Config> configs = new ArrayList<>();
        for (Map<?, ?> c : maps(snapshot.get("configs"))) {
            Long priority = number(c.get("priority"));
            configs.add(new MixinSection.Config(string(c.get("name")), string(c.get("source")),
                string(c.get("mixinPackage")), strings(c.get("mixins")), strings(c.get("targets")).size(),
                string(c.get("compatibility")), priority == null ? null : priority.intValue(), bool(c.get("required"))));
        }
        List<MixinSection.Problem> problems = new ArrayList<>();
        for (Map<?, ?> p : maps(snapshot.get("problems"))) {
            problems.add(new MixinSection.Problem(string(p.get("level")), string(p.get("message")),
                string(p.get("source")), string(p.get("detail"))));
        }
        List<MixinSection.Overlap> overlaps = methodMap == null ? List.of() : overlaps(methodMap, modNames);
        return new DiagnosticSection(MixinSection.TITLE, MixinSection.entries(
            "Hyinit" + (version == null ? "" : " " + version), versions, sources, configs, problems, overlaps, note));
    }

    private static Map<String, Long> methodsBySource(Map<String, Map<String, List<Map<String, String>>>> methodMap) {
        Map<String, Long> out = new LinkedHashMap<>();
        for (Map<String, List<Map<String, String>>> methods : methodMap.values()) {
            for (List<Map<String, String>> entries : methods.values()) {
                Set<String> seen = new LinkedHashSet<>();
                for (Map<String, String> entry : entries) {
                    String source = entry.get("source");
                    if (source != null && seen.add(source)) {
                        out.merge(source, 1L, Long::sum);
                    }
                }
            }
        }
        return out;
    }

    private static List<MixinSection.Overlap> overlaps(Map<String, Map<String, List<Map<String, String>>>> methodMap,
                                                       Map<String, String> modNames) {
        List<MixinSection.Overlap> out = new ArrayList<>();
        for (Map.Entry<String, Map<String, List<Map<String, String>>>> cls : new TreeMap<>(methodMap).entrySet()) {
            for (Map.Entry<String, List<Map<String, String>>> method : new TreeMap<>(cls.getValue()).entrySet()) {
                Set<String> mods = new LinkedHashSet<>();
                Set<MixinSection.Patch> patches = new LinkedHashSet<>();
                for (Map<String, String> entry : method.getValue()) {
                    String mod = modName(entry.get("source"), modNames);
                    mods.add(mod);
                    patches.add(new MixinSection.Patch(mod, entry.get("mixin")));
                }
                if (mods.size() > 1) {
                    out.add(new MixinSection.Overlap(cls.getKey() + "." + method.getKey(), List.copyOf(patches)));
                }
            }
        }
        return out;
    }

    static Map<String, String> modNames(Map<String, Object> snapshot) {
        Map<String, String> out = new LinkedHashMap<>();
        for (Map<?, ?> s : maps(snapshot.get("sources"))) {
            String file = string(s.get("file"));
            String pluginId = string(s.get("pluginId"));
            if (file != null && pluginId != null) {
                out.put(file, pluginId.substring(pluginId.indexOf(':') + 1));
            }
        }
        return out;
    }

    static String modName(String file, Map<String, String> modNames) {
        if (file == null || file.isBlank()) {
            return "unknown mod";
        }
        String name = modNames.get(file);
        if (name != null) {
            return name;
        }
        String base = file.toLowerCase(Locale.ROOT).endsWith(".jar") ? file.substring(0, file.length() - 4) : file;
        String stripped = JAR_VERSION.matcher(base).replaceFirst("");
        return stripped.isEmpty() ? base : stripped;
    }

    static String label(List<Map<String, String>> entries, Map<String, String> modNames) {
        if (entries == null || entries.isEmpty()) {
            return null;
        }
        boolean merged = true;
        Map<String, Set<String>> byMod = new LinkedHashMap<>();
        for (Map<String, String> entry : entries) {
            merged &= "merged".equals(entry.get("kind"));
            String mixin = entry.get("mixin");
            byMod.computeIfAbsent(modName(entry.get("source"), modNames), k -> new LinkedHashSet<>())
                .add(mixin == null ? "?" : mixin.substring(mixin.lastIndexOf('.') + 1));
        }
        List<String> parts = new ArrayList<>(byMod.size());
        byMod.forEach((mod, mixins) -> parts.add(mod + " (" + String.join(", ", mixins) + ")"));
        return (merged ? "Mixin code from " : "changed by Mixin from ") + String.join(", ", parts);
    }

    static FrameLabels.Source labels(HyinitReader reader) {
        if (!reader.present()) {
            return null;
        }
        Map<String, String> cache = new ConcurrentHashMap<>();
        AtomicReference<Map<String, String>> names = new AtomicReference<>();
        return (className, methodName) -> {
            String key = className + '#' + methodName;
            String cached = cache.get(key);
            if (cached != null) {
                return cached.isEmpty() ? null : cached;
            }
            List<Map<String, String>> entries = reader.mixinsFor(className, methodName);
            int wrapped = methodName.indexOf(WRAPPED);
            if (entries.isEmpty() && wrapped > 0) {
                entries = reader.mixinsFor(className, methodName.substring(0, wrapped));
            }
            String label = null;
            if (!entries.isEmpty()) {
                Map<String, String> mods = names.get();
                if (mods == null) {
                    Map<String, Object> snapshot = reader.snapshot();
                    mods = snapshot == null ? Map.of() : modNames(snapshot);
                    names.set(mods);
                }
                label = label(entries, mods);
            }
            if (cache.size() >= LABEL_CACHE_LIMIT) {
                cache.clear();
            }
            cache.put(key, label == null ? "" : label);
            return label;
        };
    }

    static ProblemCounts problemCounts() {
        long now = System.currentTimeMillis();
        if (now - cachedCountsAt >= COUNT_CACHE_MILLIS) {
            Map<String, Object> snapshot = HyinitReader.get().snapshot();
            long errors = 0;
            long warnings = 0;
            if (snapshot != null) {
                for (Map<?, ?> problem : maps(snapshot.get("problems"))) {
                    if ("error".equals(string(problem.get("level")))) {
                        errors++;
                    } else {
                        warnings++;
                    }
                }
            }
            cachedCounts = new long[] {errors, warnings};
            cachedCountsAt = now;
        }
        long[] counts = cachedCounts;
        return new ProblemCounts(counts[0], counts[1]);
    }

    static String methodMapText() {
        HyinitReader reader = HyinitReader.get();
        Map<String, Object> snapshot = reader.snapshot();
        Map<String, Map<String, List<Map<String, String>>>> methodMap = reader.methodMap();
        return snapshot == null || methodMap == null ? null : methodMapText(snapshot, methodMap);
    }

    static String methodMapText(Map<String, Object> snapshot,
                                Map<String, Map<String, List<Map<String, String>>>> methodMap) {
        Map<String, String> modNames = modNames(snapshot);
        StringBuilder out = new StringBuilder(64 * 1024);
        out.append("Mixin changes recorded by Hyinit ").append(text(snapshot.get("hyinitVersion")))
            .append(" (diagnostics API ").append(text(snapshot.get("apiVersion"))).append(")\n");
        out.append("Mixin ").append(text(snapshot.get("mixinVersion")))
            .append(" · Fabric Mixin ").append(text(snapshot.get("fabricMixinVersion")))
            .append(" · MixinExtras ").append(text(snapshot.get("mixinExtrasVersion")))
            .append(" · launch mode ").append(text(snapshot.get("launchMode"))).append('\n');
        Long nanos = number(snapshot.get("transformNanos"));
        out.append("Classes transformed ").append(text(snapshot.get("classesTransformed")))
            .append(nanos == null ? "" : " in " + nanos / 1_000_000 + " ms")
            .append(" · classes with Mixin methods ").append(text(snapshot.get("mixinTargetClasses"))).append("\n\n");

        out.append("Early plugin JARs\n");
        Map<String, Long> methodsBySource = methodsBySource(methodMap);
        for (Map<?, ?> s : maps(snapshot.get("sources"))) {
            String file = string(s.get("file"));
            out.append("  ").append(file)
                .append("  plugin ").append(text(s.get("pluginId")))
                .append("  version ").append(text(s.get("version")))
                .append("  sha256 ").append(text(s.get("sha256")))
                .append("  methods ").append(methodsBySource.getOrDefault(file, 0L));
            if (Boolean.TRUE.equals(bool(s.get("mixinsSkipped")))) {
                out.append("  Mixins skipped");
            }
            out.append("  configs ").append(String.join(", ", strings(s.get("configs")))).append('\n');
        }

        out.append("\nMixin configs\n");
        for (Map<?, ?> c : maps(snapshot.get("configs"))) {
            List<String> mixins = strings(c.get("mixins"));
            out.append("  ").append(text(c.get("name")))
                .append("  from ").append(text(c.get("source")))
                .append("  package ").append(text(c.get("mixinPackage")))
                .append("  ").append(mixins.size()).append(" mixins")
                .append("  ").append(strings(c.get("targets")).size()).append(" targets")
                .append("  compatibility ").append(text(c.get("compatibility")))
                .append("  priority ").append(text(c.get("priority")))
                .append(Boolean.TRUE.equals(bool(c.get("required"))) ? "  required" : "").append('\n');
            for (String mixin : mixins) {
                out.append("    ").append(mixin).append('\n');
            }
        }

        out.append("\nProblems\n");
        List<Map<?, ?>> problems = maps(snapshot.get("problems"));
        if (problems.isEmpty()) {
            out.append("  none\n");
        }
        for (Map<?, ?> p : problems) {
            out.append("  ").append(text(p.get("level")).toUpperCase(Locale.ROOT)).append("  ").append(text(p.get("message")));
            if (p.get("source") != null) {
                out.append("  (").append(text(p.get("source"))).append(')');
            }
            out.append('\n');
            if (p.get("detail") != null) {
                out.append("    ").append(text(p.get("detail"))).append('\n');
            }
        }
        Long omitted = number(snapshot.get("problemsOmitted"));
        if (omitted != null && omitted > 0) {
            out.append("  ").append(omitted).append(" more were not kept\n");
        }

        out.append("\nMethods changed by more than one mod\n");
        List<MixinSection.Overlap> overlaps = overlaps(methodMap, modNames);
        if (overlaps.isEmpty()) {
            out.append("  none\n");
        }
        for (MixinSection.Overlap overlap : overlaps) {
            out.append("  ").append(overlap.method()).append(':');
            for (MixinSection.Patch patch : overlap.patches()) {
                out.append(' ').append(patch.mod()).append(" (").append(patch.mixin()).append(')');
            }
            out.append('\n');
        }

        out.append("\nChanged methods. merged: Mixin code added to the class. changed: an original method that calls Mixin code.\n");
        out.append("Columns: method, kind, Mixin, config, JAR\n");
        for (Map.Entry<String, Map<String, List<Map<String, String>>>> cls : new TreeMap<>(methodMap).entrySet()) {
            out.append('\n').append(cls.getKey()).append('\n');
            for (Map.Entry<String, List<Map<String, String>>> method : new TreeMap<>(cls.getValue()).entrySet()) {
                for (Map<String, String> entry : method.getValue()) {
                    out.append("  ").append(method.getKey())
                        .append("  ").append(text(entry.get("kind")))
                        .append("  ").append(text(entry.get("mixin")))
                        .append("  ").append(text(entry.get("config")))
                        .append("  ").append(text(entry.get("source"))).append('\n');
                }
            }
        }
        return out.toString();
    }

    static Map<String, Object> rotateSaved(Path file) {
        Map<String, Object> previous = readSaved(file);
        try {
            Map<String, Object> current = HyinitReader.get().snapshot();
            if (current == null) {
                Files.deleteIfExists(file);
            } else {
                Files.createDirectories(file.getParent());
                Path temporary = file.resolveSibling(file.getFileName() + ".tmp");
                Files.writeString(temporary, GSON.toJson(current), StandardCharsets.UTF_8);
                Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (Exception e) {
            LOGGER.log(System.Logger.Level.WARNING, "Failed to save the Hyinit snapshot to " + file + ".", e);
        }
        return previous;
    }

    static Map<String, Object> readSaved(Path file) {
        if (!Files.isRegularFile(file)) {
            return null;
        }
        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            return GSON.fromJson(reader, new TypeToken<Map<String, Object>>() { }.getType());
        } catch (Exception e) {
            LOGGER.log(System.Logger.Level.WARNING, "Failed to read the saved Hyinit snapshot " + file + ".", e);
            return null;
        }
    }

    private static List<Map<?, ?>> maps(Object value) {
        List<Map<?, ?>> out = new ArrayList<>();
        if (value instanceof List<?> list) {
            for (Object item : list) {
                if (item instanceof Map<?, ?> map) {
                    out.add(map);
                }
            }
        }
        return out;
    }

    private static List<String> strings(Object value) {
        List<String> out = new ArrayList<>();
        if (value instanceof List<?> list) {
            for (Object item : list) {
                if (item != null) {
                    out.add(String.valueOf(item));
                }
            }
        }
        return out;
    }

    private static String string(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private static String text(Object value) {
        if (value instanceof Number number && number.doubleValue() == Math.rint(number.doubleValue())) {
            return Long.toString(number.longValue());
        }
        return value == null ? "?" : String.valueOf(value);
    }

    private static Long number(Object value) {
        return value instanceof Number number ? number.longValue() : null;
    }

    private static Boolean bool(Object value) {
        return value instanceof Boolean flag ? flag : null;
    }

    private static List<DiagnosticSection> appendLegacy(List<DiagnosticSection> base, boolean hyinitWithoutApi) {
        String bootstrapper = detectBootstrapper();
        Map<String, RegisteredConfig> registered = registeredConfigs();
        Map<String, String> configs = configEntries(registered.keySet());
        if (bootstrapper == null && configs.isEmpty()) {
            return base;
        }

        Map<String, String> entries = new LinkedHashMap<>();
        if (bootstrapper != null) {
            entries.put("Bootstrapper", bootstrapper);
        }
        entries.putAll(configs);
        entries.putAll(conflictEntries(registered));
        if (hyinitWithoutApi && bootstrapper != null && bootstrapper.startsWith("Hyinit")) {
            entries.put(MixinSection.NOTE, MixinSection.UPDATE_NOTE);
        }
        return with(base, new DiagnosticSection(MixinSection.TITLE, entries));
    }

    private static Map<String, String> conflictEntries(Map<String, RegisteredConfig> registered) {
        boolean resolvable = registered.values().stream().anyMatch(c -> !c.targets().isEmpty());
        if (!resolvable) {
            return Map.of();
        }
        Map<String, List<String>> byTarget = new TreeMap<>();
        for (Map.Entry<String, RegisteredConfig> config : registered.entrySet()) {
            for (String target : config.getValue().targets()) {
                byTarget.computeIfAbsent(target, k -> new ArrayList<>()).add(config.getKey());
            }
        }
        Map<String, String> out = new LinkedHashMap<>();
        for (Map.Entry<String, List<String>> entry : byTarget.entrySet()) {
            if (entry.getValue().size() <= 1) {
                continue;
            }
            List<String> participants = new ArrayList<>(entry.getValue().size());
            for (String configName : entry.getValue()) {
                String mixins = mixinNamesFor(registered.get(configName).config(), entry.getKey());
                participants.add(mixins == null ? configName : configName + " → " + mixins);
            }
            out.put("Conflict: " + entry.getKey(), String.join("; ", participants));
        }
        return out.isEmpty() ? Map.of("Conflicts", "none") : out;
    }

    private static String mixinNamesFor(Object mixinConfig, String target) {
        try {
            Method getMixinsFor = mixinConfig.getClass().getMethod("getMixinsFor", String.class);
            getMixinsFor.setAccessible(true);
            Object mixins = getMixinsFor.invoke(mixinConfig, target);
            if (!(mixins instanceof Iterable<?> iterable)) {
                return null;
            }
            List<String> names = new ArrayList<>();
            for (Object mixin : iterable) {
                if (mixin == null) {
                    continue;
                }
                Method getName = mixin.getClass().getMethod("getName");
                getName.setAccessible(true);
                Object name = getName.invoke(mixin);
                if (name != null && !String.valueOf(name).isBlank()) {
                    names.add(String.valueOf(name));
                }
            }
            return names.isEmpty() ? null : String.join(" + ", names);
        } catch (Throwable t) {
            return null;
        }
    }

    private static String detectBootstrapper() {
        String service;
        try {
            service = System.getProperty("mixin.service");
        } catch (Throwable t) {
            service = null;
        }
        String lower = service == null ? "" : service.toLowerCase(Locale.ROOT);
        if (lower.contains("hyinit") || isClassPresent(HYINIT_VERSION_CLASS)) {
            return withVersion("Hyinit", HYINIT_VERSION_CLASS);
        }
        if (lower.contains("hyxin") || isClassPresent(HYXIN_VERSION_CLASS)) {
            return withVersion("Hyxin", HYXIN_VERSION_CLASS);
        }
        return service == null || service.isBlank() ? null : service;
    }

    private static ClassLoader[] candidateLoaders() {
        return new ClassLoader[] {
            Thread.currentThread().getContextClassLoader(),
            HytaleMixins.class.getClassLoader(),
            ClassLoader.getSystemClassLoader()
        };
    }

    private static boolean isClassPresent(String className) {
        for (ClassLoader loader : candidateLoaders()) {
            if (loader == null) {
                continue;
            }
            try {
                Class.forName(className, false, loader);
                return true;
            } catch (Throwable ignored) {
            }
        }
        return false;
    }

    private static String withVersion(String name, String versionClass) {
        String version = HytaleEnvironment.loaderVersion(versionClass);
        return version == null ? name : name + " " + version;
    }

    private static Map<String, String> configEntries(Set<String> names) {
        Map<String, String> out = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        for (String name : names) {
            String body = readConfigResource(name);
            if (body == null) {
                out.put(name, "(config not readable)");
                continue;
            }
            Set<String> mixins = parseMixinClasses(body);
            out.put(name, mixins.isEmpty() ? "(no mixins listed)" : String.join(", ", mixins));
        }
        return new LinkedHashMap<>(out);
    }

    private record RegisteredConfig(Object config, Set<String> targets) {}

    private static Map<String, RegisteredConfig> registeredConfigs() {
        for (ClassLoader loader : candidateLoaders()) {
            if (loader == null) {
                continue;
            }
            Map<String, RegisteredConfig> out = registeredConfigs(loader);
            if (!out.isEmpty()) {
                return out;
            }
        }
        return Map.of();
    }

    private static Map<String, RegisteredConfig> registeredConfigs(ClassLoader loader) {
        Map<String, RegisteredConfig> out = new LinkedHashMap<>();
        try {
            Class<?> mixins = Class.forName(MIXINS_CLASS, false, loader);
            Method getConfigs = mixins.getMethod("getConfigs");
            Object configs = getConfigs.invoke(null);
            if (configs instanceof Iterable<?> iterable) {
                for (Object config : iterable) {
                    if (config == null) {
                        continue;
                    }
                    Object name = config.getClass().getMethod("getName").invoke(config);
                    Object mixinConfig = config.getClass().getMethod("getConfig").invoke(config);
                    if (name != null && !String.valueOf(name).isBlank() && mixinConfig != null) {
                        out.put(String.valueOf(name), new RegisteredConfig(mixinConfig, targetsOf(mixinConfig)));
                    }
                }
            }
        } catch (Throwable t) {
            LOGGER.log(System.Logger.Level.DEBUG, "Mixin config enumeration failed.", t);
        }
        return out;
    }

    private static Set<String> targetsOf(Object mixinConfig) {
        try {
            Class<?> iface = Class.forName(IMIXIN_CONFIG_CLASS, false, mixinConfig.getClass().getClassLoader());
            Object targets = iface.getMethod("getTargets").invoke(mixinConfig);
            if (targets instanceof Iterable<?> iterable) {
                Set<String> out = new LinkedHashSet<>();
                for (Object target : iterable) {
                    if (target != null && !String.valueOf(target).isBlank()) {
                        out.add(String.valueOf(target));
                    }
                }
                return out;
            }
        } catch (Throwable t) {
            LOGGER.log(System.Logger.Level.DEBUG, "Mixin target enumeration failed.", t);
        }
        return Set.of();
    }

    private static String readConfigResource(String name) {
        ClassLoader[] loaders = {
            Thread.currentThread().getContextClassLoader(),
            HytaleMixins.class.getClassLoader(),
            ClassLoader.getSystemClassLoader()
        };
        for (ClassLoader loader : loaders) {
            if (loader == null) {
                continue;
            }
            try (InputStream in = loader.getResourceAsStream(name)) {
                if (in != null) {
                    return new String(in.readAllBytes(), StandardCharsets.UTF_8);
                }
            } catch (Throwable ignored) {
            }
        }
        return null;
    }

    private static Set<String> parseMixinClasses(String json) {
        Set<String> out = new LinkedHashSet<>();
        Matcher arrays = MIXIN_ARRAY.matcher(json);
        while (arrays.find()) {
            Matcher strings = STRING_LITERAL.matcher(arrays.group(1));
            while (strings.find()) {
                String value = strings.group(1).trim();
                if (!value.isEmpty()) {
                    out.add(value);
                }
            }
        }
        return out;
    }
}
