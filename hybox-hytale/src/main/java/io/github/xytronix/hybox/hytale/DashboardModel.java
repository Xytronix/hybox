package io.github.xytronix.hybox.hytale;

import io.github.xytronix.hybox.core.bundle.BundleArtifacts;
import io.github.xytronix.hybox.core.capture.CapturePolicy;
import io.github.xytronix.hybox.core.capture.CaptureResult;
import io.github.xytronix.hybox.core.capture.HeapHistogram;
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
        OK("#80c991"), WARN("#efbd62"), ERROR("#ef8181");

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
        return from(runtime.health(), now, profile, incidents, incidentsMessage,
            HyboxChatFormatter.triggers(policy, HyboxDashboardPage.armedTriggers(runtime)),
            "Cooldown " + policy.cooldown() + " · debounce " + policy.debounce(), feedback, captureAllowed,
            captureHint(runtime.config().jfrMaxAge(), runtime.config().threadDumpCount(), runtime.config().capturePolicy(),
                !HyboxApi.registeredConfigPaths().isEmpty()), openLastAvailable,
            profileStopAllowed, profileCancelAllowed,
            captureAllowed && runtime.recoveryWaitActive(),
            profileSaving || runtime.profileCapturePending(), trendsAllowed, profileForm, heap);
    }

    static DashboardModel from(HyboxRuntime.Health health, Instant now, Profile profile,
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
        Tone captureTone = health.captureError() != null ? Tone.ERROR : Tone.OK;
        tiles.add(new Tile("Capture", health.captureBusy() ? "In progress" : health.captureError() != null ? "Failed" : "Ready",
            health.captureBusy() ? health.captureProgress() : "Ready · click Capture incident", captureTone, null));
        tiles.add(new Tile("Bundles", Long.toString(health.bundles()),
            "Busy " + health.busy() + " · dropped " + health.dropped() + " · rejected " + health.rejected(),
            health.dropped() > 0 || health.rejected() > 0 ? Tone.WARN : Tone.OK, null));
        HyboxIncidents.Incident latest = incidents.isEmpty() ? null : incidents.getFirst();
        boolean currentInHistory = latest != null && (health.lastIncident() == null || health.lastIncident().equals(latest.id()));
        Instant lastAt = currentInHistory ? latest.createdAt() : health.lastSuccess();
        String lastId = currentInHistory ? latest.id() : health.lastIncident();
        String severity = currentInHistory ? latest.severity() : null;
        tiles.add(new Tile("Last incident", lastAt == null ? lastId == null ? "None yet" : "Unknown age"
                : HyboxChatFormatter.age(lastAt, now),
            currentInHistory ? latest.headline() : lastId == null ? "Waiting for the first incident" : lastId,
            lastId == null ? Tone.OK : severityTone(severity), lastId == null ? null : severity == null ? "UNKNOWN" : severity));
        tiles.add(new Tile("Storage", !health.storageChecked() ? "Not checked" : health.storagePressure() == null ? "Healthy" : "Under pressure",
            health.storagePressure() == null ? health.storageChecked() ? "Within storage budget" : "Checked on the next capture"
                : health.storagePressure(),
            !health.storageChecked() || health.storagePressure() != null ? Tone.WARN : Tone.OK, null));
        var callbacks = health.callbacks();
        Tone callbackTone = callbacks.failures() > 0 || callbacks.timeouts() > 0 || callbacks.quarantined() > 0 ? Tone.ERROR
            : callbacks.rejected() > 0 || callbacks.active() >= 2 ? Tone.WARN : Tone.OK;
        long callbackProblems = callbacks.failures() + callbacks.timeouts() + callbacks.quarantined() + callbacks.rejected();
        tiles.add(new Tile("Callbacks", callbackProblems == 0 ? callbacks.calls() + " delivered"
                : callbackProblems + (callbackProblems == 1 ? " problem" : " problems"),
            "Failed " + callbacks.failures() + " · timed out " + callbacks.timeouts() + " · quarantined "
                + callbacks.quarantined() + " · rejected " + callbacks.rejected() + " · " + callbacks.active() + "/2 running",
            callbackTone, null));
        if (!health.running()) warnings.add(new Warning("Recorder is stopped.", Tone.ERROR));
        if (health.snapshotError() != null) warnings.add(new Warning("Snapshot: " + health.snapshotError(), Tone.WARN));
        if (health.captureError() != null) warnings.add(new Warning("Capture: " + health.captureError(), Tone.ERROR));
        if (health.storagePressure() != null) warnings.add(new Warning("Storage: " + health.storagePressure(), Tone.WARN));
        if (callbackTone != Tone.OK) warnings.add(new Warning("Callbacks need attention"
            + (callbacks.lastFailure() == null ? "." : ": " + callbacks.lastFailure()), callbackTone));
        String step = health.captureBusy() ? health.captureProgress() : null;
        return new DashboardModel(tiles, warnings, profile, step,
            incidents, incidentsMessage, triggers, triggerTiming, feedback, captureAllowed && !health.captureBusy(),
            captureHint, openLastAvailable, profileStopAllowed && profile != null, profileCancelAllowed && profile != null,
            skipWaitAllowed,
            profileSaving && profile == null && (step == null || step.equals("queued")), trendsAllowed, profileForm, heap);
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
            long seconds = recordingMaxAge.toSeconds();
            String window = seconds % 86_400 == 0 ? units(seconds / 86_400, "day")
                : seconds % 3_600 == 0 ? units(seconds / 3_600, "hour")
                : seconds % 60 == 0 ? units(seconds / 60, "minute") : units(seconds, "second");
            parts.add(recordingMaxAge.isZero() ? "the rolling recording" : "the last " + window + " of recording");
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
        return "Incident " + result.incidentId().value() + " saved · " + HyboxChatFormatter.outputPath(bundle, true);
    }

    private static String units(long count, String unit) {
        return count + " " + unit + (count == 1 ? "" : "s");
    }
}
