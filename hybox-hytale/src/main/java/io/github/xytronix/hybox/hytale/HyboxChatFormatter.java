package io.github.xytronix.hybox.hytale;

import com.hypixel.hytale.server.core.Message;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import io.github.xytronix.hybox.core.capture.CaptureResult;
import io.github.xytronix.hybox.core.capture.HeapHistogram;
import io.github.xytronix.hybox.core.config.HyboxConfig;
import io.github.xytronix.hybox.core.trigger.TriggerPolicy;

final class HyboxChatFormatter {
    enum Tone {
        OK("#80c991"), WARN("#efbd62"), ERROR("#ef8181");

        private final String color;

        Tone(String color) {
            this.color = color;
        }
    }

    record ArmedTriggers(boolean deadlock, boolean heap, boolean gc, boolean cpu, boolean net, boolean playerDrop) {}

    record Trigger(String name, boolean armed, String threshold) {
        String details() {
            return name + ": " + (armed ? "armed" : "disabled") + " · " + threshold;
        }
    }

    private HyboxChatFormatter() {
    }

    static Message message(Tone tone, String text) {
        return Message.join(Message.raw("[Hybox]").color("#7a9cc6").bold(true),
            Message.raw(" " + singleLine(text)).color(tone.color));
    }

    static List<String> status(HyboxRuntime.Health health, Instant now, long profileSeconds) {
        List<String> lines = new ArrayList<>(3);
        lines.add("Recorder " + (health.running() ? "running" : "stopped")
            + " · capture " + (health.captureBusy() ? "busy (" + singleLine(health.captureProgress()) + ")" : "idle")
            + " · " + health.bundles() + " bundles");
        lines.add("Last incident: " + value(health.lastIncident())
            + (health.lastSuccess() == null ? "" : " · " + age(health.lastSuccess(), now))
            + " · outcome: " + outcome(health.lastOutcome()));
        List<String> warnings = new ArrayList<>();
        if (health.snapshotError() != null) warnings.add("snapshot error");
        if (health.captureError() != null) warnings.add("capture error");
        if (health.storagePressure() != null) warnings.add("storage pressure");
        var callbacks = health.callbacks();
        if (callbacks.failures() > 0) warnings.add("callback failures=" + callbacks.failures());
        if (callbacks.timeouts() > 0) warnings.add("callback timeouts=" + callbacks.timeouts());
        if (callbacks.quarantined() > 0) warnings.add("callbacks quarantined=" + callbacks.quarantined());
        if (callbacks.rejected() > 0) warnings.add("callbacks rejected=" + callbacks.rejected());
        if (callbacks.active() >= 2) warnings.add("callback workers exhausted");
        if (profileSeconds > 0) warnings.add("profile " + profileSeconds + "s remaining");
        if (!warnings.isEmpty()) lines.add("Warnings: " + String.join("; ", warnings));
        return List.copyOf(lines);
    }

    static List<Message> statusMessages(HyboxRuntime.Health health, Instant now, long profileSeconds) {
        List<String> lines = status(health, now, profileSeconds);
        List<Message> messages = new ArrayList<>(lines.size());
        messages.add(message(health.running() ? Tone.OK : Tone.ERROR, lines.get(0)));
        messages.add(message(health.lastOutcome() == null ? Tone.OK : captureTone(health.lastOutcome()), lines.get(1)));
        if (lines.size() > 2) {
            messages.add(message(health.snapshotError() != null || health.captureError() != null
                ? Tone.ERROR : Tone.WARN, lines.get(2)));
        }
        return List.copyOf(messages);
    }

