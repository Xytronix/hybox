package io.github.xytronix.hybox.core.jfr;

import java.util.Objects;

public record IncidentMark(String id, String severity, String headline) {
    static final String PREFIX = "hybox incident: ";

    public IncidentMark {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(severity, "severity");
        headline = headline == null ? "" : headline;
    }

    public static void commit(String id, String severity, String headline) {
        HyboxMarkerEvent marker = new HyboxMarkerEvent();
        if (!marker.isEnabled()) return;
        marker.message = format(new IncidentMark(id, severity, headline));
        marker.commit();
    }

    static String format(IncidentMark mark) {
        String headline = mark.headline().replace('\n', ' ').strip();
        return PREFIX + mark.id() + " " + mark.severity() + (headline.isEmpty() ? "" : " " + headline);
    }

    public static IncidentMark parse(String message) {
        if (message == null || !message.startsWith(PREFIX)) return null;
        String[] parts = message.substring(PREFIX.length()).split(" ", 3);
        if (parts.length < 2 || parts[0].isBlank() || parts[1].isBlank()) return null;
        return new IncidentMark(parts[0], parts[1], parts.length > 2 ? parts[2] : "");
    }
}
