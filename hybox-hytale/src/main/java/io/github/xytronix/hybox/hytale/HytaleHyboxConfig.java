package io.github.xytronix.hybox.hytale;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.function.Function;
import java.util.function.ObjLongConsumer;
import java.util.function.ToLongFunction;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.codec.ExtraInfo;
import com.hypixel.hytale.codec.KeyedCodec;
import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.hypixel.hytale.server.core.util.Config;
import com.hypixel.hytale.server.core.util.BsonUtil;
import org.bson.BsonDocument;
import org.bson.BsonValue;

import io.github.xytronix.hybox.core.capture.CapturePolicy;
import io.github.xytronix.hybox.core.config.HyboxConfig;
import io.github.xytronix.hybox.core.env.TextRedactor;
import io.github.xytronix.hybox.core.notify.discord.DiscordWebhookConfig;
import io.github.xytronix.hybox.core.retention.RetentionPolicy;
import io.github.xytronix.hybox.core.trigger.DetectorPolicy;
import io.github.xytronix.hybox.core.trigger.ModulePolicy;
import io.github.xytronix.hybox.core.trigger.TriggerKind;
import io.github.xytronix.hybox.core.trigger.TriggerPolicy;

/**
 * Loads {@link HyboxConfig} via Hytale's built-in {@link Config} system.
 *
 * <p>Stored at {@code <pluginDataDir>/hybox.json}.
 */
final class HytaleHyboxConfig {
    private static final String FILE_NAME = "hybox";

    private static final Duration DEFAULT_JFR_MAX_AGE = Duration.ofMinutes(15);
    private static final long DEFAULT_JFR_MAX_SIZE_BYTES = 256L * 1024L * 1024L;
    private static final String DEFAULT_JFR_RECORDING_NAME = "hybox";
    private static final Duration DEFAULT_JFR_POST_INCIDENT_MAX_WAIT = Duration.ofMinutes(2);
    private static final int DEFAULT_JFR_THREAD_DUMP_COUNT = 3;
    private static final Duration DEFAULT_JFR_THREAD_DUMP_INTERVAL = Duration.ofSeconds(10);
    private static final Duration DEFAULT_JFR_SNAPSHOT_INTERVAL = Duration.ofMinutes(10);
    private static final Duration DEFAULT_JFR_SAMPLE_INTERVAL = Duration.ofSeconds(10);
    private static final String DEFAULT_JFR_CONFIGURATION = "default";
    private static final boolean DEFAULT_JFR_OLD_OBJECT_SAMPLING = true;

    private static final boolean DEFAULT_METRICS_ENABLED = false;
    private static final int DEFAULT_METRICS_RETENTION_DAYS = 7;
    private static final boolean DEFAULT_METRICS_CPU = true;
    private static final boolean DEFAULT_METRICS_ALLOCATION = false;
    private static final boolean DEFAULT_PROMETHEUS_ENABLED = false;
    private static final int DEFAULT_PROMETHEUS_PORT = 9099;
    private static final String DEFAULT_PROMETHEUS_BIND = "127.0.0.1";

    private static final Duration DEFAULT_TRIGGER_COOLDOWN = Duration.ofSeconds(30);
    private static final Duration DEFAULT_TRIGGER_DEBOUNCE = Duration.ofSeconds(2);
    private static final long DEFAULT_STALL_DEGRADED_MS = 2_000L;
    private static final long DEFAULT_STALL_CRITICAL_MS = 10_000L;
    private static final long DEFAULT_TICK_AVG_DEGRADED_MS = 100L;
    private static final long DEFAULT_TICK_AVG_CRITICAL_MS = 250L;
    private static final DetectorPolicy DEFAULT_DETECTORS = DetectorPolicy.defaults();

    private static final int DEFAULT_RETENTION_MAX_COUNT = 25;
    private static final long DEFAULT_RETENTION_MAX_TOTAL_BYTES = 1024L * 1024L * 1024L;
    private static final Duration DEFAULT_RETENTION_MAX_AGE = Duration.ofDays(7);

    private static final String DEFAULT_DISCORD_WEBHOOK_URL = "";
    private static final Duration DEFAULT_DISCORD_COOLDOWN = Duration.ofMinutes(1);
    private static final Duration DEFAULT_DISCORD_REQUEST_TIMEOUT = Duration.ofSeconds(10);
    private static final String DEFAULT_DISCORD_USERNAME = "Hybox";

    private static final boolean DEFAULT_CAPTURE_ENABLED = true;
    private static final boolean DEFAULT_CAPTURE_ALLOW_PLUGIN_EXTRAS = true;
    private static final int DEFAULT_CAPTURE_LOG_TAIL_LINES = 500;
    private static final boolean DEFAULT_CAPTURE_HEAP_HISTOGRAM = false;
    private static final boolean DEFAULT_CAPTURE_INCLUDE_SERVER_LOG = false;
    private static final boolean DEFAULT_CAPTURE_INCLUDE_SERVER_CONFIG = false;
    private static final boolean DEFAULT_CAPTURE_INCLUDE_MOD_CONFIGS = false;
    private static final boolean DEFAULT_CAPTURE_ALLOW_MOD_CONFIG_OPT_IN = true;
    private static final List<String> DEFAULT_CAPTURE_REDACT_PATTERNS = TextRedactor.DEFAULT_PATTERNS;
    private static final List<String> FRAMEWORK_PREFIXES = List.of("com.hypixel.hytale.");
    private static final List<String> DEFAULT_CAPTURE_ARTIFACTS =
            List.of("jfr", "report", "env", "threads", "serverLog", "plugins", "worlds", "heartbeats", "server", "metrics");
    private static final Set<String> LEGACY_DEFAULT_CAPTURE_ARTIFACTS =
            Set.of("jfr", "report", "env", "threads", "serverLog", "plugins", "worlds", "heartbeats", "server");

    private HytaleHyboxConfig() {
    }

    static Path path(Path dataDir) {
        Objects.requireNonNull(dataDir, "dataDir");
        return dataDir.resolve(FILE_NAME + ".json");
    }

    static HyboxConfig loadOrCreate(Path dataDir, System.Logger logger) {
        Objects.requireNonNull(dataDir, "dataDir");
        Objects.requireNonNull(logger, "logger");
        Path configPath = path(dataDir);
        boolean existed = Files.exists(configPath, java.nio.file.LinkOption.NOFOLLOW_LINKS);
        Config<FileConfig> configFile = new Config<>(dataDir, FILE_NAME, FileConfig.CODEC);
        FileConfig raw;
        try {
            raw = configFile.load().join();
        } catch (Exception e) {
            throw new IllegalStateException("Failed to load Hybox config from " + configPath
                + ". The existing configuration was not replaced.", e);
        }
        HyboxConfig loaded = Objects.requireNonNull(raw, "Loaded configuration").toCoreConfig(logger);
        if (!existed) {
            try {
                configFile.save().join();
            } catch (Exception e) {
                throw new IllegalStateException("Failed to write default Hybox config to " + configPath, e);
            }
            logger.log(System.Logger.Level.INFO, "Wrote default config: " + configPath);
        }
        return loaded;
    }

    enum SettingKind { TOGGLE, NUMBER }

    enum SettingUnit {
        BOOLEAN("", 1), COUNT("", 1), PERCENT("%", 1), MILLISECONDS("ms", 1),
        SECONDS("seconds", 1), MINUTES("minutes", 60), DAYS("days", 86400), DAY_COUNT("days", 1),
        MEBIBYTES("MiB", 1024 * 1024), MBPS("Mbit/s", 1);

        private final String label;
        private final long scale;

        SettingUnit(String label, long scale) {
            this.label = label;
            this.scale = scale;
        }

        long display(long stored) {
            return stored / scale;
        }

        long stored(long display) {
            return Math.multiplyExact(display, scale);
        }

        String format(long display) {
            return switch (this) {
                case BOOLEAN, COUNT -> Long.toString(display);
                case PERCENT -> display + "%";
                case MBPS -> display + " Mbit/s";
                case DAYS, DAY_COUNT -> amount(display, "day");
                case MILLISECONDS -> time(display, "ms");
                case SECONDS -> time(display * 1000, "second");
                case MINUTES -> time(display * 60_000, "minute");
                case MEBIBYTES -> display != 0 && display % (1024 * 1024) == 0 ? display / (1024 * 1024) + " TiB"
                    : display != 0 && display % 1024 == 0 ? display / 1024 + " GiB" : display + " MiB";
            };
        }

        private static String time(long millis, String zeroUnit) {
            if (millis == 0) return zeroUnit.equals("ms") ? "0 ms" : "0 " + zeroUnit + "s";
            if (millis % 86_400_000 == 0) return amount(millis / 86_400_000, "day");
            if (millis % 3_600_000 == 0) return amount(millis / 3_600_000, "hour");
            if (millis % 60_000 == 0) return amount(millis / 60_000, "minute");
            if (millis % 1000 == 0) return amount(millis / 1000, "second");
            if (millis >= 1000) return decimal(millis, 1000) + " seconds";
            return millis + " ms";
        }

        String formatStored(Object stored) {
            if (stored instanceof Duration duration) return time(duration.toMillis(), "second");
            long value = (long) stored;
            return switch (this) {
                case MILLISECONDS -> time(value, "ms");
                case MEBIBYTES -> bytes(value);
                default -> format(value);
            };
        }

