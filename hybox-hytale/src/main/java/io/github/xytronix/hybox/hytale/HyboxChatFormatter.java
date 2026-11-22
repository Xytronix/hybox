package io.github.xytronix.hybox.hytale;

import com.hypixel.hytale.server.core.Message;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import io.github.xytronix.hybox.core.capture.CaptureResult;
import io.github.xytronix.hybox.core.capture.HeapHistogram;
import io.github.xytronix.hybox.core.config.HyboxConfig;
import io.github.xytronix.hybox.core.env.PathMasker;
import io.github.xytronix.hybox.core.retention.RetentionText;
import io.github.xytronix.hybox.core.retention.RetentionUsage;
import io.github.xytronix.hybox.core.text.Units;
import io.github.xytronix.hybox.core.trigger.TriggerNames;
import io.github.xytronix.hybox.core.trigger.TriggerPolicy;

final class HyboxChatFormatter {
    enum Tone {
        OK("#4cc38a"), WARN("#ff8a3d"), ERROR("#f0626b");

        final String color;

        Tone(String color) {
            this.color = color;
        }
    }

    record ArmedTriggers(boolean deadlock, boolean heap, boolean gc, boolean cpu, boolean net, boolean playerDrop) {}

    record Trigger(String kind, String name, boolean armed, String threshold) {
        String details() {
            return name + " (" + (armed ? "on" : "off") + "): " + threshold;
        }
    }

    private HyboxChatFormatter() {
    }

    static Message message(Tone tone, String text) {
        return Message.join(Message.raw("[Hybox]").color("#ffd21f").bold(true),
            Message.raw(" " + singleLine(text)).color(tone.color));
    }

    static List<String> status(HyboxRuntime.Health health, HytaleTelemetrySampler.Status live, Instant now, long profileSeconds) {
        List<String> lines = new ArrayList<>(4);
        lines.add(recorderLine(health));
        String healthLine = healthLine(live);
        if (healthLine != null) lines.add(healthLine);
        lines.add(lastIncidentLine(health, now));
        String warnings = warningsLine(health, profileSeconds);
        if (warnings != null) lines.add(warnings);
        return List.copyOf(lines);
    }

    static List<Message> statusMessages(HyboxRuntime.Health health, HytaleTelemetrySampler.Status live, Instant now, long profileSeconds) {
        List<Message> messages = new ArrayList<>(4);
        messages.add(message(health.running() ? Tone.OK : Tone.ERROR, recorderLine(health)));
        String healthLine = healthLine(live);
        if (healthLine != null) messages.add(message(Tone.OK, healthLine));
        messages.add(message(health.lastOutcome() == null ? Tone.OK : captureTone(health.lastOutcome()), lastIncidentLine(health, now)));
        String warnings = warningsLine(health, profileSeconds);
        if (warnings != null) {
            messages.add(message(health.snapshotError() != null || health.captureError() != null ? Tone.ERROR
                : warnings.startsWith("Warnings") ? Tone.WARN : Tone.OK, warnings));
        }
        return List.copyOf(messages);
    }

    private static String recorderLine(HyboxRuntime.Health health) {
        return "Recorder " + (health.running() ? "running" : "stopped")
            + ", capture " + (health.captureBusy() ? "busy (" + singleLine(health.captureProgress()) + ")" : "idle")
            + ", " + Units.count(health.bundles(), "bundle") + ".";
    }

    static String healthLine(HytaleTelemetrySampler.Status live) {
        if (live == null) return null;
        List<String> parts = new ArrayList<>(4);
        if (live.tps() >= 0) parts.add("TPS " + String.format(Locale.ROOT, "%.1f", live.tps()));
        if (live.tickMs() >= 0) parts.add("tick " + String.format(Locale.ROOT, "%.1f", live.tickMs()) + " ms");
        if (live.cpuPct() >= 0) parts.add("CPU " + Math.round(live.cpuPct()) + "%");
        if (live.heapUsed() >= 0) parts.add("heap " + gib(live.heapUsed()) + (live.heapMax() > 0 ? " of " + gib(live.heapMax()) : "") + " GiB");
        if (parts.isEmpty()) return null;
        String line = String.join(", ", parts);
        return Character.toUpperCase(line.charAt(0)) + line.substring(1);
    }

    private static String gib(long bytes) {
        return String.format(Locale.ROOT, "%.1f", bytes / 1073741824.0);
    }

    private static String lastIncidentLine(HyboxRuntime.Health health, Instant now) {
        return "Last incident: " + value(health.lastIncident())
            + (health.lastSuccess() == null ? "" : ", " + age(health.lastSuccess(), now))
            + ". Outcome: " + outcome(health.lastOutcome()) + ".";
    }

