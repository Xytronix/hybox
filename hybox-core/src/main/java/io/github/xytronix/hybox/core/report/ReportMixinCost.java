package io.github.xytronix.hybox.core.report;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import io.github.xytronix.hybox.core.health.FrameLabels;
import io.github.xytronix.hybox.core.health.JfrTimeline.MixinCost;
import io.github.xytronix.hybox.core.health.JfrTimeline.MixinHit;
import io.github.xytronix.hybox.core.json.JsonWriter;

final class ReportMixinCost {
    static final int TOP_METHODS = 5;

    record Jar(String file, String mod) {}

    record Row(Jar jar, long own, long started, long reach, List<Map.Entry<String, Long>> ownMethods,
               List<Map.Entry<String, Long>> startedMethods, List<Map.Entry<String, Long>> changedMethods) {}

    private static final class Tally {
        long own;
        long started;
        long reach;
        final Map<String, Long> ownMethods = new HashMap<>();
        final Map<String, Long> startedMethods = new HashMap<>();
        final Map<String, Long> changedMethods = new HashMap<>();
    }

    private ReportMixinCost() {
    }

    static List<Row> rows(MixinCost cost, List<MixinSection.Source> sources, MixinPackages packages) {
        Map<String, Jar> resolved = new HashMap<>();
        Map<Jar, Tally> tallies = new LinkedHashMap<>();
        for (MixinHit hit : cost.hits()) {
            if (MixinCost.REACH.equals(hit.kind())) {
                List<Jar> jars = new ArrayList<>(2);
                for (String key : hit.key().split(MixinCost.KEY_SEPARATOR)) {
                    Jar jar = resolved.computeIfAbsent(key, k -> jar(k, sources));
                    if (!jars.contains(jar)) {
                        jars.add(jar);
                    }
                }
                for (Jar jar : jars) {
                    tallies.computeIfAbsent(jar, k -> new Tally()).reach += hit.samples();
                }
                continue;
            }
            Jar jar = resolved.computeIfAbsent(hit.key(), k -> jar(k, sources));
            Tally t = tallies.computeIfAbsent(jar, k -> new Tally());
            switch (hit.kind()) {
                case MixinCost.OWN -> {
                    if (ownClasses(hit, jar, packages)) {
                        t.own += hit.samples();
                        t.ownMethods.merge(hit.method(), hit.samples(), Long::sum);
                    }
                }
                case MixinCost.STARTED -> {
                    t.started += hit.samples();
                    t.startedMethods.merge(hit.method(), hit.samples(), Long::sum);
                }
                case MixinCost.CHANGED -> t.changedMethods.merge(hit.method(), hit.samples(), Long::sum);
                default -> {
                }
            }
        }
        List<Row> out = new ArrayList<>(tallies.size());
        tallies.forEach((jar, t) -> out.add(new Row(jar, t.own, t.started, t.reach, top(t.ownMethods),
            top(t.startedMethods), top(t.changedMethods))));
        out.sort((a, b) -> a.own() != b.own() ? Long.compare(b.own(), a.own())
            : a.started() != b.started() ? Long.compare(b.started(), a.started())
            : Long.compare(b.reach(), a.reach()));
        return out;
    }

    static Jar jar(String key, List<MixinSection.Source> sources) {
        String name = key.substring(1);
        boolean byId = key.startsWith(MixinCost.ID_KEY);
        Jar found = null;
        for (MixinSection.Source source : sources) {
            String file = source.file();
            if (file != null && (byId ? name.equals(FrameLabels.sourceId(file)) : name.equals(file))) {
                if (found != null) {
                    return new Jar(null, name);
                }
                found = new Jar(file, modName(source));
            }
        }
        return found != null ? found : byId ? new Jar(null, name) : new Jar(name, null);
    }

    private static boolean ownClasses(MixinHit hit, Jar jar, MixinPackages packages) {
        for (String type : hit.ownClasses()) {
            if (jar.file() == null || !jar.file().equals(packages.jar(type))) {
                return false;
            }
        }
        return true;
    }

    private static String modName(MixinSection.Source source) {
        String id = source.pluginId();
        return id == null || id.isBlank() ? null : id.substring(id.indexOf(':') + 1);
    }

    private static List<Map.Entry<String, Long>> top(Map<String, Long> methods) {
        return methods.entrySet().stream()
            .sorted(Map.Entry.<String, Long>comparingByValue().reversed().thenComparing(Map.Entry.comparingByKey()))
            .limit(TOP_METHODS)
            .map(e -> Map.entry(e.getKey(), e.getValue()))
            .toList();
    }

    static void write(JsonWriter json, MixinCost cost, MixinCost pool, MixinCost workPool, Map<String, String> mixins)
        throws IOException {
        MixinSection.Parsed parsed = MixinSection.parse(mixins);
        if (parsed == null || parsed.versions() == null || cost == null || cost.samples() <= 0) {
            json.name("mixinCost").nullValue();
            return;
        }
        json.name("mixinCost").beginObject();
        json.name("worldSamples").value(cost.samples());
        rows(json, "rows", cost, parsed);
        if (pool != null) {
            json.name("poolSamples").value(pool.samples());
            rows(json, "poolRows", pool, parsed);
        }
        if (workPool != null) {
            json.name("workPoolSamples").value(workPool.samples());
            rows(json, "workPoolRows", workPool, parsed);
        }
        json.endObject();
    }

    private static void rows(JsonWriter json, String name, MixinCost cost, MixinSection.Parsed parsed) throws IOException {
        json.name(name).beginArray();
        for (Row row : rows(cost, parsed.sources(), new MixinPackages(parsed.configs()))) {
            json.beginObject();
            json.name("file").value(row.jar().file());
            json.name("mod").value(row.jar().mod());
            json.name("own").value(row.own());
            json.name("started").value(row.started());
            json.name("reach").value(row.reach());
            methods(json, "ownMethods", row.ownMethods());
            methods(json, "startedMethods", row.startedMethods());
            methods(json, "changedMethods", row.changedMethods());
            json.endObject();
        }
        json.endArray();
    }

    private static void methods(JsonWriter json, String name, List<Map.Entry<String, Long>> methods)
        throws IOException {
        json.name(name).beginArray();
        for (Map.Entry<String, Long> e : methods) {
            json.beginArray();
            json.value(e.getKey());
            json.value(e.getValue());
            json.endArray();
        }
        json.endArray();
    }
}