        private static String bytes(long value) {
            String[] names = {"TiB", "GiB", "MiB", "KiB"};
            long[] sizes = {1L << 40, 1L << 30, 1L << 20, 1L << 10};
            for (int i = 0; i < sizes.length; i++) {
                if (value >= sizes[i] && value % (sizes[i] / 4) == 0) return decimal(value, sizes[i]) + " " + names[i];
            }
            return value + " bytes";
        }

        private static String decimal(long value, long divisor) {
            return new java.math.BigDecimal(value).divide(java.math.BigDecimal.valueOf(divisor))
                .stripTrailingZeros().toPlainString();
        }

        private static String amount(long value, String unit) {
            return value + " " + unit + (value == 1 ? "" : "s");
        }
    }

    record SettingField(String id, String eventKey, String configKey, SettingKind kind,
                        SettingUnit unit, long min, long max, String group, String label, boolean restartRequired,
                        Function<FileConfig, Object> getter, BiConsumer<FileConfig, Object> setter,
                        Function<FileConfig, Object> stored) {
        String selector() {
            return "#" + id + (kind == SettingKind.TOGGLE ? " #CheckBox.Value" : " #Input.Value");
        }

        String name() {
            return group.isEmpty() ? label : group + ": " + label;
        }

        String range() {
            return "between " + unit.format(min) + " and " + unit.format(max);
        }

        Object read(FileConfig raw) {
            return clamp(getter.apply(raw));
        }

        Object clamp(Object value) {
            return kind == SettingKind.NUMBER ? Math.clamp((long) value, min, max) : value;
        }

        Object parse(Object submitted, Object current, List<String> problems) {
            if (submitted == null) return current;
            if (kind == SettingKind.TOGGLE) return submitted;
            double number = ((Number) submitted).doubleValue();
            if (!Double.isFinite(number) || number != Math.rint(number)
                || number < -0x1p63 || number >= 0x1p63) {
                problems.add(name() + " must be a whole number " + range() + ".");
                return current;
            }
            return (long) number;
        }

        void validate(Object value, List<String> problems) {
            if (kind == SettingKind.NUMBER && ((long) value < min || (long) value > max)) {
                problems.add(name() + " must be " + range() + ".");
            }
        }
    }

    static final List<SettingField> SETTINGS_FIELDS = List.of(
        toggle("HeartbeatStall", "Trigger.Modules.HeartbeatStall", "Heartbeat stall", false,
            c -> c.trigger.modules.heartbeatStall, (c, v) -> c.trigger.modules.heartbeatStall = v),
        number("StallDegraded", "@StallDegradedMs", "Trigger.StallDegradedMs", SettingUnit.MILLISECONDS,
            500, 600_000, "Heartbeat stall", "Degraded", false,
            c -> c.trigger.stallDegradedMs, (c, v) -> c.trigger.stallDegradedMs = v),
        number("StallCritical", "@StallCriticalMs", "Trigger.StallCriticalMs", SettingUnit.MILLISECONDS,
            500, 600_000, "Heartbeat stall", "Critical", false,
            c -> c.trigger.stallCriticalMs, (c, v) -> c.trigger.stallCriticalMs = v),
        toggle("TickDegraded", "Trigger.Modules.TickDegraded", "Slow ticks", false,
            c -> c.trigger.modules.tickDegraded, (c, v) -> c.trigger.modules.tickDegraded = v),
        number("TickAvgDegraded", "@TickAvgDegradedMs", "Trigger.TickAvgDegradedMs", SettingUnit.MILLISECONDS,
            10, 10_000, "Slow ticks", "Degraded", false,
            c -> c.trigger.tickAvgDegradedMs, (c, v) -> c.trigger.tickAvgDegradedMs = v),
        number("TickAvgCritical", "@TickAvgCriticalMs", "Trigger.TickAvgCriticalMs", SettingUnit.MILLISECONDS,
            10, 10_000, "Slow ticks", "Critical", false,
            c -> c.trigger.tickAvgCriticalMs, (c, v) -> c.trigger.tickAvgCriticalMs = v),
        toggle("Deadlock", "Trigger.Modules.Deadlock", "Deadlock", false,
            c -> c.trigger.modules.deadlock, (c, v) -> c.trigger.modules.deadlock = v),
        toggle("HeapPressure", "Trigger.Modules.HeapPressure", "Heap pressure", false,
            c -> c.trigger.modules.heapPressure, (c, v) -> c.trigger.modules.heapPressure = v),
        number("HeapPressurePct", "@HeapPressurePct", "Trigger.HeapPressurePct", SettingUnit.PERCENT,
            50, 100, "Heap pressure", "Heap used", false,
            c -> c.trigger.heapPressurePct, (c, v) -> c.trigger.heapPressurePct = (int) v),
        duration("HeapPressureSustain", "@HeapPressureSustain", "Trigger.HeapPressureSustain", SettingUnit.SECONDS,
            0, 3600, "Heap pressure", "Sustain", false,
            c -> c.trigger.heapPressureSustain, (c, v) -> c.trigger.heapPressureSustain = v),
        toggle("GcPressure", "Trigger.Modules.GcPressure", "GC pressure", false,
            c -> c.trigger.modules.gcPressure, (c, v) -> c.trigger.modules.gcPressure = v),
        number("GcPressurePct", "@GcPressurePct", "Trigger.GcPressurePct", SettingUnit.PERCENT,
            1, 90, "GC pressure", "GC time", false,
            c -> c.trigger.gcPressurePct, (c, v) -> c.trigger.gcPressurePct = (int) v),
        duration("GcPressureWindow", "@GcPressureWindow", "Trigger.GcPressureWindow", SettingUnit.SECONDS,
            10, 3600, "GC pressure", "Window", false,
            c -> c.trigger.gcPressureWindow, (c, v) -> c.trigger.gcPressureWindow = v),
        toggle("CpuSaturation", "Trigger.Modules.CpuSaturation", "CPU saturation", false,
            c -> c.trigger.modules.cpuSaturation, (c, v) -> c.trigger.modules.cpuSaturation = v),
        number("CpuSaturationPct", "@CpuSaturationPct", "Trigger.CpuSaturationPct", SettingUnit.PERCENT,
            10, 100, "CPU saturation", "CPU used", false,
            c -> c.trigger.cpuSaturationPct, (c, v) -> c.trigger.cpuSaturationPct = (int) v),
        duration("CpuSaturationSustain", "@CpuSaturationSustain", "Trigger.CpuSaturationSustain", SettingUnit.SECONDS,
            0, 3600, "CPU saturation", "Sustain", false,
            c -> c.trigger.cpuSaturationSustain, (c, v) -> c.trigger.cpuSaturationSustain = v),
        toggle("NetSaturation", "Trigger.Modules.NetSaturation", "Network saturation", false,
            c -> c.trigger.modules.netSaturation, (c, v) -> c.trigger.modules.netSaturation = v),
        number("NetInMbps", "@NetInMbps", "Trigger.NetInMbps", SettingUnit.MBPS,
            0, 100_000, "Network saturation", "In", false,
            c -> c.trigger.netInMbps, (c, v) -> c.trigger.netInMbps = v),
        number("NetOutMbps", "@NetOutMbps", "Trigger.NetOutMbps", SettingUnit.MBPS,
            0, 100_000, "Network saturation", "Out", false,
            c -> c.trigger.netOutMbps, (c, v) -> c.trigger.netOutMbps = v),
        duration("NetSustain", "@NetSustain", "Trigger.NetSustain", SettingUnit.SECONDS,
            0, 3600, "Network saturation", "Sustain", false,
            c -> c.trigger.netSustain, (c, v) -> c.trigger.netSustain = v),
        toggle("PlayerDrop", "Trigger.Modules.PlayerDrop", "Player drop", false,
            c -> c.trigger.modules.playerDrop, (c, v) -> c.trigger.modules.playerDrop = v),
        number("PlayerDropPct", "@PlayerDropPct", "Trigger.PlayerDropPct", SettingUnit.PERCENT,
            10, 100, "Player drop", "Player loss", false,
            c -> c.trigger.playerDropPct, (c, v) -> c.trigger.playerDropPct = (int) v),
        duration("PlayerDropWindow", "@PlayerDropWindow", "Trigger.PlayerDropWindow", SettingUnit.SECONDS,
            10, 3600, "Player drop", "Window", false,
            c -> c.trigger.playerDropWindow, (c, v) -> c.trigger.playerDropWindow = v),
        number("PlayerDropMinPlayers", "@PlayerDropMinPlayers", "Trigger.PlayerDropMinPlayers", SettingUnit.COUNT,
            0, 1000, "Player drop", "Min players", false,
            c -> c.trigger.playerDropMinPlayers, (c, v) -> c.trigger.playerDropMinPlayers = (int) v),
        toggle("LogError", "Trigger.Modules.LogError", "Log errors", false,
            c -> c.trigger.modules.logError, (c, v) -> c.trigger.modules.logError = v),
        duration("LogErrorDedupeWindow", "@LogErrorDedupeWindow", "Trigger.LogErrorDedupeWindow", SettingUnit.SECONDS,
            0, 86400, "Log errors", "Repeat window", false,
            c -> c.trigger.logErrorDedupeWindow, (c, v) -> c.trigger.logErrorDedupeWindow = v),
        duration("Cooldown", "@CooldownSeconds", "Trigger.Cooldown", SettingUnit.SECONDS,
            0, 3600, "", "Cooldown", false,
            c -> c.trigger.cooldown, (c, v) -> c.trigger.cooldown = v),
        duration("Debounce", "@DebounceSeconds", "Trigger.Debounce", SettingUnit.SECONDS,
            0, 3600, "", "Debounce", false,
            c -> c.trigger.debounce, (c, v) -> c.trigger.debounce = v),
        toggle("CaptureEnabled", "Capture.Enabled", "Incident capture enabled", false,
            c -> c.capture.enabled, (c, v) -> c.capture.enabled = v),
        number("RetentionMaxCount", "@RetentionMaxCount", "Retention.MaxCount", SettingUnit.COUNT,
            1, 1000, "Capture and storage", "Max bundles", false,
            c -> c.retention.maxCount, (c, v) -> c.retention.maxCount = (int) v),
        number("RetentionMaxTotalMiB", "@RetentionMaxTotalMiB", "Retention.MaxTotalBytes", SettingUnit.MEBIBYTES,
            64, 1_048_576, "Capture and storage", "Total", false,
            c -> c.retention.maxTotalBytes, (c, v) -> c.retention.maxTotalBytes = v),
        duration("RetentionMaxAgeDays", "@RetentionMaxAgeDays", "Retention.MaxAge", SettingUnit.DAYS,
            1, 365, "Capture and storage", "Age", false,
            c -> c.retention.maxAge == null ? DEFAULT_RETENTION_MAX_AGE : c.retention.maxAge,
            (c, v) -> c.retention.maxAge = v),
        toggle("MetricsEnabled", "Metrics.Enabled", "Metrics history", false,
            c -> c.metrics.enabled, (c, v) -> c.metrics.enabled = v),
        number("MetricsRetentionDays", "@MetricsRetentionDays", "Metrics.RetentionDays", SettingUnit.DAY_COUNT,
            1, 365, "Metrics history", "Keep", false,
            c -> c.metrics.retentionDays, (c, v) -> c.metrics.retentionDays = (int) v),
        toggle("MetricsCpu", "Metrics.Cpu", "CPU use", false,
            c -> c.metrics.cpu, (c, v) -> c.metrics.cpu = v),
        toggle("MetricsAllocation", "Metrics.Allocation", "Allocation rate", false,
            c -> c.metrics.allocation, (c, v) -> c.metrics.allocation = v),
        duration("PostIncidentMaxWait", "@PostIncidentMaxWait", "Jfr.PostIncidentMaxWait", SettingUnit.SECONDS,
            0, 600, "Incident evidence", "Max wait", false,
            c -> c.jfr.postIncidentMaxWait, (c, v) -> c.jfr.postIncidentMaxWait = v),
        number("ThreadDumpCount", "@ThreadDumpCount", "Jfr.ThreadDumpCount", SettingUnit.COUNT,
            0, 20, "Incident evidence", "Dump count", false,
            c -> c.jfr.threadDumpCount, (c, v) -> c.jfr.threadDumpCount = (int) v),
        duration("ThreadDumpInterval", "@ThreadDumpInterval", "Jfr.ThreadDumpInterval", SettingUnit.SECONDS,
            1, 120, "Incident evidence", "Dump every", false,
            c -> c.jfr.threadDumpInterval, (c, v) -> c.jfr.threadDumpInterval = v),
        duration("JfrMaxAgeMinutes", "@JfrMaxAgeMinutes", "Jfr.MaxAge", SettingUnit.MINUTES,
            5, 1440, "Recording", "History", true,
            c -> c.jfr.maxAge, (c, v) -> c.jfr.maxAge = v),
        number("JfrMaxSizeMiB", "@JfrMaxSizeMiB", "Jfr.MaxSizeBytes", SettingUnit.MEBIBYTES,
            16, 8192, "Recording", "Size", true,
            c -> c.jfr.maxSizeBytes, (c, v) -> c.jfr.maxSizeBytes = v),
        duration("JfrSampleIntervalSeconds", "@JfrSampleIntervalSeconds", "Jfr.SampleInterval", SettingUnit.SECONDS,
            5, 300, "Recording", "Sample every", true,
            c -> c.jfr.sampleInterval, (c, v) -> c.jfr.sampleInterval = v),
        duration("JfrSnapshotIntervalMinutes", "@JfrSnapshotIntervalMinutes", "Jfr.SnapshotInterval", SettingUnit.MINUTES,
            0, 1440, "Recording", "Snapshot", true,
            c -> c.jfr.snapshotInterval, (c, v) -> c.jfr.snapshotInterval = v),
        toggle("JfrOldObjectSampling", "Jfr.OldObjectSampling", "Old-object sampling", true,
            c -> c.jfr.oldObjectSampling, (c, v) -> c.jfr.oldObjectSampling = v)
    );

