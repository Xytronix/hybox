package io.github.xytronix.hybox.core.report;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import io.github.xytronix.hybox.core.json.JsonWriter;

public final class MixinSection {
    public static final String TITLE = "Mixins";
    public static final String UPDATE_NOTE = "Update Hyinit for Mixin details";

    private static final String BOOTSTRAPPER = "Bootstrapper";
    private static final String HYINIT = "Hyinit";
    private static final String LAUNCH_MODE = "Launch mode";
    private static final String MIXIN = "Mixin";
    private static final String FABRIC_MIXIN = "Fabric Mixin";
    private static final String MIXIN_EXTRAS = "MixinExtras";
    private static final String CLASSES_TRANSFORMED = "Classes transformed";
    private static final String TRANSFORM_NANOS = "Mixin time (ns)";
    private static final String PATCHED_CLASSES = "Patched classes";
    private static final String BOOTSTRAP_MILLIS = "Startup time (ms)";
    private static final String PROBLEMS_OMITTED = "Problems not kept";
    public static final String NOTE = "Note";
    private static final String CONFLICT_PREFIX = "Conflict: ";
    private static final String CONFLICTS = "Conflicts";
    private static final List<String> SCALARS = List.of(BOOTSTRAPPER, HYINIT, LAUNCH_MODE, MIXIN, FABRIC_MIXIN,
        MIXIN_EXTRAS, CLASSES_TRANSFORMED, TRANSFORM_NANOS, PATCHED_CLASSES, BOOTSTRAP_MILLIS, PROBLEMS_OMITTED, NOTE,
        CONFLICTS);
    private static final Pattern NUMBERED = Pattern.compile("(Source|Config|Problem|Overlap) (\\d+)(?: (.+))?");

    public record Versions(String hyinit, String launchMode, String mixin, String fabricMixin, String mixinExtras,
                           Long classesTransformed, Long transformNanos, Long patchedClasses, Long bootstrapMillis,
                           Long problemsOmitted) {}

    public record Source(String file, String pluginId, String version, String sha256, List<String> configs,
                         Boolean hasMain, Boolean mixinsSkipped, String compatibility, Long methods) {}

    public record Config(String name, String source, String mixinPackage, List<String> mixins, Integer targets,
                         String compatibility, Integer priority, Boolean required) {}

    public record Problem(String level, String message, String source, String detail) {
        public boolean error() {
            return "error".equals(level);
        }
    }

    public record Patch(String mod, String mixin) {}

    public record Overlap(String method, List<Patch> patches) {}

    record Parsed(String bootstrapper, Versions versions, List<Source> sources, List<Config> configs,
                  List<Problem> problems, List<Overlap> overlaps, String note, Map<String, String> legacyConfigs,
                  Map<String, String> legacyConflicts, boolean legacyResolvable) {
        long errors() {
            return problems.stream().filter(Problem::error).count();
        }

        long warnings() {
            return problems.size() - errors();
        }
    }

    private MixinSection() {
    }

    public static Map<String, String> entries(String bootstrapper, Versions versions, List<Source> sources,
                                              List<Config> configs, List<Problem> problems, List<Overlap> overlaps,
                                              String note) {
        Map<String, String> out = new LinkedHashMap<>();
        put(out, BOOTSTRAPPER, bootstrapper);
        if (versions != null) {
            put(out, HYINIT, versions.hyinit() == null ? "" : versions.hyinit());
            put(out, LAUNCH_MODE, versions.launchMode());
            put(out, MIXIN, versions.mixin());
            put(out, FABRIC_MIXIN, versions.fabricMixin());
            put(out, MIXIN_EXTRAS, versions.mixinExtras());
            put(out, CLASSES_TRANSFORMED, versions.classesTransformed());
            put(out, TRANSFORM_NANOS, versions.transformNanos());
            put(out, PATCHED_CLASSES, versions.patchedClasses());
            put(out, BOOTSTRAP_MILLIS, versions.bootstrapMillis());
            put(out, PROBLEMS_OMITTED, versions.problemsOmitted());
        }
        int index = 0;
        for (Source source : sources) {
            String key = "Source " + ++index;
            put(out, key, source.file() == null ? "" : source.file());
            put(out, key + " plugin", source.pluginId());
            put(out, key + " version", source.version());
            put(out, key + " SHA-256", source.sha256());
            put(out, key + " configs", source.configs().isEmpty() ? null : String.join("\n", source.configs()));
            put(out, key + " main", source.hasMain());
            put(out, key + " skipped", source.mixinsSkipped());
            put(out, key + " compatibility", source.compatibility());
            put(out, key + " methods", source.methods());
        }
        index = 0;
        for (Config config : configs) {
            String key = "Config " + ++index;
            put(out, key, config.name() == null ? "" : config.name());
            put(out, key + " source", config.source());
            put(out, key + " package", config.mixinPackage());
            put(out, key + " mixins", String.join("\n", config.mixins()));
            put(out, key + " targets", config.targets());
            put(out, key + " compatibility", config.compatibility());
            put(out, key + " priority", config.priority());
            put(out, key + " required", config.required());
        }
        index = 0;
        for (Problem problem : problems) {
            String key = "Problem " + ++index;
            put(out, key, problem.message() == null ? "" : problem.message());
            put(out, key + " level", problem.level());
            put(out, key + " source", problem.source());
            put(out, key + " detail", problem.detail());
        }
        index = 0;
        for (Overlap overlap : overlaps) {
            String key = "Overlap " + ++index;
            put(out, key, overlap.method());
            List<String> patches = new ArrayList<>(overlap.patches().size());
            for (Patch patch : overlap.patches()) {
                patches.add(patch.mod() + "\t" + patch.mixin());
            }
            put(out, key + " mixins", String.join("\n", patches));
        }
        put(out, NOTE, note);
        return out;
    }

