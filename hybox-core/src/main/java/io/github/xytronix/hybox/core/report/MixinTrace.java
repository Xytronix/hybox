package io.github.xytronix.hybox.core.report;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

final class MixinTrace {
    static final String HEADER = "h";
    static final String MOD = "m";
    static final String CODE = "c";
    static final String MUTED = "x";

    private static final List<String> MUTED_PACKAGES = List.of("java.", "javax.", "jdk.", "sun.", "com.sun.",
        "org.spongepowered.", "com.llamalad7.mixinextras.", "org.objectweb.asm.", "cc.irori.hyinit.");
    private static final String HYTALE = "com.hypixel.";

    record Line(String text, String kind, String mod) {}

    private final Map<String, String> modsByFile = new HashMap<>();
    private final MixinPackages packages;
    private final ReportAttribution attribution;

    MixinTrace(List<MixinSection.Config> configs, List<MixinSection.Source> sources, ReportAttribution attribution) {
        this.attribution = attribution;
        this.packages = new MixinPackages(configs);
        for (MixinSection.Source source : sources) {
            if (source.file() != null && source.pluginId() != null) {
                modsByFile.put(source.file(), shortName(source.pluginId()));
            }
        }
    }

    List<Line> lines(String trace) {
        List<Line> out = new ArrayList<>();
        for (String text : trace.split("\r?\n")) {
            String stripped = text.strip();
            if (stripped.startsWith("at ")) {
                String type = className(stripped.substring(3));
                if (muted(type)) {
                    out.add(new Line(text, MUTED, null));
                } else {
                    String mod = mod(type);
                    out.add(mod == null ? new Line(text, CODE, null) : new Line(text, MOD, mod));
                }
            } else if (stripped.startsWith("... ")) {
                out.add(new Line(text, MUTED, null));
            } else if (!stripped.isEmpty()) {
                out.add(new Line(text, HEADER, null));
            }
        }
        return out;
    }

    private String mod(String type) {
        if (type.startsWith(HYTALE)) {
            return null;
        }
        String jar = packages.jar(type);
        if (jar != null) {
            return modsByFile.getOrDefault(jar, fileName(jar));
        }
        String owner = attribution == null ? null : attribution.owner(type);
        return owner == null || owner.equals(ReportAttribution.HYINIT) ? null : shortName(owner);
    }

    static String className(String frame) {
        int paren = frame.indexOf('(');
        String method = paren < 0 ? frame : frame.substring(0, paren);
        String qualified = method.substring(method.lastIndexOf('/') + 1);
        int dot = qualified.lastIndexOf('.');
        return dot < 0 ? qualified : qualified.substring(0, dot);
    }

    private static boolean muted(String type) {
        for (String prefix : MUTED_PACKAGES) {
            if (type.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }

    private static String shortName(String id) {
        return id.substring(id.indexOf(':') + 1);
    }

    private static String fileName(String file) {
        return file.toLowerCase(Locale.ROOT).endsWith(".jar") ? file.substring(0, file.length() - 4) : file;
    }
}
