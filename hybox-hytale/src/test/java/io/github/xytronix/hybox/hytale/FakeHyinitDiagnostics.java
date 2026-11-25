package io.github.xytronix.hybox.hytale;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class FakeHyinitDiagnostics {
    static final String REFIXES_SHA = "4f2d9c0b7e1a6d35c8b2e9f01a7d4c63b5e8f2a19d0c7b6e3f4a1d2c5b8e9f07";

    private FakeHyinitDiagnostics() {
    }

    public static int apiVersion() {
        return 1;
    }

    public static Map<String, Object> snapshot() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("apiVersion", 1);
        out.put("hyinitVersion", "0.3.1");
        out.put("launchMode", "launcher");
        out.put("mixinVersion", "0.8.7");
        out.put("fabricMixinVersion", "0.17.5");
        out.put("mixinExtrasVersion", "0.5.5");
        out.put("sources", List.of(
            source("refixes-0.6.1.jar", "IroriPowered:Refixes", "0.6.1", REFIXES_SHA, List.of("refixes.mixins.json")),
            source("RegionManagerMixin-1.5.0.jar", "Example:RegionManagerMixin", "1.5.0", null, List.of("region_manager.mixins.json"))));
        out.put("configs", List.of(Map.of("name", "refixes.mixins.json", "source", "refixes-0.6.1.jar",
            "mixinPackage", "cc.irori.refixes.early.mixin",
            "mixins", List.of("cc.irori.refixes.early.mixin.MixinWorld", "cc.irori.refixes.early.mixin.MixinChunk"),
            "targets", List.of("com.hypixel.hytale.server.core.universe.world.World"), "compatibility", "0.16.0",
            "priority", 1000, "required", true)));
        out.put("problems", List.of(Map.of("level", "error",
            "message", "Mixin transformation of com.example.Broken failed (applied by RegionManagerMixin-1.5.0.jar)",
            "source", "RegionManagerMixin-1.5.0.jar", "detail", "InvalidInjectionException: no target"),
            Map.of("level", "warn",
                "message", "Mixin com.example.region.mixin.WorldMixin from region_manager.mixins.json could not be applied to com.example.World and was skipped",
                "source", "RegionManagerMixin-1.5.0.jar", "detail", "java.lang.IllegalStateException: boom",
                "stackTrace", "java.lang.IllegalStateException: boom\n\tat com.example.region.RegionPlugin.check(RegionPlugin.java:12)\n"
                    + "\tat org.spongepowered.asm.mixin.transformer.MixinInfo.<init>(MixinInfo.java:868)\n")));
        out.put("problemsOmitted", 0L);
        out.put("classesTransformed", 812L);
        out.put("transformNanos", 431_000_000L);
        out.put("mixinTargetClasses", 2L);
        out.put("bootstrapMillis", 950L);
        return out;
    }

    public static List<Map<String, String>> mixinsFor(String className, String methodName) {
        Map<String, List<Map<String, String>>> methods = methodMap().get(className.replace('/', '.'));
        return methods == null ? List.of() : methods.getOrDefault(methodName, List.of());
    }

    public static Map<String, Map<String, List<Map<String, String>>>> methodMap() {
        return methodMap(false);
    }

    static Map<String, Map<String, List<Map<String, String>>>> methodMap(boolean overloads) {
        Shape shape = new Shape(overloads);
        return Map.of(
            WORLD, Map.of(
                "tick", List.of(
                    shape.entry("cc.irori.refixes.early.mixin.MixinWorld", REFIXES, "changed", "(F)V", "100", "140"),
                    shape.entry("com.example.region.mixin.WorldMixin", REGION, "changed", "(F)V", "100", "140")),
                "unload", List.of(
                    shape.entry("cc.irori.refixes.early.mixin.MixinWorld", REFIXES, "changed", "()V", "200", "210"),
                    shape.entry("com.example.region.mixin.WorldMixin", REGION, "changed", "(Z)V", "220", "240")),
                "handler$zej000$refixesjar$refixes$tick", List.of(
                    shape.entry("cc.irori.refixes.early.mixin.MixinWorld", REFIXES, "merged", "(F)V", null, null))),
            "com.hypixel.hytale.server.core.universe.world.chunk.WorldChunk", Map.of(
                "load", List.of(
                    shape.entry("cc.irori.refixes.early.mixin.MixinChunk", REFIXES, "changed", "()V", "20", "30"))),
            MixinOverloads.class.getName(), Map.of(
                "work", List.of(
                    shape.entry("cc.irori.refixes.early.mixin.MixinOverloads", REFIXES, "merged", "(J)J", null, null))));
    }

    static final String WORLD = "com.hypixel.hytale.server.core.universe.world.World";
    private static final String REFIXES = "refixes-0.6.1.jar";
    private static final String REGION = "RegionManagerMixin-1.5.0.jar";

    private record Shape(boolean overloads) {
        Map<String, String> entry(String mixin, String source, String kind, String descriptor, String first, String last) {
            Map<String, String> out = new LinkedHashMap<>();
            out.put("mixin", mixin);
            out.put("config", source.equals(REFIXES) ? "refixes.mixins.json" : "region_manager.mixins.json");
            out.put("source", source);
            out.put("kind", kind);
            if (overloads) {
                out.put("descriptor", descriptor);
                out.put("firstLine", first);
                out.put("lastLine", last);
            }
            return out;
        }
    }

    public static final class MixinOverloads {
        private MixinOverloads() {
        }

        public static long work(long millis) {
            long end = System.nanoTime() + millis * 1_000_000;
            long sum = 0;
            while (System.nanoTime() < end) {
                sum += Long.numberOfTrailingZeros(sum + end);
            }
            return sum;
        }

        public static long work(long millis, int seed) {
            long end = System.nanoTime() + millis * 1_000_000;
            long sum = seed;
            while (System.nanoTime() < end) {
                sum += Long.numberOfLeadingZeros(sum ^ end);
            }
            return sum;
        }
    }

    public static final class Overloads {
        private Overloads() {
        }

        public static int apiVersion() {
            return 2;
        }

        public static Map<String, Object> snapshot() {
            Map<String, Object> out = FakeHyinitDiagnostics.snapshot();
            out.put("applyNanosBySource", Map.of(REFIXES, 221_289_712L, "Hyinit-0.5.0.jar", 29_034_756L));
            out.put("conditionSkips", List.of(skip("a.MixinA", null, "configPlugin"), skip("a.MixinB", null, "configPlugin"),
                skip("a.MixinB", null, "configPlugin"), skip("a.MixinC", "onTick", "condition"),
                skip("a.MixinD", null, "requirement")));
            return out;
        }

        private static Map<String, Object> skip(String mixin, String method, String kind) {
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("mixin", mixin);
            out.put("method", method);
            out.put("field", null);
            out.put("source", REFIXES);
            out.put("kind", kind);
            out.put("condition", kind.equals("configPlugin") ? "config plugin a.Plugin" : "@IfHytale >=0.8.0");
            return out;
        }

        public static List<Map<String, String>> mixinsFor(String className, String methodName) {
            return mixinsFor(className, methodName, null);
        }

        public static List<Map<String, String>> mixinsFor(String className, String methodName, String descriptor) {
            Map<String, List<Map<String, String>>> methods = methodMap().get(className.replace('/', '.'));
            List<Map<String, String>> entries = methods == null ? List.of() : methods.getOrDefault(methodName, List.of());
            return entries.stream().filter(e -> descriptor == null || descriptor.equals(e.get("descriptor"))).toList();
        }

        public static Map<String, Map<String, List<Map<String, String>>>> methodMap() {
            return FakeHyinitDiagnostics.methodMap(true);
        }
    }

    private static Map<String, Object> source(String file, String pluginId, String version, String sha, List<String> configs) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("file", file);
        out.put("pluginId", pluginId);
        out.put("version", version);
        out.put("hasMain", true);
        out.put("mixinsSkipped", false);
        out.put("mixinCompatibility", null);
        out.put("sha256", sha);
        out.put("configs", configs);
        return out;
    }

    public static final class Throwing {
        private Throwing() {
        }

        public static int apiVersion() {
            return 1;
        }

        public static Map<String, Object> snapshot() {
            throw new IllegalStateException("broken");
        }

        public static List<Map<String, String>> mixinsFor(String className, String methodName) {
            throw new IllegalStateException("broken");
        }

        public static Map<String, Map<String, List<Map<String, String>>>> methodMap() {
            throw new IllegalStateException("broken");
        }
    }
}
