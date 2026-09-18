package io.github.xytronix.hybox.core.capture;

import java.util.Objects;

import io.github.xytronix.hybox.core.incident.IncidentId;

public record CaptureResult(Status status, IncidentId incidentId, String detail) {
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
        if (detail != null) {
            StringBuilder bounded = new StringBuilder(Math.min(detail.length(), 128));
            for (int i = 0; i < Math.min(detail.length(), 128); i++) {
                char character = detail.charAt(i);
                if (!Character.isISOControl(character) && Character.getType(character) != Character.FORMAT) {
                    bounded.append(character);
                }
            }
            detail = bounded.toString();
        }
    }

    public static CaptureResult captured(IncidentId incidentId) {
        return new CaptureResult(Status.CAPTURED, Objects.requireNonNull(incidentId, "incidentId"), null);
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
