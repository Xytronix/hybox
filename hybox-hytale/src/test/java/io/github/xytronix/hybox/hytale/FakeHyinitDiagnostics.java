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
        return Map.of(
            "com.hypixel.hytale.server.core.universe.world.World", Map.of(
                "tick", List.of(
                    entry("cc.irori.refixes.early.mixin.MixinWorld", "refixes.mixins.json", "refixes-0.6.1.jar", "changed"),
                    entry("com.example.region.mixin.WorldMixin", "region_manager.mixins.json", "RegionManagerMixin-1.5.0.jar", "changed")),
                "handler$zej000$refixesjar$refixes$tick", List.of(
                    entry("cc.irori.refixes.early.mixin.MixinWorld", "refixes.mixins.json", "refixes-0.6.1.jar", "merged"))),
            "com.hypixel.hytale.server.core.universe.world.chunk.WorldChunk", Map.of(
                "load", List.of(
                    entry("cc.irori.refixes.early.mixin.MixinChunk", "refixes.mixins.json", "refixes-0.6.1.jar", "changed"))));
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

    private static Map<String, String> entry(String mixin, String config, String source, String kind) {
        return Map.of("mixin", mixin, "config", config, "source", source, "kind", kind);
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