    private static SettingField toggle(String id, String configKey, String label, boolean restartRequired,
                                       Function<FileConfig, Boolean> getter, BiConsumer<FileConfig, Boolean> setter) {
        return new SettingField(id, "@" + id, configKey, SettingKind.TOGGLE, SettingUnit.BOOLEAN, 0, 1,
            "", label, restartRequired, getter::apply, (c, v) -> setter.accept(c, (boolean) v), getter::apply);
    }

    private static SettingField number(String id, String eventKey, String configKey, SettingUnit unit,
                                      long min, long max, String group, String label, boolean restartRequired,
                                      ToLongFunction<FileConfig> getter, ObjLongConsumer<FileConfig> setter) {
        return new SettingField(id, eventKey, configKey, SettingKind.NUMBER, unit, min, max,
            group, label, restartRequired, c -> unit.display(getter.applyAsLong(c)),
            (c, v) -> setter.accept(c, unit.stored((long) v)), c -> getter.applyAsLong(c));
    }

    private static SettingField duration(String id, String eventKey, String configKey, SettingUnit unit,
                                        long min, long max, String group, String label, boolean restartRequired,
                                        Function<FileConfig, Duration> getter, BiConsumer<FileConfig, Duration> setter) {
        return new SettingField(id, eventKey, configKey, SettingKind.NUMBER, unit, min, max,
            group, label, restartRequired, c -> unit.display(getter.apply(c).getSeconds()),
            (c, v) -> setter.accept(c, Duration.ofSeconds(unit.stored((long) v))), getter::apply);
    }

    record HyboxSettings(Map<String, Object> values) {
        HyboxSettings {
            values = Map.copyOf(values);
        }

        Object value(SettingField field) {
            return values.get(field.id());
        }

        List<String> validate() {
            List<String> problems = new ArrayList<>();
            for (SettingField field : SETTINGS_FIELDS) field.validate(value(field), problems);
            if ((long) values.get("StallCritical") < (long) values.get("StallDegraded")) {
                problems.add("Heartbeat stall: Critical must be at least Degraded.");
            }
            if ((long) values.get("TickAvgCritical") < (long) values.get("TickAvgDegraded")) {
                problems.add("Slow ticks: Critical must be at least Degraded.");
            }
            return List.copyOf(problems);
        }
    }

    static List<SettingField> pendingRestart(HyboxSettings saved, HyboxConfig running) {
        List<SettingField> pending = new ArrayList<>();
        for (SettingField field : SETTINGS_FIELDS) {
            if (!field.restartRequired()) continue;
            Object applied = switch (field.id()) {
                case "JfrMaxAgeMinutes" -> running.jfrMaxAge().toMinutes();
                case "JfrMaxSizeMiB" -> SettingUnit.MEBIBYTES.display(running.jfrMaxSizeBytes());
                case "JfrSampleIntervalSeconds" -> running.jfrSampleInterval().getSeconds();
                case "JfrSnapshotIntervalMinutes" -> running.jfrSnapshotInterval().toMinutes();
                case "JfrOldObjectSampling" -> running.jfrOldObjectSampling();
                default -> throw new IllegalStateException("No running value for " + field.id());
            };
            if (!field.clamp(applied).equals(saved.value(field))) pending.add(field);
        }
        return List.copyOf(pending);
    }

    static HyboxSettings read(Path dataDir, System.Logger logger) {
        Objects.requireNonNull(logger, "logger");
        FileConfig raw = loadFile(dataDir).get();
        Map<String, Object> values = new LinkedHashMap<>();
        for (SettingField field : SETTINGS_FIELDS) values.put(field.id(), field.read(raw));
        return new HyboxSettings(values);
    }

    static void write(Path dataDir, HyboxSettings original, HyboxSettings settings, System.Logger logger) {
        Objects.requireNonNull(original, "original");
        Objects.requireNonNull(settings, "settings");
        Objects.requireNonNull(logger, "logger");
        List<String> problems = settings.validate();
        if (!problems.isEmpty()) throw new IllegalArgumentException(String.join(" ", problems));
        Config<FileConfig> configFile = loadFile(dataDir);
        FileConfig raw = configFile.get();
        for (SettingField field : SETTINGS_FIELDS) {
            Object submitted = settings.value(field);
            if (!submitted.equals(original.value(field))) field.setter().accept(raw, submitted);
        }
        try {
            Path configPath = path(dataDir);
            BsonDocument encoded = FileConfig.CODEC.encode(raw, new ExtraInfo());
            BsonDocument document = Files.exists(configPath)
                ? BsonDocument.parse(Files.readString(configPath)) : encoded;
            for (SettingField field : SETTINGS_FIELDS) {
                if (settings.value(field).equals(original.value(field))) continue;
                String[] keys = field.configKey().split("\\.");
                BsonDocument target = document;
                BsonDocument source = encoded;
                for (int i = 0; i < keys.length - 1; i++) {
                    BsonValue child = target.get(keys[i]);
                    if (child == null || !child.isDocument()) {
                        target.put(keys[i], new BsonDocument());
                    }
                    target = target.getDocument(keys[i]);
                    source = source.getDocument(keys[i]);
                }
                String key = keys[keys.length - 1];
                target.put(key, source.get(key));
            }
            BsonUtil.writeDocument(configPath, document).join();
        } catch (IOException | RuntimeException failure) {
            throw new IllegalStateException("Failed to save Hybox settings to " + path(dataDir), failure);
        }
        logger.log(System.Logger.Level.INFO, "Saved Hybox settings: " + path(dataDir));
    }

