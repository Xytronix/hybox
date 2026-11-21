package io.github.xytronix.hybox.hytale;

import io.github.xytronix.hybox.core.metrics.HealthGauges;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;
import java.util.function.Function;

final class HyboxPlaceholders implements Function<String, String> {
    static final List<String> PLACEHOLDERS = List.of("%hybox_tps%", "%hybox_tps_<world>%", "%hybox_mspt%",
        "%hybox_mspt_<world>%", "%hybox_status%", "%hybox_heap%", "%hybox_last_incident%", "%hybox_incidents_today%");
    private static final String WAITING = "Waiting";
    private static final double GIB = 1024.0 * 1024 * 1024;

    interface Source {
        HytaleTelemetrySampler.Status status();

        List<HealthGauges.World> worlds();

        long[] incidentTimes();
    }

    private final Source source;
    private final Clock clock;
    private volatile Values values;
    private volatile Day day;

    HyboxPlaceholders(Source source, Clock clock) {
        this.source = Objects.requireNonNull(source, "source");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    static HyboxPlaceholders of(HyboxRuntime runtime, Clock clock) {
        return new HyboxPlaceholders(new Source() {
            @Override
            public HytaleTelemetrySampler.Status status() {
                return runtime.liveStatus();
            }

            @Override
            public List<HealthGauges.World> worlds() {
                return runtime.liveWorlds();
            }

            @Override
            public long[] incidentTimes() {
                return runtime.incidentTimes();
            }
        }, clock);
    }

    @Override
    public String apply(String params) {
        if (params == null) return null;
        return switch (params) {
            case "tps" -> values().tps;
            case "mspt" -> values().mspt;
            case "status" -> values().status;
            case "heap" -> values().heap;
            case "last_incident" -> lastIncident();
            case "incidents_today" -> Integer.toString(incidentsToday());
            default -> params.startsWith("tps_") ? values().world(params, 4, true)
                : params.startsWith("mspt_") ? values().world(params, 5, false) : null;
        };
    }

    private Values values() {
        HytaleTelemetrySampler.Status status = source.status();
        List<HealthGauges.World> worlds = source.worlds();
        Values current = values;
        if (current == null || current.sample != status || current.worlds != worlds) {
            current = new Values(status, worlds == null ? List.of() : worlds);
            values = current;
        }
        return current;
    }

    private String lastIncident() {
        long[] times = source.incidentTimes();
        if (times == null || times.length == 0) return "none";
        return HyboxChatFormatter.age(Instant.ofEpochMilli(times[times.length - 1]), clock.instant());
    }

    private int incidentsToday() {
        long[] times = source.incidentTimes();
        if (times == null || times.length == 0) return 0;
        long now = clock.millis();
        Day today = day;
        if (today == null || now < today.start || now >= today.end) {
            LocalDate date = LocalDate.ofInstant(Instant.ofEpochMilli(now), clock.getZone());
            today = new Day(date.atStartOfDay(clock.getZone()).toInstant().toEpochMilli(),
                date.plusDays(1).atStartOfDay(clock.getZone()).toInstant().toEpochMilli());
            day = today;
        }
        int low = 0;
        int high = times.length;
        while (low < high) {
            int mid = (low + high) >>> 1;
            if (times[mid] < today.start) low = mid + 1;
            else high = mid;
        }
        return times.length - low;
    }

    private record Day(long start, long end) {}

    private static final class Values {
        private final HytaleTelemetrySampler.Status sample;
        private final List<HealthGauges.World> worlds;
        private final String tps;
        private final String mspt;
        private final String status;
        private final String heap;
        private final String[] worldTps;
        private final String[] worldMspt;

        private Values(HytaleTelemetrySampler.Status sample, List<HealthGauges.World> worlds) {
            this.sample = sample;
            this.worlds = worlds;
            boolean ticking = sample != null && sample.tps() >= 0;
            tps = ticking ? DashboardModel.oneDecimal(sample.tps()) : WAITING;
            mspt = sample != null && sample.tickMs() >= 0 ? DashboardModel.oneDecimal(sample.tickMs()) : WAITING;
            status = ticking ? label(DashboardModel.tpsTone(sample.tps(), DashboardModel.targetTps(sample))) : WAITING;
            heap = sample == null || sample.heapUsed() < 0 ? WAITING
                : sample.heapMax() > 0 ? Math.round(sample.heapUsed() * 100.0 / sample.heapMax()) + "%"
                : DashboardModel.oneDecimal(sample.heapUsed() / GIB) + " GiB";
            worldTps = new String[worlds.size()];
            worldMspt = new String[worlds.size()];
            for (int i = 0; i < worlds.size(); i++) {
                HealthGauges.World world = worlds.get(i);
                worldTps[i] = world.tps() >= 0 ? DashboardModel.oneDecimal(world.tps()) : WAITING;
                worldMspt[i] = world.tickMs() >= 0 ? DashboardModel.oneDecimal(world.tickMs()) : WAITING;
            }
        }

        private String world(String params, int offset, boolean tps) {
            int length = params.length() - offset;
            for (int i = 0; i < worlds.size(); i++) {
                String name = worlds.get(i).name();
                if (name != null && name.length() == length && params.regionMatches(true, offset, name, 0, length)) {
                    return tps ? worldTps[i] : worldMspt[i];
                }
            }
            return null;
        }

        private static String label(DashboardModel.Tone tone) {
            return switch (tone) {
                case ERROR -> "Critical";
                case WARN -> "Degraded";
                default -> "OK";
            };
        }
    }
}
