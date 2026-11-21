package io.github.xytronix.hybox.core.report;

import java.util.Locale;

public final class JavaRuntime {
    private JavaRuntime() {
    }

    public static String shortName(String vendor, String version, String jvm) {
        String distro = vendor == null || vendor.isBlank() ? "" : distro(vendor) + " ";
        String text = (distro + (version == null ? "" : version)).trim();
        return text.isEmpty() ? jvm : text;
    }

    public static String distro(String vendor) {
        String s = vendor == null ? "" : vendor.toLowerCase(Locale.ROOT);
        if (s.contains("adoptium") || s.contains("temurin")) return "Temurin";
        if (s.contains("corretto") || s.contains("amazon")) return "Corretto";
        if (s.contains("graalvm")) return "GraalVM";
        if (s.contains("azul") || s.contains("zulu")) return "Zulu";
        if (s.contains("oracle")) return "Oracle";
        if (s.contains("microsoft")) return "Microsoft";
        if (s.contains("bellsoft") || s.contains("liberica")) return "Liberica";
        String first = vendor == null ? "" : vendor.trim().split(" ")[0];
        return first.isEmpty() ? "JDK" : first;
    }
}