    private static void addHealthDetails(List<String> lines, HyboxRuntime.Health health, Instant now) {
        lines.add("Last snapshot: " + (health.lastSnapshot() == null ? "none"
            : age(health.lastSnapshot(), now) + " (" + health.lastSnapshot() + ")")
            + (health.snapshotError() == null ? "" : " · error: " + value(health.snapshotError())));
        lines.add("Capture: " + value(health.captureProgress())
            + (health.captureError() == null ? "" : " · error: " + value(health.captureError())));
        lines.add("Last success: " + value(health.lastSuccess()));
        lines.add("Admission: busy=" + health.busy() + ", dropped=" + health.dropped() + ", rejected=" + health.rejected());
        lines.add("Storage: " + (!health.storageChecked() ? "not checked yet"
            : health.storagePressure() == null ? "healthy" : "under pressure · " + value(health.storagePressure())));
        var callbacks = health.callbacks();
        lines.add("Callbacks: calls=" + callbacks.calls() + ", failures=" + callbacks.failures()
            + ", timeouts=" + callbacks.timeouts() + ", quarantined=" + callbacks.quarantined()
            + ", rejected=" + callbacks.rejected() + ", active=" + callbacks.active() + "/2"
            + (callbacks.lastFailure() == null ? "" : " · last failure: " + value(callbacks.lastFailure())));
    }

    static List<String> statusDetails(HyboxRuntime.Health health, HyboxConfig config, Path configPath,
                                     Path incidentDir, Instant now) {
        List<String> lines = new ArrayList<>();
        addHealthDetails(lines, health, now);
        lines.add("Config: " + configPath);
        lines.add("Incidents: " + incidentDir);
        if (health.lastOutcome() != null) lines.add("Last outcome: " + captureMessage(health.lastOutcome()));
        var retention = config.capturePolicy().retention();
        lines.add("Retention: maxCount=" + retention.maxCount() + ", maxTotalBytes=" + retention.maxTotalBytes()
            + ", maxAge=" + value(retention.maxAge()));
        var triggers = config.triggerPolicy();
        lines.add("Trigger timing: cooldown=" + triggers.cooldown() + ", debounce=" + triggers.debounce()
            + ", stallDegradedMs=" + triggers.stallDegradedMs() + ", stallCriticalMs=" + triggers.stallCriticalMs());
        lines.add("Discord webhook: " + (config.discordWebhook().webhookUrl().isBlank() ? "disabled" : "enabled"));
        return List.copyOf(lines);
    }

    static Tone captureTone(CaptureResult result) {
        return switch (result.status()) {
            case CAPTURED -> Tone.OK;
            case FAILED, STOPPED -> Tone.ERROR;
            default -> Tone.WARN;
        };
    }

    static String captureMessage(CaptureResult result) {
        return switch (result.status()) {
            case CAPTURED -> "Captured incident " + result.incidentId().value();
            case DISABLED -> "Capture disabled by configuration.";
            case COOLDOWN -> "Capture skipped: cooldown active.";
            case DEBOUNCE -> "Capture skipped: duplicate trigger (debounce).";
            case STORAGE_PRESSURE -> "Capture skipped: storage pressure.";
            case CANCELLED -> "Capture cancelled.";
            case FAILED -> "Capture failed: " + value(result.detail());
            case BUSY -> "Capture busy; try again shortly.";
            case STOPPED -> "Capture stopped: Hybox is not running.";
        };
    }

    static String captureLine(CaptureResult result, Path incidentDir, boolean player) {
        if (!result.captured()) return result.status() == CaptureResult.Status.FAILED
            ? "Capture failed; use /hybox status --verbose for details." : captureMessage(result);
        Path bundle = incidentDir.resolve("incident-" + result.incidentId().value() + ".zip");
        return "Captured · " + outputPath(bundle, player);
    }

    static String outputPath(Path path, boolean player) {
        return (player ? path.getFileName() : path).toString();
    }