    private static String warningsLine(HyboxRuntime.Health health, long profileSeconds) {
        List<String> warnings = new ArrayList<>();
        if (health.snapshotError() != null) warnings.add("crash backup error");
        if (health.captureError() != null) warnings.add("capture error");
        if (health.storagePressure() != null) warnings.add("storage pressure");
        var callbacks = health.callbacks();
        if (callbacks.failures() > 0) warnings.add(Units.count(callbacks.failures(), "callback failure"));
        if (callbacks.timeouts() > 0) warnings.add(Units.count(callbacks.timeouts(), "callback timeout"));
        if (callbacks.quarantined() > 0) warnings.add(callbacks.quarantined() + (callbacks.quarantined() == 1 ? " callback" : " callbacks") + " quarantined");
        if (callbacks.rejected() > 0) warnings.add(callbacks.rejected() + (callbacks.rejected() == 1 ? " callback" : " callbacks") + " rejected");
        if (callbacks.active() >= 2) warnings.add("callback workers exhausted");
        String profile = profileSeconds > 0 ? "Profile ends in " + span(Duration.ofSeconds(profileSeconds)) + "." : "";
        if (warnings.isEmpty()) return profile.isEmpty() ? null : profile;
        return "Warnings: " + String.join(", ", warnings) + "." + (profile.isEmpty() ? "" : " " + profile);
    }

    static String span(Duration duration) {
        long seconds = Math.max(0, duration.toSeconds());
        if (seconds <= 120) return seconds + " s";
        if (seconds % 3600 == 0) return seconds / 3600 + " h";
        if (seconds < 3600 && seconds % 60 == 0) return seconds / 60 + " min";
        if (seconds < 3600) return seconds / 60 + " min " + seconds % 60 + " s";
        return seconds / 3600 + " h " + seconds % 3600 / 60 + " min";
    }

    static String millis(long ms) {
        if (ms < 1000) return ms + " ms";
        return ms % 1000 == 0 ? ms / 1000 + " s" : String.format(Locale.ROOT, "%.1f s", ms / 1000.0);
    }

    static String timing(TriggerPolicy policy) {
        return "At most one capture every " + span(policy.cooldown()) + ". Repeats within " + span(policy.debounce()) + " are ignored.";
    }

    private static void addHealthDetails(List<String> lines, HyboxRuntime.Health health, Instant now) {
        lines.add("Last crash backup: " + (health.lastSnapshot() == null ? "none"
            : age(health.lastSnapshot(), now) + " (" + health.lastSnapshot() + ")")
            + (health.snapshotError() == null ? "" : ". Error: " + value(health.snapshotError())));
        lines.add("Capture: " + value(health.captureProgress())
            + (health.captureError() == null ? "" : ". Error: " + value(health.captureError())));
        lines.add("Last success: " + value(health.lastSuccess()));
        List<String> skipped = new ArrayList<>(3);
        if (health.busy() > 0) skipped.add(health.busy() + " busy");
        if (health.dropped() > 0) skipped.add(health.dropped() + " dropped");
        if (health.rejected() > 0) skipped.add(health.rejected() + " rejected");
        lines.add("Triggers skipped: " + (health.busy() + health.dropped() + health.rejected())
            + (skipped.isEmpty() ? "" : " (" + String.join(", ", skipped) + ")"));
        lines.add("Recording storage: " + (!health.storageChecked() ? "not checked yet"
            : health.storagePressure() == null ? "healthy" : "under pressure. " + value(health.storagePressure())));
        var callbacks = health.callbacks();
        List<String> callbackParts = new ArrayList<>(6);
        callbackParts.add(Units.count(callbacks.calls(), "call"));
        if (callbacks.failures() > 0) callbackParts.add(callbacks.failures() + " failed");
        if (callbacks.timeouts() > 0) callbackParts.add(callbacks.timeouts() + " timed out");
        if (callbacks.quarantined() > 0) callbackParts.add(callbacks.quarantined() + " quarantined");
        if (callbacks.rejected() > 0) callbackParts.add(callbacks.rejected() + " rejected");
        callbackParts.add(callbacks.active() + " of 2 running");
        lines.add("Callbacks: " + String.join(", ", callbackParts)
            + (callbacks.lastFailure() == null ? "" : ". Last failure: " + value(callbacks.lastFailure())));
    }

