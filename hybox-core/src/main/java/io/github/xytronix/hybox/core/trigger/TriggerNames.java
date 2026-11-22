package io.github.xytronix.hybox.core.trigger;

import java.util.Locale;

public final class TriggerNames {
    private TriggerNames() {
    }

    public static String label(String trigger) {
        if (trigger == null || trigger.isBlank()) {
            return "Unknown";
        }
        return switch (trigger) {
            case "MANUAL" -> "Manual";
            case "HEARTBEAT_STALL" -> "Freeze";
            case "WORLD_FAILURE" -> "World failure";
            case "TICK_DEGRADED" -> "Slow ticks";
            case "DEADLOCK" -> "Deadlock";
            case "HEAP_PRESSURE" -> "Heap pressure";
            case "GC_PRESSURE" -> "GC pressure";
            case "CPU_SATURATION" -> "CPU saturation";
            case "NET_SATURATION" -> "Network saturation";
            case "PLAYER_DROP" -> "Player drop";
            case "LOG_ERROR" -> "Log error";
            case "UNCLEAN_SHUTDOWN" -> "Unclean shutdown";
            default -> {
                String spaced = trigger.strip().replace('_', ' ').toLowerCase(Locale.ROOT);
                yield Character.toUpperCase(spaced.charAt(0)) + spaced.substring(1);
            }
        };
    }
}