    private static Config<FileConfig> loadFile(Path dataDir) {
        Objects.requireNonNull(dataDir, "dataDir");
        Config<FileConfig> configFile = new Config<>(dataDir, FILE_NAME, FileConfig.CODEC);
        try {
            Objects.requireNonNull(configFile.load().join(), "Loaded configuration");
        } catch (RuntimeException failure) {
            throw new IllegalStateException("Failed to load Hybox config from " + path(dataDir)
                + ". The existing configuration was not replaced.", failure);
        }
        return configFile;
    }

    private static HyboxConfig defaultCoreConfig() {
        TriggerPolicy triggerPolicy = new TriggerPolicy(
            DEFAULT_TRIGGER_COOLDOWN,
            DEFAULT_TRIGGER_DEBOUNCE,
            DEFAULT_STALL_DEGRADED_MS,
            DEFAULT_STALL_CRITICAL_MS,
            DEFAULT_TICK_AVG_DEGRADED_MS,
            DEFAULT_TICK_AVG_CRITICAL_MS
        );
        RetentionPolicy retentionPolicy = new RetentionPolicy(
            DEFAULT_RETENTION_MAX_COUNT,
            DEFAULT_RETENTION_MAX_TOTAL_BYTES,
            DEFAULT_RETENTION_MAX_AGE
        );
        DiscordWebhookConfig discord = new DiscordWebhookConfig(
            DEFAULT_DISCORD_WEBHOOK_URL,
            DEFAULT_DISCORD_COOLDOWN,
            DEFAULT_DISCORD_REQUEST_TIMEOUT,
            DEFAULT_DISCORD_USERNAME
        );
        return new HyboxConfig(
            DEFAULT_JFR_MAX_AGE,
            DEFAULT_JFR_MAX_SIZE_BYTES,
            DEFAULT_JFR_RECORDING_NAME,
            List.of(),
            triggerPolicy,
            new CapturePolicy(retentionPolicy, DEFAULT_CAPTURE_ENABLED,
                DEFAULT_CAPTURE_ALLOW_PLUGIN_EXTRAS, DEFAULT_CAPTURE_LOG_TAIL_LINES,
                DEFAULT_CAPTURE_REDACT_PATTERNS).withFrameworkPrefixes(FRAMEWORK_PREFIXES),
            discord,
            DEFAULT_JFR_POST_INCIDENT_MAX_WAIT,
            DEFAULT_JFR_SNAPSHOT_INTERVAL,
            DEFAULT_JFR_CONFIGURATION
        );
    }

    private static final class FileConfig {
        public int version = 1;
        public Jfr jfr = new Jfr();
        public Trigger trigger = new Trigger();
        public Retention retention = new Retention();
        public Capture capture = new Capture();
        public Discord discord = new Discord();
        public Metrics metrics = new Metrics();

        static final BuilderCodec<FileConfig> CODEC = BuilderCodec
            .builder(FileConfig.class, FileConfig::new)
            .append(new KeyedCodec<>("Version", Codec.INTEGER), (c, v, ei) -> {
                if (v != null) {
                    c.version = v;
                }
            }, (c, ei) -> c.version).add()
            .append(new KeyedCodec<>("Jfr", Jfr.CODEC), (c, v, ei) -> {
                if (v != null) {
                    c.jfr = v;
                }
            }, (c, ei) -> c.jfr).add()
            .append(new KeyedCodec<>("Trigger", Trigger.CODEC), (c, v, ei) -> {
                if (v != null) {
                    c.trigger = v;
                }
            }, (c, ei) -> c.trigger).add()
            .append(new KeyedCodec<>("Retention", Retention.CODEC), (c, v, ei) -> {
                if (v != null) {
                    c.retention = v;
                }
            }, (c, ei) -> c.retention).add()
            .append(new KeyedCodec<>("Capture", Capture.CODEC), (c, v, ei) -> {
                if (v != null) {
                    c.capture = v;
                }
            }, (c, ei) -> c.capture).add()
            .append(new KeyedCodec<>("Discord", Discord.CODEC), (c, v, ei) -> {
                if (v != null) {
                    c.discord = v;
                }
            }, (c, ei) -> c.discord).add()
            .append(new KeyedCodec<>("Metrics", Metrics.CODEC), (c, v, ei) -> {
                if (v != null) {
                    c.metrics = v;
                }
            }, (c, ei) -> c.metrics).add()
            .build();