    static Parsed parse(Map<String, String> entries) {
        if (entries == null) {
            return null;
        }
        Map<Integer, Map<String, String>> sources = new TreeMap<>();
        Map<Integer, Map<String, String>> configs = new TreeMap<>();
        Map<Integer, Map<String, String>> problems = new TreeMap<>();
        Map<Integer, Map<String, String>> overlaps = new TreeMap<>();
        Map<String, String> legacyConfigs = new LinkedHashMap<>();
        Map<String, String> legacyConflicts = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : entries.entrySet()) {
            String key = entry.getKey();
            Matcher numbered = NUMBERED.matcher(key);
            if (numbered.matches()) {
                Map<Integer, Map<String, String>> group = switch (numbered.group(1)) {
                    case "Source" -> sources;
                    case "Config" -> configs;
                    case "Problem" -> problems;
                    default -> overlaps;
                };
                group.computeIfAbsent(Integer.parseInt(numbered.group(2)), k -> new LinkedHashMap<>())
                    .put(numbered.group(3) == null ? "" : numbered.group(3), entry.getValue());
            } else if (key.startsWith(CONFLICT_PREFIX)) {
                legacyConflicts.put(key.substring(CONFLICT_PREFIX.length()), entry.getValue());
            } else if (!SCALARS.contains(key)) {
                legacyConfigs.put(key, entry.getValue());
            }
        }
        Versions versions = entries.containsKey(HYINIT)
            ? new Versions(blankToNull(entries.get(HYINIT)), entries.get(LAUNCH_MODE), entries.get(MIXIN),
                entries.get(FABRIC_MIXIN), entries.get(MIXIN_EXTRAS), number(entries.get(CLASSES_TRANSFORMED)),
                number(entries.get(TRANSFORM_NANOS)), number(entries.get(PATCHED_CLASSES)),
                number(entries.get(BOOTSTRAP_MILLIS)), number(entries.get(PROBLEMS_OMITTED)))
            : null;
        List<Source> sourceList = new ArrayList<>();
        for (Map<String, String> s : sources.values()) {
            sourceList.add(new Source(blankToNull(s.get("")), s.get("plugin"), s.get("version"), s.get("SHA-256"),
                lines(s.get("configs")), bool(s.get("main")), bool(s.get("skipped")), s.get("compatibility"),
                number(s.get("methods"))));
        }
        List<Config> configList = new ArrayList<>();
        for (Map<String, String> c : configs.values()) {
            Long targets = number(c.get("targets"));
            Long priority = number(c.get("priority"));
            configList.add(new Config(blankToNull(c.get("")), c.get("source"), c.get("package"), lines(c.get("mixins")),
                targets == null ? null : targets.intValue(), c.get("compatibility"),
                priority == null ? null : priority.intValue(), bool(c.get("required"))));
        }
        List<Problem> problemList = new ArrayList<>();
        for (Map<String, String> p : problems.values()) {
            problemList.add(new Problem(p.get("level"), p.get(""), p.get("source"), p.get("detail")));
        }
        List<Overlap> overlapList = new ArrayList<>();
        for (Map<String, String> o : overlaps.values()) {
            List<Patch> patches = new ArrayList<>();
            for (String line : lines(o.get("mixins"))) {
                int tab = line.indexOf('\t');
                patches.add(tab < 0 ? new Patch(line, null) : new Patch(line.substring(0, tab), line.substring(tab + 1)));
            }
            overlapList.add(new Overlap(o.get(""), patches));
        }
        return new Parsed(entries.get(BOOTSTRAPPER), versions, sourceList, configList, problemList, overlapList,
            entries.get(NOTE), legacyConfigs, legacyConflicts, "none".equals(entries.get(CONFLICTS)));
    }

    static void write(JsonWriter json, Map<String, String> entries) throws IOException {
        Parsed parsed = parse(entries);
        if (parsed == null || parsed.bootstrapper() == null && parsed.versions() == null && parsed.note() == null
            && parsed.legacyConfigs().isEmpty()
            && parsed.legacyConflicts().isEmpty()) {
            json.name("mixins").nullValue();
            return;
        }
        json.name("mixins").beginObject();
        json.name("bootstrapper").value(parsed.bootstrapper());
        json.name("configs").beginArray();
        if (parsed.versions() == null) {
            for (Map.Entry<String, String> e : parsed.legacyConfigs().entrySet()) {
                json.beginArray();
                json.value(e.getKey());
                stringArray(json, List.of(e.getValue().split(", ")));
                json.endArray();
            }
        } else {
            for (Config config : parsed.configs()) {
                json.beginArray();
                json.value(config.name());
                stringArray(json, config.mixins());
                json.value(config.source());
                json.value(config.mixinPackage());
                nullable(json, config.targets());
                json.value(config.compatibility());
                nullable(json, config.priority());
                nullable(json, config.required());
                json.endArray();
            }
        }
        json.endArray();
        writeLegacyConflicts(json, parsed);
        Versions v = parsed.versions();
        json.name("hyinit");
        if (v == null) {
            json.nullValue();
        } else {
            json.beginObject();
            json.name("version").value(v.hyinit());
            json.name("launchMode").value(v.launchMode());
            json.name("mixin").value(v.mixin());
            json.name("fabricMixin").value(v.fabricMixin());
            json.name("mixinExtras").value(v.mixinExtras());
            json.name("classesTransformed");
            nullable(json, v.classesTransformed());
            json.name("transformMs");
            nullable(json, v.transformNanos() == null ? null : Math.round(v.transformNanos() / 100_000.0) / 10.0);
            json.name("patchedClasses");
            nullable(json, v.patchedClasses());
            json.name("bootstrapMs");
            nullable(json, v.bootstrapMillis());
            json.name("problemsOmitted");
            nullable(json, v.problemsOmitted());
            json.endObject();
        }
        json.name("sources");
        if (v == null) {
            json.nullValue();
        } else {
            json.beginArray();
            for (Source s : parsed.sources()) {
                json.beginArray();
                json.value(s.file());
                json.value(s.pluginId());
                json.value(s.version());
                json.value(s.sha256());
                stringArray(json, s.configs());
                nullable(json, s.hasMain());
                nullable(json, s.mixinsSkipped());
                json.value(s.compatibility());
                nullable(json, s.methods());
                json.endArray();
            }
            json.endArray();
        }
        json.name("problems");
        if (v == null) {
            json.nullValue();
        } else {
            json.beginArray();
            for (Problem p : parsed.problems()) {
                json.beginArray();
                json.value(p.level());
                json.value(p.message());
                json.value(p.source());
                json.value(p.detail());
                json.endArray();
            }
            json.endArray();
        }
        json.name("overlaps");
        if (v == null) {
            json.nullValue();
        } else {
            json.beginArray();
            for (Overlap o : parsed.overlaps()) {
                json.beginArray();
                json.value(o.method());
                json.beginArray();
                for (Patch patch : o.patches()) {
                    json.beginArray();
                    json.value(patch.mod());
                    json.value(patch.mixin());
                    json.endArray();
                }
                json.endArray();
                json.endArray();
            }
            json.endArray();
        }
        json.name("note").value(parsed.note());
        json.endObject();
    }

    private static void writeLegacyConflicts(JsonWriter json, Parsed parsed) throws IOException {
        json.name("conflicts");
        if (parsed.versions() != null || parsed.legacyConflicts().isEmpty() && !parsed.legacyResolvable()) {
            json.nullValue();
            return;
        }
        json.beginArray();
        for (Map.Entry<String, String> e : parsed.legacyConflicts().entrySet()) {
            json.beginArray();
            json.value(e.getKey());
            json.beginArray();
            for (String participant : e.getValue().split("; ")) {
                if (participant.isBlank()) {
                    continue;
                }
                int arrow = participant.indexOf(" → ");
                json.beginArray();
                if (arrow < 0) {
                    json.value(participant.trim());
                    json.nullValue();
                } else {
                    json.value(participant.substring(0, arrow).trim());
                    json.value(participant.substring(arrow + " → ".length()).trim());
                }
                json.endArray();
            }
            json.endArray();
            json.endArray();
        }
        json.endArray();
    }

    private static void stringArray(JsonWriter json, List<String> values) throws IOException {
        json.beginArray();
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                json.value(value);
            }
        }
        json.endArray();
    }

    private static void nullable(JsonWriter json, Number value) throws IOException {
        if (value == null) {
            json.nullValue();
        } else {
            json.value(value);
        }
    }

    private static void nullable(JsonWriter json, Boolean value) throws IOException {
        if (value == null) {
            json.nullValue();
        } else {
            json.value(value.booleanValue());
        }
    }

    private static void put(Map<String, String> out, String key, Object value) {
        if (value != null) {
            out.put(key, value.toString());
        }
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    private static List<String> lines(String value) {
        return value == null || value.isEmpty() ? List.of() : List.of(value.split("\n"));
    }

    private static Long number(String value) {
        if (value == null) {
            return null;
        }
        try {
            return Long.parseLong(value.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static Boolean bool(String value) {
        return value == null ? null : Boolean.valueOf(value);
    }
}
