package io.github.xytronix.hybox.core.capture;

import java.util.Objects;

import io.github.xytronix.hybox.core.incident.IncidentId;

public record CaptureResult(Status status, IncidentId incidentId, String detail, String retention) {
    public enum Status {
        CAPTURED,
        DISABLED,
        COOLDOWN,
        DEBOUNCE,
        STORAGE_PRESSURE,
        CANCELLED,
        FAILED,
        BUSY,
        STOPPED
    }

    public CaptureResult {
        Objects.requireNonNull(status, "status");
        if ((status == Status.CAPTURED) != (incidentId != null)) {
            throw new IllegalArgumentException("Only captured results must have an incident ID");
        }
        detail = bounded(detail, 128);
        retention = bounded(retention, 256);
    }

    public CaptureResult(Status status, IncidentId incidentId, String detail) {
        this(status, incidentId, detail, null);
    }

    private static String bounded(String text, int limit) {
        if (text == null) {
            return null;
        }
        StringBuilder bounded = new StringBuilder(Math.min(text.length(), limit));
        for (int i = 0; i < Math.min(text.length(), limit); i++) {
            char character = text.charAt(i);
            if (!Character.isISOControl(character) && Character.getType(character) != Character.FORMAT) {
                bounded.append(character);
            }
        }
        return bounded.toString();
    }

    public static CaptureResult captured(IncidentId incidentId) {
        return captured(incidentId, null);
    }

    public static CaptureResult captured(IncidentId incidentId, String retention) {
        return new CaptureResult(Status.CAPTURED, Objects.requireNonNull(incidentId, "incidentId"), null, retention);
    }

    public static CaptureResult of(Status status) {
        return new CaptureResult(status, null, null);
    }

    public static CaptureResult failed(String detail) {
        return new CaptureResult(Status.FAILED, null, detail);
    }

    public boolean captured() {
        return status == Status.CAPTURED;
    }
}