        HyboxConfig toCoreConfig(System.Logger logger) {
            Objects.requireNonNull(logger, "logger");

            if (this.jfr == null) this.jfr = new Jfr();
            if (this.trigger == null) this.trigger = new Trigger();
            if (this.trigger.modules == null) this.trigger.modules = new Modules();
            if (this.retention == null) this.retention = new Retention();
            if (this.capture == null) this.capture = new Capture();
            if (this.discord == null) this.discord = new Discord();
            if (this.metrics == null) this.metrics = new Metrics();
            clampToSettingRanges(logger);
            Jfr jfrCfg = this.jfr;
            Trigger triggerCfg = this.trigger;
            Retention retentionCfg = this.retention;
            Capture captureCfg = this.capture;
            Discord discordCfg = this.discord;
            Metrics metricsCfg = this.metrics;

            Duration jfrMaxAge = nonNegativeDuration(jfrCfg.maxAge, DEFAULT_JFR_MAX_AGE, "Jfr.MaxAge", logger);
            long jfrMaxSizeBytes = nonNegativeLong(jfrCfg.maxSizeBytes, DEFAULT_JFR_MAX_SIZE_BYTES, "Jfr.MaxSizeBytes", logger);
            String recordingName = nonBlankString(
                jfrCfg.recordingName,
                DEFAULT_JFR_RECORDING_NAME,
                "Jfr.RecordingName",
                logger
            );
            List<String> jfrDisabledEvents = jfrCfg.disabledEvents == null ? List.of() : List.copyOf(jfrCfg.disabledEvents);
            Duration postIncidentMaxWait = nonNegativeDuration(
                jfrCfg.postIncidentMaxWait,
                DEFAULT_JFR_POST_INCIDENT_MAX_WAIT,
                "Jfr.PostIncidentMaxWait",
                logger
            );
            Duration threadDumpInterval = nonNegativeDuration(
                jfrCfg.threadDumpInterval,
                DEFAULT_JFR_THREAD_DUMP_INTERVAL,
                "Jfr.ThreadDumpInterval",
                logger
            );
            Duration snapshotInterval = nonNegativeDuration(
                jfrCfg.snapshotInterval,
                DEFAULT_JFR_SNAPSHOT_INTERVAL,
                "Jfr.SnapshotInterval",
                logger
            );
            String jfrConfiguration = nonBlankString(
                jfrCfg.configuration,
                DEFAULT_JFR_CONFIGURATION,
                "Jfr.Configuration",
                logger
            );
            Duration sampleInterval = nonNegativeDuration(
                jfrCfg.sampleInterval,
                DEFAULT_JFR_SAMPLE_INTERVAL,
                "Jfr.SampleInterval",
                logger
            );

            Duration cooldown = nonNegativeDuration(
                triggerCfg.cooldown,
                DEFAULT_TRIGGER_COOLDOWN,
                "Trigger.Cooldown",
                logger
            );
            Duration debounce = nonNegativeDuration(
                triggerCfg.debounce,
                DEFAULT_TRIGGER_DEBOUNCE,
                "Trigger.Debounce",
                logger
            );
            Set<TriggerKind> cooldownExemptKinds = triggerKinds(
                triggerCfg.cooldownExemptKinds,
                TriggerPolicy.DEFAULT_COOLDOWN_EXEMPT_KINDS,
                "Trigger.CooldownExemptKinds",
                logger
            );
            long stallDegradedMs = positiveLong(
                triggerCfg.stallDegradedMs,
                DEFAULT_STALL_DEGRADED_MS,
                "Trigger.StallDegradedMs",
                logger
            );
            long stallCriticalMs = positiveLong(
                triggerCfg.stallCriticalMs,
                DEFAULT_STALL_CRITICAL_MS,
                "Trigger.StallCriticalMs",
                logger
            );
            if (stallCriticalMs < stallDegradedMs) {
                logger.log(
                    System.Logger.Level.WARNING,
                    String.format("Config Trigger.StallCriticalMs (%d) is < Trigger.StallDegradedMs (%d). Clamping.",
                        stallCriticalMs, stallDegradedMs)
                );
                stallCriticalMs = stallDegradedMs;
            }
            long tickAvgDegradedMs = positiveLong(
                triggerCfg.tickAvgDegradedMs,
                DEFAULT_TICK_AVG_DEGRADED_MS,
                "Trigger.TickAvgDegradedMs",
                logger
            );
            long tickAvgCriticalMs = positiveLong(
                triggerCfg.tickAvgCriticalMs,
                DEFAULT_TICK_AVG_CRITICAL_MS,
                "Trigger.TickAvgCriticalMs",
                logger
            );
            if (tickAvgCriticalMs < tickAvgDegradedMs) {
                logger.log(
                    System.Logger.Level.WARNING,
                    String.format("Config Trigger.TickAvgCriticalMs (%d) is < Trigger.TickAvgDegradedMs (%d). Clamping.",
                        tickAvgCriticalMs, tickAvgDegradedMs)
                );
                tickAvgCriticalMs = tickAvgDegradedMs;
            }

            Modules modulesCfg = triggerCfg.modules == null ? new Modules() : triggerCfg.modules;
            ModulePolicy modules = new ModulePolicy(
                modulesCfg.heartbeatStall,
                modulesCfg.tickDegraded,
                modulesCfg.deadlock,
                modulesCfg.heapPressure,
                modulesCfg.gcPressure,
                modulesCfg.cpuSaturation,
                modulesCfg.netSaturation,
                modulesCfg.playerDrop,
                modulesCfg.logError
            );

            DetectorPolicy detectors = new DetectorPolicy(
                modules,
                percentInt(triggerCfg.heapPressurePct, DEFAULT_DETECTORS.heapPressurePct(),
                    "Trigger.HeapPressurePct", logger),
                nonNegativeDuration(triggerCfg.heapPressureSustain, DEFAULT_DETECTORS.heapPressureSustain(),
                    "Trigger.HeapPressureSustain", logger),
                percentInt(triggerCfg.gcPressurePct, DEFAULT_DETECTORS.gcPressurePct(),
                    "Trigger.GcPressurePct", logger),
                nonNegativeDuration(triggerCfg.gcPressureWindow, DEFAULT_DETECTORS.gcPressureWindow(),
                    "Trigger.GcPressureWindow", logger),
                percentInt(triggerCfg.cpuSaturationPct, DEFAULT_DETECTORS.cpuSaturationPct(),
                    "Trigger.CpuSaturationPct", logger),
                nonNegativeDuration(triggerCfg.cpuSaturationSustain, DEFAULT_DETECTORS.cpuSaturationSustain(),
                    "Trigger.CpuSaturationSustain", logger),
                nonNegativeLong(triggerCfg.netInMbps, DEFAULT_DETECTORS.netInMbps(),
                    "Trigger.NetInMbps", logger),
                nonNegativeLong(triggerCfg.netOutMbps, DEFAULT_DETECTORS.netOutMbps(),
                    "Trigger.NetOutMbps", logger),
                nonNegativeDuration(triggerCfg.netSustain, DEFAULT_DETECTORS.netSustain(),
                    "Trigger.NetSustain", logger),
                percentInt(triggerCfg.playerDropPct, DEFAULT_DETECTORS.playerDropPct(),
                    "Trigger.PlayerDropPct", logger),
                nonNegativeDuration(triggerCfg.playerDropWindow, DEFAULT_DETECTORS.playerDropWindow(),
                    "Trigger.PlayerDropWindow", logger),
                nonNegativeInt(triggerCfg.playerDropMinPlayers, DEFAULT_DETECTORS.playerDropMinPlayers(),
                    "Trigger.PlayerDropMinPlayers", logger),
                triggerCfg.logErrorSkipSentry,
                triggerCfg.logErrorRequireThrowable,
                nonNegativeDuration(triggerCfg.logErrorDedupeWindow, DEFAULT_DETECTORS.logErrorDedupeWindow(),
                    "Trigger.LogErrorDedupeWindow", logger),
                compilePatterns(triggerCfg.logErrorIgnore, "Trigger.LogErrorIgnore", logger)
            );

            int maxCount = nonNegativeInt(retentionCfg.maxCount, DEFAULT_RETENTION_MAX_COUNT, "Retention.MaxCount", logger);
            long maxTotalBytes = nonNegativeLong(
                retentionCfg.maxTotalBytes,
                DEFAULT_RETENTION_MAX_TOTAL_BYTES,
                "Retention.MaxTotalBytes",
                logger
            );
            Duration maxAge = retentionCfg.maxAge;
            if (maxAge != null && maxAge.isNegative()) {
                logger.log(
                    System.Logger.Level.WARNING,
                    String.format("Config Retention.MaxAge is negative. Using default %s.", DEFAULT_RETENTION_MAX_AGE)
                );
                maxAge = DEFAULT_RETENTION_MAX_AGE;
            }
            int failedRecordingMaxCount = nonNegativeInt(
                retentionCfg.failedRecordingMaxCount,
                RetentionPolicy.DEFAULT_FAILED_RECORDING_MAX_COUNT,
                "Retention.FailedRecordingMaxCount",
                logger
            );

            String webhookUrl = discordCfg.webhookUrl == null ? DEFAULT_DISCORD_WEBHOOK_URL : discordCfg.webhookUrl;
            Duration webhookCooldown = nonNegativeDuration(
                discordCfg.cooldown,
                DEFAULT_DISCORD_COOLDOWN,
                "Discord.Cooldown",
                logger
            );
            Duration requestTimeout = nonNegativeDuration(
                discordCfg.requestTimeout,
                DEFAULT_DISCORD_REQUEST_TIMEOUT,
                "Discord.RequestTimeout",
                logger
            );
            String username = nonBlankString(discordCfg.username, DEFAULT_DISCORD_USERNAME, "Discord.Username", logger);

            int logTailLines = nonNegativeInt(
                captureCfg.logTailLines,
                DEFAULT_CAPTURE_LOG_TAIL_LINES,
                "Capture.LogTailLines",
                logger
            );

            try {
                return new HyboxConfig(
                    jfrMaxAge,
                    jfrMaxSizeBytes,
                    recordingName,
                    jfrDisabledEvents,
                    new TriggerPolicy(cooldown, debounce, stallDegradedMs, stallCriticalMs,
                        tickAvgDegradedMs, tickAvgCriticalMs, detectors, cooldownExemptKinds),
                    new CapturePolicy(
                        new RetentionPolicy(maxCount, maxTotalBytes, maxAge, failedRecordingMaxCount,
                            nonNegativeLong(retentionCfg.stagingMaxTotalBytes, RetentionPolicy.DEFAULT_STAGING_MAX_TOTAL_BYTES,
                                "Retention.StagingMaxTotalBytes", logger),
                            nonNegativeDuration(retentionCfg.stagingMaxAge, RetentionPolicy.DEFAULT_STAGING_MAX_AGE,
                                "Retention.StagingMaxAge", logger),
                            nonNegativeLong(retentionCfg.repositoryMaxTotalBytes, RetentionPolicy.DEFAULT_REPOSITORY_MAX_TOTAL_BYTES,
                                "Retention.RepositoryMaxTotalBytes", logger),
                            nonNegativeDuration(retentionCfg.repositoryMaxAge, RetentionPolicy.DEFAULT_REPOSITORY_MAX_AGE,
                                "Retention.RepositoryMaxAge", logger)),
                        captureCfg.enabled,
                        captureCfg.allowPluginExtras,
                        logTailLines,
                        captureCfg.redactPatterns == null ? DEFAULT_CAPTURE_REDACT_PATTERNS : List.copyOf(captureCfg.redactPatterns),
                        captureCfg.artifacts == null ? Set.copyOf(DEFAULT_CAPTURE_ARTIFACTS) : Set.copyOf(captureCfg.artifacts),
                        captureCfg.heapHistogram,
                        captureCfg.includeServerLog,
                        captureCfg.includeServerConfig,
                        captureCfg.includeModConfigs,
                        captureCfg.allowModConfigOptIn,
                        FRAMEWORK_PREFIXES
                    ),
                    new DiscordWebhookConfig(webhookUrl, webhookCooldown, requestTimeout, username),
                    postIncidentMaxWait,
                    snapshotInterval,
                    jfrConfiguration,
                    sampleInterval,
                    jfrCfg.oldObjectSampling,
                    metricsCfg.enabled,
                    nonNegativeInt(metricsCfg.retentionDays, DEFAULT_METRICS_RETENTION_DAYS, "Metrics.RetentionDays", logger),
                    metricsCfg.cpu,
                    metricsCfg.allocation,
                    metricsCfg.prometheus.enabled,
                    metricsCfg.prometheus.port,
                    metricsCfg.prometheus.bind,
                    jfrCfg.threadDumpCount,
                    threadDumpInterval
                );
            } catch (RuntimeException e) {
                logger.log(System.Logger.Level.WARNING, "Invalid Hybox config. Falling back to defaults.", e);
                return defaultCoreConfig();
            }
        }

        private void clampToSettingRanges(System.Logger logger) {
            for (SettingField field : SETTINGS_FIELDS) {
                if (field.kind() != SettingKind.NUMBER) continue;
                long value = (long) field.getter().apply(this);
                if (value <= 0 || (value >= field.min() && value <= field.max())) continue;
                long allowed = Math.clamp(value, field.min(), field.max());
                String before = field.unit().formatStored(field.stored().apply(this));
                field.setter().accept(this, allowed);
                logger.log(System.Logger.Level.WARNING, String.format(
                    "Config %s (%s) is %s, outside the allowed range %s to %s. Using %s. hybox.json was not changed.",
                    field.configKey(), field.name(), before, field.unit().format(field.min()),
                    field.unit().format(field.max()), field.unit().formatStored(field.stored().apply(this))));
            }
        }

        private static Duration nonNegativeDuration(
            Duration value,
            Duration defaultValue,
            String key,
            System.Logger logger
        ) {
            if (value == null || value.isNegative()) {
                logger.log(System.Logger.Level.WARNING,
                    String.format("Config %s must be >= 0. Using default %s.", key, defaultValue));
                return defaultValue;
            }
            return value;
        }