    static List<Trigger> triggers(TriggerPolicy policy, ArmedTriggers armed) {
        var detectors = policy.detectors();
        var modules = detectors.modules();
        return List.of(
            new Trigger("heartbeat stall", modules.heartbeatStall(), "degraded >= " + policy.stallDegradedMs()
                + " ms, critical >= " + policy.stallCriticalMs() + " ms"),
            new Trigger("tick degraded", modules.tickDegraded(), "avg >= " + policy.tickAvgDegradedMs()
                + " ms, critical >= " + policy.tickAvgCriticalMs() + " ms"),
            new Trigger("world failure", true, "always on"),
            new Trigger("deadlock", armed.deadlock(), "JVM deadlock detected"),
            new Trigger("heap pressure", armed.heap(), ">= " + detectors.heapPressurePct()
                + "% after GC for " + detectors.heapPressureSustain()),
            new Trigger("gc pressure", armed.gc(), ">= " + detectors.gcPressurePct()
                + "% of " + detectors.gcPressureWindow() + " window"),
            new Trigger("cpu saturation", armed.cpu(), ">= " + detectors.cpuSaturationPct()
                + "% for " + detectors.cpuSaturationSustain()),
            new Trigger("net saturation", armed.net(), "in >= " + detectors.netInMbps()
                + ", out >= " + detectors.netOutMbps() + " Mbit/s for " + detectors.netSustain() + " (0 disables a direction)"),
            new Trigger("player drop", armed.playerDrop(), ">= " + detectors.playerDropPct()
                + "% within " + detectors.playerDropWindow() + ", min " + detectors.playerDropMinPlayers() + " players"),
            new Trigger("log error", modules.logError(), "throwable=" + detectors.logErrorRequireThrowable()
                + ", skipSentry=" + detectors.logErrorSkipSentry() + ", dedupe=" + detectors.logErrorDedupeWindow()
                + ", ignore=" + detectors.logErrorIgnore().size()));
    }

    static String triggerSummary(List<Trigger> triggers) {
        String enabled = String.join(", ", triggers.stream().filter(Trigger::armed).map(Trigger::name).toList());
        String disabled = String.join(", ", triggers.stream().filter(trigger -> !trigger.armed()).map(Trigger::name).toList());
        return "Armed: " + (enabled.isEmpty() ? "none" : enabled)
            + " · disabled: " + (disabled.isEmpty() ? "none" : disabled);
    }

    static String incidentLine(HyboxIncidents.Incident incident, Instant now, boolean details) {
        return incident.id() + "  " + incidentHeadline(incident) + "  "
            + (incident.createdAt() == null ? "unknown age" : age(incident.createdAt(), now))
            + (details ? "  " + incident.path() : "");
    }

    static String incidentHeadline(HyboxIncidents.Incident incident) {
        String headline = singleLine(incident.headline());
        if (headline.length() > 100) headline = headline.substring(0, 99) + "…";
        return incident.kind() == null ? headline : singleLine(incident.kind()) + "/" + headline;
    }

    static String histogramLine(HeapHistogram.Entry entry, long shareBase) {
        return entry.className() + " · " + String.format(Locale.US, "%,d", entry.instances()) + " objects · "
            + bytes(entry.bytes()) + " · " + heapShare(entry, shareBase);
    }

    static String heapShare(HeapHistogram.Entry entry, long shareBase) {
        return String.format(Locale.US, "%.1f%%", 100.0 * entry.bytes() / Math.max(1, shareBase));
    }

    static String bytes(long bytes) {
        if (bytes < 1024) return bytes + " B";
        String[] units = {"KiB", "MiB", "GiB", "TiB"};
        double value = bytes;
        int unit = -1;
        do {
            value /= 1024.0;
            unit++;
        } while (value >= 1024 && unit < units.length - 1);
        return String.format(Locale.US, "%.1f %s", value, units[unit]);
    }

    static String age(Instant at, Instant now) {
        long seconds = Math.max(0, Duration.between(at, now).getSeconds());
        if (seconds < 60) return "just now";
        if (seconds < 3600) return seconds / 60 + "m ago";
        if (seconds < 86400) return seconds / 3600 + "h ago";
        return seconds / 86400 + "d ago";
    }

    static String singleLine(String text) {
        if (text == null) return "none";
        StringBuilder out = new StringBuilder(text.length());
        boolean space = false;
        for (int i = 0; i < text.length(); i++) {
            char character = text.charAt(i);
            if (Character.isWhitespace(character) || Character.isISOControl(character)) {
                if (!space && !out.isEmpty()) out.append(' ');
                space = true;
            } else if (Character.getType(character) != Character.FORMAT) {
                out.append(character);
                space = false;
            }
        }
        return out.toString().strip();
    }

    private static String outcome(CaptureResult result) {
        return result == null ? "none" : result.status().name().toLowerCase(Locale.ROOT).replace('_', ' ');
    }

    private static String value(Object value) {
        return value == null ? "none" : singleLine(value.toString());
    }
}
