package io.github.xytronix.hybox.hytale;

import com.hypixel.hytale.server.core.HytaleServer;
import com.hypixel.hytale.server.core.universe.world.events.RemoveWorldEvent;
import java.time.Clock;
import java.util.Map;
import java.util.Objects;
import io.github.xytronix.hybox.core.trigger.TriggerEvent;
import io.github.xytronix.hybox.core.trigger.TriggerKind;

final class HytaleWorldFailureListener {
    private final HyboxRuntime runtime;
    private final HyboxPlugin plugin;
    private final Clock clock;
    private final System.Logger logger;

    HytaleWorldFailureListener(HyboxRuntime runtime, HyboxPlugin plugin, Clock clock, System.Logger logger) {
        this.runtime = Objects.requireNonNull(runtime, "runtime");
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.logger = Objects.requireNonNull(logger, "logger");
    }

    void register() {
        plugin.getEventRegistry().registerGlobal(RemoveWorldEvent.class, this::onRemoveWorld);
    }

    private void onRemoveWorld(RemoveWorldEvent event) {
        HytaleServer server = HytaleServer.get();
        if (server != null && server.isShuttingDown()) {
            return;
        }
        if (event.getRemovalReason() != RemoveWorldEvent.RemovalReason.EXCEPTIONAL) {
            return;
        }
        String scope = event.getWorld().getName();
        if (scope == null || scope.isBlank()) {
            scope = "unknown-world";
        }
        TriggerEvent trigger = new TriggerEvent(
            TriggerKind.WORLD_FAILURE,
            scope,
            clock.instant(),
            failureAttrs(event.getWorld().getFailureException())
        );
        try {
            runtime.scheduleCapture(trigger);
        } catch (Exception e) {
            logger.log(System.Logger.Level.WARNING, "Failed to schedule world-failure capture for " + scope, e);
        }
    }

    static Map<String, String> failureAttrs(Throwable failure) {
        if (failure == null) {
            return Map.of();
        }
        String error = failure.getClass().getName();
        String message = failure.getMessage();
        if (message != null && !message.isBlank()) {
            error += ": " + message;
        }
        StackTraceElement[] frames = failure.getStackTrace();
        StringBuilder stack = new StringBuilder();
        for (int i = 0; i < frames.length && i < 5; i++) {
            if (i > 0) {
                stack.append('\n');
            }
            stack.append("at ").append(frames[i]);
        }
        if (stack.isEmpty()) {
            return Map.of("error", error);
        }
        return Map.of("error", error, "errorStack", stack.toString());
    }
}