    static List<String> statusDetails(HyboxRuntime.Health health, HyboxConfig config, Path configPath,
                                     Path incidentDir, Instant now, boolean player) {
        List<String> lines = new ArrayList<>();
        addHealthDetails(lines, health, now);
        lines.add("Config: " + shownPath(configPath, player));
        lines.add("Incidents: " + shownPath(incidentDir, player));
        if (health.lastOutcome() != null) lines.add("Last outcome: " + captureMessage(health.lastOutcome()));
        lines.add("Retention: " + RetentionText.limits(config.capturePolicy().retention()) + ".");
        var triggers = config.triggerPolicy();
        lines.add("Triggers: " + Character.toLowerCase(timing(triggers).charAt(0)) + timing(triggers).substring(1)
            + " Stall: degraded at " + millis(triggers.stallDegradedMs()) + ", critical at " + millis(triggers.stallCriticalMs()) + ".");
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
            case CAPTURED -> "Captured incident " + result.incidentId().value()
                + (result.retention() == null ? "" : ". " + result.retention());
            case DISABLED -> "Capture disabled by configuration.";
            case COOLDOWN -> "Capture skipped. Another capture ran too recently. See the cooldown in /hybox settings.";
            case DEBOUNCE -> "Capture skipped. The same trigger fired a moment ago.";
            case STORAGE_PRESSURE -> "Capture skipped. Recording storage is full. Run /hybox status --verbose.";
            case CANCELLED -> "Capture cancelled.";
            case FAILED -> "Capture failed: " + value(result.detail());
            case BUSY -> "A capture is already running. Try again when it ends.";
            case STOPPED -> "Capture stopped: Hybox is not running.";
        };
    }

    static final String SHARING_NOTE = "Player names, paths and IP addresses are hidden. "
        + "Check world names and mod data before you share it.";

    private static final DateTimeFormatter SAVED_TIME = DateTimeFormatter.ofPattern("HH:mm 'UTC'").withZone(ZoneOffset.UTC);

    static String captureLine(CaptureResult result, Path incidentDir, boolean player) {
        if (!result.captured()) return result.status() == CaptureResult.Status.FAILED
            ? "Capture failed. Run /hybox status --verbose for the reason." : captureMessage(result);
        return saved(result, incidentDir, player)
            + (result.retention() == null ? "" : " " + result.retention()) + " " + SHARING_NOTE;
    }

    static String saved(CaptureResult result, Path incidentDir, boolean player) {
        Instant at = HyboxIncidents.createdAt(result.incidentId().value());
        long bytes;
        try {
            bytes = Files.size(incidentDir.resolve("incident-" + result.incidentId().value() + ".zip"));
        } catch (IOException | RuntimeException missing) {
            bytes = -1;
        }
        String folder = shownPath(incidentDir, player);
        if (folder.startsWith("./")) folder = folder.substring(2);
        return "Incident saved" + (at == null ? "" : " at " + SAVED_TIME.format(at))
            + (bytes < 0 ? "" : ", " + Units.bytes(bytes) + ",") + " in " + folder + ".";
    }

    static String alertTitle(HyboxNotifications.Alert alert) {
        return "Hybox: " + DashboardModel.severityLabel(alert.severity()) + " incident saved";
    }

    static String alertChat(HyboxNotifications.Alert alert) {
        return DashboardModel.severityLabel(alert.severity()) + " incident saved: " + sentence(alert.headline())
            + " Open it with /hybox incident."
            + (alert.earlier() > 0 ? " " + Units.count(alert.earlier(), "other incident") + " saved since the last alert." : "");
    }

    static Tone alertTone(String severity) {
        return "CRITICAL".equalsIgnoreCase(severity) ? Tone.ERROR : "DEGRADED".equalsIgnoreCase(severity) ? Tone.WARN : Tone.OK;
    }

    static String alertLog(String severity, String headline, Path bundle, long bytes, int told) {
        return DashboardModel.severityLabel(severity) + " incident saved: " + sentence(headline) + " Bundle " + bundle
            + (bytes < 0 ? "" : ", " + Units.bytes(bytes)) + "."
            + (told < 0 ? " Staff were alerted less than " + span(HyboxNotifications.ALERT_QUIET) + " ago, so no new alert."
            : " Alerted " + Units.count(told, "staff member") + " in game.");
    }

    private static String sentence(String text) {
        String line = singleLine(text);
        return line.endsWith(".") ? line : line + ".";
    }

    static String storageLine(RetentionUsage usage) {
        String expired = RetentionText.expired(usage);
        return "Storage: " + RetentionText.usage(usage) + (usage.lowDisk() ? ", low disk space" : "")
            + (expired == null ? "" : ". " + expired);
    }

    static Tone storageTone(RetentionUsage usage) {
        return usage.lowDisk() || usage.expired() > 0 || usage.nearSizeLimit() ? Tone.WARN : Tone.OK;
    }

    static String outputPath(Path path, boolean player) {
        return (player ? path.getFileName() : path).toString();
    }

    private static String shownPath(Path path, boolean player) {
        return player ? PathMasker.local().apply(path.toAbsolutePath().toString()) : path.toString();
    }

    static List<Trigger> triggers(TriggerPolicy policy, ArmedTriggers armed) {
        var detectors = policy.detectors();
        var modules = detectors.modules();
        return List.of(
            new Trigger("HEARTBEAT_STALL", "Heartbeat stall", modules.heartbeatStall(), "Degraded at " + millis(policy.stallDegradedMs())
                + ", critical at " + millis(policy.stallCriticalMs())),
            new Trigger("TICK_DEGRADED", "Slow ticks", modules.tickDegraded(), "Degraded at " + millis(policy.tickAvgDegradedMs())
                + " average tick time, critical at " + millis(policy.tickAvgCriticalMs())),
            new Trigger("WORLD_FAILURE", "World failure", true, "Always on"),
            new Trigger("DEADLOCK", "Deadlock", armed.deadlock(), "When JVM threads deadlock"),
            new Trigger("HEAP_PRESSURE", "Heap pressure", armed.heap(), detectors.heapPressurePct()
                + "% after GC for " + span(detectors.heapPressureSustain())),
            new Trigger("GC_PRESSURE", "GC pressure", armed.gc(), detectors.gcPressurePct()
                + "% of a " + span(detectors.gcPressureWindow()) + " window"),
            new Trigger("CPU_SATURATION", "CPU saturation", armed.cpu(), detectors.cpuSaturationPct()
                + "% for " + span(detectors.cpuSaturationSustain())),
            new Trigger("NET_SATURATION", "Network saturation", armed.net(), detectors.netInMbps() == 0 && detectors.netOutMbps() == 0 ? "Off while both limits are 0"
                : "In " + detectors.netInMbps() + ", out " + detectors.netOutMbps() + " Mbit/s for " + span(detectors.netSustain()) + ". 0 turns a direction off"),
            new Trigger("PLAYER_DROP", "Player drop", armed.playerDrop(), detectors.playerDropPct()
                + "% in " + span(detectors.playerDropWindow()) + " with at least " + Units.count(detectors.playerDropMinPlayers(), "player")),
            new Trigger("LOG_ERROR", "Log errors", modules.logError(), (detectors.logErrorRequireThrowable() ? "With an exception" : "Any error")
                + (detectors.logErrorSkipSentry() ? ", not marked to skip Sentry" : "")
                + ", repeats ignored for " + span(detectors.logErrorDedupeWindow())
                + ", " + Units.count(detectors.logErrorIgnore().size(), "ignore pattern")));
    }

    static String triggerSummary(List<Trigger> triggers) {
        String enabled = String.join(", ", triggers.stream().filter(Trigger::armed).map(Trigger::name).toList());
        String disabled = String.join(", ", triggers.stream().filter(trigger -> !trigger.armed()).map(Trigger::name).toList());
        return "On: " + (enabled.isEmpty() ? "none" : enabled)
            + ". Off: " + (disabled.isEmpty() ? "none" : disabled) + ".";
    }

    static String incidentLine(HyboxIncidents.Incident incident, Instant now, boolean details) {
        return incident.id() + "  " + incidentHeadline(incident) + "  "
            + (incident.createdAt() == null ? "unknown age" : age(incident.createdAt(), now))
            + (details ? "  " + incident.path() : "");
    }

    static String incidentHeadline(HyboxIncidents.Incident incident) {
        String headline = singleLine(incident.headline());
        if (headline.length() > 100) headline = headline.substring(0, 99) + "…";
        return incident.kind() == null ? headline : TriggerNames.label(incident.kind()) + ": " + headline;
    }

    static String histogramLine(HeapHistogram.Entry entry, long shareBase) {
        return entry.className() + " · " + Units.number(entry.instances()) + " objects · "
            + Units.bytes(entry.bytes()) + " · " + heapShare(entry, shareBase);
    }

    static String heapShare(HeapHistogram.Entry entry, long shareBase) {
        return Units.percent(entry.bytes(), Math.max(1, shareBase));
    }

    static String age(Instant at, Instant now) {
        long seconds = Math.max(0, Duration.between(at, now).getSeconds());
        if (seconds < 60) return "just now";
        if (seconds < 3600) return seconds / 60 + " min ago";
        if (seconds < 86400) return seconds / 3600 + " h ago";
        return Units.count(seconds / 86400, "day") + " ago";
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
