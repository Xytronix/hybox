package io.github.xytronix.hybox.hytale;

import static org.junit.jupiter.api.Assertions.*;

import io.github.xytronix.hybox.core.capture.CaptureResult;
import io.github.xytronix.hybox.core.incident.IncidentId;
import io.github.xytronix.hybox.core.jfr.JfrController;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.Test;

class ProfileSessionControllerTest {
    @Test
    void profileExposesItsActualRecordingStartAndClampedTotal() throws Exception {
        Instant now = Instant.parse("2026-10-22T12:00:00Z");
        var scheduler = Executors.newSingleThreadScheduledExecutor();
        var recorder = new JfrController(Duration.ofMinutes(1), 1024 * 1024, "hybox-dashboard-profile-time");
        var profiles = new ProfileSessionController(Clock.fixed(now, ZoneOffset.UTC), System.getLogger("dashboard-test"),
            scheduler, recorder, event -> CaptureResult.captured(new IncidentId("lead-up")),
            event -> CompletableFuture.completedFuture(CaptureResult.captured(new IncidentId("final"))), result -> {});
        try {
            recorder.start();
            assertNull(profiles.start(0, true, "Operator", result -> {}));
            assertEquals(now, profiles.startedAt());
            assertEquals(Duration.ofMinutes(1), profiles.totalDuration());
            assertEquals(60, profiles.remainingSeconds());
            assertEquals("Operator", profiles.requesterName());
            assertNotNull(profiles.start(1, true, "Other player", result -> {}));
            assertEquals("Operator", profiles.requesterName());
        } finally {
            profiles.close();
            scheduler.shutdownNow();
            recorder.close();
        }
    }

    @Test
    void completionRemainsBoundToItsRequesterWhenAnotherProfileStarts() throws Exception {
        var scheduler = Executors.newSingleThreadScheduledExecutor();
        var recorder = new JfrController(Duration.ofMinutes(1), 1024 * 1024, "hybox-dashboard-profile-completion");
        List<CompletableFuture<CaptureResult>> captures = new ArrayList<>();
        List<CaptureResult> firstRequester = new ArrayList<>();
        List<CaptureResult> secondRequester = new ArrayList<>();
        var profiles = new ProfileSessionController(Clock.systemUTC(), System.getLogger("dashboard-test"), scheduler,
            recorder, event -> CaptureResult.captured(new IncidentId("lead-up")), event -> {
                var capture = new CompletableFuture<CaptureResult>();
                captures.add(capture);
                return capture;
            }, result -> {});
        try {
            recorder.start();
            assertNull(profiles.start(1, true, "First", firstRequester::add));
            finish(profiles);
            assertFalse(profiles.active());
            assertNull(profiles.requesterName());
            assertTrue(firstRequester.isEmpty());
            assertNull(profiles.start(1, true, "Second", secondRequester::add));
            assertEquals("Second", profiles.requesterName());
            finish(profiles);

            CaptureResult second = CaptureResult.of(CaptureResult.Status.STORAGE_PRESSURE);
            captures.get(1).complete(second);
            CaptureResult first = CaptureResult.captured(new IncidentId("first"));
            captures.get(0).complete(first);
            assertEquals(List.of(first), firstRequester);
            assertEquals(List.of(second), secondRequester);
        } finally {
            profiles.close();
            scheduler.shutdownNow();
            recorder.close();
        }
    }

    @Test
    void closingAnActiveProfileNotifiesItsRequesterOnlyOnce() throws Exception {
        var scheduler = Executors.newSingleThreadScheduledExecutor();
        var recorder = new JfrController(Duration.ofMinutes(1), 1024 * 1024, "hybox-dashboard-profile-close");
        List<CaptureResult> completion = new ArrayList<>();
        var profiles = new ProfileSessionController(Clock.systemUTC(), System.getLogger("dashboard-test"), scheduler,
            recorder, event -> CaptureResult.captured(new IncidentId("lead-up")),
            event -> CompletableFuture.completedFuture(CaptureResult.captured(new IncidentId("final"))), result -> {});
        try {
            recorder.start();
            assertNull(profiles.start(1, true, null, completion::add));
            assertNull(profiles.requesterName());
            profiles.close();
            assertNull(profiles.requesterName());
            profiles.close();
            assertEquals(1, completion.size());
            assertEquals(CaptureResult.Status.STOPPED, completion.getFirst().status());
        } finally {
            scheduler.shutdownNow();
            recorder.close();
        }
    }

    @Test
    void saveNowCapturesImmediatelyAndTheScheduledFinishDoesNothing() throws Exception {
        var scheduler = Executors.newSingleThreadScheduledExecutor();
        var recorder = new JfrController(Duration.ofMinutes(1), 1024 * 1024, "hybox-dashboard-profile-save-now");
        List<CaptureResult> completion = new ArrayList<>();
        List<String> finalCaptures = new ArrayList<>();
        var profiles = new ProfileSessionController(Clock.systemUTC(), System.getLogger("dashboard-test"), scheduler,
            recorder, event -> CaptureResult.captured(new IncidentId("lead-up")), event -> {
                finalCaptures.add(event.attrs().get("reason"));
                return CompletableFuture.completedFuture(CaptureResult.captured(new IncidentId("final")));
            }, result -> {});
        try {
            recorder.start();
            assertNull(profiles.start(5, true, null, completion::add));
            assertNull(profiles.finishNow());
            assertFalse(profiles.active());
            assertEquals(List.of("profile session"), finalCaptures);
            assertEquals(1, completion.size());
            assertTrue(completion.getFirst().captured());
            finish(profiles);
            assertEquals(1, finalCaptures.size(), "The original timer must not capture again");
            assertEquals("No profile session is running.", profiles.finishNow());
        } finally {
            scheduler.shutdownNow();
            recorder.close();
        }
    }

    @Test
    void cancelSavesNothingNotifiesOnceAndAllowsANewProfile() throws Exception {
        var scheduler = Executors.newSingleThreadScheduledExecutor();
        var recorder = new JfrController(Duration.ofMinutes(1), 1024 * 1024, "hybox-dashboard-profile-cancel");
        List<CaptureResult> completion = new ArrayList<>();
        List<String> finalCaptures = new ArrayList<>();
        var profiles = new ProfileSessionController(Clock.systemUTC(), System.getLogger("dashboard-test"), scheduler,
            recorder, event -> CaptureResult.captured(new IncidentId("lead-up")), event -> {
                finalCaptures.add("final");
                return CompletableFuture.completedFuture(CaptureResult.captured(new IncidentId("final")));
            }, result -> {});
        try {
            recorder.start();
            assertNull(profiles.start(5, true, null, completion::add));
            assertNull(profiles.cancel());
            assertFalse(profiles.active());
            finish(profiles);
            assertTrue(finalCaptures.isEmpty(), "Cancel saves no bundle");
            assertEquals(1, completion.size());
            assertEquals(CaptureResult.Status.CANCELLED, completion.getFirst().status());
            assertEquals("No profile session is running.", profiles.cancel());
            assertNull(profiles.start(5, true, null, result -> {}));
        } finally {
            scheduler.shutdownNow();
            recorder.close();
        }
    }

    private static void finish(ProfileSessionController profiles) throws Exception {
        var generation = ProfileSessionController.class.getDeclaredField("generation");
        generation.setAccessible(true);
        var finish = ProfileSessionController.class.getDeclaredMethod("finish", long.class);
        finish.setAccessible(true);
        finish.invoke(profiles, generation.getLong(profiles));
    }
}