        private static long positiveLong(long value, long defaultValue, String key, System.Logger logger) {
            if (value <= 0L) {
                logger.log(System.Logger.Level.WARNING,
                    String.format("Config %s must be > 0. Using default %d.", key, defaultValue));
                return defaultValue;
            }
            return value;
        }

        private static List<Pattern> compilePatterns(List<String> regexes, String key, System.Logger logger) {
            if (regexes == null || regexes.isEmpty()) {
                return List.of();
            }
            List<Pattern> compiled = new ArrayList<>(regexes.size());
            for (String regex : regexes) {
                if (regex == null || regex.isBlank()) {
                    continue;
                }
                try {
                    compiled.add(Pattern.compile(regex));
                } catch (PatternSyntaxException e) {
                    logger.log(System.Logger.Level.WARNING,
                        String.format("Skipping invalid %s pattern: %s", key, regex), e);
                }
            }
            return List.copyOf(compiled);
        }

        private static Set<TriggerKind> triggerKinds(
            List<String> names, Set<TriggerKind> fallback, String key, System.Logger logger) {
            if (names == null) {
                return fallback;
            }
            Set<TriggerKind> kinds = new LinkedHashSet<>();
            for (String name : names) {
                if (name == null || name.isBlank()) {
                    continue;
                }
                try {
                    kinds.add(TriggerKind.valueOf(name.trim().toUpperCase(Locale.ROOT)));
                } catch (IllegalArgumentException e) {
                    logger.log(System.Logger.Level.WARNING,
                        String.format("Config %s has unknown trigger kind '%s'. Ignoring it.", key, name));
                }
            }
            return kinds;
        }

        private static long nonNegativeLong(long value, long defaultValue, String key, System.Logger logger) {
            if (value < 0L) {
                logger.log(System.Logger.Level.WARNING,
                    String.format("Config %s must be >= 0. Using default %d.", key, defaultValue));
                return defaultValue;
            }
            return value;
        }

        private static int percentInt(int value, int defaultValue, String key, System.Logger logger) {
            if (value < 0 || value > 100) {
                logger.log(System.Logger.Level.WARNING,
                    String.format("Config %s must be within 0..100 (0 = disabled). Using default %d.",
                        key, defaultValue));
                return defaultValue;
            }
            return value;
        }

        private static int nonNegativeInt(int value, int defaultValue, String key, System.Logger logger) {
            if (value < 0) {
                logger.log(System.Logger.Level.WARNING,
                    String.format("Config %s must be >= 0. Using default %d.", key, defaultValue));
                return defaultValue;
            }
            return value;
        }

        private static String nonBlankString(String value, String defaultValue, String key, System.Logger logger) {
            if (value == null || value.isBlank()) {
                logger.log(System.Logger.Level.WARNING,
                    String.format("Config %s must be non-blank. Using default %s.", key, defaultValue));
                return defaultValue;
            }
            return value.trim();
        }
    }

    private static final class Jfr {
        public Duration maxAge = DEFAULT_JFR_MAX_AGE;
        public long maxSizeBytes = DEFAULT_JFR_MAX_SIZE_BYTES;
        public String recordingName = DEFAULT_JFR_RECORDING_NAME;
        public List<String> disabledEvents = List.of();
        public Duration postIncidentMaxWait = DEFAULT_JFR_POST_INCIDENT_MAX_WAIT;
        public int threadDumpCount = DEFAULT_JFR_THREAD_DUMP_COUNT;
        public Duration threadDumpInterval = DEFAULT_JFR_THREAD_DUMP_INTERVAL;
        public Duration snapshotInterval = DEFAULT_JFR_SNAPSHOT_INTERVAL;
        public String configuration = DEFAULT_JFR_CONFIGURATION;
        public Duration sampleInterval = DEFAULT_JFR_SAMPLE_INTERVAL;
        public boolean oldObjectSampling = DEFAULT_JFR_OLD_OBJECT_SAMPLING;

        static final BuilderCodec<Jfr> CODEC = BuilderCodec
            .builder(Jfr.class, Jfr::new)
            .append(new KeyedCodec<>("MaxAge", Codec.DURATION), (c, v, ei) -> {
                if (v != null) {
                    c.maxAge = v;
                }
            }, (c, ei) -> c.maxAge).add()
            .append(new KeyedCodec<>("MaxSizeBytes", Codec.LONG), (c, v, ei) -> {
                if (v != null) {
                    c.maxSizeBytes = v;
                }
            }, (c, ei) -> c.maxSizeBytes).add()
            .append(new KeyedCodec<>("RecordingName", Codec.STRING), (c, v, ei) -> {
                if (v != null) {
                    c.recordingName = v;
                }
            }, (c, ei) -> c.recordingName).add()
            .append(new KeyedCodec<>("DisabledEvents", Codec.STRING_ARRAY), (c, v, ei) -> {
                if (v != null) {
                    c.disabledEvents = Arrays.stream(v).filter(Objects::nonNull).toList();
                }
            }, (c, ei) -> c.disabledEvents.toArray(new String[0])).add()
            .append(new KeyedCodec<>("PostIncidentMaxWait", Codec.DURATION), (c, v, ei) -> {
                if (v != null) {
                    c.postIncidentMaxWait = v;
                }
            }, (c, ei) -> c.postIncidentMaxWait).add()
            .append(new KeyedCodec<>("ThreadDumpCount", Codec.INTEGER), (c, v, ei) -> {
                if (v != null) {
                    c.threadDumpCount = v;
                }
            }, (c, ei) -> c.threadDumpCount).add()
            .append(new KeyedCodec<>("ThreadDumpInterval", Codec.DURATION), (c, v, ei) -> {
                if (v != null) {
                    c.threadDumpInterval = v;
                }
            }, (c, ei) -> c.threadDumpInterval).add()
            .append(new KeyedCodec<>("SnapshotInterval", Codec.DURATION), (c, v, ei) -> {
                if (v != null) {
                    c.snapshotInterval = v;
                }
            }, (c, ei) -> c.snapshotInterval).add()
            .append(new KeyedCodec<>("Configuration", Codec.STRING), (c, v, ei) -> {
                if (v != null) {
                    c.configuration = v;
                }
            }, (c, ei) -> c.configuration).add()
            .append(new KeyedCodec<>("SampleInterval", Codec.DURATION), (c, v, ei) -> {
                if (v != null) {
                    c.sampleInterval = v;
                }
            }, (c, ei) -> c.sampleInterval).add()
            .append(new KeyedCodec<>("OldObjectSampling", Codec.BOOLEAN), (c, v, ei) -> {
                if (v != null) {
                    c.oldObjectSampling = v;
                }
            }, (c, ei) -> c.oldObjectSampling).add()
            .build();
    }

    private static final class Capture {
        public boolean enabled = DEFAULT_CAPTURE_ENABLED;
        public boolean allowPluginExtras = DEFAULT_CAPTURE_ALLOW_PLUGIN_EXTRAS;
        public int logTailLines = DEFAULT_CAPTURE_LOG_TAIL_LINES;
        public List<String> redactPatterns = new ArrayList<>(DEFAULT_CAPTURE_REDACT_PATTERNS);
        public List<String> artifacts = new ArrayList<>(DEFAULT_CAPTURE_ARTIFACTS);
        public boolean heapHistogram = DEFAULT_CAPTURE_HEAP_HISTOGRAM;
        public boolean includeServerLog = DEFAULT_CAPTURE_INCLUDE_SERVER_LOG;
        public boolean includeServerConfig = DEFAULT_CAPTURE_INCLUDE_SERVER_CONFIG;
        public boolean includeModConfigs = DEFAULT_CAPTURE_INCLUDE_MOD_CONFIGS;
        public boolean allowModConfigOptIn = DEFAULT_CAPTURE_ALLOW_MOD_CONFIG_OPT_IN;

        static final BuilderCodec<Capture> CODEC = BuilderCodec
            .builder(Capture.class, Capture::new)
            .append(new KeyedCodec<>("Enabled", Codec.BOOLEAN), (c, v, ei) -> {
                if (v != null) {
                    c.enabled = v;
                }
            }, (c, ei) -> c.enabled).add()
            .append(new KeyedCodec<>("HeapHistogram", Codec.BOOLEAN), (c, v, ei) -> {
                if (v != null) {
                    c.heapHistogram = v;
                }
            }, (c, ei) -> c.heapHistogram).add()
            .append(new KeyedCodec<>("AllowPluginExtras", Codec.BOOLEAN), (c, v, ei) -> {
                if (v != null) {
                    c.allowPluginExtras = v;
                }
            }, (c, ei) -> c.allowPluginExtras).add()
            .append(new KeyedCodec<>("LogTailLines", Codec.INTEGER), (c, v, ei) -> {
                if (v != null) {
                    c.logTailLines = v;
                }
            }, (c, ei) -> c.logTailLines).add()
            .append(new KeyedCodec<>("IncludeServerLog", Codec.BOOLEAN), (c, v, ei) -> {
                if (v != null) {
                    c.includeServerLog = v;
                }
            }, (c, ei) -> c.includeServerLog).add()
            .append(new KeyedCodec<>("IncludeServerConfig", Codec.BOOLEAN), (c, v, ei) -> {
                if (v != null) {
                    c.includeServerConfig = v;
                }
            }, (c, ei) -> c.includeServerConfig).add()
            .append(new KeyedCodec<>("IncludeModConfigs", Codec.BOOLEAN), (c, v, ei) -> {
                if (v != null) {
                    c.includeModConfigs = v;
                }
            }, (c, ei) -> c.includeModConfigs).add()
            .append(new KeyedCodec<>("AllowModConfigOptIn", Codec.BOOLEAN), (c, v, ei) -> {
                if (v != null) {
                    c.allowModConfigOptIn = v;
                }
            }, (c, ei) -> c.allowModConfigOptIn).add()
            .append(new KeyedCodec<>("RedactPatterns", Codec.STRING_ARRAY), (c, v, ei) -> {
                if (v != null) {
                    c.redactPatterns = Arrays.stream(v).filter(Objects::nonNull).toList();
                }
            }, (c, ei) -> c.redactPatterns.toArray(new String[0])).add()
            .append(new KeyedCodec<>("Artifacts", Codec.STRING_ARRAY), (c, v, ei) -> {
                if (v != null) {
                    c.artifacts = new ArrayList<>(List.of(v));
                    if (Set.copyOf(c.artifacts).equals(LEGACY_DEFAULT_CAPTURE_ARTIFACTS)) {
                        c.artifacts.add("metrics");
                    }
                }
            }, (c, ei) -> c.artifacts.toArray(new String[0])).add()
            .build();
    }

