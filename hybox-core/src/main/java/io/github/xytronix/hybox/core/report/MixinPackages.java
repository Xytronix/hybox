package io.github.xytronix.hybox.core.report;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

final class MixinPackages {
    private static final String HYTALE = "com.hypixel.";
    private static final int ROOT_SEGMENTS = 3;

    private final Map<String, String> jars = new HashMap<>();

    MixinPackages(List<MixinSection.Config> configs) {
        Set<String> shared = new HashSet<>();
        for (MixinSection.Config config : configs) {
            String pkg = config.mixinPackage();
            if (pkg == null || pkg.isBlank() || config.source() == null || pkg.startsWith(HYTALE)) {
                continue;
            }
            for (String prefix : List.of(pkg, root(pkg))) {
                String previous = jars.putIfAbsent(prefix, config.source());
                if (previous != null && !previous.equals(config.source())) {
                    shared.add(prefix);
                }
            }
        }
        shared.forEach(jars::remove);
    }

    String jar(String type) {
        for (int dot = type.lastIndexOf('.'); dot > 0; dot = type.lastIndexOf('.', dot - 1)) {
            String jar = jars.get(type.substring(0, dot));
            if (jar != null) {
                return jar;
            }
        }
        return null;
    }

    private static String root(String pkg) {
        int dot = -1;
        for (int i = 0; i < ROOT_SEGMENTS; i++) {
            dot = pkg.indexOf('.', dot + 1);
            if (dot < 0) {
                return pkg;
            }
        }
        return pkg.substring(0, dot);
    }
}
