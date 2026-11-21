package io.github.xytronix.hybox.core.jfr;

import java.util.Locale;

public enum ProfileMode {
    LAG("lag"),
    MEMORY("memory");

    public static final String CONTEXT_KEY = "profileMode";
    public static final String DURING_KEY = "duringProfile";

    private final String id;

    ProfileMode(String id) {
        this.id = id;
    }

    public String id() {
        return id;
    }

    public static ProfileMode parse(String value) {
        if (value == null) return null;
        String id = value.strip().toLowerCase(Locale.ROOT);
        for (ProfileMode mode : values()) {
            if (mode.id.equals(id)) return mode;
        }
        return null;
    }
}