    private static final class Trigger {
        public Duration cooldown = DEFAULT_TRIGGER_COOLDOWN;
        public Duration debounce = DEFAULT_TRIGGER_DEBOUNCE;
        public List<String> cooldownExemptKinds = defaultCooldownExemptKinds();
        public long stallDegradedMs = DEFAULT_STALL_DEGRADED_MS;
        public long stallCriticalMs = DEFAULT_STALL_CRITICAL_MS;
        public long tickAvgDegradedMs = DEFAULT_TICK_AVG_DEGRADED_MS;
        public long tickAvgCriticalMs = DEFAULT_TICK_AVG_CRITICAL_MS;
        public Modules modules = new Modules();
        public int heapPressurePct = DEFAULT_DETECTORS.heapPressurePct();
        public Duration heapPressureSustain = DEFAULT_DETECTORS.heapPressureSustain();
        public int gcPressurePct = DEFAULT_DETECTORS.gcPressurePct();
        public Duration gcPressureWindow = DEFAULT_DETECTORS.gcPressureWindow();
        public int cpuSaturationPct = DEFAULT_DETECTORS.cpuSaturationPct();
        public Duration cpuSaturationSustain = DEFAULT_DETECTORS.cpuSaturationSustain();
        public long netInMbps = DEFAULT_DETECTORS.netInMbps();
        public long netOutMbps = DEFAULT_DETECTORS.netOutMbps();
        public Duration netSustain = DEFAULT_DETECTORS.netSustain();
        public int playerDropPct = DEFAULT_DETECTORS.playerDropPct();
        public Duration playerDropWindow = DEFAULT_DETECTORS.playerDropWindow();
        public int playerDropMinPlayers = DEFAULT_DETECTORS.playerDropMinPlayers();
        public boolean logErrorSkipSentry = DEFAULT_DETECTORS.logErrorSkipSentry();
        public boolean logErrorRequireThrowable = DEFAULT_DETECTORS.logErrorRequireThrowable();
        public Duration logErrorDedupeWindow = DEFAULT_DETECTORS.logErrorDedupeWindow();
        public List<String> logErrorIgnore = new ArrayList<>();

        private static List<String> defaultCooldownExemptKinds() {
            List<String> names = new ArrayList<>();
            for (TriggerKind kind : TriggerPolicy.DEFAULT_COOLDOWN_EXEMPT_KINDS) {
                names.add(kind.name());
            }
            java.util.Collections.sort(names);
            return names;
        }

        static final BuilderCodec<Trigger> CODEC = BuilderCodec
            .builder(Trigger.class, Trigger::new)
            .append(new KeyedCodec<>("Cooldown", Codec.DURATION), (c, v, ei) -> {
                if (v != null) {
                    c.cooldown = v;
                }
            }, (c, ei) -> c.cooldown).add()
            .append(new KeyedCodec<>("Debounce", Codec.DURATION), (c, v, ei) -> {
                if (v != null) {
                    c.debounce = v;
                }
            }, (c, ei) -> c.debounce).add()
            .append(new KeyedCodec<>("CooldownExemptKinds", Codec.STRING_ARRAY), (c, v, ei) -> {
                if (v != null) {
                    c.cooldownExemptKinds = new ArrayList<>(List.of(v));
                }
            }, (c, ei) -> c.cooldownExemptKinds.toArray(new String[0])).add()
            .append(new KeyedCodec<>("StallDegradedMs", Codec.LONG), (c, v, ei) -> {
                if (v != null) {
                    c.stallDegradedMs = v;
                }
            }, (c, ei) -> c.stallDegradedMs).add()
            .append(new KeyedCodec<>("StallCriticalMs", Codec.LONG), (c, v, ei) -> {
                if (v != null) {
                    c.stallCriticalMs = v;
                }
            }, (c, ei) -> c.stallCriticalMs).add()
            .append(new KeyedCodec<>("TickAvgDegradedMs", Codec.LONG), (c, v, ei) -> {
                if (v != null) {
                    c.tickAvgDegradedMs = v;
                }
            }, (c, ei) -> c.tickAvgDegradedMs).add()
            .append(new KeyedCodec<>("TickAvgCriticalMs", Codec.LONG), (c, v, ei) -> {
                if (v != null) {
                    c.tickAvgCriticalMs = v;
                }
            }, (c, ei) -> c.tickAvgCriticalMs).add()
            .append(new KeyedCodec<>("Modules", Modules.CODEC), (c, v, ei) -> {
                if (v != null) {
                    c.modules = v;
                }
            }, (c, ei) -> c.modules).add()
            .append(new KeyedCodec<>("HeapPressurePct", Codec.INTEGER), (c, v, ei) -> {
                if (v != null) {
                    c.heapPressurePct = v;
                }
            }, (c, ei) -> c.heapPressurePct).add()
            .append(new KeyedCodec<>("HeapPressureSustain", Codec.DURATION), (c, v, ei) -> {
                if (v != null) {
                    c.heapPressureSustain = v;
                }
            }, (c, ei) -> c.heapPressureSustain).add()
            .append(new KeyedCodec<>("GcPressurePct", Codec.INTEGER), (c, v, ei) -> {
                if (v != null) {
                    c.gcPressurePct = v;
                }
            }, (c, ei) -> c.gcPressurePct).add()
            .append(new KeyedCodec<>("GcPressureWindow", Codec.DURATION), (c, v, ei) -> {
                if (v != null) {
                    c.gcPressureWindow = v;
                }
            }, (c, ei) -> c.gcPressureWindow).add()
            .append(new KeyedCodec<>("CpuSaturationPct", Codec.INTEGER), (c, v, ei) -> {
                if (v != null) {
                    c.cpuSaturationPct = v;
                }
            }, (c, ei) -> c.cpuSaturationPct).add()
            .append(new KeyedCodec<>("CpuSaturationSustain", Codec.DURATION), (c, v, ei) -> {
                if (v != null) {
                    c.cpuSaturationSustain = v;
                }
            }, (c, ei) -> c.cpuSaturationSustain).add()
            .append(new KeyedCodec<>("NetInMbps", Codec.LONG), (c, v, ei) -> {
                if (v != null) {
                    c.netInMbps = v;
                }
            }, (c, ei) -> c.netInMbps).add()
            .append(new KeyedCodec<>("NetOutMbps", Codec.LONG), (c, v, ei) -> {
                if (v != null) {
                    c.netOutMbps = v;
                }
            }, (c, ei) -> c.netOutMbps).add()
            .append(new KeyedCodec<>("NetSustain", Codec.DURATION), (c, v, ei) -> {
                if (v != null) {
                    c.netSustain = v;
                }
            }, (c, ei) -> c.netSustain).add()
            .append(new KeyedCodec<>("PlayerDropPct", Codec.INTEGER), (c, v, ei) -> {
                if (v != null) {
                    c.playerDropPct = v;
                }
            }, (c, ei) -> c.playerDropPct).add()
            .append(new KeyedCodec<>("PlayerDropWindow", Codec.DURATION), (c, v, ei) -> {
                if (v != null) {
                    c.playerDropWindow = v;
                }
            }, (c, ei) -> c.playerDropWindow).add()
            .append(new KeyedCodec<>("PlayerDropMinPlayers", Codec.INTEGER), (c, v, ei) -> {
                if (v != null) {
                    c.playerDropMinPlayers = v;
                }
            }, (c, ei) -> c.playerDropMinPlayers).add()
            .append(new KeyedCodec<>("LogErrorSkipSentry", Codec.BOOLEAN), (c, v, ei) -> {
                if (v != null) {
                    c.logErrorSkipSentry = v;
                }
            }, (c, ei) -> c.logErrorSkipSentry).add()
            .append(new KeyedCodec<>("LogErrorRequireThrowable", Codec.BOOLEAN), (c, v, ei) -> {
                if (v != null) {
                    c.logErrorRequireThrowable = v;
                }
            }, (c, ei) -> c.logErrorRequireThrowable).add()
            .append(new KeyedCodec<>("LogErrorDedupeWindow", Codec.DURATION), (c, v, ei) -> {
                if (v != null) {
                    c.logErrorDedupeWindow = v;
                }
            }, (c, ei) -> c.logErrorDedupeWindow).add()
            .append(new KeyedCodec<>("LogErrorIgnore", Codec.STRING_ARRAY), (c, v, ei) -> {
                if (v != null) {
                    c.logErrorIgnore = new ArrayList<>(List.of(v));
                }
            }, (c, ei) -> c.logErrorIgnore.toArray(new String[0])).add()
            .build();
    }

