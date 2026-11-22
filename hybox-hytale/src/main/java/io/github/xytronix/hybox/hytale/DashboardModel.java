package io.github.xytronix.hybox.hytale;

import io.github.xytronix.hybox.core.bundle.BundleArtifacts;
import io.github.xytronix.hybox.core.capture.CapturePolicy;
import io.github.xytronix.hybox.core.capture.CaptureResult;
import io.github.xytronix.hybox.core.capture.HeapHistogram;
import io.github.xytronix.hybox.core.retention.RetentionText;
import io.github.xytronix.hybox.core.retention.RetentionUsage;
import io.github.xytronix.hybox.core.text.Units;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

record DashboardModel(List<Tile> tiles, List<Warning> warnings, Profile profile, String captureStep,
                      List<HyboxIncidents.Incident> incidents, String incidentsMessage,
                      List<HyboxChatFormatter.Trigger> triggers, String triggerTiming,
                      String feedback, boolean captureAllowed, String captureHint, boolean openLastAvailable,
                      boolean profileStopAllowed, boolean profileCancelAllowed, boolean skipWaitAllowed, boolean profileSaving,
                      boolean trendsAllowed, ProfileForm profileForm, Heap heap) {
    enum Tone {
        OK("#80c991"), WARN("#efbd62"), ERROR("#ef8181"), NEUTRAL("#d3e3ed");

        final String color;

        Tone(String color) {
            this.color = color;
        }
    }

    record Tile(String label, String value, String detail, Tone tone, String severity) {}
    record Warning(String text, Tone tone) {}

    record ProfileForm(boolean allowed, int minutes, boolean keepBuffer, boolean starting) {
        static final ProfileForm DEFAULT = new ProfileForm(true, 5, true, false);
    }

    record Heap(boolean allowed, boolean confirming, boolean walking, HeapHistogram.Snapshot snapshot,
                Instant takenAt, String message) {
        static final int ROWS = 20;
        static final Heap EMPTY = new Heap(true, false, false, null, null, null);

        int rows() {
            return snapshot == null ? 0 : Math.min(ROWS, snapshot.entries().size());
        }
    }

    record Profile(Instant startedAt, Duration total, Instant now, String requesterName) {
        float fraction() {
            if (total.isNegative() || total.isZero()) return 1f;
            double elapsed = Duration.between(startedAt, now).toMillis();
            return (float) Math.max(0, Math.min(1, elapsed / total.toMillis()));
        }

        long remainingSeconds() {
            long remaining = Duration.between(now, captureAt()).toMillis();
            return remaining <= 0 ? 0 : (remaining + 999) / 1000;
        }

        Instant captureAt() {
            return startedAt.plus(total);
        }
    }

    DashboardModel {
        tiles = List.copyOf(tiles);
        warnings = List.copyOf(warnings);
        incidents = List.copyOf(incidents);
        triggers = List.copyOf(triggers);
    }

    static DashboardModel snapshot(HyboxRuntime runtime, Instant now, List<HyboxIncidents.Incident> incidents,
                                   String incidentsMessage, String feedback, boolean captureAllowed, boolean openLastAvailable,
                                   boolean profileStopAllowed, boolean profileCancelAllowed, boolean profileSaving, boolean trendsAllowed,
                                   ProfileForm profileForm, Heap heap) {
        var policy = runtime.config().triggerPolicy();
        Instant profileStart = runtime.profileStartedAt();
        Profile profile = runtime.profileActive() && profileStart != null
            ? new Profile(profileStart, runtime.profileTotalDuration(), now, runtime.profileRequesterName()) : null;
        return from(runtime.health(), runtime.storageUsage(), now, profile, incidents, incidentsMessage,
            HyboxChatFormatter.triggers(policy, HyboxDashboardPage.armedTriggers(runtime)),
            "Cooldown " + policy.cooldown() + " · debounce " + policy.debounce(), feedback, captureAllowed,
            captureHint(runtime.config().jfrMaxAge(), runtime.config().threadDumpCount(), runtime.config().capturePolicy(),
                !HyboxApi.registeredConfigPaths().isEmpty()), openLastAvailable,
            profileStopAllowed, profileCancelAllowed,
            captureAllowed && runtime.recoveryWaitActive(),
            profileSaving || runtime.profileCapturePending(), trendsAllowed, profileForm, heap);
    }

    static DashboardModel from(HyboxRuntime.Health health, RetentionUsage storage, Instant now, Profile profile,
                               List<HyboxIncidents.Incident> incidents, String incidentsMessage,
                               List<HyboxChatFormatter.Trigger> triggers, String triggerTiming,
                               String feedback, boolean captureAllowed, String captureHint, boolean openLastAvailable,
                               boolean profileStopAllowed, boolean profileCancelAllowed, boolean skipWaitAllowed, boolean profileSaving,
                               boolean trendsAllowed, ProfileForm profileForm, Heap heap) {
        List<Tile> tiles = new ArrayList<>(6);
        List<Warning> warnings = new ArrayList<>();
        tiles.add(new Tile("Recorder", !health.running() ? "Stopped" : health.snapshotError() != null ? "Snapshot failing" : "Running",
            health.lastSnapshot() == null ? "No snapshot yet" : "Last snapshot " + HyboxChatFormatter.age(health.lastSnapshot(), now),
            !health.running() ? Tone.ERROR : health.snapshotError() != null ? Tone.WARN : Tone.OK, null));
        if (health.captureBusy() || health.captureError() != null) {
            tiles.add(new Tile("Capture", health.captureBusy() ? "In progress" : "Failed",
                health.captureBusy() ? health.captureProgress() : "The last capture did not save",
                health.captureError() != null ? Tone.ERROR : Tone.OK, null));
        }
        List<String> bundleCounters = new ArrayList<>(3);
        if (health.busy() > 0) bundleCounters.add(health.busy() + " busy");
        if (health.dropped() > 0) bundleCounters.add(health.dropped() + " dropped");
        if (health.rejected() > 0) bundleCounters.add(health.rejected() + " rejected");
        tiles.add(new Tile("Bundles", Long.toString(health.bundles()),
            bundleCounters.isEmpty() ? "Saved incident bundles" : String.join(" · ", bundleCounters),
            health.dropped() > 0 || health.rejected() > 0 ? Tone.WARN : Tone.OK, null));
        HyboxIncidents.Incident latest = incidents.isEmpty() ? null : incidents.getFirst();
        boolean currentInHistory = latest != null && (health.lastIncident() == null || health.lastIncident().equals(latest.id()));
        Instant lastAt = currentInHistory ? latest.createdAt() : health.lastSuccess();
        String lastId = currentInHistory ? latest.id() : health.lastIncident();
        String severity = currentInHistory ? latest.severity() : null;
        tiles.add(new Tile("Last incident", lastAt == null ? lastId == null ? "None yet" : "Unknown age"
                : HyboxChatFormatter.age(lastAt, now),
            currentInHistory ? latest.headline() : lastId == null ? "Waiting for the first incident" : lastId,
            lastId == null ? Tone.OK : Tone.NEUTRAL, lastId == null ? null : severity == null ? "UNKNOWN" : severity));
        tiles.add(storageTile(health, storage));
        var callbacks = health.callbacks();
        Tone callbackTone = callbacks.failures() > 0 || callbacks.timeouts() > 0 || callbacks.quarantined() > 0 ? Tone.ERROR
            : callbacks.rejected() > 0 || callbacks.active() >= 2 ? Tone.WARN : Tone.OK;
        long callbackProblems = callbacks.failures() + callbacks.timeouts() + callbacks.quarantined() + callbacks.rejected();
        if (callbackProblems > 0) {
            tiles.add(new Tile("Callbacks", Units.count(callbackProblems, "problem"),
                "Failed " + callbacks.failures() + " · timed out " + callbacks.timeouts() + " · quarantined "
                    + callbacks.quarantined() + " · rejected " + callbacks.rejected(),
                callbackTone, null));
        }
        if (!health.running()) warnings.add(new Warning("Recorder is stopped.", Tone.ERROR));
        if (health.snapshotError() != null) warnings.add(new Warning("Snapshot: " + health.snapshotError(), Tone.WARN));
        if (health.captureError() != null) warnings.add(new Warning("Capture: " + health.captureError(), Tone.ERROR));
        if (health.storagePressure() != null) warnings.add(new Warning("Storage: " + health.storagePressure(), Tone.WARN));
        if (storage != null && storage.lowDisk()) warnings.add(new Warning("Low disk space: "
            + Units.bytes(storage.freeBytes()) + " free. Captures can fail to write.", Tone.WARN));
        String expired = storage == null ? null : RetentionText.expired(storage);
        if (expired != null) warnings.add(new Warning(expired, Tone.WARN));
        if (callbackTone != Tone.OK) warnings.add(new Warning("Callbacks need attention"
            + (callbacks.lastFailure() == null ? "." : ": " + callbacks.lastFailure()), callbackTone));
        String mixins = mixinAttention(health.mixinProblems());
        if (mixins != null) warnings.add(new Warning(mixins, health.mixinProblems().errors() > 0 ? Tone.ERROR : Tone.WARN));
        String step = health.captureBusy() ? health.captureProgress() : null;
        return new DashboardModel(tiles, warnings, profile, step,
            incidents, incidentsMessage, triggers, triggerTiming, feedback, captureAllowed && !health.captureBusy(),
            captureHint, openLastAvailable, profileStopAllowed && profile != null, profileCancelAllowed && profile != null,
            skipWaitAllowed,
            profileSaving && profile == null && (step == null || step.equals("queued")), trendsAllowed, profileForm, heap);
    }

    static String mixinAttention(HytaleMixins.ProblemCounts problems) {
        if (problems == null || problems.errors() + problems.warnings() == 0) return null;
        List<String> parts = new ArrayList<>(2);
        if (problems.errors() > 0) parts.add(problems.errors() + (problems.errors() == 1 ? " error" : " errors"));
        if (problems.warnings() > 0) parts.add(problems.warnings() + (problems.warnings() == 1 ? " warning" : " warnings"));
        return "Mixins: " + String.join(" and ", parts) + " since startup. Capture an incident to see them.";
    }

    static Tile storageTile(HyboxRuntime.Health health, RetentionUsage storage) {
        if (health.storagePressure() != null) {
            return new Tile("Storage", "Under pressure", health.storagePressure(), Tone.WARN, null);
        }
        if (storage == null) {
            return new Tile("Storage", "Unknown", "Could not read the bundle folder", Tone.WARN, null);
        }
        String limit = storage.policy().maxTotalBytes() > 0
            ? Units.bytes(storage.bytes()) + " of " + Units.bytes(storage.policy().maxTotalBytes())
            : Units.bytes(storage.bytes());
        String detail = limit + " in bundles"
            + (storage.freeBytes() < 0 ? "" : " · " + Units.bytes(storage.freeBytes()) + " free on disk");
        if (storage.lowDisk()) return new Tile("Storage", "Low disk space", detail, Tone.WARN, null);
        if (storage.nearSizeLimit()) {
            return new Tile("Storage", "Near limit", detail + " · oldest removed on next capture", Tone.WARN, null);
        }
        return new Tile("Storage", "Healthy", detail, Tone.OK, null);
    }

    static Tone severityTone(String severity) {
        if ("CRITICAL".equalsIgnoreCase(severity)) return Tone.ERROR;
        if ("INFO".equalsIgnoreCase(severity)) return Tone.OK;
        return Tone.WARN;
    }

    int captureStepIndex() {
        if (captureStep == null) return -1;
        return switch (captureStep) {
            case "queued" -> 0;
            case "collecting" -> 1;
            case "waiting for post-incident evidence" -> 2;
            case "dumping recording" -> 3;
            case "building bundle" -> 4;
            default -> -1;
        };
    }

    boolean canOpenLastIncident() {
        return openLastAvailable && captureStep == null && !incidents.isEmpty();
    }

    boolean profileIdle() {
        return profile == null && captureStep == null && !profileSaving;
    }

    boolean canStartProfile() {
        return profileIdle() && profileForm.allowed() && !profileForm.starting();
    }

    static String captureHint(Duration recordingMaxAge, int threadDumpCount, CapturePolicy capture,
                              boolean pluginConfigsRegistered) {
        Set<String> artifacts = capture.artifacts();
        boolean report = artifacts.contains(BundleArtifacts.REPORT);
        List<String> parts = new ArrayList<>(4);
        if (artifacts.contains(BundleArtifacts.JFR)) {
            parts.add(recordingMaxAge.isZero() ? "the rolling recording" : "the last " + Units.duration(recordingMaxAge) + " of recording");
        }
        if (artifacts.contains(BundleArtifacts.THREADS) && threadDumpCount > 0) parts.add("thread dumps");
        if (capture.includeServerLog() && capture.logTailLines() > 0
            && (report || artifacts.contains(BundleArtifacts.SERVER_LOG))) parts.add("server log");
        if (report && (capture.includeServerConfig() || capture.includeModConfigs()
            || capture.allowModConfigOptIn() && pluginConfigsRegistered)) parts.add("config");
        if (parts.isEmpty()) return "Saves an incident summary as an incident bundle.";
        String last = parts.removeLast();
        return "Saves " + (parts.isEmpty() ? last : String.join(", ", parts) + " and " + last) + " as an incident bundle.";
    }

    static String captureFeedback(CaptureResult result, Path incidentDir) {
        if (!result.captured()) return HyboxChatFormatter.captureMessage(result);
        Path bundle = incidentDir.resolve("incident-" + result.incidentId().value() + ".zip");
        return "Incident " + result.incidentId().value() + " saved · " + HyboxChatFormatter.outputPath(bundle, true)
            + "." + (result.retention() == null ? "" : " " + result.retention()) + " " + HyboxChatFormatter.SHARING_NOTE;
    }
}
