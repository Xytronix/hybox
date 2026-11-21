package io.github.xytronix.hybox.hytale;

import static io.github.xytronix.hybox.hytale.IncidentModel.array;
import static io.github.xytronix.hybox.hytale.IncidentModel.at;
import static io.github.xytronix.hybox.hytale.IncidentModel.number;
import static io.github.xytronix.hybox.hytale.IncidentModel.object;
import static io.github.xytronix.hybox.hytale.IncidentModel.text;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.hypixel.hytale.protocol.packets.interface_.CustomUIEventBindingType;
import com.hypixel.hytale.server.core.ui.builder.EventData;
import com.hypixel.hytale.server.core.ui.builder.UICommandBuilder;
import com.hypixel.hytale.server.core.ui.builder.UIEventBuilder;
import io.github.xytronix.hybox.core.health.StalledThread;
import io.github.xytronix.hybox.core.metrics.ChartAxis;
import io.github.xytronix.hybox.core.report.CompareMetrics;
import io.github.xytronix.hybox.core.report.JavaRuntime;
import io.github.xytronix.hybox.core.report.ModNames;
import io.github.xytronix.hybox.core.report.WorldNames;
import io.github.xytronix.hybox.core.text.Units;
import io.github.xytronix.hybox.core.trigger.TriggerNames;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class HyboxIncidentRenderer {
    record ViewState(String sectionId, int pageIndex, String flameZoomPath, String timeRangeId, boolean showCore) {
        ViewState {
            sectionId = sectionId != null && SECTIONS.containsKey(sectionId) ? sectionId : "top";
            pageIndex = Math.max(0, pageIndex);
            flameZoomPath = flameZoomPath == null ? "" : flameZoomPath;
            if (timeRangeId == null || !(Set.of("incident", "profile", "1m", "5m", "all").contains(timeRangeId) || timeRangeId.matches("d[0-9]+"))) timeRangeId = "all";
        }
        static ViewState initial() { return new ViewState("top", 0, "", "all", false); }
    }
    static final String NAV_HEADING = "#";
    static final Map<String, String> SECTIONS;
    static final List<String> NAV;
    private static final Map<String, List<String>> GROUPS;
    static {
        Map<String, String> sections = new LinkedHashMap<>();
        sections.put("top", "Overview"); sections.put("diagnosis", "Diagnosis"); sections.put("stalled", "Stalled thread");
        sections.put("metrics", "Key metrics"); sections.put("cpu", "CPU & load"); sections.put("engine", "Engine systems");
        sections.put("methods", "Hot methods"); sections.put("flame", "Flame graph"); sections.put("threads", "Hot threads");
        sections.put("states", "Thread states"); sections.put("memory", "Memory & GC"); sections.put("network", "Network");
        sections.put("pluginmetrics", "Plugin metrics"); sections.put("worlds", "Worlds"); sections.put("system", "System & hardware");
        sections.put("plugins", "Plugins"); sections.put("mixins", "Mixins");
        SECTIONS = Collections.unmodifiableMap(sections);
        Map<String, List<String>> groups = new LinkedHashMap<>();
        groups.put("Incident", List.of("top", "diagnosis", "stalled"));
        groups.put("Evidence", List.of("metrics", "cpu", "engine", "methods", "flame", "threads", "states", "memory", "network", "pluginmetrics"));
        groups.put("Reference", List.of("worlds", "system", "plugins", "mixins"));
        GROUPS = Collections.unmodifiableMap(groups);
        List<String> nav = new ArrayList<>();
        groups.forEach((heading, ids) -> { nav.add(NAV_HEADING + heading); nav.addAll(ids); });
        NAV = List.copyOf(nav);
    }
    private static final int PAGE_COST = 20;
    private static final int CHART_COLUMNS = 120;
    private static final int PLOT_WIDTH = 940;
    private static final int CHART_WIDTH = 880;
    private static final String PLUGIN_METRIC_KEY = "pluginMetric:";
    private static final Pattern EVENT_TIMES = Pattern.compile(" \u00d7(\\d+)$");
    private static final Pattern THREAD_ID = Pattern.compile("(.*-)(?=[0-9a-fA-F]*[0-9])[0-9a-fA-F]{12,}");
    private static final int OVERVIEW_FINDINGS = 3;
    private static final int OTHER_LINES = 5;
    private static final int STALLED_THREADS = 3;
    private static final int STUCK_THREADS = 5;
    private static final int STACK_FRAMES = 25;
    private static final int HEARTBEATS = 3;
    private static final int CPU_WORLDS = 3;
    private static final int CPU_WORLD_ROWS = 5;
    private static final int CPU_MODS = 5;
    private static final int CPU_SUBSYSTEMS = 5;
    private static final int ENGINE_SYSTEMS = 8;
    private static final int HOT_METHODS = 6;
    private static final int FLAME_CHILDREN = 8;
    private static final int HOT_THREADS = 6;
    private static final int THREAD_POOLS = 3;
    private static final int OTHER_INTERFACES = 2;
    private static final int PLUGIN_CHARTS = 2;
    private static final int PLUGIN_ROWS = 8;
    private static final int WORLD_CARDS = 3;
    private static final int OTHER_WORLDS = 5;
    private static final int WORLD_PLAYERS = 5;
    private static final int ENTITY_TYPES = 6;
    private static final int MIXIN_ROWS = 10;
    private static final int TIP_CALLERS = 5;
    private static final int ROW_CHARS = 1800;
    private static final int KEY_TIP_CHARS = 34;
    private static final int THREAD_TIP_LINES = 6;
    private static final int THREAD_ID_SHOWN = 8;
    private static final String NO_WORLD = "Not tied to one world";
    private static final String LEGACY_NO_WORLD = "(shared)";
    private static final String WORLD_THREAD = "WorldThread - ";
    private static final Pattern HEARTBEAT_AGE = Pattern.compile("(\\d+) ms ago");
    private static final Pattern HEARTBEAT_SUMMARY = Pattern.compile("on time, the oldest heartbeat was (\\d+) ms old");
    private static final Set<String> NOT_IN_GAME = Set.of("Lock waits", "Slow file operations", "Server log");
    private static final double DISK_MIB = 1e6 / 1048576;
    private static final Pattern MIXIN_HANDLER = Pattern.compile("^((?:handler|redirect|modify|args|constant|localvar|wrapOperation|wrapMethod"
        + "|modifyExpressionValue|modifyReceiver|modifyReturnValue|wrapWithCondition)|md[0-9a-f]+)\\$.*\\$([^$]+)$");

    private HyboxIncidentRenderer() {}

    record ChartRequest(java.util.function.Supplier<byte[]> draw, java.util.function.BiConsumer<String, UICommandBuilder> apply) {}
    interface ChartImages { void request(ChartRequest request); }

    static void render(IncidentModel model, ViewState state, UICommandBuilder cmd, UIEventBuilder ev, ChartImages images) {
        render(model, state, cmd, ev, images, true);
    }

    static void render(IncidentModel model, ViewState state, UICommandBuilder cmd, UIEventBuilder ev, ChartImages images, boolean freshScroll) {
        header(model, cmd);
        for (int index = 0; index < NAV.size(); index++) {
            String entry = NAV.get(index), selector = "#SectionNav[" + index + "]";
            if (entry.startsWith(NAV_HEADING)) {
                String heading = entry.substring(NAV_HEADING.length());
                cmd.set(selector + ".Text", heading);
                cmd.set(selector + ".Visible", GROUPS.get(heading).stream().anyMatch(id -> id.equals(state.sectionId()) || hasContent(model, id)));
                continue;
            }
            boolean selected = entry.equals(state.sectionId());
            cmd.set(selector + ".Text", (selected ? "› " : "") + sectionLabel(model, entry) + (entry.equals("mixins") ? mixinProblemSuffix(model) : ""));
            cmd.set(selector + ".Style", com.hypixel.hytale.server.core.ui.Value.ref("Pages/HyboxIncidentButton.ui", selected ? "SelectedStyle" : "DefaultStyle"));
            bind(ev, selector, "section", entry);
            cmd.set(selector + ".Visible", selected || hasContent(model, entry));
        }
        cmd.set("#SectionTitle.Text", sectionLabel(model, state.sectionId()));
        boolean timeSeriesSection = Set.of("metrics", "memory", "pluginmetrics", "network", "threads").contains(state.sectionId());
        boolean whole = state.timeRangeId().equals("all");
        cmd.set("#RecordingBadge.Text", timeSeriesSection ? (whole ? "Whole recording" : rangeScope(state.timeRangeId()))
            : whole ? "" : "Whole recording");
        cmd.set("#RangeControls.Visible", timeSeriesSection);
        cmd.clear("#RangeControls");
        if (timeSeriesSection) {
            int rangeIndex = 0;
            for (var preset : presets(model).entrySet()) {
                boolean selected = state.timeRangeId().equals(preset.getKey());
                cmd.append("#RangeControls", selected ? "Pages/HyboxIncidentRangeSelected.ui" : "Pages/HyboxIncidentRange.ui");
                String rangeSelector = "#RangeControls[" + rangeIndex++ + "]";
                cmd.set(rangeSelector + " #RangeLabel.Text", preset.getValue());
                bind(ev, rangeSelector, "range", preset.getKey());
            }
        }
        bind(ev, "#PagePrev", "page", Integer.toString(Math.max(0, state.pageIndex() - 1)));
        bind(ev, "#PageNext", "page", Integer.toString(state.pageIndex() + 1));
        if (freshScroll) {
            cmd.clear("#SectionHost");
            cmd.append("#SectionHost", "Pages/HyboxIncidentScroll.ui");
        } else {
            cmd.clear("#Tiles"); cmd.clear("#SectionBody");
        }
        Plan plan = plan(model, state);
        List<List<Block>> pages = pages(plan, state.sectionId());
        int pageIndex = Math.min(state.pageIndex(), pages.size() - 1);
        List<Block> visible = pages.get(pageIndex);
        cmd.set("#Pagination.Visible", pages.size() > 1);
        cmd.set("#PageStatus.Text", pages.size() > 1 ? "Page " + (pageIndex + 1) + " of " + pages.size() : "");
        cmd.set("#PagePrev.Disabled", pageIndex == 0);
        cmd.set("#PageNext.Disabled", pageIndex + 1 >= pages.size());
        if (state.sectionId().equals("top")) overviewTiles(model, cmd);
        int[] budget = budget(visible);
        int columns = budget[0], hoverCells = budget[1];
        String parent = "#SectionBody";
        int child = 0;
        int row = 0;
        Set<String> cardTargets = new HashSet<>();
        for (Block block : visible) {
            if (block.kind == Kind.CARD) {
                cmd.append("#SectionBody", "Pages/HyboxIncidentCard.ui");
                String cardSelector = "#SectionBody[" + row++ + "]";
                if (block.label.isEmpty()) cmd.set(cardSelector + " #CardTitle.Visible", false);
                else cmd.set(cardSelector + " #CardTitle.Text", block.label);
                parent = cardSelector + " #CardRows";
                child = 0;
                cardTargets.clear();
                continue;
            }
            if (parent.equals("#SectionBody")) {
                cmd.append("#SectionBody", "Pages/HyboxIncidentCard.ui");
                String cardSelector = "#SectionBody[" + row++ + "]";
                cmd.set(cardSelector + " #CardTitle.Visible", false);
                parent = cardSelector + " #CardRows";
                child = 0;
            }
            String selector = parent + "[" + child++ + "]";
            switch (block.kind) {
                case CHART -> chart(model, state, block, parent, selector, columns, hoverCells, images, cmd);
                case THREAD -> {
                    cmd.append(parent, "Pages/HyboxIncidentThread.ui");
                    JsonArray thread = array(block.data);
                    String name = block.label;
                    cmd.set(selector + " #Rank.Text", "#" + block.arg);
                    cmd.set(selector + " #Name.Text", threadLabel(name));
                    cmd.set(selector + " #Name.TooltipText", name);
                    cmd.set(selector + " #Samples.Text", count(number(at(thread, 0), 0), "sample"));
                    cmd.set(selector + " #Self.Text", percent(number(at(thread, 0), 0), number(model.object("incident").get("samples"), 0)) + " self");
                    cmd.set(selector + " #Share.Value", Math.max(0, Math.min(1, block.share)));
                    String topLabel = opt(at(thread, 5));
                    cmd.set(selector + " #Top.Text", "Top method · " + text(at(thread, 3)) + (topLabel.isEmpty() ? "" : " · " + topLabel));
                    cmd.set(selector + " #Top.TooltipText", block.value.isEmpty() ? text(at(thread, 3)) + (topLabel.isEmpty() ? "" : "\n" + topLabel) : firstLines(block.value, THREAD_TIP_LINES));
                    JsonArray series = block.timeline;
                    boolean hasTimeline = series != null && !series.isEmpty();
                    cmd.set(selector + " #Strip.Visible", series != null);
                    if (series != null && series.isEmpty()) HyboxChartRenderer.hover(cmd, selector + " #StripHover", PLOT_WIDTH, 12, List.of("No timeline saved for this thread"));
                    if (hasTimeline) {
                        int[] selected = range(model, state, series.size());
                        List<String> tips = stateTips(model, series, selected, hoverCells);
                        HyboxChartRenderer.hover(cmd, selector + " #StripHover", PLOT_WIDTH, 12, block.action.isEmpty() ? tips : tips.stream().map(tip -> "All " + block.action + " threads · " + tip).toList());
                        int stripColumns = Math.min(columns, selected[1] - selected[0]);
                        if (images != null) {
                            int[] codes = stateCodes(series, selected);
                            images.request(new ChartRequest(() -> HyboxChartImage.states(codes, PLOT_WIDTH, 12), (path, swap) -> {
                                swap.clear(selector + " #StripBars");
                                swap.set(selector + " #StripImage.AssetPath", path);
                                swap.set(selector + " #StripImage.Visible", true);
                            }));
                        }
                        int[] counts = new int[5];
                        int runStart = 0, previous = -1;
                        for (int column = 0; column <= stripColumns; column++) {
                            int value = -1;
                            if (column < stripColumns) {
                                java.util.Arrays.fill(counts, 0);
                                int from = selected[0] + column * (selected[1] - selected[0]) / stripColumns;
                                int to = selected[0] + (column + 1) * (selected[1] - selected[0]) / stripColumns;
                                for (int sample = from; sample < to; sample++) counts[Math.max(0, Math.min(4, (int) number(series.get(sample), 0)))]++;
                                value = 0;
                                for (int candidate = 1; candidate < counts.length; candidate++) if (counts[candidate] > counts[value]) value = candidate;
                            }
                            if (column > 0 && value != previous && previous > 0) {
                                int left = runStart * PLOT_WIDTH / stripColumns, width = column * PLOT_WIDTH / stripColumns - left;
                                cmd.appendInline(selector + " #StripBars", "Group { Anchor: (Left: " + left + ", Top: 0, Width: " + width + ", Height: 12); Background: " + stateColor(previous) + "; }");
                            }
                            if (value != previous) { runStart = column; previous = value; }
                        }
                    }
                }
                case THREAD_AXIS -> {
                    cmd.append(parent, "Pages/HyboxIncidentThreadAxis.ui");
                    int size = array(block.data).size();
                    axes(model, selector, range(model, state, size), size, cmd);
                }
                case FLAME -> flame(model, state, parent, selector, images, cmd, ev);
                case PARAGRAPH -> {
                    cmd.append(parent, "Pages/HyboxIncidentParagraph.ui");
                    if (block.data.isJsonArray()) cmd.set(selector + " #Paragraph.TextSpans", styledSpans(block.data));
                    else cmd.set(selector + " #Paragraph.Text", block.value);
                }
                case LEGEND -> cmd.append(parent, block.arg.equals("flame") ? "Pages/HyboxIncidentFlameLegend.ui" : "Pages/HyboxIncidentThreadLegend.ui");
                case EVIDENCE -> {
                    cmd.append(parent, "Pages/HyboxIncidentEvidence.ui");
                    JsonObject entry = object(block.data);
                    String label = opt(entry.get("label")), target = text(entry, "section");
                    boolean linked = SECTIONS.containsKey(target) && hasContent(model, target) && cardTargets.add(target);
                    JsonArray spans = array(entry.get("spans")).deepCopy();
                    if (linked) dropSectionName(spans, List.of(label, sectionLabel(model, target)));
                    cmd.set(selector + " #Evidence.TextSpans", styledSpans(spans));
                    String tip = lineTip(model, entry);
                    if (!tip.isEmpty()) cmd.set(selector + " #Evidence.TooltipText", tip);
                    cmd.set(selector + " #EvidenceLink.Visible", linked);
                    if (linked) {
                        String caption = NOT_IN_GAME.contains(label) || label.isEmpty() ? sectionLabel(model, target) : Character.toUpperCase(label.charAt(0)) + label.substring(1);
                        cmd.set(selector + " #EvidenceLink.Text", screen(caption));
                        cmd.set(selector + " #EvidenceLink.TooltipText", "Go to " + sectionLabel(model, target));
                        bind(ev, selector + " #EvidenceLink", "section", target);
                    }
                }
                case COMPARE -> {
                    cmd.append(parent, block.action.equals("header") ? "Pages/HyboxIncidentCompareHead.ui" : "Pages/HyboxIncidentCompare.ui");
                    if (block.action.equals("header")) cmd.set(selector + " #WindowHead.Text", block.arg);
                    else {
                        JsonArray cells = array(block.data);
                        cmd.set(selector + " #Metric.Text", block.label);
                        if (!block.arg.isEmpty()) cmd.set(selector + " #Metric.TooltipText", block.arg);
                        cmd.set(selector + " #Baseline.Text", text(at(cells, 0)));
                        cmd.set(selector + " #Incident.Text", text(at(cells, 1)));
                        cmd.set(selector + " #Change.Text", text(at(cells, 2)));
                        if (block.action.equals("worse")) cmd.set(selector + " #Change.Style.TextColor", DashboardModel.Tone.WARN.color);
                    }
                }
                case TABLE -> {
                    cmd.append(parent, "Pages/HyboxIncidentTable.ui");
                    JsonObject cells = object(block.data);
                    String rank = screen(opt(cells.get("rank"))), amount = screen(text(cells, "amount")), details = screen(text(cells, "details"));
                    cmd.set(selector + " #Rank.Visible", !rank.isEmpty());
                    if (!rank.isEmpty()) cmd.set(selector + " #Rank.Text", rank);
                    cmd.set(selector + " #Name.Text", block.label);
                    String tip = screen(opt(cells.get("tip")));
                    if (!tip.isEmpty()) cmd.set(selector + " #Name.TooltipText", tip);
                    cmd.set(selector + " #Facts.Text", block.value);
                    cmd.set(selector + " #Facts.Visible", !block.value.isEmpty());
                    cmd.set(selector + " #Amount.Text", amount);
                    cmd.set(selector + " #Amount.Visible", !amount.isEmpty());
                    cmd.set(selector + " #Details.Text", details);
                    cmd.set(selector + " #Details.Visible", !details.isEmpty());
                    if (block.share >= 0) {
                        cmd.set(selector + " #Share.Visible", true);
                        cmd.set(selector + " #Share.Value", Math.min(1, block.share));
                    }
                }
                case LINK -> {
                    cmd.append(parent, "Pages/HyboxIncidentButton.ui");
                    String caption = block.value.isEmpty() ? block.label : block.label + " · " + block.value;
                    cmd.set(selector + ".Text", caption);
                    cmd.set(selector + ".TooltipText", caption);
                    bind(ev, selector, block.action, block.arg);
                }
                default -> {
                    cmd.append(parent, "Pages/HyboxIncidentRow.ui");
                    cmd.set(selector + " #Key.Text", block.label);
                    if (!block.arg.isEmpty() || block.label.length() > KEY_TIP_CHARS) cmd.set(selector + " #Key.TooltipText", block.arg.isEmpty() ? block.label : block.arg);
                    if (!block.arg.isEmpty()) cmd.set(selector + " #Value.TooltipText", block.arg);
                    cmd.set(selector + " #Value.Text", block.value);
                    if (block.data.isJsonArray()) cmd.set(selector + " #Value.TextSpans", styledSpans(block.data));
                    if (block.kind == Kind.SHARE) {
                        cmd.set(selector + " #Share.Visible", true);
                        cmd.set(selector + " #Share.Value", Math.max(0, Math.min(1, block.share)));
                    }
                }
            }
        }
    }

    static int pageCount(IncidentModel model, ViewState state) { return pages(plan(model, state), state.sectionId()).size(); }

    private static void header(IncidentModel model, UICommandBuilder cmd) {
        JsonObject incident = model.object("incident");
        String severity = text(incident, "severity");
        cmd.set("#Severity.Text", DashboardModel.severityLabel(present(incident.get("severity")) ? severity : null));
        var tone = DashboardModel.severityTone(severity);
        cmd.set("#Severity.Style.TextColor", tone.color);
        HyboxDashboardRenderer.background(cmd, "#Severity", tone, "Pill");
        String world = opt(incident.get("world"));
        String shortWorld = worldShort(world).isEmpty() ? "" : worldLabel(worldLabels(model), world);
        Long stallMs = present(incident.get("stallMs")) ? Math.round(number(incident.get("stallMs"), 0)) : null;
        cmd.set("#Headline.Text", screen(headlineFor(opt(incident.get("trigger")), shortWorld, stallMs, model.frozen(), model.headline())));
        List<String> meta = new ArrayList<>();
        meta.add(trigger(incident));
        if (!shortWorld.isEmpty()) meta.add("world " + shortWorld);
        if (present(incident.get("created"))) meta.add("captured " + text(incident, "created"));
        cmd.set("#IncidentMeta.Text", String.join(", ", meta));
        cmd.set("#IncidentMeta.TooltipText", shortWorld.isEmpty() || shortWorld.equals(world) ? "" : "World " + world);
        String blockedIn = model.blockedIn();
        boolean hasFrame = !blockedIn.equals("Not captured");
        cmd.set("#BlockedIn.Visible", hasFrame);
        cmd.set("#BlockedIn.Text", hasFrame ? StalledThread.frameVerb(stalledState(model)) + " " + blockedIn : "");
        cmd.set("#BlockedIn.TooltipText", hasFrame ? blockedIn : "");
        cmd.set("#CleanBanner.Visible", !model.cleaned());
    }

    static String headline(String trigger, String world, Long stallMs, boolean frozen, String stored) {
        return headlineFor(trigger, worldShort(world), stallMs, frozen, stored);
    }

    private static String headlineFor(String trigger, String worldLabel, Long stallMs, boolean frozen, String stored) {
        if ("HEARTBEAT_STALL".equals(trigger) && stallMs != null && !worldLabel.isEmpty()) {
            return "World " + worldLabel + " stopped responding for " + Units.elapsed(stallMs)
                + (frozen ? " and was still frozen when the bundle was saved" : "");
        }
        String flat = stored == null ? "" : stored.strip().replaceAll("\\s*\\R\\s*", " ");
        return flat.isEmpty() ? TriggerNames.label(trigger == null || trigger.isEmpty() ? null : trigger) : flat;
    }

    static String worldShort(String world) {
        if (!WorldNames.isWorld(world) || world.equals("Not captured")) return "";
        return new WorldNames(List.of(world)).label(world);
    }

    private static String stalledState(IncidentModel model) {
        for (JsonElement item : model.array("diag")) {
            JsonObject diagnostic = object(item);
            String title = text(diagnostic, "title");
            if (!title.equals("Stalled thread") && !title.equals("Deadlocked thread")) continue;
            for (JsonElement row : array(diagnostic.get("entries"))) {
                JsonArray pair = array(row);
                if (text(at(pair, 0)).equals("State")) return text(at(pair, 1));
            }
        }
        return "";
    }

    private record Vitals(String world, String tickWorld, double target, double budget, double tickAvg, double tickWorst, double tpsAvg, double tpsMin,
                          double cpuServer, double cpuMachine, double heapPeak, double heapMax) {}

    private static Vitals vitals(IncidentModel model) {
        JsonObject v = model.object("vitals"), tps = model.object("tps"), cpu = model.object("cpu"), heap = model.object("heap");
        double target = number(v.get("tpsTarget"), number(tps.get("target"), 30));
        if (!(target > 0)) target = 30;
        double budget = number(v.get("tickBudgetMs"), 1000 / target);
        String pick = opt(tps.get("pick"));
        String tickWorld = present(v.get("world")) ? text(v, "world") : opt(tps.get("world"));
        String world = !pick.isEmpty() && !pick.equals("incident") ? tickWorld : "";
        JsonObject series = model.object("series");
        double tickAvg = number(v.get("tickAvgMs"), average(array(series.get("mspt"))));
        double tickWorst = number(v.get("tickWorstMs"), Math.max(peak(array(series.get("msptWorst"))), peak(array(series.get("mspt")))));
        if (!present(v.get("tickWorstMs"))) tickWorst = Math.max(tickWorst, number(worldSnapshot(model, tickWorld).get("msptMax"), -1));
        return new Vitals(world, tickWorld, target, budget, tickAvg, tickWorst, number(v.get("tpsAvg"), number(tps.get("avg"), -1)), number(v.get("tpsMin"), number(tps.get("min"), -1)),
            number(v.get("cpuServerAvg"), number(cpu.get("jvmAvg"), -1)), number(v.get("cpuMachineAvg"), number(cpu.get("machAvg"), -1)),
            number(v.get("heapPeak"), number(heap.get("peak"), -1)), number(v.get("heapMax"), number(heap.get("max"), -1)));
    }

    private static double average(JsonArray values) {
        double sum = 0; int n = 0;
        for (JsonElement value : values) if (present(value)) { sum += number(value, 0); n++; }
        return n == 0 ? -1 : sum / n;
    }

    private static double peak(JsonArray values) {
        double max = -1;
        for (JsonElement value : values) if (present(value)) max = Math.max(max, number(value, 0));
        return max;
    }

    private static void overviewTiles(IncidentModel model, UICommandBuilder cmd) {
        JsonObject i = model.object("incident");
        int index = 0;
        if (present(i.get("stallMs"))) tile(cmd, index++, "Stall", millis(number(i.get("stallMs"), 0)), model.frozen() ? "Still frozen when saved" : trigger(i), false);
        Vitals v = vitals(model);
        String in = v.world().isEmpty() ? "" : " in " + worldLabel(worldLabels(model), v.world());
        if (v.tickAvg() >= 0 || v.tickWorst() >= 0) {
            String worst = v.tickAvg() >= 0 && v.tickWorst() >= 0 ? "worst " + millis(v.tickWorst()) + ", " : "";
            tile(cmd, index++, "Tick time" + in, v.tickAvg() >= 0 ? millis(v.tickAvg()) + " avg" : millis(v.tickWorst()) + " worst",
                worst + "budget " + decimal(v.budget(), 1) + " ms", v.tickAvg() >= v.budget());
        }
        if (v.tpsAvg() >= 0) tile(cmd, index++, "TPS" + in, decimal(v.tpsAvg(), 1) + " avg",
            (v.tpsMin() >= 0 ? "min " + decimal(v.tpsMin(), 1) + ", " : "") + "target " + (v.target() == Math.rint(v.target()) ? count(v.target()) : decimal(v.target(), 1)),
            v.tpsAvg() < 0.9 * v.target());
        if (v.cpuServer() >= 0 || v.cpuMachine() >= 0) tile(cmd, index++, "CPU", v.cpuServer() >= 0 ? decimal(v.cpuServer(), 0) + "% server" : decimal(v.cpuMachine(), 0) + "% machine",
            v.cpuServer() >= 0 && v.cpuMachine() >= 0 ? decimal(v.cpuMachine(), 0) + "% machine" : "average", v.cpuMachine() >= 90);
        if (v.heapPeak() >= 0) tile(cmd, index, "Heap", v.heapMax() > 0 ? gib(v.heapPeak()) + " of " + gib(v.heapMax()) + " GiB" : gib(v.heapPeak()) + " GiB",
            v.heapMax() > 0 ? "peak, max heap" : "peak", v.heapMax() > 0 && v.heapPeak() >= 0.9 * v.heapMax());
    }

    private static void tile(UICommandBuilder cmd, int index, String key, String value, String sub, boolean warn) {
        cmd.append("#Tiles", "Pages/HyboxIncidentTile.ui");
        String selector = "#Tiles[" + index + "]";
        cmd.set(selector + " #Key.Text", key); cmd.set(selector + " #Value.Text", value); cmd.set(selector + " #Sub.Text", sub);
        if (warn) cmd.set(selector + " #Value.Style.TextColor", DashboardModel.Tone.WARN.color);
    }

    private static String trigger(JsonObject incident) {
        return TriggerNames.label(present(incident.get("trigger")) ? text(incident, "trigger") : null);
    }

    static boolean hasContent(IncidentModel model, String section) {
        return switch (section) {
            case "top" -> true;
            case "stalled" -> !stalledThreads(model).isEmpty();
            case "diagnosis" -> {
                Plan rows = new Plan(model);
                diagnosis(rows);
                yield !rows.blocks.isEmpty();
            }
            case "metrics" -> dataIsSeries(model.object("series").get("mspt")) || dataIsSeries(model.object("series").get("msptIncident"))
                || dataIsSeries(model.object("series").get("tps"));
            case "cpu" -> filled(model, "cpu") || filled(model, "subsystems") || filled(model, "modCpu") || filled(model, "cpuByWorld");
            case "engine" -> filled(model, "tickSystems");
            case "methods" -> filled(model, "hotMethods");
            case "flame" -> filled(model, "flame");
            case "threads" -> filled(model, "hotThreads");
            case "states" -> filled(model, "threadStates") || filled(model, "deadlocked") || !threadProgress(model).isEmpty();
            case "memory" -> filled(model, "heap") || filled(model, "gc") || filled(model, "alloc") || dataIsSeries(model.object("series").get("heap"));
            case "network" -> filled(model, "net");
            case "pluginmetrics" -> !modMetrics(model).isEmpty() || !quietMetrics(model, "single").isEmpty() || !quietMetrics(model, "zero").isEmpty();
            case "worlds" -> filled(model, "worlds");
            case "system" -> filled(model, "sys");
            case "plugins" -> filled(model, "plugins");
            case "mixins" -> filled(model, "mixins");
            default -> false;
        };
    }

    static String sectionLabel(IncidentModel model, String section) {
        if (!section.equals("stalled")) return SECTIONS.getOrDefault(section, section);
        return stalledThreads(model).stream().anyMatch(card -> text(card, "title").equals("Deadlocked thread")) ? "Deadlocked threads" : "Stalled thread";
    }

    private static boolean filled(IncidentModel model, String key) {
        JsonElement value = model.field(key);
        if (value.isJsonArray()) return !value.getAsJsonArray().isEmpty();
        if (value.isJsonObject()) return !value.getAsJsonObject().isEmpty();
        return present(value);
    }

    private static List<JsonObject> stalledThreads(IncidentModel model) {
        List<JsonObject> cards = new ArrayList<>();
        for (JsonElement element : model.array("diag")) {
            JsonObject card = object(element);
            String title = text(card, "title");
            if (title.equals("Stalled thread") || title.equals("Deadlocked thread")) cards.add(card);
        }
        return cards;
    }

    private static void threadCard(Plan p, JsonObject card, boolean stack) {
        Map<String, String> entries = new LinkedHashMap<>();
        for (JsonElement row : array(card.get("entries"))) entries.put(text(at(array(row), 0)), text(at(array(row), 1)));
        p.card(text(card, "title"));
        String state = entries.getOrDefault("State", "");
        if (entries.containsKey("Thread")) p.row("Thread", entries.get("Thread"));
        if (!state.isEmpty()) p.row("State", StalledThread.stateLabel(state));
        if (entries.containsKey(StalledThread.FRAME_KEY)) p.row(StalledThread.frameVerb(state), entries.get(StalledThread.FRAME_KEY));
        p.row("Lock", entries.getOrDefault("Waiting on", "None"));
        if (entries.containsKey("Lock owner")) p.row("Lock owner", entries.get("Lock owner"));
        String stalled = entries.getOrDefault("Stalled for", "");
        if (!stalled.isEmpty()) p.row("Stalled for", stalled.matches("\\d+ ms") ? millis(Double.parseDouble(stalled.substring(0, stalled.length() - 3))) : stalled);
        String pre = opt(card.get("pre"));
        if (!stack || pre.isEmpty()) return;
        String[] frames = pre.split("\n");
        p.row("Stack", String.join("\n", java.util.Arrays.asList(frames).subList(0, Math.min(STACK_FRAMES, frames.length)))
            + (frames.length > STACK_FRAMES ? "\nand " + count(frames.length - STACK_FRAMES, "more frame") : ""));
    }

    private static Plan plan(IncidentModel model, ViewState state) {
        Plan p = new Plan(model);
        switch (state.sectionId()) {
            case "top" -> overview(p);
            case "diagnosis" -> {
                if (!filled(model, "findings") && !filled(model, "diagnosis")) p.row("Older bundle", "Diagnosis findings are not stored in this older bundle.");
                diagnosis(p);
            }
            case "stalled" -> {
                p.use("diag");
                List<JsonObject> threads = stalledThreads(model);
                for (int k = 0; k < Math.min(STALLED_THREADS, threads.size()); k++) threadCard(p, threads.get(k), true);
                p.rest(threads.size() - STALLED_THREADS, "more thread");
            }
            case "metrics" -> keyMetrics(p);
            case "cpu" -> cpu(p);
            case "engine" -> {
                double budget = 1000 / Math.max(1, number(model.object("tps").get("target"), 30));
                List<JsonArray> systems = array(p.use("tickSystems")).asList().stream().map(IncidentModel::array)
                    .sorted((a, b) -> Double.compare(number(at(b, 2), 0), number(at(a, 2), 0))).toList();
                Map<String, String> labels = worldLabels(model);
                Map<String, Integer> seen = new HashMap<>();
                for (JsonArray a : systems.subList(0, Math.min(ENGINE_SYSTEMS, systems.size()))) {
                    String system = text(at(a, 0)), world = worldLabel(labels, text(at(a, 1)));
                    int copy = seen.merge(system + " in " + world, 1, Integer::sum);
                    p.row(system + (copy > 1 ? " (" + copy + ")" : "") + " in " + world, millis(number(at(a, 2), 0)) + " avg 1m, " + millis(number(at(a, 3), 0)) + " max 1m, "
                        + (present(at(a, 5)) ? millis(number(at(a, 5), 0)) + " max 5m, " : "") + decimal(100 * number(at(a, 2), 0) / budget, 1) + "% of the " + decimal(budget, 1) + " ms tick");
                }
                p.rest(systems.size() - ENGINE_SYSTEMS, "more system");
            }
            case "methods" -> {
                JsonArray methods = array(p.use("hotMethods"));
                double total = number(model.object("incident").get("samples"), 0);
                for (int rank = 0; rank < Math.min(HOT_METHODS, methods.size()); rank++) {
                    JsonArray a = array(methods.get(rank)); double samples = number(at(a, 0), 0);
                    JsonArray callers = array(at(a, 2)), callerLabels = array(at(a, 4));
                    List<String> callerText = new ArrayList<>(callers.size());
                    for (int i = 0; i < callers.size(); i++) {
                        String callerLabel = opt(at(callerLabels, i));
                        callerText.add(text(callers.get(i)) + (callerLabel.isEmpty() ? "" : " [" + callerLabel + "]"));
                    }
                    String name = text(at(a, 1));
                    p.table(shortMethod(name), opt(at(a, 3)), count(samples, "sample") + ", " + percent(samples, total) + " self", "", -1, "#" + (rank + 1),
                        name + (callerText.isEmpty() ? "" : "\nCallers, nearest first: " + String.join(", ", callerText.subList(0, Math.min(TIP_CALLERS, callerText.size())))));
                }
                p.rest(methods.size() - HOT_METHODS, "more method");
            }
            case "flame" -> {
                JsonElement flame = p.use("flame");
                if (present(flame)) {
                    JsonArray root = flameRoot(model, state.flameZoomPath());
                    p.paragraph(flameBreadcrumb(model, state.flameZoomPath()) + " · " + flameFacts(root));
                    p.paragraph("Click a frame to zoom. Up goes back one frame. All samples shows everything.");
                    p.blocks.add(new Block(Kind.LEGEND, "", "", JsonNull.INSTANCE, 0, "", "flame"));
                    p.link("All samples", "Reset zoom", "zoom", "");
                    if (!state.flameZoomPath().isEmpty()) p.link("Up one frame", "Parent", "zoom", parentPath(state.flameZoomPath()));
                    p.blocks.add(new Block(Kind.FLAME, "", "", flame, 0, "", ""));
                    JsonArray children = array(at(root, 2));
                    List<Integer> order = new ArrayList<>();
                    for (int i = 0; i < children.size(); i++) order.add(i);
                    order.sort((a, b) -> Double.compare(number(at(array(children.get(b)), 1), 0), number(at(array(children.get(a)), 1), 0)));
                    for (int i : order.subList(0, Math.min(FLAME_CHILDREN, order.size()))) {
                        JsonArray child = array(children.get(i));
                        p.link(text(at(child, 0)), flameFacts(child), "zoom", childPath(state.flameZoomPath(), i));
                    }
                }
            }
            case "threads" -> threads(p);
            case "states" -> {
                JsonObject states = object(p.use("threadStates"));
                boolean checked = model.has("threadStates");
                if (checked) {
                    double blocked = number(states.get("BLOCKED"), 0);
                    p.row("Threads waiting for a lock", blocked == 0 ? "None at capture."
                        : count(blocked) + (blocked == 1 ? " thread was" : " threads were") + " waiting for a lock at capture.");
                }
                JsonArray deadlocked = array(p.use("deadlocked"));
                if (!deadlocked.isEmpty()) p.row("Deadlocked threads", join(deadlocked, ", "));
                else p.row("Deadlock", checked ? "No deadlock found." : "Deadlock check did not run.");
                JsonElement live = p.use("threadTotal");
                if (present(live)) p.row("Live threads", count(number(live, 0)));
                double total = states.entrySet().stream().mapToDouble(e -> number(e.getValue(), 0)).sum();
                states.entrySet().stream().sorted((a, b) -> Double.compare(number(b.getValue(), 0), number(a.getValue(), 0))).forEach(e ->
                    p.share(StalledThread.stateLabel(e.getKey()), count(number(e.getValue(), 0)) + " · " + percent(number(e.getValue(), 0), total), number(e.getValue(), 0) / Math.max(1, total)));
                threadProgress(p);
            }
            case "memory" -> memory(p);
            case "network" -> network(p);
            case "pluginmetrics" -> pluginMetrics(p);
            case "worlds" -> worlds(p);
            case "system" -> systemFacts(p);
            case "plugins" -> plugins(p, state.showCore());
            case "mixins" -> mixins(p);
            default -> { }
        }
        if (p.blocks.isEmpty()) p.row(sectionLabel(model, state.sectionId()), "Not captured.");
        return p;
    }

    private static void cpu(Plan p) {
        JsonObject cpu = object(p.use("cpu")), sys = object(p.use("sys"));
        double total = number(p.model.object("incident").get("samples"), 0);
        if (!cpu.isEmpty() || total > 0 && total < 300) p.card("CPU");
        if (!cpu.isEmpty()) {
            p.row("Machine CPU", decimal(number(cpu.get("machAvg"), 0), 1) + "% avg, " + decimal(number(cpu.get("machMax"), 0), 1) + "% peak"
                + (present(sys.get("hwThreads")) ? ", " + text(sys, "hwThreads") + " hardware threads" : ""));
            p.row("Server CPU", decimal(number(cpu.get("jvmAvg"), 0), 1) + "% avg, " + decimal(number(cpu.get("jvmMax"), 0), 1) + "% peak");
        }
        if (total > 0 && total < 300) p.row("Limited CPU sample count", "Only " + count(total) + " CPU samples. Too few to rank threads.");
        List<Map.Entry<String, Double>> worlds = worldShares(p.model);
        if (!worlds.isEmpty()) p.card("CPU share by world");
        Map<String, String> labels = worldLabels(p.model);
        String incidentWorld = worldKey(opt(p.model.object("incident").get("world")));
        int shownWorlds = 0, cap = worlds.size() <= CPU_WORLD_ROWS ? worlds.size() : CPU_WORLDS;
        for (var world : worlds) {
            if (shownWorlds >= cap && !worldKey(world.getKey()).equals(incidentWorld)) continue;
            p.share(worldLabel(labels, world.getKey()), percent(world.getValue(), total) + ", " + count(world.getValue(), "sample"), world.getValue() / Math.max(1, total));
            shownWorlds++;
        }
        p.rest(worlds.size() - shownWorlds, "more world");
        JsonArray mods = array(p.use("modCpu"));
        Map<String, String> owners = modOwners(p.model);
        List<Block> modShares = new ArrayList<>();
        if (split(mods, 4)) {
            for (JsonArray a : modRows(mods, 4)) {
                String name = modLabel(text(at(a, 0)), owners);
                double self = number(at(a, 4), 0);
                if (name != null && visible(self, total)) {
                    String sentence = present(at(a, 11)) ? text(at(a, 11)) : ModNames.onStackSentence(text(at(a, 0)), Math.round(number(at(a, 5), 0)), Math.round(total), "samples", opt(at(a, 9)));
                    modShares.add(new Block(Kind.SHARE, name, percent(self, total), JsonNull.INSTANCE, self / Math.max(1, total), "", ownCode(count(self, "sample"), sentence)));
                }
            }
        } else {
            Map<String, Double> merged = new LinkedHashMap<>();
            for (JsonElement element : mods) {
                JsonArray a = array(element);
                String name = modLabel(text(at(a, 0)), owners);
                if (name != null) merged.merge(name, number(at(a, 1), 0), Double::sum);
            }
            merged.entrySet().stream().filter(e -> visible(e.getValue(), total)).sorted((a, b) -> Double.compare(b.getValue(), a.getValue()))
                .forEach(e -> modShares.add(new Block(Kind.SHARE, e.getKey(), percent(e.getValue(), total), JsonNull.INSTANCE, e.getValue() / Math.max(1, total), "", "")));
        }
        if (!modShares.isEmpty()) {
            p.card("CPU by mod");
            p.blocks.addAll(modShares.subList(0, Math.min(CPU_MODS, modShares.size())));
            p.rest(modShares.size() - CPU_MODS, "more mod");
        }
        JsonArray subsystems = array(p.use("subsystems"));
        if (!subsystems.isEmpty()) p.card("Engine subsystems");
        for (JsonElement element : subsystems.asList().subList(0, Math.min(CPU_SUBSYSTEMS, subsystems.size()))) {
            JsonArray a = array(element);
            p.share(text(at(a, 0)), percent(number(at(a, 1), 0), total), number(at(a, 1), 0) / Math.max(1, total));
        }
        p.rest(subsystems.size() - CPU_SUBSYSTEMS, "more subsystem");
    }

    private static List<Map.Entry<String, Double>> worldShares(IncidentModel model) {
        Map<String, String> known = new LinkedHashMap<>();
        for (JsonElement element : model.array("worlds")) { String name = text(object(element), "name"); known.put(worldKey(name), name); }
        Map<String, Double> shares = new LinkedHashMap<>();
        JsonArray byWorld = model.array("cpuByWorld");
        for (JsonElement element : byWorld) {
            JsonObject entry = object(element); String name = text(entry, "world");
            String shown = known.containsKey(worldKey(name)) ? known.get(worldKey(name))
                : LEGACY_NO_WORLD.equals(name) || NO_WORLD.equals(name) || name.matches("\\d+") ? NO_WORLD : name;
            shares.merge(shown, number(entry.get("samples"), 0), Double::sum);
        }
        if (byWorld.isEmpty()) for (JsonElement element : model.array("worlds")) shares.put(text(object(element), "name"), number(object(element).get("samples"), 0));
        return shares.entrySet().stream().filter(e -> e.getValue() > 0).sorted((a, b) -> Double.compare(b.getValue(), a.getValue())).toList();
    }

    private static List<Integer> modMetrics(IncidentModel model) {
        JsonArray metrics = model.array("pluginMetrics");
        List<Integer> real = new ArrayList<>();
        for (int i = 0; i < metrics.size(); i++) {
            JsonObject metric = object(metrics.get(i));
            if (!hyboxMetric(metric) && (dataIsSeries(metric.get("series")) || "limit".equals(opt(metric.get("uncharted"))))) real.add(i);
        }
        real.sort((a, b) -> Double.compare(number(object(metrics.get(b)).get("events"), 0), number(object(metrics.get(a)).get("events"), 0)));
        return real;
    }

    private static List<Integer> quietMetrics(IncidentModel model, String kind) {
        JsonArray metrics = model.array("pluginMetrics");
        List<Integer> quiet = new ArrayList<>();
        for (int i = 0; i < metrics.size(); i++) {
            JsonObject metric = object(metrics.get(i));
            if (!hyboxMetric(metric) && !dataIsSeries(metric.get("series")) && kind.equals(opt(metric.get("uncharted")))) quiet.add(i);
        }
        return quiet;
    }

    private static boolean hyboxMetric(JsonObject metric) {
        return text(metric, "name").split("/", 2)[0].toLowerCase(Locale.ROOT).contains("hybox") || opt(metric.get("mod")).toLowerCase(Locale.ROOT).contains("hybox");
    }

    private static String metricLabel(JsonObject metric) {
        return present(metric.get("label")) ? text(metric, "label") : ModNames.metricLabel(text(metric, "name"));
    }

    private static void pluginMetrics(Plan p) {
        JsonArray metrics = array(p.use("pluginMetrics"));
        List<Integer> real = modMetrics(p.model), single = quietMetrics(p.model, "single"), zero = quietMetrics(p.model, "zero");
        if (real.isEmpty() && single.isEmpty() && zero.isEmpty()) return;
        int charts = 0, rows = 0;
        for (int i : real) {
            JsonObject metric = object(metrics.get(i));
            String name = text(metric, "name"), shown = metricLabel(metric);
            boolean counter = "count".equals(text(metric, "kind"));
            if (charts < PLUGIN_CHARTS && dataIsSeries(metric.get("series"))) {
                boolean rate = counter && present(metric.get("bucketSec"));
                p.chart(shown + " · " + (rate ? "counter, per second" : counter ? "count " + interval(p.model, metric.get("series")) : "gauge"),
                    metric.get("series"), rate ? "/s" : "", PLUGIN_METRIC_KEY + i);
                charts++;
                continue;
            }
            if (rows < PLUGIN_ROWS) {
                if (rows == 0) p.card("More metrics");
                double value = number(metric.get("total"), 0);
                p.row(shown, counter ? "Total " + count(value) + " in this window" : "Last value " + (value == Math.rint(value) ? count(value) : decimal(value, 2)), name);
                rows++;
            }
        }
        p.rest(real.size() - charts - rows, "more metric");
        if ((!single.isEmpty() || !zero.isEmpty()) && rows == 0) p.card("More metrics");
        if (!single.isEmpty()) p.row("Recorded once", metricNames(metrics, single, true));
        if (!zero.isEmpty()) p.row("Zero for the whole window", metricNames(metrics, zero, false));
    }

    private static String metricNames(JsonArray metrics, List<Integer> indexes, boolean totals) {
        List<String> names = new ArrayList<>();
        for (int i : indexes.subList(0, Math.min(PLUGIN_ROWS, indexes.size()))) {
            JsonObject metric = object(metrics.get(i));
            names.add(metricLabel(metric) + (totals && "count".equals(text(metric, "kind")) ? " (total " + count(number(metric.get("total"), 0)) + ")" : ""));
        }
        return String.join(", ", names) + (indexes.size() > PLUGIN_ROWS ? " and " + count(indexes.size() - PLUGIN_ROWS) + " more" : "");
    }

    private static void keyMetrics(Plan p) {
        JsonObject series = object(p.use("series"));
        String selected = opt(p.model.object("tps").get("world")), stalled = opt(p.model.object("incident").get("world"));
        String first = vitals(p.model).tickWorld();
        if (first.isEmpty()) first = stalled;
        Map<String, String> labels = worldLabels(p.model);
        tickCharts(p, series, first, selected, stalled, labels);
        if (!worldShort(stalled).isEmpty() && !worldKey(stalled).equals(worldKey(first))) tickCharts(p, series, stalled, selected, stalled, labels);
    }

    private static void tickCharts(Plan p, JsonObject series, String world, String selected, String stalled, Map<String, String> labels) {
        String key = worldKey(world), name = world.isEmpty() ? "" : " · " + worldLabel(labels, world);
        boolean main = key.equals(worldKey(selected));
        String tick = main && dataIsSeries(series.get("mspt")) ? "mspt" : key.equals(worldKey(stalled)) && dataIsSeries(series.get("msptIncident")) ? "msptIncident" : "";
        JsonElement tps = main ? series.get("tps") : null;
        for (JsonElement element : p.model.array("worldTps")) {
            if (!dataIsSeries(tps) && !key.isEmpty() && worldKey(text(object(element), "name")).equals(key)) tps = object(element).get("series");
        }
        JsonObject now = worldSnapshot(p.model, world);
        String missing = "No history for this world in this bundle. At capture: ";
        if (!tick.isEmpty()) p.chart(label("mspt") + name, series.get(tick), unit("mspt"), tick, world);
        else if (present(now.get("mspt"))) p.row(label("mspt") + name, missing + millis(number(now.get("mspt"), 0)) + " average"
            + (present(now.get("msptMax")) ? ", " + millis(number(now.get("msptMax"), 0)) + " worst." : "."));
        if (dataIsSeries(tps)) p.chart(label("tps") + name, tps, unit("tps"), main ? "tps" : "worldTps", world);
        else if (present(now.get("tps"))) p.row(label("tps") + name, missing + decimal(number(now.get("tps"), 0), 1) + " TPS.");
    }

    private static JsonObject worldSnapshot(IncidentModel model, String world) {
        for (JsonElement element : model.array("worlds")) {
            if (!world.isEmpty() && worldKey(text(object(element), "name")).equals(worldKey(world))) return object(element);
        }
        return new JsonObject();
    }

    private static void worlds(Plan p) {
        double total = number(p.model.object("incident").get("samples"), 0);
        JsonArray all = array(p.use("worlds"));
        Map<String, JsonArray> entities = new LinkedHashMap<>();
        for (JsonElement element : array(p.use("entities"))) entities.put(text(at(array(element), 0)), array(at(array(element), 1)));
        List<JsonObject> worlds = all.asList().stream().map(IncidentModel::object)
            .sorted((a, b) -> "true".equals(text(a, "stalled")) != "true".equals(text(b, "stalled")) ? ("true".equals(text(a, "stalled")) ? -1 : 1)
                : Double.compare(worldCpu(p.model, b), worldCpu(p.model, a))).toList();
        Map<String, String> labels = worldLabels(p.model);
        long[] cpuSamples = all.asList().stream().mapToLong(world -> Math.round(worldCpu(p.model, object(world)))).toArray();
        for (int k = 0; k < Math.min(WORLD_CARDS, worlds.size()); k++) {
            JsonObject world = worlds.get(k); String name = text(world, "name");
            double samples = worldCpu(p.model, world);
            int rank = WorldNames.cpuRank(cpuSamples, all.asList().indexOf(world));
            p.card(worldLabel(labels, name));
            p.row("Status", (name.startsWith("instance-") ? "instance" : "persistent") + ", CPU share " + percent(samples, total) + ", #" + rank + " of " + worlds.size() + " by CPU"
                + ("true".equals(text(world, "stalled")) ? ", stalled world" : ""));
            worldFacts(p, world, entities.get(name));
        }
        if (worlds.size() > WORLD_CARDS) p.card("Other worlds");
        for (JsonObject world : worlds.subList(Math.min(WORLD_CARDS, worlds.size()), Math.min(WORLD_CARDS + OTHER_WORLDS, worlds.size()))) {
            p.row(worldLabel(labels, text(world, "name")), "CPU share " + percent(worldCpu(p.model, world), total) + (present(world.get("players")) ? ", " + count(number(world.get("players"), 0), "player") : ""));
        }
        p.rest(worlds.size() - WORLD_CARDS - OTHER_WORLDS, "more world");
    }

    private static JsonObject threadProgress(IncidentModel model) {
        for (JsonElement element : model.array("diag")) {
            JsonObject card = object(element);
            if (text(card, "title").equals("Thread progress") && !array(card.get("entries")).isEmpty()) return card;
        }
        return new JsonObject();
    }

    private static void threadProgress(Plan p) {
        JsonObject card = threadProgress(p.model);
        if (card.isEmpty()) return;
        p.card("Thread progress");
        int listed = 0, hidden = 0;
        for (JsonElement element : array(card.get("entries"))) {
            String key = text(at(array(element), 0)), value = text(at(array(element), 1));
            if (key.equals("Dumps compared")) p.row(key, value);
            else if (key.equals("Verdict")) p.row(key, value.isEmpty() ? value : Character.toUpperCase(value.charAt(0)) + value.substring(1));
            else if (key.equals("(and more)")) hidden += (int) number(new com.google.gson.JsonPrimitive(value.split(" ", 2)[0].replace(",", "")), 0);
            else if (listed >= STUCK_THREADS) hidden++;
            else {
                int at = value.indexOf(" @ ");
                p.row(key, at < 0 ? value : StalledThread.frameVerb(value.substring(0, at)) + " " + value.substring(at + 3));
                listed++;
            }
        }
        p.rest(hidden, "more stuck thread");
    }

    private static Map<String, String> worldLabels(IncidentModel model) {
        List<String> names = new ArrayList<>();
        for (JsonElement element : model.array("worlds")) names.add(opt(object(element).get("name")));
        for (JsonElement element : model.array("cpuByWorld")) names.add(opt(object(element).get("world")));
        for (JsonElement element : model.array("worldTps")) names.add(opt(object(element).get("name")));
        for (JsonElement element : model.array("tickSystems")) names.add(opt(at(array(element), 1)));
        for (JsonElement element : model.array("tickSeries")) {
            String name = opt(object(element).get("name"));
            if (name.contains(" @ ")) names.add(name.substring(name.lastIndexOf(" @ ") + 3));
        }
        for (JsonElement element : model.array("hotThreads")) {
            String thread = opt(at(array(element), 2));
            if (thread.startsWith(WORLD_THREAD)) names.add(thread.substring(WORLD_THREAD.length()));
        }
        names.add(opt(model.object("tps").get("world")));
        names.add(opt(model.object("vitals").get("world")));
        names.add(opt(model.object("incident").get("world")));
        Map<String, String> labels = new HashMap<>(new WorldNames(names).labels());
        for (var entry : model.object("worldNames").entrySet()) labels.put(entry.getKey(), opt(entry.getValue()));
        return labels;
    }

    private static String worldLabel(Map<String, String> labels, String world) {
        String label = labels.get(worldKey(world));
        return label != null ? label : WorldNames.display(world);
    }

    private static void diagnosis(Plan p) {
        JsonObject g = object(p.use("diagnosis"));
        if (!g.isEmpty()) {
            JsonArray why = array(g.get("why")), other = array(g.get("other"));
            if (present(g.get("cause")) && !why.isEmpty()) {
                p.card("Why");
                for (JsonElement line : why) finding(p, object(line), "Why");
            }
            if (!other.isEmpty()) {
                p.card("Other findings");
                for (JsonElement line : other.asList().subList(0, Math.min(OTHER_LINES, other.size()))) finding(p, object(line), "Observation");
                p.rest(other.size() - OTHER_LINES, "more finding");
            }
        } else {
            JsonObject f = object(p.use("findings"));
            JsonArray evidence = array(f.get("evidence")), steps = array(f.get("steps"));
            if (evidence.size() > OVERVIEW_FINDINGS || steps.size() > OVERVIEW_FINDINGS) {
                p.card("Other findings");
                for (int k = OVERVIEW_FINDINGS; k < evidence.size(); k++) finding(p, object(evidence.get(k)), "Evidence");
                for (int k = OVERVIEW_FINDINGS; k < steps.size(); k++) finding(p, object(steps.get(k)), "Next step " + (k + 1));
            }
        }
        similar(p);
        comparison(p);
        usual(p);
    }

    private static String lineTip(IncidentModel model, JsonObject line) {
        Map<String, String> meanings = new HashMap<>();
        for (JsonElement term : array(model.object("diagnosis").get("terms"))) meanings.put(opt(object(term).get("term")), opt(object(term).get("meaning")));
        List<String> parts = new ArrayList<>();
        for (JsonElement item : array(line.get("spans"))) {
            String term = opt(at(array(item), 2)), meaning = meanings.getOrDefault(term, "");
            if (!meaning.isEmpty()) parts.add(term + ": " + meaning);
        }
        if (present(line.get("hover"))) parts.add(text(line, "hover"));
        return screen(String.join("\n", parts));
    }

    private static void dropSectionName(JsonArray spans, List<String> names) {
        String full = spans(spans), kept = full.stripTrailing();
        for (String name : names) {
            String suffix = " (" + name + ")";
            if (name.isEmpty() || !kept.toLowerCase(Locale.ROOT).endsWith(suffix.toLowerCase(Locale.ROOT))) continue;
            int drop = full.length() - kept.length() + suffix.length();
            for (int i = spans.size() - 1; i >= 0 && drop > 0; i--) {
                JsonArray span = array(spans.get(i));
                String part = text(at(span, 0));
                if (part.length() <= drop) { drop -= part.length(); spans.remove(i); }
                else { span.set(0, new com.google.gson.JsonPrimitive(part.substring(0, part.length() - drop))); drop = 0; }
            }
            return;
        }
    }

    private static void finding(Plan p, JsonObject finding, String heading) {
        p.blocks.add(new Block(Kind.EVIDENCE, heading, spans(finding.get("spans")), finding, 0, "", ""));
    }

    private static com.hypixel.hytale.server.core.Message styledSpans(JsonElement spans) {
        var message = com.hypixel.hytale.server.core.Message.empty();
        for (JsonElement item : array(spans)) {
            JsonArray span = array(item);
            var part = com.hypixel.hytale.server.core.Message.raw(screen(text(at(span, 0))));
            if (text(at(span, 1)).equals("b")) part.bold(true);
            if (text(at(span, 1)).equals("code")) part.monospace(true);
            message.insert(part);
        }
        return message;
    }

    private static String spans(JsonElement element) {
        StringBuilder result = new StringBuilder();
        for (JsonElement span : array(element)) result.append(text(at(array(span), 0)));
        return result.toString();
    }

    private static void threads(Plan p) {
        JsonArray hot = array(p.use("hotThreads"));
        double total = number(p.model.object("incident").get("samples"), 0);
        Map<String, double[]> pools = new LinkedHashMap<>();
        for (JsonElement element : hot) {
            JsonArray a = array(element);
            double[] pool = pools.computeIfAbsent(threadGroup(text(at(a, 2))), ignored -> new double[2]); pool[0]++; pool[1] += number(at(a, 0), 0);
        }
        Map<String, JsonArray> timelines = new LinkedHashMap<>();
        JsonArray timelineEntries = array(p.use("threadTimeline")), axisSeries = new JsonArray();
        for (JsonElement element : timelineEntries) {
            JsonObject entry = object(element); JsonArray states = array(entry.get("states"));
            timelines.put(text(entry, "name"), states);
            if (states.size() > axisSeries.size()) axisSeries = states;
        }
        p.card("Hot threads · ranked by CPU samples");
        if (hot.isEmpty()) { p.row("Hot threads", "Not captured."); return; }
        p.blocks.add(new Block(Kind.LEGEND, "", "", JsonNull.INSTANCE, 0, "", "threads"));
        p.blocks.add(new Block(Kind.THREAD_AXIS, "", "", axisSeries, 0, "", ""));
        for (int rank = 0; rank < Math.min(HOT_THREADS, hot.size()); rank++) {
            JsonArray a = array(hot.get(rank)); double samples = number(at(a, 0), 0);
            StringBuilder methods = new StringBuilder();
            for (JsonElement method : array(at(a, 4))) {
                if (!methods.isEmpty()) methods.append('\n');
                JsonArray m = array(method);
                methods.append(text(at(m, 0))).append(" · ").append(count(number(at(m, 1), 0), "sample")).append(" · ").append(percent(number(at(m, 1), 0), samples));
                String label = opt(at(m, 2));
                if (!label.isEmpty()) methods.append(" · ").append(label);
            }
            String name = text(at(a, 2)), mods = threadMods(at(a, 6), samples);
            String lines = mods.isEmpty() ? methods.toString() : methods.isEmpty() ? mods : mods + "\n" + methods;
            String pool = timelines.containsKey(name) ? name : threadGroup(name);
            JsonArray timeline = timelines.get(pool);
            p.blocks.add(new Block(Kind.THREAD, name, lines, a, samples / Math.max(1, total), timeline == null || pool.equals(name) ? "" : pool, Integer.toString(rank + 1),
                timeline == null && !timelines.isEmpty() ? new JsonArray() : timeline));
        }
        List<String> poolText = pools.entrySet().stream().filter(e -> e.getValue()[0] >= 2).sorted((a, b) -> Double.compare(b.getValue()[1], a.getValue()[1])).limit(THREAD_POOLS)
            .map(e -> e.getKey() + " " + count(e.getValue()[0], "thread") + ", " + percent(e.getValue()[1], total)).toList();
        if (!poolText.isEmpty()) p.row("Thread pools", String.join(". ", poolText));
        p.rest(hot.size() - HOT_THREADS, "more thread");
    }

    private static void memory(Plan p) {
        p.series("heap");
        JsonObject sys = object(p.use("sys")), heap = object(p.use("heap")), gc = object(p.use("gc")), alloc = object(p.use("alloc"));
        double window = number(p.model.object("incident").get("windowSec"), 0);
        p.card("Memory");
        double maxHeap = number(heap.get("max"), number(sys.get("xmxBytes"), 0));
        if (maxHeap > 0) p.row("Max heap", gib(maxHeap) + " GiB");
        if (present(heap.get("peak"))) p.row("Peak heap used", gib(number(heap.get("peak"), 0)) + " GiB" + (maxHeap > 0 ? ", " + percent(number(heap.get("peak"), 0), maxHeap) + " of the max" : ""));
        if (present(heap.get("floor"))) p.row("Post-GC floor", gib(number(heap.get("floor"), 0)) + " GiB");
        if (present(gc.get("maxPauseMs"))) p.row("Longest GC pause", millis(number(gc.get("maxPauseMs"), 0)));
        if (present(gc.get("totalPauseMs"))) p.row("Time in GC pauses", (window > 0 ? decimal(100 * number(gc.get("totalPauseMs"), 0) / (window * 1000), 1) + "% of the window, " : "")
            + millis(number(gc.get("totalPauseMs"), 0)) + " in total");
        double total = number(alloc.get("total"), 0);
        if (present(alloc.get("total"))) p.row("Allocation rate", (window > 0 ? bytes(total / window) + "/s" : bytes(total) + " in the window") + ", freed by GC, not memory in use");
        JsonArray byMod = array(alloc.get("byMod"));
        if (split(byMod, 2)) {
            List<JsonArray> rows = modRows(byMod, 2);
            if (!rows.isEmpty()) {
                JsonArray top = rows.getFirst();
                double own = number(at(top, 2), 0);
                p.row("Top allocator by mod", modName(text(at(top, 0))) + ", " + percent(own, total),
                    ownCode(bytes(own), ModNames.onStackSentence(text(at(top, 0)), Math.round(number(at(top, 3), 0)), Math.round(total), "sampled memory", null)));
            }
        } else if (!byMod.isEmpty()) {
            JsonArray top = byMod.asList().stream().map(IncidentModel::array).max(java.util.Comparator.comparingDouble(a -> number(at(a, 1), 0))).orElseThrow();
            p.row("Top allocator by mod", modName(text(at(top, 0))) + ", " + bytes(number(at(top, 1), 0)) + ", " + percent(number(at(top, 1), 0), total));
        }
    }

    private static void mixins(Plan p) {
        JsonObject mixins = object(p.use("mixins"));
        if (mixins.isEmpty()) return;
        JsonObject hyinit = object(mixins.get("hyinit"));
        if (hyinit.isEmpty()) {
            p.card("Mixins");
            JsonArray configs = array(mixins.get("configs"));
            int total = configs.asList().stream().mapToInt(item -> array(at(array(item), 1)).size()).sum();
            if (!configs.isEmpty() || !present(mixins.get("note"))) p.row("Mixins applied", count(total, "Mixin") + " across " + count(configs.size(), "config"));
            JsonArray conflicts = array(mixins.get("conflicts"));
            if (!conflicts.isEmpty()) p.row("Mixin overlap", count(conflicts.size()) + (conflicts.size() == 1 ? " class" : " classes") + " patched by more than one config. Two mods change the same code here. If the server misbehaves, test with one of them removed.");
            else if (present(mixins.get("conflicts")) && configs.size() >= 2) p.row("Mixin overlap", "No classes patched by more than one config.");
            else if (!present(mixins.get("conflicts")) && !configs.isEmpty()) p.row("Mixin overlap", "Conflict data was not captured.");
            return;
        }
        JsonArray problems = array(mixins.get("problems")), overlaps = array(mixins.get("overlaps")), sources = array(mixins.get("sources"));
        p.card("Problems since startup");
        if (problems.isEmpty()) p.row("Mixin problems", "None. Every Mixin applied.");
        for (JsonElement element : problems.asList().subList(0, Math.min(MIXIN_ROWS, problems.size()))) {
            JsonArray problem = array(element);
            String source = opt(at(problem, 2));
            p.table(("error".equals(text(at(problem, 0))) ? "ERROR" : "WARN") + (source.isEmpty() ? "" : " · " + source), text(at(problem, 1)), "", opt(at(problem, 3)));
        }
        p.rest(problems.size() - MIXIN_ROWS, "more problem");
        double omitted = number(hyinit.get("problemsOmitted"), 0);
        if (omitted > 0) p.row("Problems not kept", count(omitted) + " more. The server log has them.");
        if (!problems.isEmpty()) p.row("Stack traces", stackTraceNote(problems));
        p.card("Methods changed by more than one mod");
        if (overlaps.isEmpty()) p.row("Shared methods", sources.size() >= 2 ? "None. No method is changed by more than one mod." : "None");
        for (JsonElement element : overlaps.asList().subList(0, Math.min(MIXIN_ROWS, overlaps.size()))) {
            JsonArray o = array(element);
            List<String> patches = new ArrayList<>();
            for (JsonElement patch : array(at(o, 1))) {
                JsonArray pair = array(patch); String mixin = opt(at(pair, 1));
                patches.add(text(at(pair, 0)) + (mixin.isEmpty() ? "" : " (" + mixin.substring(mixin.lastIndexOf('.') + 1) + ")"));
            }
            p.row(shortMethod(text(at(o, 0))), String.join(", ", patches));
        }
        p.rest(overlaps.size() - MIXIN_ROWS, "more shared method");
    }

    private static String stackTraceNote(JsonArray problems) {
        long traced = 0, untraced = 0;
        for (JsonElement element : problems) {
            JsonArray problem = array(element);
            if (at(problem, 4).isJsonArray()) traced++;
            else if (present(at(problem, 3))) untraced++;
        }
        if (traced == 0) return "The server log has the full stack traces.";
        return "The bundle has the stack traces."
            + (untraced == 0 ? "" : untraced == 1 ? " The server log has the stack trace of the other problem." : " The server log has the stack traces of the other " + untraced + " problems.");
    }

    private static String mixinProblemSuffix(IncidentModel model) {
        JsonArray problems = array(model.object("mixins").get("problems"));
        if (problems.isEmpty()) return "";
        long errors = problems.asList().stream().filter(e -> "error".equals(text(at(array(e), 0)))).count();
        return errors > 0 ? " · " + errors + (errors == 1 ? " error" : " errors") : " · " + problems.size() + (problems.size() == 1 ? " warning" : " warnings");
    }

    private static void plugins(Plan p, boolean showCore) {
        JsonArray plugins = array(p.use("plugins")), packs = array(p.use("assetPacks")), configs = array(p.use("configs"));
        int core = 0, incompatible = 0;
        for (JsonElement element : plugins) {
            JsonArray a = array(element);
            if ("true".equals(text(at(a, 3)))) core++;
            if ("INCOMPATIBLE".equals(text(at(a, 5)))) incompatible++;
        }
        p.row("Plugins loaded", plugins.size() + " loaded: " + core + " Hytale core, " + (plugins.size() - core) + " community." + (incompatible > 0 ? " " + incompatible + " built for another version." : ""));
        List<String> settings = new ArrayList<>();
        for (JsonElement element : p.model.array("diag")) {
            String title = text(object(element), "title");
            int entries = array(object(element).get("entries")).size();
            if (!title.endsWith(" settings") || entries == 0) continue;
            String mod = modName(title.substring(0, title.length() - " settings".length()));
            settings.add(settings.isEmpty() ? count(entries, "setting") + " from " + mod : count(entries) + " from " + mod);
        }
        if (!settings.isEmpty()) p.row("Mod settings", String.join(", ", settings) + ".");
        List<String> more = new ArrayList<>();
        if (!packs.isEmpty()) more.add(count(packs.size(), "asset pack"));
        if (!configs.isEmpty()) more.add(count(configs.size(), "config file"));
        if (!more.isEmpty()) p.row("Asset packs and configs", String.join(" and ", more));
        if (core > 0) p.link((showCore ? "Hide " : "Show ") + core + " core modules", "", "toggle", "core");
        List<JsonArray> sorted = plugins.asList().stream().map(IncidentModel::array).sorted(java.util.Comparator.comparing(a -> "true".equals(text(at(a, 3))))).toList();
        for (JsonArray a : sorted) {
            boolean corePlugin = "true".equals(text(at(a, 3)));
            if (corePlugin && !showCore) continue;
            String range = text(at(a, 4)), compat = text(at(a, 5));
            p.table(text(at(a, 1)), "Author: " + text(at(a, 0)) + " · Version: " + text(at(a, 2)), corePlugin ? "CORE" : "COMMUNITY",
                compat.equals("INCOMPATIBLE") ? "Built for another version: " + range : "");
        }
    }

    private static void network(Plan p) {
        JsonObject net = object(p.use("net"));
        p.series("netOut");
        if (net.isEmpty()) return;
        p.card("Network");
        p.row("Inbound", rate(net.get("inAvg")) + " average, " + rate(net.get("inPeak")) + " peak");
        p.row("Outbound", rate(net.get("outAvg")) + " average, " + rate(net.get("outPeak")) + " peak");
        int shown = 0, hidden = 0;
        for (JsonElement item : array(net.get("others"))) {
            JsonArray a = array(item);
            if (number(at(a, 1), 0) == 0 && number(at(a, 2), 0) == 0 && number(at(a, 3), 0) == 0 && number(at(a, 4), 0) == 0) continue;
            if (shown >= OTHER_INTERFACES) { hidden++; continue; }
            p.row("Interface " + text(at(a, 0)), "In " + rate(at(a, 1)) + " average, " + rate(at(a, 2)) + " peak. Out " + rate(at(a, 3)) + " average, " + rate(at(a, 4)) + " peak.");
            shown++;
        }
        p.rest(hidden, "more interface");
    }

    static HyboxChartRenderer.Mark mark(IncidentModel model) {
        JsonObject incident = model.object("incident");
        if (present(incident.get("markerFrac"))) {
            boolean stopped = "UNCLEAN_SHUTDOWN".equals(text(incident, "trigger"));
            return new HyboxChartRenderer.Mark(stopped ? "stopped" : "incident", number(incident.get("markerFrac"), 1),
                stopped ? "server stopped" : "incident", HyboxChartImage.INCIDENT);
        }
        if (present(incident.get("profileFrac"))) {
            return new HyboxChartRenderer.Mark("profile", number(incident.get("profileFrac"), 0), "profile start", HyboxChartImage.PROFILE);
        }
        if (present(incident.get("captureFrac"))) {
            return new HyboxChartRenderer.Mark("captured", number(incident.get("captureFrac"), 1), "captured", HyboxChartImage.PROFILE);
        }
        return null;
    }

    private static void comparison(Plan p) {
        JsonObject incident = p.model.object("incident");
        double marker = number(incident.get("markerFrac"), -1);
        if (marker < 0.2) return;
        JsonObject series = object(p.use("series"));
        if (CompareMetrics.METRICS.stream().filter(metric -> array(series.get(metric.key())).size() >= 10).count() < 3) return;
        double baselineEnd = Math.max(0.1, marker - 0.1), incidentStart = Math.max(0, marker - 0.02);
        double minutes = number(incident.get("windowSec"), 0) / 60;
        String term = "MANUAL".equals(opt(incident.get("trigger"))) ? "profile" : "incident";
        p.card("Baseline vs " + term + " window");
        p.paragraph("Baseline is the first " + count(baselineEnd * 100) + "% of the recording. " + Character.toUpperCase(term.charAt(0)) + term.substring(1)
            + " is from just before the marker" + (present(incident.get("recoverySec")) ? " through recovery." : " to the end.")
            + " Marked worse when the change is 5% or more in the direction that hurts. Players, chunks generated, chunks loaded and network traffic are not marked.");
        p.blocks.add(new Block(Kind.COMPARE, "", "", JsonNull.INSTANCE, 0, "header", Character.toUpperCase(term.charAt(0)) + term.substring(1)));
        for (CompareMetrics.Metric metric : CompareMetrics.METRICS) {
            JsonArray values = array(series.get(metric.key()));
            if (values.size() < 10 || metric.perMinute() && minutes <= 0) continue;
            double scale = metric.perMinute() ? values.size() / minutes : 1;
            comparisonRow(p, metric, metric.label(), scale * sliceAverage(values, 0, baselineEnd), scale * sliceAverage(values, incidentStart, 1), "");
        }
        CompareMetrics.Metric errors = CompareMetrics.metric(CompareMetrics.LOG_ERRORS), system = CompareMetrics.metric(CompareMetrics.ENGINE_SYSTEM);
        if (minutes > 0) comparisonRow(p, errors, errors.label(), errorCount(array(p.use("events")), 0, baselineEnd) / (baselineEnd * minutes), errorCount(p.model.array("events"), incidentStart, 1.0001) / ((1 - incidentStart) * minutes), "");
        Map<String, String> labels = worldLabels(p.model);
        List<JsonObject> ticks = array(p.use("tickSeries")).asList().stream().map(IncidentModel::object).sorted((a, b) -> Double.compare(sliceAverage(array(b.get("series")), 0, 1), sliceAverage(array(a.get("series")), 0, 1))).limit(3).toList();
        for (JsonObject tick : ticks) {
            String name = text(tick, "name");
            comparisonRow(p, system, systemLabel(name, labels), sliceAverage(array(tick.get("series")), 0, baselineEnd), sliceAverage(array(tick.get("series")), incidentStart, 1), name);
        }
    }

    private static String systemLabel(String name, Map<String, String> labels) {
        int at = name.lastIndexOf(" @ ");
        return at < 0 ? name : name.substring(0, at) + " in " + worldLabel(labels, name.substring(at + 3));
    }

    private static void comparisonRow(Plan p, CompareMetrics.Metric metric, String name, double baseline, double incident, String tip) {
        String unit = metric.unit();
        double factor = unit.equals("GiB") ? 1073741824.0 : unit.equals("MiB/s") ? 1 / DISK_MIB : 1;
        double base = baseline / factor, now = incident / factor;
        if (!CompareMetrics.informative(metric.bad(), base, now, metric.decimals())) return;
        int precision = metric.decimals();
        if (Math.abs(now - base) > 0) precision = Math.max(precision, Math.min(6, (int) Math.ceil(-Math.log10(Math.abs(now - base)))));
        String change = CompareMetrics.change(base, now);
        boolean marked = CompareMetrics.worse(metric.bad(), base, now);
        JsonArray cells = new JsonArray();
        cells.add(withUnit(base, unit, precision));
        cells.add(withUnit(now, unit, precision));
        cells.add(change == null ? "n/a" : marked ? change + " worse" : change);
        p.blocks.add(new Block(Kind.COMPARE, name, "", cells, 0, marked ? "worse" : "", tip));
    }

    private static double sliceAverage(JsonArray values, double from, double to) {
        int first = (int) Math.floor(from * Math.max(0, values.size() - 1)), last = (int) Math.ceil(to * Math.max(0, values.size() - 1));
        double sum = 0; int count = 0;
        for (int index = first; index <= last && index < values.size(); index++) if (present(values.get(index))) { sum += number(values.get(index), 0); count++; }
        return count == 0 ? 0 : sum / count;
    }

    private static double errorCount(JsonArray events, double from, double to) {
        double total = 0;
        for (JsonElement event : events) {
            JsonArray a = array(event); double fraction = number(at(a, 0), 0);
            if (!text(at(a, 1)).equals("error") || fraction < from || fraction >= to) continue;
            var repeat = EVENT_TIMES.matcher(text(at(a, 2)));
            total += repeat.find() ? Double.parseDouble(repeat.group(1)) : 1;
        }
        return total;
    }

    private static void similar(Plan p) {
        JsonObject similar = object(object(p.use("history")).get("similar"));
        if (similar.isEmpty()) return;
        p.card("Similar incidents");
        p.rich("Earlier incidents", similar.get("line"));
        Map<String, String> labels = worldLabels(p.model);
        for (JsonElement element : array(similar.get("rows"))) {
            JsonObject row = object(element);
            List<String> facts = new ArrayList<>();
            facts.add(text(row, "triggerLabel") + (present(row.get("world")) ? " in world " + worldLabel(labels, text(row, "world")) : ""));
            if (present(row.get("mod"))) facts.add("Top mod " + text(row, "mod") + (row.has("sameMod") && row.get("sameMod").getAsBoolean() ? ", same as now" : ""));
            if (present(row.get("method"))) facts.add("Top method " + shortMethod(text(row, "method")) + (row.has("sameMethod") && row.get("sameMethod").getAsBoolean() ? ", same as now" : ""));
            p.row(text(row, "created"), String.join(". ", facts) + ".");
        }
        double checked = number(similar.get("checked"), 0);
        p.paragraph("Counted from the " + count(checked) + (checked == 1 ? " older bundle" : " older bundles") + " still on disk. Manual captures are not counted.");
    }

    static String similarCount(JsonObject similar) {
        double week = number(similar.get("week"), 0), month = number(similar.get("month"), 0);
        if (month == 0) return number(similar.get("checked"), 0) == 0 ? "None. There are no older bundles on disk." : "None in the last 30 days";
        return count(week) + " in the last 7 days, " + count(month) + " in the last 30 days";
    }

    private static void usual(Plan p) {
        JsonObject usual = object(object(p.use("history")).get("usual"));
        if (usual.isEmpty()) return;
        p.card("Usual for this server");
        double hours = number(usual.get("hours"), 0), days = number(usual.get("days"), 0);
        boolean off = usual.has("off") && usual.get("off").getAsBoolean();
        if (!(usual.has("enough") && usual.get("enough").getAsBoolean())) {
            p.paragraph(off && hours == 0 ? "Metrics history is off, so there are no usual values to compare with. Turn it on in /hybox settings."
                : "Not enough history yet (" + count(hours) + (hours == 1 ? " hour" : " hours") + "). Usual values need this time of day on "
                    + count(number(usual.get("minDays"), 3)) + " of the last 7 days, and the history has it on " + count(days) + "."
                    + (off ? " Metrics history is off. Turn it on in /hybox settings." : ""));
            return;
        }
        boolean recording = "recording".equals(opt(usual.get("window")));
        p.paragraph("Median of the samples within 30 minutes of " + text(usual, "clock") + " UTC on the last 7 days, from " + count(days)
            + " days of data. Marked unusual when the " + (recording ? "recording" : "incident") + " is 25% or more past the value 95% of those samples stayed under, or above for TPS.");
        for (JsonElement element : array(usual.get("metrics"))) {
            JsonObject metric = object(element);
            String unit = opt(metric.get("unit"));
            String key = opt(metric.get("key"));
            boolean tps = key.equals("tps");
            int digits = unit.equals("GiB") ? 2 : key.equals("players") ? 0 : 1;
            String value = (recording ? "Recording " : "Incident ") + (present(metric.get("incident")) ? withUnit(number(metric.get("incident"), 0), unit, digits) : "not captured")
                + ", usual " + withUnit(number(metric.get("median"), 0), unit, digits) + ", 95% " + (tps ? "above " : "under ")
                + withUnit(number(metric.get(tps ? "low" : "high"), 0), unit, digits) + (present(metric.get("flag")) ? ". Unusual for this server." : ".");
            p.row(text(metric, "label"), value);
        }
    }

    private static String withUnit(double value, String unit, int digits) {
        String number = decimal(value, digits);
        return unit.isEmpty() ? number : unit.equals("%") || unit.startsWith("/") ? number + unit : number + " " + unit;
    }

    private static void overview(Plan p) {
        JsonObject i = object(p.use("incident"));
        JsonArray diag = array(p.use("diag"));
        List<JsonObject> threads = stalledThreads(p.model);
        if (!threads.isEmpty()) {
            threadCard(p, threads.getFirst(), false);
            if (threads.size() > 1) p.row("Other threads", count(threads.size() - 1, "more thread") + " in " + sectionLabel(p.model, "stalled"));
            p.link("Full stack", sectionLabel(p.model, "stalled"), "section", "stalled");
        }
        JsonObject g = object(p.use("diagnosis"));
        if (!g.isEmpty()) {
            JsonObject cause = object(g.get("cause"));
            JsonArray why = array(g.get("why")), steps = array(g.get("steps")), other = array(g.get("other"));
            if (!cause.isEmpty()) {
                p.card(switch (opt(cause.get("confidence"))) { case "likely" -> "Likely cause"; case "unclear" -> ""; default -> "Cause"; });
                finding(p, cause, "Cause");
            } else if (!why.isEmpty()) {
                p.card("Notable");
                for (JsonElement line : why) finding(p, object(line), "Notable");
            } else if (other.isEmpty() && steps.isEmpty() && !present(p.model.field("jfrUnavailableReason"))) {
                p.card("");
                p.paragraph("Nothing crossed a threshold in this recording.");
            }
            if (!steps.isEmpty()) {
                p.card("Next steps");
                for (int k = 0; k < steps.size(); k++) finding(p, object(steps.get(k)), "Step " + (k + 1));
            }
            int more = (cause.isEmpty() ? 0 : why.size()) + other.size();
            if (more > 0) p.link(count(more, "more finding") + " in Diagnosis", "", "section", "diagnosis");
        } else {
            JsonObject findings = object(p.use("findings"));
            JsonArray evidence = array(findings.get("evidence")), steps = array(findings.get("steps"));
            if (!evidence.isEmpty()) {
                p.card("What the recording shows");
                for (int k = 0; k < Math.min(OVERVIEW_FINDINGS, evidence.size()); k++) finding(p, object(evidence.get(k)), "Evidence");
            }
            if (!steps.isEmpty()) {
                p.card("Next steps");
                for (int k = 0; k < Math.min(OVERVIEW_FINDINGS, steps.size()); k++) finding(p, object(steps.get(k)), "Next step " + (k + 1));
            }
            int more = Math.max(0, evidence.size() - OVERVIEW_FINDINGS) + Math.max(0, steps.size() - OVERVIEW_FINDINGS);
            if (more > 0) p.link(count(more, "more finding") + " in Diagnosis", "", "section", "diagnosis");
        }
        p.card("Recording");
        JsonObject similar = object(object(p.use("history")).get("similar"));
        if (!similar.isEmpty()) p.row("Similar incidents", similarCount(similar));
        if (present(i.get("recoverySec"))) p.row("Recovery captured", decimal(number(i.get("recoverySec"), 0), 1) + " s");
        String mixinProblems = mixinProblemSuffix(p.model);
        if (!mixinProblems.isEmpty()) p.row("Mixin problems", mixinProblems.substring(3) + " since startup. See Mixins.");
        if (present(i.get("note"))) p.row("Capture note", text(i, "note"));
        JsonElement unavailable = p.use("jfrUnavailableReason");
        if (present(unavailable)) p.row("Recording unavailable", text(unavailable));
        String beats = "";
        for (JsonElement element : diag) {
            JsonObject card = object(element);
            if (text(card, "title").equals("Heartbeats")) beats = heartbeats(p.model, array(card.get("entries")));
        }
        if (!beats.isEmpty()) p.row("Heartbeats", beats);
        if (present(i.get("players"))) p.row("Players", count(number(i.get("players"), 0)) + " online at capture");
        if (present(i.get("windowSec"))) p.row("Window", duration(number(i.get("windowSec"), 0) * 1000) + (present(i.get("recStart")) ? " from " + text(i, "recStart") + " UTC" : ""));
        JsonArray log = array(p.use("log"));
        if (!log.isEmpty()) {
            long errors = log.asList().stream().filter(e -> "ERROR".equals(text(at(array(e), 0)))).count();
            long warnings = log.asList().stream().filter(e -> "WARN".equals(text(at(array(e), 0)))).count();
            p.row("Server log", count(errors, "error") + ", " + count(warnings, "warning") + " in the server log.");
        }
        JsonElement version = p.use("hyboxVersion");
        p.row("Bundle", p.model.id() + (present(version) ? " · Hybox " + text(version) : ""));
    }

    private static String heartbeats(IncidentModel model, JsonArray entries) {
        if (entries.isEmpty()) return "";
        Map<String, String> labels = worldLabels(model);
        List<String> late = new ArrayList<>(), other = new ArrayList<>();
        long onTime = 0, oldest = 0;
        for (JsonElement row : entries) {
            JsonArray pair = array(row);
            Matcher age = HEARTBEAT_AGE.matcher(text(at(pair, 1)));
            if (!age.find()) {
                Matcher summary = HEARTBEAT_SUMMARY.matcher(text(at(pair, 1)));
                other.add(summary.find() ? text(at(pair, 0)) + " beat within " + summary.group(1) + " ms of capture" : text(at(pair, 0)) + " " + text(at(pair, 1)));
                continue;
            }
            long ms = Long.parseLong(age.group(1));
            if (ms > 1000) late.add(worldLabel(labels, text(at(pair, 0))) + " " + Units.elapsed(ms) + " late");
            else { onTime++; oldest = Math.max(oldest, ms); }
        }
        List<String> parts = new ArrayList<>(late.subList(0, Math.min(HEARTBEATS, late.size())));
        if (late.size() > HEARTBEATS) parts.add(count(late.size() - HEARTBEATS, "more late world"));
        if (onTime > 0) parts.add((!late.isEmpty() ? count(onTime, "other world") : onTime == 1 ? "1 world" : "All " + onTime + " worlds") + " beat within " + oldest + " ms of capture");
        parts.addAll(other);
        return String.join(". ", parts);
    }

    private static String osLabel(String os, String arch) {
        if (!os.startsWith("uname:")) return arch.isEmpty() ? os : os + ", " + arch;
        String[] parts = os.substring("uname:".length()).trim().split("\\s+");
        String release = parts.length > 1 ? parts[1].split("[-+]", 2)[0] : "";
        String name = parts[0].equals("Darwin") ? (release.isEmpty() ? "macOS" : "macOS (Darwin " + release + ")") : (parts[0] + " " + release).trim();
        String machine = parts.length > 2 ? parts[parts.length - 1] : arch;
        return machine.isEmpty() ? name : name + ", " + machine;
    }

    private static Map<String, String> modOwners(IncidentModel model) {
        Map<String, String> owners = new HashMap<>();
        for (JsonElement element : model.array("plugins")) {
            JsonArray plugin = array(element);
            if (!"true".equals(text(at(plugin, 3)))) owners.put(text(at(plugin, 1)).toLowerCase(Locale.ROOT), text(at(plugin, 1)));
        }
        for (JsonElement element : array(model.object("sys").get("loaders"))) owners.put(text(at(array(element), 0)).toLowerCase(Locale.ROOT), text(at(array(element), 0)));
        String bootstrapper = opt(model.object("mixins").get("bootstrapper")).split(" ", 2)[0];
        if (!bootstrapper.isEmpty()) owners.put(bootstrapper.toLowerCase(Locale.ROOT), bootstrapper);
        return owners;
    }

    private static String modLabel(String id, Map<String, String> owners) {
        if (id.indexOf(':') >= 0 || id.indexOf('.') < 0) return modName(id);
        return owners.get(id.substring(id.lastIndexOf('.') + 1).toLowerCase(Locale.ROOT));
    }

    private static boolean visible(double amount, double total) { return total > 0 && 100 * amount / total >= 0.05; }

    private static String playerCount(JsonElement value) {
        if (!present(value)) return "Players not captured";
        double players = number(value, 0);
        return count(players) + (players == 1 ? " player" : " players");
    }

    static List<String> consoleSummary(IncidentModel model) {
        JsonObject i = model.object("incident");
        List<String> first = new ArrayList<>();
        if (present(i.get("severity"))) first.add(DashboardModel.severityLabel(text(i, "severity")));
        if (present(i.get("trigger"))) first.add(trigger(i));
        if (WorldNames.isWorld(opt(i.get("world")))) first.add(text(i, "world"));
        List<String> second = new ArrayList<>();
        if (present(i.get("created"))) second.add(text(i, "created"));
        if (present(i.get("stallMs"))) second.add("stall " + count(number(i.get("stallMs"), 0)) + " ms");
        if (present(i.get("players"))) second.add(playerCount(i.get("players")));
        if (present(i.get("samples"))) second.add(count(number(i.get("samples"), 0)) + " samples");
        List<String> third = new ArrayList<>();
        String blocked = model.blockedIn();
        if (blocked != null && !blocked.isBlank() && !blocked.equals("Not captured")) third.add(StalledThread.frameVerb(stalledState(model)) + " " + blocked);
        List<String> lines = new ArrayList<>();
        lines.add(model.id() + (first.isEmpty() ? "" : " · " + String.join(" · ", first)));
        if (!second.isEmpty()) lines.add(String.join(" · ", second));
        if (!third.isEmpty()) lines.add(String.join(" · ", third));
        return lines;
    }

    private static void systemFacts(Plan p) {
        JsonObject s = object(p.use("sys"));
        if (s.isEmpty()) { p.row("System capture", "Not captured"); return; }
        JsonObject container = object(s.get("container")), disk = object(s.get("disk")), swap = object(s.get("swap"));
        double limit = number(container.get("memLimit"), 0), xmx = number(s.get("xmxBytes"), 0);
        double cpuLimit = number(container.get("cpuLimit"), 0), cores = number(s.get("cpuCores"), 0);
        p.card("Processor and memory");
        p.known("CPU", s.get("cpuBrand"));
        List<String> threads = new ArrayList<>();
        if (present(s.get("cpuCores"))) threads.add(text(s, "cpuCores") + " cores");
        if (present(s.get("hwThreads"))) threads.add(text(s, "hwThreads") + " hardware threads");
        if (!threads.isEmpty()) p.row("Cores", String.join(", ", threads));
        if (cpuLimit > 0) p.row("CPU limit", cores > 0 && cpuLimit >= cores ? "Unlimited" : decimal(cpuLimit, 1) + " cores");
        if (number(s.get("cpuStealPct"), -1) >= 0) p.row("CPU steal", decimal(number(s.get("cpuStealPct"), 0), 1) + "% of the window");
        double ramTotal = number(s.get("ramTotal"), 0), hostUsed = number(s.get("hostMemUsed"), -1);
        if (ramTotal > 0) p.row("Machine RAM", gib(ramTotal) + " GiB" + (hostUsed >= 0 ? ", " + gib(hostUsed) + " GiB used" : ""));
        if (number(swap.get("total"), 0) > 0) p.row("Swap used", bytes(Math.max(0, number(swap.get("total"), 0) - number(swap.get("free"), 0))) + " of " + bytes(number(swap.get("total"), 0)));
        if (number(s.get("containerMemUsed"), -1) >= 0) p.row("Container memory", bytes(number(s.get("containerMemUsed"), 0)) + (limit > 0 ? " of " + bytes(limit) : ""));
        if (number(disk.get("total"), 0) > 0) p.row("Disk", gib(number(disk.get("free"), 0)) + " GiB free of " + gib(number(disk.get("total"), 0)) + " GiB");
        p.card("Software");
        if (present(s.get("os"))) p.row("OS", osLabel(text(s, "os"), opt(s.get("arch"))));
        String jvm = opt(s.get("jvm")), vendor = opt(s.get("javaVendor"));
        String javaShown = present(s.get("javaShort")) ? text(s, "javaShort") : JavaRuntime.shortName(vendor, opt(s.get("javaVersion")), jvm);
        if (javaShown != null && !javaShown.isEmpty()) p.row("Java", javaShown, String.join(" · ", java.util.stream.Stream.of(jvm, vendor).filter(part -> !part.isEmpty()).toList()));
        if (present(s.get("hytale"))) p.row("Hytale", "Hytale Dedicated " + text(s, "hytale"));
        if (present(s.get("maxViewRadius"))) p.row("Max view radius", count(number(s.get("maxViewRadius"), 0), "chunk"),
            "MaxViewRadius in the server config.json caps how far each player loads chunks");
        if (number(s.get("uptimeMs"), 0) > 0) p.row("Uptime", duration(number(s.get("uptimeMs"), 0)));
        if (limit > 0 && xmx > limit * 0.9) {
            String runtime = present(container.get("runtime")) ? text(container, "runtime") + " " : "";
            p.card("Container memory risk");
            p.row("Heap and container limit", "The heap limit (-Xmx " + gib(xmx) + " GiB) is " + Math.round(100 * xmx / limit) + "% of the " + runtime
                + "container's " + gib(limit) + " GiB. The container can kill the server. Set -Xmx to about 75% of the container limit.");
        }
        double free = number(disk.get("free"), -1), total = number(disk.get("total"), 0);
        if (free >= 0 && total > 0 && (free < total * 0.05 || free < 2 * 1073741824.0)) { p.card("Low disk space"); p.row("Free space", gib(free) + " GiB free of " + gib(total) + " GiB: the JFR recording and incident bundles can fail to write."); }
    }

    private enum Kind { CARD, TEXT, PARAGRAPH, LEGEND, SHARE, EVIDENCE, TABLE, THREAD, THREAD_AXIS, CHART, LINK, FLAME, COMPARE }
    private record Block(Kind kind, String label, String value, JsonElement data, double share, String action, String arg, JsonArray timeline) {
        Block {
            label = screen(label);
            value = screen(value);
        }
        Block(Kind kind, String label, String value, JsonElement data, double share, String action, String arg) {
            this(kind, label, value, data, share, action, arg, null);
        }
    }
    private static final class Plan {
        final IncidentModel model;
        final List<Block> blocks = new ArrayList<>();
        Plan(IncidentModel model) { this.model = model; }
        JsonElement use(String key) { return model.field(key); }
        void card(String title) { blocks.add(new Block(Kind.CARD, title, "", JsonNull.INSTANCE, 0, "", "")); }
        void known(String label, JsonElement value) { if (present(value)) row(label, value.isJsonPrimitive() && value.getAsJsonPrimitive().isNumber() ? number(value, 0) == Math.rint(number(value, 0)) ? count(number(value, 0)) : decimal(number(value, 0), 2) : text(value)); }
        void paragraph(String value) { blocks.add(new Block(Kind.PARAGRAPH, "", value, JsonNull.INSTANCE, 0, "", "")); }
        void rest(double hidden, String unit) { if (hidden > 0) paragraph("And " + count(hidden, unit) + "."); }
        void row(String label, String value) { row(label, value, ""); }
        void row(String label, String value, String tip) {
            if (value.isEmpty()) value = "None";
            for (int from = 0; from < value.length();) {
                int end = Math.min(value.length(), from + ROW_CHARS), next = end;
                if (end < value.length()) {
                    int line = value.lastIndexOf('\n', end - 1), comma = value.lastIndexOf(", ", end - 2), dot = value.lastIndexOf(" · ", end - 3);
                    int cut = Math.max(line, Math.max(comma, dot));
                    if (cut > from && cut == line) { end = line; next = line + 1; }
                    else if (cut > from && cut == comma) { end = comma + 1; next = comma + 2; }
                    else if (cut > from) { end = dot; next = dot + 3; }
                }
                blocks.add(new Block(Kind.TEXT, label + (from > 0 ? " · continued" : ""), value.substring(from, end), JsonNull.INSTANCE, 0, "", from > 0 ? "" : tip));
                from = next;
            }
        }
        void rich(String label, JsonElement spans) {
            if (spans(spans).length() > ROW_CHARS) { row(label, spans(spans)); return; }
            blocks.add(new Block(Kind.TEXT, label, spans(spans), spans == null ? JsonNull.INSTANCE : spans, 0, "", ""));
        }
        void share(String title, String value, double ratio) { blocks.add(new Block(Kind.SHARE, title, value, JsonNull.INSTANCE, ratio, "", "")); }
        void table(String title, String facts, String amount, String details) { table(title, facts, amount, details, -1); }
        void table(String title, String facts, String amount, String details, double share) { table(title, facts, amount, details, share, ""); }
        void table(String title, String facts, String amount, String details, double share, String rank) { table(title, facts, amount, details, share, rank, ""); }
        void table(String title, String facts, String amount, String details, double share, String rank, String tip) {
            if (details.length() > ROW_CHARS) {
                String head = String.join(" · ", java.util.stream.Stream.of(facts, amount).filter(part -> !part.isEmpty()).toList());
                row(rank.isEmpty() ? title : rank + " " + title, head.isEmpty() ? details : head + "\n" + details);
                return;
            }
            JsonObject cells = new JsonObject(); cells.addProperty("amount", amount); cells.addProperty("details", details); cells.addProperty("rank", rank); cells.addProperty("tip", tip);
            blocks.add(new Block(Kind.TABLE, title, facts, cells, share, "", ""));
        }
        void link(String title, String value, String action, String arg) { blocks.add(new Block(Kind.LINK, title, value, JsonNull.INSTANCE, 0, action, arg)); }
        void chart(String title, JsonElement data, String unit, String key) { chart(title, data, unit, key, ""); }
        void chart(String title, JsonElement data, String unit, String key, String world) {
            if (!dataIsSeries(data)) { row(title, "Not captured"); return; }
            blocks.add(new Block(Kind.CHART, title, unit, data, 0, world, key));
        }
        void series(String... keys) {
            JsonObject series = object(use("series"));
            for (String key : keys) chart(label(key), series.get(key), unit(key), key);
        }
    }

    private static List<List<Block>> pages(Plan plan, String section) {
        List<Block> blocks = plan.blocks;
        boolean tableSection = section.equals("plugins");
        List<List<Block>> result = new ArrayList<>();
        if (!tableSection) {
            List<Block> page = new ArrayList<>(); int nonCharts = 80;
            for (Block block : blocks) {
                int cost = switch (block.kind) { case TABLE -> 8; case TEXT, SHARE, COMPARE -> 5; case CARD -> 3; case EVIDENCE -> 3; case CHART -> 47; default -> 0; };
                if (nonCharts + cost > 1320 && !page.isEmpty()) { result.add(page); page = new ArrayList<>(); nonCharts = 80; }
                page.add(block); nonCharts += cost;
            }
            if (!page.isEmpty()) result.add(page);
        } else {
            List<Block> common = blocks.stream().filter(b -> b.kind != Kind.TABLE).toList();
            List<Block> rows = blocks.stream().filter(b -> b.kind == Kind.TABLE).toList();
            if (rows.isEmpty()) result.add(blocks);
            else for (int first = 0; first < rows.size(); first += PAGE_COST) {
                List<Block> page = new ArrayList<>(common); page.addAll(rows.subList(first, Math.min(rows.size(), first + PAGE_COST))); result.add(page);
            }
        }
        return result.isEmpty() ? List.of(List.of()) : result;
    }

    private static int[] budget(List<Block> blocks) {
        int base = 80, charts = 0, threadStrips = 0;
        for (Block b : blocks) {
            if (b.kind == Kind.CHART) charts++;
            else if (b.kind == Kind.THREAD) {
                base += 17;
                if (b.timeline != null && !b.timeline.isEmpty()) threadStrips++;
            } else base += switch (b.kind) { case TABLE -> 8; case CARD, EVIDENCE -> 3; case TEXT, SHARE, COMPARE -> 5; case PARAGRAPH -> 2; case LEGEND -> 21; case THREAD_AXIS -> 6; case FLAME -> 1353; default -> 1; };
        }
        int elementTarget = threadStrips > 0 ? 1100 : 1400;
        int free = elementTarget - base - charts * 27, users = charts + threadStrips;
        int cells = users == 0 ? 0 : Math.max(0, Math.min(HyboxChartRenderer.HOVER_CELLS, free / (3 * users)));
        int columns = Math.max(1, Math.min(CHART_COLUMNS, (free - users * cells) / Math.max(1, charts * 2 + threadStrips)));
        return new int[] {columns, cells};
    }

    private static void chart(IncidentModel model, ViewState state, Block block, String parent, String selector, int columnCap, int hoverCells, ChartImages images, UICommandBuilder cmd) {
        JsonArray series = array(block.data); int[] range = range(model, state, series.size());
        double min = Double.POSITIVE_INFINITY, max = Double.NEGATIVE_INFINITY, sum = 0, now = 0; int samples = 0;
        for (int i = range[0]; i < range[1]; i++) {
            if (!present(series.get(i))) continue;
            double n = number(series.get(i), 0); min = Math.min(min, n); max = Math.max(max, n); sum += n; now = n; samples++;
        }
        String title = block.label;
        if (block.arg.equals("netOut") && present(model.object("net").get("iface"))) title += " · " + text(model.object("net"), "iface");
        if (samples > 0 && min == max) {
            cmd.append(parent, "Pages/HyboxIncidentParagraph.ui");
            cmd.set(selector + " #Paragraph.Text", title + ": " + chartValue(min, block.value, Set.of("count", "players", "entities", "chunks").contains(block.value) ? 0 : 1) + " the whole time.");
            return;
        }
        cmd.append(parent, "Pages/HyboxIncidentChart.ui");
        if (samples == 0) { min = 0; max = 0; }
        double average = samples == 0 ? 0 : sum / samples;
        if (state.timeRangeId().equals("all")) {
            JsonObject heap = model.object("heap"), net = model.object("net"), tps = model.object("tps");
            switch (block.arg) {
                case "heap" -> { min = number(heap.get("floor"), min); max = number(heap.get("peak"), max); }
                case "tps" -> { min = number(tps.get("min"), min); average = number(tps.get("avg"), average); }
                case "netOut" -> { average = number(net.get("outAvg"), average); max = number(net.get("outPeak"), max); }
                default -> { }
            }
        }
        String extra = "";
        JsonObject plugin = block.arg.startsWith(PLUGIN_METRIC_KEY)
            ? object(model.array("pluginMetrics").get(Integer.parseInt(block.arg.substring(PLUGIN_METRIC_KEY.length())))) : null;
        boolean rate = plugin != null && block.value.equals("/s");
        if (plugin != null && "count".equals(text(plugin, "kind"))) {
            double total = sum;
            if (rate) {
                JsonArray amounts = array(plugin.get("amounts"));
                total = 0;
                if (state.timeRangeId().equals("all") || amounts.isEmpty()) total = number(plugin.get("total"), 0);
                else for (int i = range[0]; i < Math.min(range[1], amounts.size()); i++) total += number(amounts.get(i), 0);
            }
            extra += " · total " + count(total);
            if (present(plugin.get("cumulative"))) extra += " · since start " + count(number(plugin.get("cumulative"), 0));
        }
        if (block.arg.equals("mspt") || block.arg.equals("msptIncident")) {
            JsonObject incident = model.object("incident");
            boolean stalledWorld = block.action.isEmpty() || worldKey(block.action).equals(worldKey(opt(incident.get("world"))));
            double marker = number(incident.get("markerFrac"), 1) * Math.max(0, series.size() - 1);
            boolean stall = present(incident.get("stallMs")) && stalledWorld && marker >= range[0] - 1e-9 && marker <= range[1] - 1 + 1e-9;
            JsonArray worst = array(model.object("series").get(block.arg.equals("mspt") ? "msptWorst" : "msptWorstIncident"));
            boolean full = !worst.isEmpty() && worst.asList().stream().allMatch(HyboxIncidentRenderer::present);
            double top = Double.NEGATIVE_INFINITY;
            if (full) for (int i = range[0]; i < Math.min(range[1], worst.size()); i++) top = Math.max(top, number(worst.get(i), 0));
            JsonObject snapshot = worldSnapshot(model, block.action);
            if (state.timeRangeId().equals("all") && present(snapshot.get("msptMax"))) top = Math.max(top, number(snapshot.get("msptMax"), 0));
            if (top > max) extra += " · worst tick " + decimal(top, 1) + " ms";
            if (stall) extra += " · stall " + millis(number(incident.get("stallMs"), 0));
        }
        cmd.set(selector + " #ChartTitle.Text", title);
        if (plugin != null) cmd.set(selector + " #ChartTitle.TooltipText", text(plugin, "name"));
        int precision = Set.of("count", "players", "entities", "chunks").contains(block.value) || rate && max >= 100 ? 0 : 1;
        double factor = block.value.equals("GiB") ? 1073741824.0 : block.value.equals("GB") ? 1e9 : 1;
        double span = Math.abs(max - min) / factor;
        if (span > 0) precision = Math.max(precision, Math.min(6, (int) Math.ceil(-Math.log10(span))));
        String unit = block.value.equals("bytes") ? "" : HyboxChartRenderer.suffix(block.value);
        axes(model, selector, range, series.size(), cmd);
        int count = range[1] - range[0];
        double[] values = new double[count];
        for (int i = 0; i < count; i++) {
            JsonElement value = series.get(range[0] + i);
            values[i] = present(value) ? Math.max(0, number(value, 0)) : Double.NaN;
        }
        HyboxChartRenderer.Mark mark = mark(model);
        double marker = mark == null ? -1 : mark.frac() * Math.max(0, series.size() - 1);
        double[] markers = mark != null && marker >= range[0] && marker <= range[1] - 1
            ? new double[] {marker - range[0]} : new double[0];
        ChartAxis.Axis axis = HyboxChartRenderer.plot(selector, values, markers, columnCap, block.value, precision, family(block.arg),
            reference(model, block), mark, images, cmd);
        String scale = axis.log() ? ", log scale" : "";
        String stats = rate
            ? "avg " + stat(average, block.value, precision) + " · max " + stat(max, block.value, precision) + unit + scale + extra
            : "min " + stat(min, block.value, precision) + " · avg " + stat(average, block.value, precision) + " · max " + stat(max, block.value, precision)
                + " · last " + stat(now, block.value, precision) + unit + scale + extra;
        cmd.set(selector + " #ChartStats.Text", stats);
        int cells = Math.min(hoverCells, count);
        List<String> tips = new ArrayList<>();
        for (int c = 0; c < cells; c++) {
            int from = c * count / cells, to = (c + 1) * count / cells;
            double cellSum = 0; int n = 0;
            for (int i = from; i < to; i++) if (!Double.isNaN(values[i])) { cellSum += values[i]; n++; }
            boolean marked = markers.length > 0 && Math.round(markers[0]) >= from && Math.round(markers[0]) < to;
            tips.add(timeAt(model, range[0] + from, series.size()) + " · " + (n == 0 ? "no data" : chartValue(cellSum / n, block.value, precision))
                + (marked ? " · " + mark.word() : ""));
        }
        HyboxChartRenderer.hover(cmd, selector + " #Hover", CHART_WIDTH, 96, tips);
    }

    private static ChartAxis.Family family(String key) {
        return switch (key) {
            case "mspt", "msptIncident" -> ChartAxis.Family.TICK_TIME;
            case "tps", "worldTps" -> ChartAxis.Family.TPS;
            case "heap" -> ChartAxis.Family.HEAP;
            case "netOut", "netIn" -> ChartAxis.Family.NETWORK;
            default -> ChartAxis.Family.OTHER;
        };
    }

    private static HyboxChartRenderer.Ref reference(IncidentModel model, Block block) {
        String key = block.arg;
        double target = number(worldSnapshot(model, block.action).get("targetTps"), number(model.object("tps").get("target"), 30));
        if (!(target > 0)) target = 30;
        return switch (key) {
            case "mspt", "msptIncident" -> new HyboxChartRenderer.Ref(1000 / target, decimal(1000 / target, 1) + " ms budget");
            case "tps", "worldTps" -> new HyboxChartRenderer.Ref(target, (target == Math.rint(target) ? count(target) : decimal(target, 1)) + " TPS target", true);
            default -> null;
        };
    }

    private static String stat(double n, String unit, int precision) {
        return unit.equals("bytes") ? bytes(n) : chartNumber(n, unit, precision);
    }

    private static String interval(IncidentModel model, JsonElement series) {
        double window = number(model.object("incident").get("windowSec"), 0);
        int size = array(series).size();
        return window > 0 && size > 1 ? "per " + Units.elapsed(Math.round(window * 1000 / size)) : "per sample";
    }

    static String stateColor(int code) {
        return HyboxChartImage.hex(HyboxChartImage.STATES[Math.max(1, Math.min(HyboxChartImage.STATES.length - 1, code))]);
    }
    private static int[] stateCodes(JsonArray series, int[] range) {
        int[] codes = new int[Math.max(0, range[1] - range[0])];
        for (int i = 0; i < codes.length; i++) codes[i] = Math.max(0, Math.min(4, (int) number(series.get(range[0] + i), 0)));
        return codes;
    }
    private static List<String> stateTips(IncidentModel model, JsonArray series, int[] range, int hoverCells) {
        int count = range[1] - range[0], cells = Math.min(hoverCells, Math.max(0, count));
        List<String> tips = new ArrayList<>();
        int[] counts = new int[5];
        for (int c = 0; c < cells; c++) {
            int from = range[0] + c * count / cells, to = range[0] + (c + 1) * count / cells;
            java.util.Arrays.fill(counts, 0);
            for (int i = from; i < to; i++) counts[Math.max(0, Math.min(4, (int) number(series.get(i), 0)))]++;
            int value = 0;
            for (int candidate = 1; candidate < counts.length; candidate++) if (counts[candidate] > counts[value]) value = candidate;
            String name = value == 0 ? "no samples" : StalledThread.stateLabel(switch (value) { case 1 -> "RUNNABLE"; case 2 -> "WAITING"; case 3 -> "TIMED_WAITING"; default -> "BLOCKED"; });
            tips.add(timeAt(model, from, series.size()) + " to " + timeAt(model, to - 1, series.size()) + " · " + name);
        }
        return tips;
    }
    private static String timeAt(IncidentModel model, int index, int size) {
        JsonObject incident = model.object("incident");
        return clock(text(incident, "recStart"), number(incident.get("windowSec"), 0) * index / Math.max(1, size - 1));
    }

    private record FlameSegment(JsonArray node, String path, int left, int width, int depth) {}
    private static List<FlameSegment> flameSegments(IncidentModel model, ViewState state) {
        List<FlameSegment> segments = new ArrayList<>();
        flameSegments(flameRoot(model, state.flameZoomPath()), state.flameZoomPath(), 0, PLOT_WIDTH, 0, segments);
        return segments;
    }
    private static void flameSegments(JsonArray node, String path, int left, int width, int depth, List<FlameSegment> segments) {
        if (depth >= 12 || width < 2 || segments.size() >= 450) return;
        segments.add(new FlameSegment(node, path, left, width, depth));
        double total = number(at(node, 1), 0); double start = 0;
        JsonArray children = array(at(node, 2));
        for (int i = 0; i < children.size(); i++) {
            JsonArray child = array(children.get(i)); double amount = number(at(child, 1), 0);
            int x = (int) Math.round(start * width / Math.max(1, total)); start += amount;
            int childWidth = (int) Math.round(start * width / Math.max(1, total)) - x;
            flameSegments(child, childPath(path, i), left + x, childWidth, depth + 1, segments);
        }
    }
    private static final int FLAME_BUTTON_MIN = 12;
    private static void flame(IncidentModel model, ViewState state, String parent, String selector, ChartImages images, UICommandBuilder cmd, UIEventBuilder ev) {
        cmd.appendInline(parent, "Group #FlameArea { Anchor: (Width: " + PLOT_WIDTH + ", Height: 300, Bottom: 10); "
            + "AssetImage #FlameImage { Anchor: (Left: 0, Top: 0, Width: " + PLOT_WIDTH + ", Height: 300); Visible: false; } "
            + "Group #FlameFrames { Anchor: (Full: 0); } }");
        List<HyboxChartImage.FlameRect> narrow = new ArrayList<>();
        int i = 0;
        for (FlameSegment segment : flameSegments(model, state)) {
            int kind = (int) number(at(segment.node, 3), 0);
            if (images != null && segment.width < FLAME_BUTTON_MIN) {
                narrow.add(new HyboxChartImage.FlameRect(segment.left, segment.depth * 25, Math.max(1, segment.width - 1), 23, kind));
                continue;
            }
            cmd.append("#FlameFrames", "Pages/HyboxIncidentFlame.ui");
            String s = "#FlameFrames[" + i++ + "] #Frame";
            cmd.setObject(s + ".Anchor", anchor(segment.left, segment.depth * 25, Math.max(1, segment.width - 1), 23));
            cmd.set(s + " #FrameLabel.Text", segment.width >= 60 ? text(at(segment.node, 0)) : "");
            cmd.set(s + ".TooltipText", text(at(segment.node, 0)) + " · " + flameFacts(segment.node));
            String style = switch (kind) { case 1 -> "EngineStyle"; case 2 -> "ModStyle"; case 3 -> "JdkStyle"; default -> "DefaultStyle"; };
            cmd.set(s + ".Style", com.hypixel.hytale.server.core.ui.Value.ref("Pages/HyboxIncidentFlame.ui", style));
            bind(ev, s, "zoom", segment.path);
        }
        if (!narrow.isEmpty()) {
            List<HyboxChartImage.FlameRect> frames = List.copyOf(narrow);
            images.request(new ChartRequest(() -> HyboxChartImage.flame(frames, PLOT_WIDTH, 300), (path, swap) -> {
                swap.set("#FlameImage.AssetPath", path);
                swap.set("#FlameImage.Visible", true);
            }));
        }
    }
    static com.hypixel.hytale.server.core.ui.Anchor anchor(int left, int top, int width, int height) {
        var anchor = new com.hypixel.hytale.server.core.ui.Anchor();
        anchor.setLeft(com.hypixel.hytale.server.core.ui.Value.of(left));
        anchor.setTop(com.hypixel.hytale.server.core.ui.Value.of(top));
        anchor.setWidth(com.hypixel.hytale.server.core.ui.Value.of(width));
        anchor.setHeight(com.hypixel.hytale.server.core.ui.Value.of(height));
        return anchor;
    }

    private static String flameLabel(JsonArray node) {
        String label = opt(at(node, 4));
        return label.isEmpty() ? "" : " · " + label;
    }

    private static String ownerSuffix(JsonArray node) {
        return switch ((int) number(at(node, 3), 0)) { case 1 -> " · engine"; case 2 -> " · mod"; case 3 -> " · JDK"; default -> ""; };
    }

    private static JsonArray flameRoot(IncidentModel model, String path) {
        JsonArray node = model.array("flame");
        if (!path.isEmpty()) for (String index : path.split("/")) {
            try { JsonArray children = array(at(node, 2)); int i = Integer.parseInt(index); if (i < 0 || i >= children.size()) break; node = array(children.get(i)); }
            catch (NumberFormatException ignored) { break; }
        }
        return node;
    }
    private static String flameBreadcrumb(IncidentModel model, String path) {
        StringBuilder text = new StringBuilder(text(at(model.array("flame"), 0)));
        JsonArray node = model.array("flame");
        if (!path.isEmpty()) for (String index : path.split("/")) {
            try { JsonArray children = array(at(node, 2)); int i = Integer.parseInt(index); if (i < 0 || i >= children.size()) break; node = array(children.get(i)); text.append(" › ").append(text(at(node, 0))); }
            catch (NumberFormatException ignored) { break; }
        }
        return text.toString();
    }
    private static String childPath(String parent, int child) { return parent.isEmpty() ? Integer.toString(child) : parent + "/" + child; }
    private static String parentPath(String path) { int slash = path.lastIndexOf('/'); return slash < 0 ? "" : path.substring(0, slash); }
    private static String flameFacts(JsonArray node) {
        JsonArray children = array(at(node, 2));
        double leftover = number(at(node, 1), 0) - children.asList().stream().mapToDouble(c -> number(at(array(c), 1), 0)).sum();
        return count(number(at(node, 1), 0), "sample") + (!children.isEmpty() && leftover > 0 ? " · " + count(leftover, "sample") + " in frames too small to show" : "")
            + ownerSuffix(node) + flameLabel(node);
    }

    private static Map<String, String> presets(IncidentModel model) {
        Map<String, String> presets = new LinkedHashMap<>();
        double window = number(model.object("incident").get("windowSec"), 0);
        HyboxChartRenderer.Mark mark = mark(model);
        if (mark != null && mark.kind().equals("incident")) presets.put("incident", "Incident");
        if (mark != null && mark.kind().equals("profile")) presets.put("profile", "Profile");
        int buckets = model.object("series").entrySet().stream().mapToInt(e -> array(e.getValue()).size()).max().orElse(1);
        double bucketSeconds = buckets > 1 ? window / buckets : 0;
        int[] durations = {60, 120, 300, 600, 900, 1800, 3600, 7200, 21600, 43200, 86400, 172800, 604800, 1209600, 2592000};
        List<Integer> picked = new ArrayList<>();
        for (int i = durations.length - 1; i >= 0 && picked.size() < 5; i--) {
            int duration = durations[i];
            if (duration >= window / 1.05 || bucketSeconds > 0 && duration < 10 * bucketSeconds) continue;
            if (!picked.isEmpty() && picked.getLast() / (double) duration < 3) continue;
            picked.add(duration);
        }
        Collections.reverse(picked);
        for (int duration : picked) presets.put(duration == 60 ? "1m" : duration == 300 ? "5m" : "d" + duration, duration >= 86400 ? duration / 86400 + "d" : duration >= 3600 ? duration / 3600 + "h" : duration / 60 + "m");
        presets.put("all", "All");
        return presets;
    }

    private static String rangeScope(String rangeId) {
        if (rangeId.equals("incident")) return "From 1 min before the incident";
        if (rangeId.equals("profile")) return "From the profile start";
        int seconds = rangeId.startsWith("d") ? Integer.parseInt(rangeId.substring(1)) : rangeId.equals("5m") ? 300 : 60;
        return "Last " + (seconds >= 86400 ? seconds / 86400 + " d" : seconds >= 3600 ? seconds / 3600 + " h" : seconds / 60 + " min");
    }

    private static int[] range(IncidentModel model, ViewState state, int size) {
        if (size == 0) return new int[]{0, 0};
        double window = number(model.object("incident").get("windowSec"), 0);
        if (state.timeRangeId().equals("all") || window <= 0) return new int[]{0, size};
        if (state.timeRangeId().equals("profile")) {
            HyboxChartRenderer.Mark mark = mark(model);
            double start = mark == null ? 0 : mark.frac();
            return new int[]{Math.max(0, Math.min(size - 1, (int) Math.floor(start * (size - 1)))), size};
        }
        double seconds = state.timeRangeId().startsWith("d") ? Double.parseDouble(state.timeRangeId().substring(1)) : state.timeRangeId().equals("5m") ? 300 : 60;
        double marker = number(model.object("incident").get("markerFrac"), 1);
        double start = Math.max(0, (state.timeRangeId().equals("incident") ? marker : 1) - seconds / window);
        return new int[]{Math.max(0, Math.min(size - 1, (int) Math.floor(start * (size - 1)))), size};
    }
    private static void axes(IncidentModel model, String selector, int[] range, int size, UICommandBuilder cmd) {
        JsonObject incident = model.object("incident"); double window = number(incident.get("windowSec"), 0);
        int last = Math.max(range[0], range[1] - 1);
        for (int i = 0; i < 5; i++) {
            double sample = range[0] + (last - range[0]) * i / 4.0;
            cmd.set(selector + " #X" + i + ".Text", clock(text(incident, "recStart"), window * sample / Math.max(1, size - 1)));
        }
    }
    private static String clock(String start, double offset) {
        try { return LocalTime.parse(start).plusSeconds((long) offset).format(DateTimeFormatter.ofPattern("HH:mm:ss")); }
        catch (java.time.DateTimeException ignored) { return "+" + decimal(offset, 0) + "s"; }
    }
    private static boolean present(JsonElement value) { return value != null && !value.isJsonNull(); }
    private static String opt(JsonElement value) { return present(value) ? text(value) : ""; }
    private static boolean split(JsonArray rows, int self) {
        for (JsonElement row : rows) if (array(row).size() > self) return true;
        return false;
    }
    private static List<JsonArray> modRows(JsonArray rows, int self) {
        List<JsonArray> result = new ArrayList<>();
        for (JsonElement row : rows) {
            JsonArray a = array(row);
            if (a.size() > self && (number(at(a, self), 0) > 0 || number(at(a, self + 1), 0) > 0)) result.add(a);
        }
        result.sort((a, b) -> number(at(a, self), 0) != number(at(b, self), 0) ? Double.compare(number(at(b, self), 0), number(at(a, self), 0))
            : Double.compare(number(at(b, self + 1), 0), number(at(a, self + 1), 0)));
        return result;
    }
    private static String shortMethod(String method) {
        int dot = method.lastIndexOf('.');
        if (dot <= 0) return method;
        String name = method.substring(dot + 1);
        Matcher handler = MIXIN_HANDLER.matcher(name);
        return method.substring(method.lastIndexOf('.', dot - 1) + 1, dot + 1) + (handler.matches() ? handler.group(1) + "..." + handler.group(2) : name);
    }
    private static String threadMods(JsonElement mods, double samples) {
        List<String> shown = new ArrayList<>(), sentences = new ArrayList<>();
        List<JsonArray> rows = array(mods).asList().stream().map(IncidentModel::array).filter(m -> 100 * number(at(m, 1), 0) / Math.max(1, samples) >= 0.5)
            .sorted((a, b) -> Double.compare(number(at(b, 1), 0), number(at(a, 1), 0))).limit(3).toList();
        for (JsonArray m : rows) {
            shown.add(modName(text(at(m, 0))) + " " + percent(number(at(m, 1), 0), samples));
            String sentence = ModNames.onStackSentence(text(at(m, 0)), Math.round(number(at(m, 2), 0)), Math.round(samples), "this thread's samples", null);
            if (sentence != null) sentences.add(sentence);
        }
        return shown.isEmpty() ? "" : "Mods on this thread: " + String.join(", ", shown) + "." + (sentences.isEmpty() ? "" : " " + String.join(" ", sentences));
    }

    private static String ownCode(String amount, String sentence) {
        return amount + " in its own code." + (sentence == null ? "" : " " + sentence);
    }
    private static boolean dataIsSeries(JsonElement value) { return value != null && value.isJsonArray() && !value.getAsJsonArray().isEmpty(); }
    private static String join(JsonArray a, String separator) { return String.join(separator, a.asList().stream().map(IncidentModel::text).toList()); }
    private static String chartNumber(double n, String unit, int precision) {
        if (unit.equals("GiB")) n /= 1073741824.0;
        if (unit.equals("GB")) n /= 1e9;
        return grouped(n, precision);
    }

    private static String chartValue(double n, String unit, int precision) {
        if (unit.equals("bytes")) return bytes(n);
        String text = chartNumber(n, unit, precision);
        return text + HyboxChartRenderer.suffix(unit, text);
    }
    private static String grouped(double n, int places) {
        String plain = decimal(n, places);
        int end = plain.indexOf('.') < 0 ? plain.length() : plain.indexOf('.'), start = plain.startsWith("-") ? 1 : 0;
        StringBuilder out = new StringBuilder(plain.length() + end / 3);
        for (int i = 0; i < plain.length(); i++) {
            if (i > start && i < end && (end - i) % 3 == 0) out.append(',');
            out.append(plain.charAt(i));
        }
        return out.toString();
    }
    private static String unit(String key) {
        return switch (key) {
            case "cpu", "cpuJvm", "gcTime" -> "%";
            case "heap", "heapCommitted" -> "GiB";
            case "rss", "hostMem", "disk" -> "GiB";
            case "diskRead", "diskWrite" -> "MiB/s";
            case "mspt", "msptWorst", "msptIncident", "msptWorstIncident", "gcPause", "ping" -> "ms";
            case "netIn", "netOut" -> "Mbit/s";
            case "tps" -> "TPS";
            default -> "count";
        };
    }
    private static String bytes(double n) { return Units.bytes(Math.round(n)); }
    private static String gib(double n) { return decimal(n / 1073741824.0, 1); }
    private static String duration(double ms) { return Units.elapsed(Math.max(0, Math.round(ms))); }
    private static String millis(double ms) { return ms >= 1000 ? Units.elapsed(Math.round(ms)) : decimal(ms, ms < 10 ? 2 : 1) + " ms"; }
    private static String rate(JsonElement mbits) { return present(mbits) ? decimal(number(mbits, 0), 1) + " Mbit/s" : "n/a"; }
    private static String percent(double amount, double total) { return total > 0 ? decimal(100 * amount / total, 1) + "%" : "n/a"; }
    private static String count(double n) { return Units.number(Math.round(n)); }
    private static String count(double n, String unit) { return Units.count(Math.round(n), unit); }
    private static String decimal(double n, int places) { return Units.decimal(n, places); }
    private static String threadGroup(String name) {
        Matcher id = THREAD_ID.matcher(name);
        if (id.matches()) return id.group(1) + "*";
        int i = name.length() - 1;
        while (i >= 0 && Character.isDigit(name.charAt(i))) i--;
        return i >= 0 && i < name.length() - 1 && (name.charAt(i) == '-' || name.charAt(i) == '#') ? name.substring(0, i) : name;
    }
    private static String threadLabel(String name) {
        Matcher id = THREAD_ID.matcher(name);
        if (!id.matches()) return name;
        int start = id.group(1).length();
        return name.substring(0, start + THREAD_ID_SHOWN);
    }
    private static String firstLines(String text, int lines) {
        String[] all = text.split("\n");
        if (all.length <= lines) return text;
        return String.join("\n", java.util.Arrays.asList(all).subList(0, lines)) + "\nand " + count(all.length - lines, "more method");
    }
    static String screen(String text) {
        if (text == null) return "";
        return text.replace("\u2192", ">").replace("\u2026", "...").replace("\u00d7", "x");
    }
    private static String modName(String id) {
        int colon = id.indexOf(':');
        return colon >= 0 && colon < id.length() - 1 ? id.substring(colon + 1) : id;
    }
    private static String worldKey(String world) { return world == null ? "" : world.toLowerCase(Locale.ROOT); }
    private static double worldCpu(IncidentModel model, JsonObject world) {
        String key = worldKey(text(world, "name"));
        double samples = 0; boolean found = false;
        for (JsonElement element : model.array("cpuByWorld")) {
            JsonObject entry = object(element);
            if (!key.isEmpty() && worldKey(text(entry, "world")).equals(key)) { samples += number(entry.get("samples"), 0); found = true; }
        }
        return found ? samples : number(world.get("samples"), 0);
    }
    private static void worldFacts(Plan p, JsonObject world, JsonArray types) {
        if (present(world.get("players"))) {
            List<String> names = array(world.get("playerNames")).asList().stream().map(IncidentModel::text).toList();
            p.row("Players", count(number(world.get("players"), 0)) + (names.isEmpty() ? "" : ": " + String.join(", ", names.subList(0, Math.min(WORLD_PLAYERS, names.size())))
                + (names.size() > WORLD_PLAYERS ? ", " + count(names.size() - WORLD_PLAYERS, "more player") : "")));
        }
        List<String> tick = new ArrayList<>();
        for (String[] part : new String[][] {{"mspt", "average"}, {"msptP95", "p95"}, {"msptMax", "worst"}}) {
            if (present(world.get(part[0]))) tick.add(millis(number(world.get(part[0]), 0)) + " " + part[1]);
        }
        if (!tick.isEmpty()) p.row("Tick time at capture", String.join(", ", tick));
        JsonElement tps = world.get("tps");
        if (present(tps) && tps.isJsonPrimitive()) p.row("TPS at capture", decimal(number(tps, 0), 1)
            + (present(world.get("targetTps")) ? ", target " + count(number(world.get("targetTps"), 0)) : ""));
        List<String> parts = new ArrayList<>();
        for (JsonElement type : array(types).asList().subList(0, Math.min(ENTITY_TYPES, array(types).size()))) parts.add(text(at(array(type), 0)) + " " + count(number(at(array(type), 1), 0)));
        if (present(world.get("entities")) || !parts.isEmpty()) p.row("Entities", (present(world.get("entities")) ? count(number(world.get("entities"), 0)) : "")
            + (present(world.get("entities")) && !parts.isEmpty() ? ". " : "") + String.join(", ", parts));
        List<String> chunks = new ArrayList<>();
        if (present(world.get("chunks"))) chunks.add(count(number(world.get("chunks"), 0)) + " in memory");
        if (present(world.get("chunksLoaded"))) chunks.add(count(number(world.get("chunksLoaded"), 0)) + " loaded in this window");
        if (present(world.get("chunksGenerated"))) chunks.add(count(number(world.get("chunksGenerated"), 0)) + " generated");
        if (!chunks.isEmpty()) p.row("Chunks", String.join(", ", chunks));
        JsonObject radius = object(world.get("radius"));
        if (radius.size() == 0) return;
        int players = (int) number(radius.get("players"), 0);
        String view = spread(array(radius.get("view")), players);
        if (!view.isEmpty()) p.row("View radius", view);
        String hot = spread(array(radius.get("hot")), players);
        double lowered = number(radius.get("hotLowered"), 0);
        if (!hot.isEmpty()) p.row("Hot radius", hot + (lowered > 0 ? ", lowered by a mod for " + count(lowered, "player") : ""));
        if (present(radius.get("sections"))) p.row("Loaded sections", count(number(radius.get("sections"), 0)) + " in total"
            + (present(radius.get("sectionsMax")) ? ", up to " + count(number(radius.get("sectionsMax"), 0)) + " for one player" : ""));
        List<String> top = new ArrayList<>();
        for (JsonElement entry : array(radius.get("top"))) {
            JsonArray load = array(entry);
            if (load.isEmpty()) continue;
            top.add(text(at(load, 0)) + " " + (present(at(load, 3)) ? count(number(at(load, 3), 0)) : "?") + " sections, view "
                + (present(at(load, 1)) ? count(number(at(load, 1), 0)) : "?") + ", hot " + (present(at(load, 2)) ? count(number(at(load, 2), 0)) : "?"));
        }
        if (!top.isEmpty()) p.row("Top players by loaded sections", String.join(". ", top) + ".");
    }

    private static String spread(JsonArray values, int players) {
        if (values.size() < 3 || !present(values.get(1))) return "";
        long low = Math.round(number(values.get(0), 0)), middle = Math.round(number(values.get(1), 0)), high = Math.round(number(values.get(2), 0));
        String who = " across " + count(players, "player");
        return low == high ? count(middle, "chunk") + who : "median " + middle + ", from " + low + " to " + count(high, "chunk") + who;
    }
    private static String label(String key) {
        CompareMetrics.Metric metric = CompareMetrics.metric(key);
        return metric != null ? metric.label() : key.replaceAll("([a-z])([A-Z])", "$1 $2").replace('_', ' ');
    }
    private static void bind(UIEventBuilder ev, String selector, String action, String arg) {
        ev.addEventBinding(CustomUIEventBindingType.Activating, selector, EventData.of("Action", action).append("Arg", arg));
    }
}