    private static final class Modules {
        public boolean heartbeatStall = true;
        public boolean tickDegraded = true;
        public boolean deadlock = true;
        public boolean heapPressure = true;
        public boolean gcPressure = true;
        public boolean cpuSaturation = true;
        public boolean netSaturation = true;
        public boolean playerDrop = true;
        public boolean logError = false;

        static final BuilderCodec<Modules> CODEC = BuilderCodec
            .builder(Modules.class, Modules::new)
            .append(new KeyedCodec<>("HeartbeatStall", Codec.BOOLEAN), (c, v, ei) -> {
                if (v != null) {
                    c.heartbeatStall = v;
                }
            }, (c, ei) -> c.heartbeatStall).add()
            .append(new KeyedCodec<>("TickDegraded", Codec.BOOLEAN), (c, v, ei) -> {
                if (v != null) {
                    c.tickDegraded = v;
                }
            }, (c, ei) -> c.tickDegraded).add()
            .append(new KeyedCodec<>("Deadlock", Codec.BOOLEAN), (c, v, ei) -> {
                if (v != null) {
                    c.deadlock = v;
                }
            }, (c, ei) -> c.deadlock).add()
            .append(new KeyedCodec<>("HeapPressure", Codec.BOOLEAN), (c, v, ei) -> {
                if (v != null) {
                    c.heapPressure = v;
                }
            }, (c, ei) -> c.heapPressure).add()
            .append(new KeyedCodec<>("GcPressure", Codec.BOOLEAN), (c, v, ei) -> {
                if (v != null) {
                    c.gcPressure = v;
                }
            }, (c, ei) -> c.gcPressure).add()
            .append(new KeyedCodec<>("CpuSaturation", Codec.BOOLEAN), (c, v, ei) -> {
                if (v != null) {
                    c.cpuSaturation = v;
                }
            }, (c, ei) -> c.cpuSaturation).add()
            .append(new KeyedCodec<>("NetSaturation", Codec.BOOLEAN), (c, v, ei) -> {
                if (v != null) {
                    c.netSaturation = v;
                }
            }, (c, ei) -> c.netSaturation).add()
            .append(new KeyedCodec<>("PlayerDrop", Codec.BOOLEAN), (c, v, ei) -> {
                if (v != null) {
                    c.playerDrop = v;
                }
            }, (c, ei) -> c.playerDrop).add()
            .append(new KeyedCodec<>("LogError", Codec.BOOLEAN), (c, v, ei) -> {
                if (v != null) {
                    c.logError = v;
                }
            }, (c, ei) -> c.logError).add()
            .build();
    }

    private static final class Metrics {
        public boolean enabled = DEFAULT_METRICS_ENABLED;
        public int retentionDays = DEFAULT_METRICS_RETENTION_DAYS;
        public boolean cpu = DEFAULT_METRICS_CPU;
        public boolean allocation = DEFAULT_METRICS_ALLOCATION;
        public Prometheus prometheus = new Prometheus();

        static final BuilderCodec<Metrics> CODEC = BuilderCodec
            .builder(Metrics.class, Metrics::new)
            .append(new KeyedCodec<>("Enabled", Codec.BOOLEAN), (c, v, ei) -> {
                if (v != null) {
                    c.enabled = v;
                }
            }, (c, ei) -> c.enabled).add()
            .append(new KeyedCodec<>("RetentionDays", Codec.INTEGER), (c, v, ei) -> {
                if (v != null) {
                    c.retentionDays = v;
                }
            }, (c, ei) -> c.retentionDays).add()
            .append(new KeyedCodec<>("Cpu", Codec.BOOLEAN), (c, v, ei) -> {
                if (v != null) {
                    c.cpu = v;
                }
            }, (c, ei) -> c.cpu).add()
            .append(new KeyedCodec<>("Allocation", Codec.BOOLEAN), (c, v, ei) -> {
                if (v != null) {
                    c.allocation = v;
                }
            }, (c, ei) -> c.allocation).add()
            .append(new KeyedCodec<>("Prometheus", Prometheus.CODEC), (c, v, ei) -> {
                if (v != null) {
                    c.prometheus = v;
                }
            }, (c, ei) -> c.prometheus).add()
            .build();
    }

    private static final class Prometheus {
        public boolean enabled = DEFAULT_PROMETHEUS_ENABLED;
        public int port = DEFAULT_PROMETHEUS_PORT;
        public String bind = DEFAULT_PROMETHEUS_BIND;

        static final BuilderCodec<Prometheus> CODEC = BuilderCodec
            .builder(Prometheus.class, Prometheus::new)
            .append(new KeyedCodec<>("Enabled", Codec.BOOLEAN), (c, v, ei) -> {
                if (v != null) {
                    c.enabled = v;
                }
            }, (c, ei) -> c.enabled).add()
            .append(new KeyedCodec<>("Port", Codec.INTEGER), (c, v, ei) -> {
                if (v != null) {
                    c.port = v;
                }
            }, (c, ei) -> c.port).add()
            .append(new KeyedCodec<>("Bind", Codec.STRING), (c, v, ei) -> {
                if (v != null) {
                    c.bind = v;
                }
            }, (c, ei) -> c.bind).add()
            .build();
    }

    private static final class Retention {
        public int maxCount = DEFAULT_RETENTION_MAX_COUNT;
        public long maxTotalBytes = DEFAULT_RETENTION_MAX_TOTAL_BYTES;
        public Duration maxAge = DEFAULT_RETENTION_MAX_AGE;
        public int failedRecordingMaxCount = RetentionPolicy.DEFAULT_FAILED_RECORDING_MAX_COUNT;
        public long stagingMaxTotalBytes = RetentionPolicy.DEFAULT_STAGING_MAX_TOTAL_BYTES;
        public Duration stagingMaxAge = RetentionPolicy.DEFAULT_STAGING_MAX_AGE;
        public long repositoryMaxTotalBytes = RetentionPolicy.DEFAULT_REPOSITORY_MAX_TOTAL_BYTES;
        public Duration repositoryMaxAge = RetentionPolicy.DEFAULT_REPOSITORY_MAX_AGE;

        static final BuilderCodec<Retention> CODEC = BuilderCodec
            .builder(Retention.class, Retention::new)
            .append(new KeyedCodec<>("MaxCount", Codec.INTEGER), (c, v, ei) -> {
                if (v != null) {
                    c.maxCount = v;
                }
            }, (c, ei) -> c.maxCount).add()
            .append(new KeyedCodec<>("FailedRecordingMaxCount", Codec.INTEGER), (c, v, ei) -> {
                if (v != null) {
                    c.failedRecordingMaxCount = v;
                }
            }, (c, ei) -> c.failedRecordingMaxCount).add()
            .append(new KeyedCodec<>("MaxTotalBytes", Codec.LONG), (c, v, ei) -> {
                if (v != null) {
                    c.maxTotalBytes = v;
                }
            }, (c, ei) -> c.maxTotalBytes).add()
            .append(new KeyedCodec<>("MaxAge", Codec.DURATION),
                (c, v, ei) -> c.maxAge = v, (c, ei) -> c.maxAge).add()
            .append(new KeyedCodec<>("StagingMaxTotalBytes", Codec.LONG), (c, v, ei) -> {
                if (v != null) {
                    c.stagingMaxTotalBytes = v;
                }
            }, (c, ei) -> c.stagingMaxTotalBytes).add()
            .append(new KeyedCodec<>("StagingMaxAge", Codec.DURATION),
                (c, v, ei) -> c.stagingMaxAge = v, (c, ei) -> c.stagingMaxAge).add()
            .append(new KeyedCodec<>("RepositoryMaxTotalBytes", Codec.LONG), (c, v, ei) -> {
                if (v != null) {
                    c.repositoryMaxTotalBytes = v;
                }
            }, (c, ei) -> c.repositoryMaxTotalBytes).add()
            .append(new KeyedCodec<>("RepositoryMaxAge", Codec.DURATION),
                (c, v, ei) -> c.repositoryMaxAge = v, (c, ei) -> c.repositoryMaxAge).add()
            .build();
    }

    private static final class Discord {
        public String webhookUrl = DEFAULT_DISCORD_WEBHOOK_URL;
        public Duration cooldown = DEFAULT_DISCORD_COOLDOWN;
        public Duration requestTimeout = DEFAULT_DISCORD_REQUEST_TIMEOUT;
        public String username = DEFAULT_DISCORD_USERNAME;

        static final BuilderCodec<Discord> CODEC = BuilderCodec
            .builder(Discord.class, Discord::new)
            .append(new KeyedCodec<>("WebhookUrl", Codec.STRING), (c, v, ei) -> {
                if (v != null) {
                    c.webhookUrl = v;
                }
            }, (c, ei) -> c.webhookUrl).add()
            .append(new KeyedCodec<>("Cooldown", Codec.DURATION), (c, v, ei) -> {
                if (v != null) {
                    c.cooldown = v;
                }
            }, (c, ei) -> c.cooldown).add()
            .append(new KeyedCodec<>("RequestTimeout", Codec.DURATION), (c, v, ei) -> {
                if (v != null) {
                    c.requestTimeout = v;
                }
            }, (c, ei) -> c.requestTimeout).add()
            .append(new KeyedCodec<>("Username", Codec.STRING), (c, v, ei) -> {
                if (v != null) {
                    c.username = v;
                }
            }, (c, ei) -> c.username).add()
            .build();
    }

}
