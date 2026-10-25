package io.github.xytronix.hybox.hytale;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.function.Function;
import io.github.xytronix.hybox.core.capture.CaptureResult;
import io.github.xytronix.hybox.core.jfr.JfrController;
import io.github.xytronix.hybox.core.trigger.TriggerEvent;
import io.github.xytronix.hybox.core.trigger.TriggerKind;

final class ProfileSessionController {
    private final Clock clock;
    private final System.Logger logger;
    private final ScheduledExecutorService scheduler;
    private final JfrController jfr;
    private final Function<TriggerEvent, CaptureResult> leadUpCapture;
    private final Function<TriggerEvent, CompletableFuture<CaptureResult>> finalCapture;
    private final Consumer<CaptureResult> outcome;
    private final AtomicBoolean profileActive = new AtomicBoolean(false);
    private volatile long profileEndsAtMs;
    private long profileStartMs;
    private volatile Instant recordingStartedAt;
    private volatile Duration totalDuration = Duration.ZERO;
    private volatile String requesterName;
    private Consumer<CaptureResult> completion;
    private boolean closed;
    private long generation;

    ProfileSessionController(Clock clock, System.Logger logger, ScheduledExecutorService scheduler,
                             JfrController jfr, Function<TriggerEvent, CaptureResult> leadUpCapture,
                             Function<TriggerEvent, CompletableFuture<CaptureResult>> finalCapture,
                             Consumer<CaptureResult> outcome) {
        this.clock = Objects.requireNonNull(clock, "clock");
        this.logger = Objects.requireNonNull(logger, "logger");
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
        this.jfr = Objects.requireNonNull(jfr, "jfr");
        this.leadUpCapture = Objects.requireNonNull(leadUpCapture, "leadUpCapture");
        this.finalCapture = Objects.requireNonNull(finalCapture, "finalCapture");
        this.outcome = Objects.requireNonNull(outcome, "outcome");
    }

    synchronized String start(int minutes, boolean keepBuffer, String requesterName, Consumer<CaptureResult> onComplete) {
        Objects.requireNonNull(onComplete, "onComplete");
        if (closed) {
            return "Hybox is not running.";
        }
        int clamped = Math.max(1, Math.min(30, minutes));
        if (!profileActive.compareAndSet(false, true)) {
            return "A profile session is already running (" + remainingSeconds() + "s remaining).";
        }
        this.requesterName = requesterName;
        recordingStartedAt = null;
        totalDuration = Duration.ZERO;
        profileEndsAtMs = 0;
        long session = ++generation;
        profileStartMs = clock.millis();
        if (!keepBuffer) {
            try {
                CaptureResult leadUp = leadUpCapture.apply(new TriggerEvent(TriggerKind.MANUAL, "server", clock.instant(),
                    Map.of("reason", "profile lead-up")));
                if (!leadUp.captured()) {
                    profileActive.set(false);
                    return "Profile lead-up capture: " + leadUp.status() + "; recording unchanged.";
                }
            } catch (Exception e) {
                logger.log(System.Logger.Level.WARNING, "Failed to capture the pre-profile lead-up.", e);
                profileActive.set(false);
                return "Failed to capture the pre-profile lead-up; recording unchanged.";
            }
        }
        try {
            if (keepBuffer) {
                jfr.applyConfiguration("profile");
            } else {
                jfr.restart("profile");
            }
        } catch (Exception failure) {
            profileActive.set(false);
            return "Failed to switch the recording to the profile preset: " + failure;
        }
        recordingStartedAt = clock.instant();
        totalDuration = Duration.ofMinutes(clamped);
        profileEndsAtMs = recordingStartedAt.toEpochMilli() + totalDuration.toMillis();
        completion = onComplete;
        try {
            scheduler.schedule(() -> finish(session), clamped, TimeUnit.MINUTES);
        } catch (RejectedExecutionException failure) {
            try {
                jfr.applyConfiguration(null);
                CaptureResult failed = CaptureResult.failed("Profile completion scheduling rejected");
                outcome.accept(failed);
                onComplete.accept(failed);
            } finally {
                profileActive.set(false);
                completion = null;
            }
            return "Failed to schedule profile completion: Hybox is stopping.";
        }
        return null;
    }

    boolean active() {
        return profileActive.get();
    }

    long remainingSeconds() {
        long remaining = profileEndsAtMs - clock.millis();
        return remaining <= 0 ? 0 : (remaining + 999) / 1000;
    }

    Instant startedAt() {
        return recordingStartedAt;
    }

    Duration totalDuration() {
        return totalDuration;
    }

    String requesterName() {
        return profileActive.get() ? requesterName : null;
    }

    private synchronized void finish(long session) {
        if (closed || !profileActive.get() || generation != session) {
            return;
        }
        Consumer<CaptureResult> completed = completion;
        completion = null;
        try {
            jfr.applyConfiguration(null);
            finalCapture.apply(new TriggerEvent(TriggerKind.MANUAL, "server", clock.instant(),
                Map.of("reason", "profile session", "profileStartMs", Long.toString(profileStartMs))))
                .whenComplete((result, failure) -> {
                    if (failure != null) {
                        outcome.accept(CaptureResult.failed(failure.getClass().getSimpleName()));
                    } else if (result.status() == CaptureResult.Status.BUSY
                        || result.status() == CaptureResult.Status.STOPPED) {
                        outcome.accept(CaptureResult.failed("Profile capture not admitted: " + result.status()));
                    } else if (!result.captured()) {
                        logger.log(System.Logger.Level.WARNING, "Profile-session capture: " + result.status());
                    }
                    completed.accept(failure == null ? result
                        : CaptureResult.failed(failure.getClass().getSimpleName()));
                });
        } catch (Exception failure) {
            CaptureResult failed = CaptureResult.failed(failure.getClass().getSimpleName());
            outcome.accept(failed);
            completed.accept(failed);
            logger.log(System.Logger.Level.WARNING, "Profile-session completion failed.", failure);
        } finally {
            profileActive.set(false);
        }
    }

    synchronized String finishNow() {
        if (closed || !profileActive.get() || completion == null) return "No profile session is running.";
        finish(generation);
        return null;
    }

    synchronized String cancel() {
        if (closed || !profileActive.get() || completion == null) return "No profile session is running.";
        Consumer<CaptureResult> cancelled = completion;
        completion = null;
        generation++;
        try {
            jfr.applyConfiguration(null);
        } catch (Exception failure) {
            logger.log(System.Logger.Level.WARNING, "Failed to restore the normal recording preset after cancelling a profile.", failure);
        } finally {
            profileActive.set(false);
        }
        cancelled.accept(CaptureResult.of(CaptureResult.Status.CANCELLED));
        return null;
    }

    synchronized void close() {
        closed = true;
        generation++;
        if (profileActive.getAndSet(false)) completion.accept(CaptureResult.of(CaptureResult.Status.STOPPED));
        completion = null;
    }
}
