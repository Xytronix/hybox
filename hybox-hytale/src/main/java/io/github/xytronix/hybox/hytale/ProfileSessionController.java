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
import java.util.function.Consumer;
import java.util.function.Function;
import io.github.xytronix.hybox.core.capture.CaptureResult;
import io.github.xytronix.hybox.core.jfr.JfrController;
import io.github.xytronix.hybox.core.jfr.ProfileMode;
import io.github.xytronix.hybox.core.trigger.TriggerEngine;
import io.github.xytronix.hybox.core.trigger.TriggerEvent;
import io.github.xytronix.hybox.core.trigger.TriggerKind;

final class ProfileSessionController {
    record Owner(ProfileMode mode, String requesterName) {}

    private final Clock clock;
    private final System.Logger logger;
    private final ScheduledExecutorService scheduler;
    private final JfrController jfr;
    private final Function<TriggerEvent, CaptureResult> leadUpCapture;
    private final Function<TriggerEvent, CompletableFuture<CaptureResult>> finalCapture;
    private final Consumer<CaptureResult> outcome;
    private volatile Session running;
    private volatile Session finishing;
    private boolean closed;
    private long generation;

    private static final class Session {
        final long id;
        final ProfileMode mode;
        final String requesterName;
        final long startMs;
        volatile Instant startedAt;
        volatile Duration total = Duration.ZERO;
        volatile long endsAtMs;
        Consumer<CaptureResult> completion;

        Session(long id, ProfileMode mode, String requesterName, long startMs) {
            this.id = id;
            this.mode = mode;
            this.requesterName = requesterName;
            this.startMs = startMs;
        }

        Owner owner() {
            return new Owner(mode, requesterName);
        }
    }

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

    static int clampMinutes(int minutes) {
        return Math.max(1, Math.min(30, minutes));
    }

    synchronized String start(ProfileMode mode, int minutes, boolean keepBuffer, String requesterName,
                              Consumer<CaptureResult> onComplete) {
        Objects.requireNonNull(mode, "mode");
        Objects.requireNonNull(onComplete, "onComplete");
        if (closed) {
            return "Hybox is not running.";
        }
        int clamped = clampMinutes(minutes);
        if (running != null) {
            return "A profile session is already running (" + remainingSeconds() + "s remaining).";
        }
        Session session = new Session(++generation, mode, requesterName, clock.millis());
        running = session;
        if (!keepBuffer) {
            try {
                CaptureResult leadUp = leadUpCapture.apply(new TriggerEvent(TriggerKind.MANUAL, "server", clock.instant(),
                    Map.of("reason", TriggerEngine.PROFILE_LEAD_UP)));
                if (!leadUp.captured()) {
                    running = null;
                    return "Profile lead-up capture: " + leadUp.status() + ". The recording is unchanged.";
                }
            } catch (Exception e) {
                logger.log(System.Logger.Level.WARNING, "Failed to capture the pre-profile lead-up.", e);
                running = null;
                return "Failed to capture the pre-profile lead-up. The recording is unchanged.";
            }
        }
        try {
            if (keepBuffer) {
                jfr.applyConfiguration(mode);
            } else {
                jfr.restart(mode);
            }
        } catch (Exception failure) {
            running = null;
            return "Failed to switch the recording to the " + mode.id() + " profile settings: " + failure;
        }
        session.startedAt = clock.instant();
        session.total = Duration.ofMinutes(clamped);
        session.endsAtMs = session.startedAt.toEpochMilli() + session.total.toMillis();
        session.completion = onComplete;
        try {
            scheduler.schedule(() -> finish(session.id), clamped, TimeUnit.MINUTES);
        } catch (RejectedExecutionException failure) {
            running = null;
            session.completion = null;
            restoreNormalSettings();
            CaptureResult failed = CaptureResult.failed("Profile completion scheduling rejected");
            outcome.accept(failed);
            onComplete.accept(failed);
            return "Failed to schedule profile completion: Hybox is stopping.";
        }
        return null;
    }

    boolean active() {
        return running != null;
    }

    boolean finishing() {
        return finishing != null;
    }

    ProfileMode mode() {
        Session session = running;
        return session == null ? null : session.mode;
    }

    long remainingSeconds() {
        Session session = running;
        long remaining = session == null ? 0 : session.endsAtMs - clock.millis();
        return remaining <= 0 ? 0 : (remaining + 999) / 1000;
    }

    Instant startedAt() {
        Session session = running;
        return session == null ? null : session.startedAt;
    }

    Duration totalDuration() {
        Session session = running;
        return session == null ? Duration.ZERO : session.total;
    }

    String requesterName() {
        Session session = running;
        return session == null ? null : session.requesterName;
    }

    Owner finishingOwner() {
        Session session = finishing;
        return session == null ? null : session.owner();
    }

    private synchronized void finish(long id) {
        Session session = running;
        if (closed || session == null || session.id != id || session.completion == null) {
            return;
        }
        Consumer<CaptureResult> completed = session.completion;
        session.completion = null;
        running = null;
        finishing = session;
        try {
            finalCapture.apply(new TriggerEvent(TriggerKind.MANUAL, "server", clock.instant(),
                    Map.of("reason", "profile session", "profileStartMs", Long.toString(session.startMs),
                        ProfileMode.CONTEXT_KEY, session.mode.id())))
                .whenComplete((result, failure) -> settle(session, completed, result, failure));
        } catch (Exception failure) {
            logger.log(System.Logger.Level.WARNING, "Profile-session completion failed.", failure);
            settle(session, completed, null, failure);
        }
    }

    private void settle(Session session, Consumer<CaptureResult> completed, CaptureResult result, Throwable failure) {
        synchronized (this) {
            if (finishing == session) {
                finishing = null;
            }
            if (running == null && !closed) {
                restoreNormalSettings();
            }
        }
        CaptureResult settled = failure != null ? CaptureResult.failed(failure.getClass().getSimpleName()) : result;
        if (failure != null) {
            outcome.accept(settled);
        } else if (result.status() == CaptureResult.Status.BUSY || result.status() == CaptureResult.Status.STOPPED) {
            outcome.accept(CaptureResult.failed("Profile capture not admitted: " + result.status()));
        } else if (!result.captured() && result.status() != CaptureResult.Status.CANCELLED) {
            logger.log(System.Logger.Level.WARNING, "Profile-session capture: " + result.status());
        }
        completed.accept(settled);
    }

    synchronized String finishNow() {
        Session session = running;
        if (closed || session == null || session.completion == null) return "No profile session is running.";
        finish(session.id);
        return null;
    }

    synchronized Owner cancelRunning() {
        Session session = running;
        if (closed || session == null || session.completion == null) return null;
        Consumer<CaptureResult> cancelled = session.completion;
        session.completion = null;
        generation++;
        running = null;
        restoreNormalSettings();
        cancelled.accept(CaptureResult.of(CaptureResult.Status.CANCELLED));
        return session.owner();
    }

    private void restoreNormalSettings() {
        try {
            jfr.applyConfiguration(null);
        } catch (Exception failure) {
            logger.log(System.Logger.Level.WARNING, "Failed to restore the normal recording settings after a profile.", failure);
        }
    }

    synchronized void close() {
        closed = true;
        generation++;
        Session session = running;
        running = null;
        finishing = null;
        if (session != null && session.completion != null) {
            Consumer<CaptureResult> stopped = session.completion;
            session.completion = null;
            stopped.accept(CaptureResult.of(CaptureResult.Status.STOPPED));
        }
    }
}
