package io.github.xytronix.hybox.hytale;

import com.hypixel.hytale.server.core.entity.entities.player.hud.CustomUIHud;
import com.hypixel.hytale.server.core.ui.builder.UICommandBuilder;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import java.time.Duration;
import java.time.Instant;
import java.util.function.Consumer;

final class HyboxProgressHud extends CustomUIHud {
    static final String KEY = "hybox.progress";
    static final String DOCUMENT = "Hud/HyboxProgress.ui";
    private static final int STEPS = 40;

    private final HyboxRuntime runtime;
    private final HyboxProgressHuds.Kind kind;
    private final Consumer<HyboxProgressHud> removed;
    private State shown;

    HyboxProgressHud(PlayerRef requester, HyboxRuntime runtime, HyboxProgressHuds.Kind kind,
                     Consumer<HyboxProgressHud> removed) {
        super(requester, KEY);
        this.runtime = runtime;
        this.kind = kind;
        this.removed = removed;
    }

    @Override
    protected void build(UICommandBuilder commands) {
        State state = state();
        commands.append(DOCUMENT);
        render(commands, state, null);
        shown = state;
    }

    void refresh() {
        State state = state();
        if (state.equals(shown)) return;
        UICommandBuilder commands = new UICommandBuilder();
        render(commands, state, shown);
        shown = state;
        update(false, commands);
    }

    private State state() {
        boolean profile = kind == HyboxProgressHuds.Kind.PROFILE && runtime.profileActive();
        if (!profile) {
            String step = runtime.health().captureProgress();
            return new State(false, null, 0, 0, step == null ? "" : step);
        }
        Instant started = runtime.profileStartedAt();
        double fraction = profileFraction(started, runtime.profileTotalDuration(), Instant.now());
        return new State(true, started, runtime.profileRemainingSeconds(), step(fraction), "");
    }

    record State(boolean profile, Instant startedAt, long remainingSeconds, int ring, String step) {
        @Override
        public boolean equals(Object other) {
            return other instanceof State that && profile == that.profile && ring == that.ring
                && java.util.Objects.equals(startedAt, that.startedAt) && step.equals(that.step);
        }

        @Override
        public int hashCode() {
            return java.util.Objects.hash(profile, startedAt, ring, step);
        }
    }

    @Override
    protected void onRemove() {
        removed.accept(this);
    }

    static void render(UICommandBuilder commands, State state, State previous) {
        boolean switched = previous == null || previous.profile() != state.profile();
        if (switched) {
            commands.set("#Profile.Visible", state.profile());
            commands.set("#Capture.Visible", !state.profile());
        }
        if (state.profile()) {
            if (previous == null || previous.ring() != state.ring() || switched) {
                commands.set("#Ring.Value", state.ring() / (float) STEPS);
            }
            if (switched || !java.util.Objects.equals(previous.startedAt(), state.startedAt())) {
                commands.clear("#TimerHost");
                commands.appendInline("#TimerHost", "TimerLabel { Seconds: " + Math.max(0, state.remainingSeconds())
                    + "; Style: (FontSize: 24, RenderBold: true, TextColor: #ffffff); }");
            }
        } else if (switched || !previous.step().equals(state.step())) {
            commands.set("#Step.Text", state.step());
        }
    }

    static int step(double fraction) {
        return (int) Math.round(STEPS * Math.max(0, Math.min(1, fraction)));
    }

    static double profileFraction(Instant startedAt, Duration total, Instant now) {
        if (startedAt == null) return 0;
        long totalMillis = total == null ? 0 : total.toMillis();
        if (totalMillis <= 0) return 1;
        double elapsed = Duration.between(startedAt, now).toMillis();
        return Math.max(0, Math.min(1, elapsed / totalMillis));
    }
}
