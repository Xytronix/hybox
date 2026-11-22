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
import io.github.xytronix.hybox.core.text.Units;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

final class HyboxIncidentRenderer {
    record ViewState(String sectionId, int pageIndex, String flameZoomPath, String timeRangeId, boolean showCore) {
        ViewState {
            sectionId = sectionId != null && SECTIONS.containsKey(sectionId) ? sectionId : "top";
            pageIndex = Math.max(0, pageIndex);
            flameZoomPath = flameZoomPath == null ? "" : flameZoomPath;
            if (timeRangeId == null || !(Set.of("incident", "1m", "5m", "all").contains(timeRangeId) || timeRangeId.matches("d[0-9]+"))) timeRangeId = "all";
        }
        static ViewState initial() { return new ViewState("top", 0, "", "all", false); }
    }
    static final Map<String, String> SECTIONS;
    static {
        Map<String, String> sections = new LinkedHashMap<>();
        sections.put("top", "Overview"); sections.put("stalled", "Captured diagnostics");
        sections.put("diagnosis", "Diagnosis"); sections.put("metrics", "Key metrics");
        sections.put("cpu", "CPU & load"); sections.put("engine", "Engine systems");
        sections.put("methods", "Hot methods"); sections.put("flame", "Flame graph");
        sections.put("threads", "Hot threads"); sections.put("states", "Thread states");
        sections.put("memory", "Memory & GC"); sections.put("pluginmetrics", "Plugin metrics");
        sections.put("network", "Network"); sections.put("worlds", "Worlds");
        sections.put("system", "System & hardware"); sections.put("plugins", "Plugins");
        sections.put("mixins", "Mixins"); sections.put("assetpacks", "Asset packs");
        sections.put("log", "Server log"); sections.put("config", "Config files");
        SECTIONS = Collections.unmodifiableMap(sections);
    }
    private static final int PAGE_COST = 20;
    private static final int CHART_COLUMNS = 120;
    private static final int PLOT_WIDTH = 940;
    private static final int CHART_WIDTH = 880;
    private static final String PLUGIN_METRIC_KEY = "pluginMetric:";
    private static final Map<String, String> LABELS = labels();

    private HyboxIncidentRenderer() {}

    record ChartRequest(java.util.function.Supplier<byte[]> draw, java.util.function.BiConsumer<String, UICommandBuilder> apply) {}
    interface ChartImages { void request(ChartRequest request); }

    static void render(IncidentModel model, ViewState state, UICommandBuilder cmd, UIEventBuilder ev) {
        render(model, state, cmd, ev, null);
    }

    static void render(IncidentModel model, ViewState state, UICommandBuilder cmd, UIEventBuilder ev, ChartImages images) {
        header(model, cmd);
        int index = 0;
        for (var section : SECTIONS.entrySet()) {
            String selector = "#SectionNav[" + index++ + "]";
            cmd.set(selector + ".Text", (section.getKey().equals(state.sectionId()) ? "› " : "") + section.getValue()
                + (section.getKey().equals("mixins") ? mixinProblemSuffix(model) : ""));
            if (section.getKey().equals(state.sectionId())) cmd.set(selector + ".Style", com.hypixel.hytale.server.core.ui.Value.ref("Pages/HyboxIncidentButton.ui", "SelectedStyle"));
            else cmd.set(selector + ".Style", com.hypixel.hytale.server.core.ui.Value.ref("Pages/HyboxIncidentButton.ui", "DefaultStyle"));
            bind(ev, selector, "section", section.getKey());
            cmd.set(selector + ".Visible", section.getKey().equals(state.sectionId()) || hasContent(model, section.getKey()));
        }
        cmd.set("#SectionTitle.Text", SECTIONS.get(state.sectionId()));
        boolean timeSeriesSection = Set.of("metrics", "cpu", "engine", "states", "memory", "pluginmetrics", "network", "threads").contains(state.sectionId());
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
        cmd.clear("#Tiles"); cmd.clear("#SectionBody");
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
        for (Block block : visible) {
            if (block.kind == Kind.CARD) {
                cmd.append("#SectionBody", "Pages/HyboxIncidentCard.ui");
                String cardSelector = "#SectionBody[" + row++ + "]";
                cmd.set(cardSelector + " #CardTitle.Text", block.label);
                parent = cardSelector + " #CardRows";
                child = 0;
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
                case TIMELINE -> timeline(model, state, block, parent, selector, columns, hoverCells, images, cmd);
                case THREAD -> {
                    cmd.append(parent, "Pages/HyboxIncidentThread.ui");
                    JsonArray thread = array(block.data);
                    String name = block.label;
                    boolean hybox = name.startsWith("hybox-");
                    cmd.set(selector + " #Rank.Text", "#" + block.arg);
                    cmd.set(selector + " #Name.Text", name);
                    cmd.set(selector + " #Name.TooltipText", name);
                    cmd.set(selector + " #State.Visible", hybox);
                    if (hybox) {
                        cmd.set(selector + " #State.Text", "HYBOX");
                        cmd.set(selector + " #State.TooltipText", "Hybox itself, not your server");
                    }
                    cmd.set(selector + " #Samples.Text", count(number(at(thread, 0), 0)) + " samples");
                    cmd.set(selector + " #Self.Text", percent(number(at(thread, 0), 0), number(model.object("incident").get("samples"), 0)) + " self");
                    cmd.set(selector + " #Share.Value", Math.max(0, Math.min(1, block.share)));
                    String topLabel = opt(at(thread, 5));
                    cmd.set(selector + " #Top.Text", "Top method · " + text(at(thread, 3)) + (topLabel.isEmpty() ? "" : " · " + topLabel));
                    cmd.set(selector + " #Top.TooltipText", block.value.isEmpty() ? text(at(thread, 3)) + (topLabel.isEmpty() ? "" : "\n" + topLabel) : block.value);
                    cmd.set(selector + " #Methods.Text", block.value);
                    cmd.set(selector + " #Methods.Visible", !block.value.isEmpty());
                    JsonArray series = block.timeline;
                    boolean hasTimeline = series != null && !series.isEmpty();
                    cmd.set(selector + " #Strip.Visible", hasTimeline);
                    if (hasTimeline) {
                        int[] selected = range(model, state, series.size());
                        HyboxChartRenderer.hover(cmd, selector + " #StripHover", PLOT_WIDTH, 12, stateTips(model, series, selected, hoverCells));
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
                            if (column > 0 && value != previous) {
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
                    cmd.set(selector + " #Evidence.TextSpans", styledSpans(entry.get("spans")));
                    String target = text(entry, "section");
                    boolean linked = SECTIONS.containsKey(target);
                    cmd.set(selector + " #EvidenceLink.Visible", linked);
                    if (linked) {
                        String caption = text(entry, "label");
                        caption = caption.isEmpty() ? SECTIONS.get(target) : Character.toUpperCase(caption.charAt(0)) + caption.substring(1);
                        cmd.set(selector + " #EvidenceLink.Text", caption);
                        cmd.set(selector + " #EvidenceLink.TooltipText", "Go to " + SECTIONS.get(target));
                        bind(ev, selector + " #EvidenceLink", "section", target);
                    }
                }
                case TABLE -> {
                    cmd.append(parent, "Pages/HyboxIncidentTable.ui");
                    JsonObject cells = object(block.data);
                    cmd.set(selector + " #Name.Text", block.label);
                    cmd.set(selector + " #Facts.Text", block.value);
                    cmd.set(selector + " #Amount.Text", text(cells, "amount"));
                    cmd.set(selector + " #Details.Text", text(cells, "details"));
                    cmd.set(selector + " #Details.Visible", !text(cells, "details").isEmpty());
                }
                case LINK -> {
                    cmd.append(parent, "Pages/HyboxIncidentButton.ui");
                    cmd.set(selector + ".Text", block.label + " · " + block.value);
                    cmd.set(selector + ".TooltipText", block.label + " · " + block.value);
                    bind(ev, selector, block.action, block.arg);
                }
                default -> {
                    cmd.append(parent, "Pages/HyboxIncidentRow.ui");
                    cmd.set(selector + " #Key.Text", block.label);
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

    static Map<String, Set<String>> renderedFields(IncidentModel model) {
        Map<String, Set<String>> fields = new LinkedHashMap<>();
        for (String section : SECTIONS.keySet()) fields.put(section, Set.copyOf(plan(model, new ViewState(section, 0, "", "all", false)).fields));
        return Collections.unmodifiableMap(fields);
    }

    static int pageCount(IncidentModel model, ViewState state) { return pages(plan(model, state), state.sectionId()).size(); }

    static int maxElements(IncidentModel model, String section) {
        ViewState state = new ViewState(section, 0, "", "all", false);
        int max = 0;
        for (List<Block> page : pages(plan(model, state), section)) {
            int[] budget = budget(page);
            int elements = 80;
            for (Block block : page) elements += elements(block, budget, model, state);
            if (section.equals("top")) elements += 24;
            max = Math.max(max, elements);
        }
        return max;
    }

    private static int timelineSegments(JsonArray states) {
        int segments = 0; double previous = Double.NaN;
        for (JsonElement state : states) {
            double value = number(state, 0);
            if (segments == 0 || value != previous) segments++;
            previous = value;
        }
        return segments;
    }

    private static void header(IncidentModel model, UICommandBuilder cmd) {
        JsonObject incident = model.object("incident");
        String severity = text(incident, "severity");
        cmd.set("#Severity.Text", severity);
        var tone = DashboardModel.severityTone(severity);
        cmd.set("#Severity.Style.TextColor", tone.color);
        HyboxDashboardRenderer.background(cmd, "#Severity", tone, "Pill");
        String world = text(incident, "world");
        boolean hasWorld = !world.isEmpty() && !world.equals("server") && !world.equals("Not captured");
        cmd.set("#Headline.Text", text(incident, "trigger").replace('_', ' ') + (hasWorld ? " · " + world : ""));
        cmd.set("#IncidentMeta.Text", text(incident, "created"));
        String blockedIn = model.blockedIn();
        boolean hasFrame = !blockedIn.equals("Not captured");
        cmd.set("#BlockedIn.Visible", hasFrame);
        cmd.set("#BlockedIn.Text", hasFrame ? "Blocked in " + blockedIn : "");
        cmd.set("#BlockedIn.TooltipText", hasFrame ? blockedIn : "");
    }

    private static void overviewTiles(IncidentModel model, UICommandBuilder cmd) {
        JsonObject i = model.object("incident");
        int index = 0;
        if (present(i.get("stallMs"))) tile(cmd, index++, "Stall", display(i.get("stallMs"), "stallMs"), words(text(i, "trigger")));
        tile(cmd, index++, "Recording window", duration(number(i.get("windowSec"), 0) * 1000), "Starts " + text(i, "recStart") + (present(i.get("recStart")) ? " UTC" : ""));
        tile(cmd, index, "Players", display(i.get("players"), "players"), "online at capture");
    }

    private static String words(String trigger) {
        String spaced = trigger.replace('_', ' ').toLowerCase(Locale.ROOT);
        return spaced.isEmpty() ? "" : Character.toUpperCase(spaced.charAt(0)) + spaced.substring(1);
    }

    static boolean hasContent(IncidentModel model, String section) {
        return switch (section) {
            case "top" -> true;
            case "stalled" -> !diagCards(model).isEmpty();
            case "diagnosis" -> {
                if (filled(model, "findings")) yield true;
                Plan rows = new Plan(model);
                comparison(rows);
                yield !rows.blocks.isEmpty();
            }
            case "metrics" -> filled(model, "series");
            case "cpu" -> filled(model, "cpu") || filled(model, "subsystems") || filled(model, "modCpu") || filled(model, "cpuByWorld")
                || dataIsSeries(model.object("series").get("cpu")) || dataIsSeries(model.object("series").get("cpuJvm"));
            case "engine" -> filled(model, "tickSeries") || filled(model, "tickSystems");
            case "methods" -> filled(model, "hotMethods");
            case "flame" -> filled(model, "flame");
            case "threads" -> filled(model, "hotThreads") || filled(model, "threadTimeline");
            case "states" -> filled(model, "threadStates") || filled(model, "deadlocked");
            case "memory" -> filled(model, "heap") || filled(model, "gc") || filled(model, "alloc") || dataIsSeries(model.object("series").get("heap"));
            case "pluginmetrics" -> filled(model, "pluginMetrics");
            case "network" -> filled(model, "net");
            case "worlds" -> filled(model, "worlds") || filled(model, "entities");
            case "system" -> filled(model, "sys");
            case "plugins" -> filled(model, "plugins");
            case "mixins" -> filled(model, "mixins");
            case "assetpacks" -> filled(model, "assetPacks");
            case "log" -> filled(model, "log");
            case "config" -> filled(model, "configs");
            default -> false;
        };
    }

    private static boolean filled(IncidentModel model, String key) {
        JsonElement value = model.field(key);
        if (value.isJsonArray()) return !value.getAsJsonArray().isEmpty();
        if (value.isJsonObject()) return !value.getAsJsonObject().isEmpty();
        return present(value);
    }

    private static final Set<String> DUPLICATE_DIAGNOSTICS = Set.of("Mixins", "Plugins", "Asset packs", "Tick systems",
        "Mod hot-path contribution (JFR)", "Heap histogram", "Memory pools", "Entities", "Server log");
    private static final Set<String> DUPLICATE_ENVIRONMENT = Set.of("Server name", "Hytale version", "Container runtime", "Mods", "Mods (count)");
    private record DiagCard(String title, List<JsonArray> entries, String pre) {}

    private static List<DiagCard> diagCards(IncidentModel model) {
        List<DiagCard> cards = new ArrayList<>();
        for (JsonElement element : model.array("diag")) {
            JsonObject d = object(element); String title = text(d, "title");
            if (DUPLICATE_DIAGNOSTICS.contains(title) || title.toLowerCase(Locale.ROOT).matches(".*\\.(json|toml|yaml|yml|properties|cfg)")) continue;
            List<JsonArray> entries = new ArrayList<>();
            for (JsonElement pair : array(d.get("entries"))) {
                JsonArray a = array(pair);
                if (title.equals("Environment") && (DUPLICATE_ENVIRONMENT.contains(text(at(a, 0))) || opt(at(a, 1)).startsWith("installed"))) continue;
                entries.add(a);
            }
            String pre = opt(d.get("pre"));
            if (!entries.isEmpty() || !pre.isEmpty()) cards.add(new DiagCard(title, entries, pre));
        }
        return cards;
    }

    private static String modName(String id) {
        return id.equals("Xytronix:Hybox") || id.startsWith("io.github.xytronix.hybox") ? id + " · Hybox itself" : id;
    }

    private static void tile(UICommandBuilder cmd, int index, String key, String value, String sub) {
        cmd.append("#Tiles", "Pages/HyboxIncidentTile.ui");
        String selector = "#Tiles[" + index + "]";
        cmd.set(selector + " #Key.Text", key); cmd.set(selector + " #Value.Text", value); cmd.set(selector + " #Sub.Text", sub);
    }

    private static Plan plan(IncidentModel model, ViewState state) {
        Plan p = new Plan(model);
        switch (state.sectionId()) {
            case "top" -> overview(p);
            case "stalled" -> {
                p.use("diag");
                for (DiagCard card : diagCards(model)) {
                    p.card(card.title());
                    for (JsonArray a : card.entries()) p.row(text(at(a, 0)), text(at(a, 1)));
                    if (!card.pre().isEmpty()) p.textLines("Captured stack", card.pre());
                }
            }
            case "diagnosis" -> { findings(p); comparison(p); }
            case "metrics" -> {
                JsonObject series = object(p.use("series"));
                if (series.isEmpty()) p.row("Time series", "Not captured");
                Set<String> ordered = new LinkedHashSet<>(List.of("mspt", "tps", "players", "connects", "joins", "leaves", "entities", "chunks", "chunksGenerated", "chunksLoaded", "ping", "cpuJvm", "cpu", "heap", "heapCommitted", "rss", "hostMem", "disk", "diskWrite", "diskRead", "gcPause", "exceptions"));
                ordered.addAll(series.keySet());
                ordered.removeAll(Set.of("threads", "netOut", "netIn"));
                String tickWorld = text(model.object("tps"), "world");
                for (String key : ordered) {
                    if (dataIsSeries(series.get(key))) p.chart(label(key), series.get(key), unit(key), key);
                    if (!key.equals("tps")) continue;
                    for (JsonElement element : array(p.use("worldTps"))) {
                        JsonObject world = object(element); String name = text(world, "name");
                        if (name.equals(tickWorld) && dataIsSeries(series.get("tps")) || !dataIsSeries(world.get("series"))) continue;
                        p.chart("Tick rate · " + name, world.get("series"), "tps", "worldTps");
                    }
                }
                p.card("Recording facts");
                for (String key : List.of("tps", "cpu", "heap", "ram", "gc", "net", "threadStates", "worlds", "sys")) p.use(key);
                p.use("gcOverlap");
                recordingFacts(p);
                JsonElement hidden = p.use("eventsHidden"); if (present(hidden)) p.row("Additional events omitted", text(hidden));
            }
            case "cpu" -> {
                p.data("CPU", "cpu"); p.series("cpu", "cpuJvm");
                double total = number(model.object("incident").get("samples"), 0); p.use("incident");
                cpuFacts(p);
                p.shares("Engine subsystems", p.use("subsystems"), total, "samples");
                JsonArray mods = array(p.use("modCpu"));
                if (!mods.isEmpty()) p.card("CPU by mod");
                for (JsonElement item : mods) {
                    JsonArray a = array(item); double samples = number(at(a, 1), 0);
                    List<String> threads = new ArrayList<>();
                    for (JsonElement thread : array(at(a, 3))) {
                        JsonArray t = array(thread);
                        threads.add(text(at(t, 0)) + " " + decimal(100 * number(at(t, 1), 0) / Math.max(1, samples), 0) + "%");
                    }
                    String top = opt(at(a, 2));
                    p.table(modName(text(at(a, 0))), top.isEmpty() ? "" : "Top method · " + top,
                        count(samples) + " samples · " + decimal(100 * samples / Math.max(1, total), 1) + "%", String.join(" · ", threads));
                }
                for (JsonElement element : array(p.use("cpuByWorld"))) {
                    JsonObject world = object(element); String name = text(world, "world");
                    p.card(name + " · CPU attribution");
                    p.row("Samples", display(world.get("samples"), "samples") + " samples · " + percent(number(world.get("samples"), 0), total));
                    p.shares(name + " · subsystems", world.get("subsystems"), number(world.get("samples"), 0), "samples");
                    p.shares(name + " · mods", world.get("mods"), number(world.get("samples"), 0), "samples", HyboxIncidentRenderer::modName);
                }
                if (!model.array("worldTps").isEmpty()) p.link("Tick rate per world", "Key metrics", "section", "metrics");
            }
            case "engine" -> {
                p.namedCharts("Tick system", "tickSeries", "ms");
                p.row("Per-system tick wall time", "Measured by the engine for every system tick: exact time, not sampling. Includes time blocked on locks and I/O.");
                double budget = 1000 / Math.max(1, number(model.object("tps").get("target"), 30)); p.use("tps");
                for (JsonElement item : array(p.use("tickSystems"))) {
                    JsonArray a = array(item);
                    p.row(text(at(a, 0)) + " @ " + text(at(a, 1)), decimal(100 * number(at(a, 2), 0) / budget, 1) + "% of " + decimal(budget, 1) + " ms budget · "
                        + decimal(number(at(a, 2), 0), 2) + " ms avg · " + decimal(number(at(a, 3), 0), 2) + " ms worst (last 1 min) · 5 min: "
                        + decimal(number(at(a, 4), 0), 2) + " ms avg, " + decimal(number(at(a, 5), 0), 2) + " ms worst");
                }
                p.data("Engine profiling note", "tickSystemsNote");
            }
            case "methods" -> {
                JsonArray methods = array(p.use("hotMethods"));
                double total = number(model.object("incident").get("samples"), 0); p.use("incident");
                if (!methods.isEmpty()) p.row("Leaf-frame aggregation", "Top " + methods.size() + " methods over " + count(total) + " execution samples from all threads. Top frame " + text(at(array(methods.get(0)), 1)) + " holds " + percent(number(at(array(methods.get(0)), 0), 0), total) + " of samples.");
                int rank = 0;
                for (JsonElement element : methods) {
                    JsonArray a = array(element); double samples = number(at(a, 0), 0);
                    String label = opt(at(a, 3));
                    JsonArray callers = array(at(a, 2)), callerLabels = array(at(a, 4));
                    List<String> callerText = new ArrayList<>(callers.size());
                    for (int i = 0; i < callers.size(); i++) {
                        String callerLabel = opt(at(callerLabels, i));
                        callerText.add(text(callers.get(i)) + (callerLabel.isEmpty() ? "" : " [" + callerLabel + "]"));
                    }
                    p.table("#" + ++rank + " · " + text(at(a, 1)), label, count(samples) + " samples · " + percent(samples, total) + " self", "Callers: " + String.join(" → ", callerText));
                }
            }
            case "flame" -> {
                JsonElement flame = p.use("flame");
                if (present(flame)) {
                    JsonArray root = flameRoot(model, state.flameZoomPath());
                    p.paragraph(flameBreadcrumb(model, state.flameZoomPath()) + " · " + count(number(at(root, 1), 0)) + " samples · self " + flameSelf(root) + ownerSuffix(root) + flameLabel(root));
                    p.paragraph("Click a frame to zoom; use Up or All samples to navigate.");
                    p.blocks.add(new Block(Kind.LEGEND, "", "", JsonNull.INSTANCE, 0, "", "flame"));
                    p.link("All samples", "Reset zoom", "zoom", "");
                    if (!state.flameZoomPath().isEmpty()) p.link("Up one frame", "Parent", "zoom", parentPath(state.flameZoomPath()));
                    p.blocks.add(new Block(Kind.FLAME, "", "", flame, 0, "", ""));
                    JsonArray children = array(at(root, 2));
                    for (int i = 0; i < children.size(); i++) {
                        JsonArray child = array(children.get(i));
                        p.link(text(at(child, 0)), count(number(at(child, 1), 0)) + " samples · self " + flameSelf(child) + ownerSuffix(child) + flameLabel(child), "zoom", childPath(state.flameZoomPath(), i));
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
                if (!model.array("deadlocked").isEmpty()) p.data("Deadlocked threads", "deadlocked");
                else { p.use("deadlocked"); p.row("Deadlock", checked ? "No deadlock found." : "Deadlock check did not run."); }
                JsonElement threads = object(p.use("series")).get("threads");
                if (dataIsSeries(threads) || model.has("threadTotal") || !states.isEmpty()) p.card("All threads");
                if (dataIsSeries(threads)) p.chart("Live threads", threads, "count", "threads");
                p.data("Live threads", "threadTotal");
                double total = states.entrySet().stream().mapToDouble(e -> number(e.getValue(), 0)).sum();
                for (var e : states.entrySet()) p.share(e.getKey().replace('_', ' '), count(number(e.getValue(), 0)) + " · " + percent(number(e.getValue(), 0), total), number(e.getValue(), 0) / Math.max(1, total));
            }
            case "memory" -> memory(p);
            case "pluginmetrics" -> {
                JsonArray metrics = array(p.use("pluginMetrics"));
                Map<String, List<String>> uncharted = new LinkedHashMap<>();
                for (String heading : List.of("Published once, no rate", "Zero for the whole window", "Not charted, over the limit")) uncharted.put(heading, new ArrayList<>());
                for (int i = 0; i < metrics.size(); i++) {
                    JsonObject metric = object(metrics.get(i));
                    String name = text(metric, "name");
                    boolean counter = "count".equals(text(metric, "kind"));
                    if (!dataIsSeries(metric.get("series"))) {
                        String reason = text(metric, "uncharted");
                        String withTotal = counter ? name + " · total " + count(number(metric.get("total"), 0)) : name;
                        if (reason.equals("zero")) uncharted.get("Zero for the whole window").add(name);
                        else if (reason.equals("single")) uncharted.get("Published once, no rate").add(withTotal);
                        else uncharted.get("Not charted, over the limit").add(withTotal);
                        continue;
                    }
                    boolean rate = counter && present(metric.get("bucketSec"));
                    p.chart(name + " · " + (rate ? "counter, per second" : counter ? "count per interval" : "gauge"),
                        metric.get("series"), rate ? "/s" : "", PLUGIN_METRIC_KEY + i);
                }
                uncharted.forEach((heading, names) -> {
                    for (int i = 0; i < names.size(); i++) p.row(i == 0 ? heading : "", names.get(i));
                });
            }
            case "network" -> network(p);
            case "worlds" -> {
                double total = number(model.object("incident").get("samples"), 0); p.use("incident");
                JsonArray worlds = array(p.use("worlds"));
                if (worlds.asList().stream().anyMatch(w -> present(object(w).get("tps")))) p.link("Tick rate per world", "Key metrics", "section", "metrics");
                for (JsonElement element : worlds) {
                    JsonObject world = object(element); String name = text(world, "name");
                    p.card(name);
                    int rank = 1;
                    for (JsonElement other : worlds) if (number(object(other).get("samples"), 0) > number(world.get("samples"), 0)) rank++;
                    p.row("World status", (name.startsWith("instance-") ? "instance" : "persistent") + " · CPU share " + decimal(100 * number(world.get("samples"), 0) / Math.max(1, total), 1) + "% · #" + rank + " of " + worlds.size() + " by CPU" + ("true".equals(text(world, "stalled")) ? " · stalled world" : ""));
                    JsonObject facts = world.deepCopy(); facts.remove("tps");
                    p.flatten(name, facts);
                }
                p.tuples("Entity component breakdown", "entities", "World", "Entity types (type, count)");
            }
            case "system" -> systemFacts(p);
            case "plugins" -> plugins(p, "plugins", "Plugins", state.showCore());
            case "mixins" -> mixins(p);
            case "assetpacks" -> plugins(p, "assetPacks", "Asset packs", true);
            case "log" -> {
                JsonArray logs = array(p.use("log"));
                long errors = logs.asList().stream().filter(e -> "ERROR".equals(text(at(array(e), 0)))).count();
                long warnings = logs.asList().stream().filter(e -> "WARN".equals(text(at(array(e), 0)))).count();
                int distinct = 0;
                for (int from = 0; from < logs.size();) {
                    int to = from + 1;
                    while (to < logs.size() && logs.get(to).equals(logs.get(from))) to++;
                    JsonArray entry = array(logs.get(from));
                    String message = text(at(entry, 2));
                    int newlines = (int) message.chars().filter(c -> c == '\n').count();
                    String first = newlines == 0 ? message : message.substring(0, message.indexOf('\n'));
                    p.table(text(at(entry, 0)) + " · " + text(at(entry, 1)) + (to - from > 1 ? " ×" + (to - from) : ""), first + (newlines > 0 ? " · +" + newlines + " more lines" : ""), "", newlines > 0 ? message : "");
                    distinct++; from = to;
                }
                p.row("Captured server log", logs.size() + " lines · " + errors + " errors · " + warnings + " warnings · " + (logs.size() - distinct) + " repeated lines collapsed");
                if (logs.isEmpty()) p.row("Server log", "Not captured");
            }
            case "config" -> config(p);
            default -> { }
        }
        if (p.blocks.isEmpty()) p.row(SECTIONS.get(state.sectionId()), "Not captured.");
        return p;
    }

    private static void findings(Plan p) {
        JsonObject f = object(p.use("findings"));
        if (f.isEmpty()) {
            p.row("Older bundle", "Diagnosis findings are not stored in this older bundle.");
            return;
        }
        p.card("What the recording shows");
        p.blocks.add(new Block(Kind.PARAGRAPH, "", spans(f.get("intro")), f.get("intro"), 0, "", ""));
        for (JsonElement entry : array(f.get("evidence"))) finding(p, object(entry), "Evidence");
        p.card("Next steps");
        int step = 0;
        for (JsonElement entry : array(f.get("steps"))) finding(p, object(entry), "Next step " + ++step);
    }

    private static void finding(Plan p, JsonObject finding, String heading) {
        p.blocks.add(new Block(Kind.EVIDENCE, heading, spans(finding.get("spans")), finding, 0, "", ""));
    }

    private static com.hypixel.hytale.server.core.Message styledSpans(JsonElement spans) {
        var message = com.hypixel.hytale.server.core.Message.empty();
        for (JsonElement item : array(spans)) {
            JsonArray span = array(item);
            var part = com.hypixel.hytale.server.core.Message.raw(text(at(span, 0)));
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
        double total = number(p.model.object("incident").get("samples"), 0); p.use("incident");
        Map<String, double[]> pools = new LinkedHashMap<>();
        Set<String> rankedNames = new LinkedHashSet<>();
        for (JsonElement element : hot) {
            JsonArray a = array(element); String name = text(at(a, 2));
            rankedNames.add(name);
            double[] pool = pools.computeIfAbsent(threadGroup(name), ignored -> new double[2]); pool[0]++; pool[1] += number(at(a, 0), 0);
        }
        p.card("Thread pools");
        List<Map.Entry<String, double[]>> poolRows = pools.entrySet().stream().filter(e -> e.getValue()[0] >= 2).sorted((a, b) -> Double.compare(b.getValue()[1], a.getValue()[1])).toList();
        List<Map.Entry<String, double[]>> shown = poolRows.stream().filter(e -> e.getValue()[1] >= total * 0.003).limit(12).toList();
        if (shown.isEmpty() && !poolRows.isEmpty()) shown = poolRows.subList(0, 1);
        for (var entry : shown) p.share("Thread pool · " + entry.getKey() + (entry.getKey().startsWith("hybox-") ? " · Hybox itself" : ""), count(entry.getValue()[0]) + " threads · " + count(entry.getValue()[1]) + " samples · " + decimal(100 * entry.getValue()[1] / Math.max(1, total), 1) + "%", entry.getValue()[1] / Math.max(1, total));
        if (poolRows.size() > shown.size()) p.row("Smaller thread pools", "+" + (poolRows.size() - shown.size()) + " smaller pool(s) below 0.3% not shown.");
        if (shown.isEmpty()) p.row("Thread pools", hot.isEmpty() ? "Not captured" : "No sampled threads share a pool name.");
        Map<String, JsonArray> timelines = new LinkedHashMap<>();
        JsonArray timelineEntries = array(p.use("threadTimeline")), axisSeries = new JsonArray();
        for (JsonElement element : timelineEntries) {
            JsonObject entry = object(element); JsonArray states = array(entry.get("states"));
            timelines.put(text(entry, "name"), states);
            if (states.size() > axisSeries.size()) axisSeries = states;
        }
        p.card("Hot threads · ranked by CPU samples");
        p.blocks.add(new Block(Kind.LEGEND, "", "", JsonNull.INSTANCE, 0, "", "threads"));
        p.blocks.add(new Block(Kind.THREAD_AXIS, "", "", axisSeries, 0, "", ""));
        if (hot.isEmpty()) p.row("Hot threads", "Not captured.");
        int rank = 0;
        for (JsonElement element : hot) {
            JsonArray a = array(element); double samples = number(at(a, 0), 0);
            StringBuilder methods = new StringBuilder();
            for (JsonElement method : array(at(a, 4))) {
                if (!methods.isEmpty()) methods.append('\n');
                JsonArray m = array(method);
                methods.append(text(at(m, 0))).append(" · ").append(count(number(at(m, 1), 0))).append(" samples · ").append(percent(number(at(m, 1), 0), samples));
                String label = opt(at(m, 2));
                if (!label.isEmpty()) methods.append(" · ").append(label);
            }
            String name = text(at(a, 2));
            p.blocks.add(new Block(Kind.THREAD, name, methods.toString(), a, samples / Math.max(1, total), "", Integer.toString(++rank), timelines.get(name)));
        }
        boolean others = false;
        for (JsonElement element : timelineEntries) {
            JsonObject entry = object(element); String name = text(entry, "name");
            if (rankedNames.contains(name)) continue;
            if (!others) { p.card("Other sampled threads"); others = true; }
            p.blocks.add(new Block(Kind.TIMELINE, name, "", entry.get("states"), 0, "", ""));
        }
    }

    private static void memory(Plan p) {
        p.series("heap", "heapCommitted", "gcPause");
        JsonObject sys = object(p.use("sys")), heap = object(p.use("heap")), ram = object(p.use("ram")), gc = object(p.use("gc"));
        p.card("Memory");
        double maxHeap = number(heap.get("max"), number(sys.get("xmxBytes"), 0));
        if (maxHeap > 0) p.row("Max heap", gib(maxHeap) + " GiB (-Xmx)");
        if (present(heap.get("peak"))) p.row("Peak heap used", gib(number(heap.get("peak"), 0)) + " GiB");
        if (present(heap.get("max"))) p.row("Heap utilization", percent(number(heap.get("peak"), 0), number(heap.get("max"), 0)) + " peak");
        if (present(heap.get("floor"))) p.row("Post-GC floor", "~" + gib(number(heap.get("floor"), 0)) + " GiB");
        if (present(sys.get("gcYoung")) || present(sys.get("gcOld"))) p.row("Collector", text(sys, "gcYoung") + " + " + text(sys, "gcOld"));
        if (present(gc.get("collections"))) p.row("Collections", text(gc, "collections"));
        if (present(gc.get("maxPauseMs"))) p.row("Longest pause", text(gc, "maxPauseMs") + " ms");
        if (present(gc.get("totalPauseMs"))) p.row("Total pause time", text(gc, "totalPauseMs") + " ms");
        JsonObject alloc = object(p.use("alloc")); double total = number(alloc.get("total"), 0);
        double window = number(p.model.object("incident").get("windowSec"), 0); p.use("incident");
        if (present(alloc.get("total"))) {
            p.row("Allocation pressure, not retained RAM", "JFR allocation samples over the entire recording: " + bytes(total) + " sampled" + (window > 0 ? " · ≈ " + bytes(total / window) + "/s" : "") + ". Owner unknown; grouped by package.");
            p.row("Allocation · total sampled", bytes(total) + " · " + count(total) + " bytes");
        } else p.row("Allocation · total sampled", "Not captured");
        for (String key : List.of("bySubsystem", "byMod")) p.shares("Allocation · " + label(key), alloc.get(key), total, "bytes");
        for (JsonElement element : array(alloc.get("byWorld"))) {
            JsonObject world = object(element); String name = text(world, "world"); double size = number(world.get("bytes"), 0);
            p.row("Allocation · " + name, bytes(size) + " · " + percent(size, total));
            p.shares(name + " · subsystem allocation", world.get("bySubsystem"), size, "bytes");
            p.shares(name + " · mod allocation", world.get("byMod"), size, "bytes");
        }
        p.tuples("Retained objects", "retained", "Class", "Samples", "Allocation site");
        p.tuples("Heap histogram", "heapHistogram", "Class", "Instances", "Bytes");
        JsonArray histogram = p.model.array("heapHistogram");
        double histTotal = histogram.asList().stream().mapToDouble(e -> number(at(array(e), 2), 0)).sum();
        for (JsonElement item : histogram) { JsonArray a = array(item); p.row(text(at(a, 0)) + " · % of listed", decimal(100 * number(at(a, 2), 0) / Math.max(1, histTotal), 1) + "% · " + bytes(number(at(a, 2), 0))); }
        p.tuples("Slow file I/O", "slowIo", "Path", "Duration (ms)", "Bytes", "Operation");
        JsonElement slowIoThreshold = p.use("slowIoThresholdMs");
        String slowIoLimit = present(slowIoThreshold)
            ? "Slow file operations list the slowest single reads and writes. The recording keeps only those of " + count(number(slowIoThreshold, 0)) + " ms or more."
            : "Slow file operations list the slowest single reads and writes the recording kept.";
        p.row("Memory capture semantics", "Retained objects are approximate JFR old-object samples, not exact retained bytes. Heap histogram is a point-in-time heap walk: exact instances and shallow bytes. " + slowIoLimit);
        p.card("Advanced");
        if (present(sys.get("gcParallel"))) p.row("GC worker threads", text(sys, "gcParallel") + " parallel, " + text(sys, "gcConc") + " concurrent");
        JsonObject nonHeap = object(sys.get("nonHeap"));
        if (present(nonHeap.get("used"))) p.row("Non-heap", bytes(number(nonHeap.get("used"), 0)) + " used / " + bytes(number(nonHeap.get("committed"), 0)) + " committed");
        if (present(ram.get("rssPeak"))) p.row("Process RSS (peak)", decimal(number(ram.get("rssPeak"), 0) / 1e9, 1) + " GB");
        double memLimit = number(object(sys.get("container")).get("memLimit"), number(sys.get("ramTotal"), 0));
        if (memLimit > 0) p.row("Process memory basis", decimal(memLimit / 1e9, 1) + " GB " + (present(object(sys.get("container")).get("memLimit")) ? "container limit" : "machine RAM") + " · RSS " + decimal(100 * number(ram.get("rssLast"), 0) / memLimit, 1) + "%");
        if (present(ram.get("nmtOn")) && !ram.get("nmtOn").getAsBoolean()) p.row("Native memory (NMT)", "n/a, start with -XX:NativeMemoryTracking=summary");
        if (present(gc.get("explicitDisabled"))) p.row("Explicit GC", gc.get("explicitDisabled").getAsBoolean() ? "disabled (-XX:+DisableExplicitGC)" : "true".equals(text(gc, "explicitConcurrent")) ? "concurrent (invokes concurrent)" : "stop-the-world");
        if (present(gc.get("explicitCalls"))) {
            double calls = array(gc.get("explicitCalls")).asList().stream().mapToDouble(e -> number(at(array(e), 1), 0)).sum();
            p.row("Explicit GC calls", calls == 0 ? "none in window" : count(calls) + " · " + readable(gc.get("explicitCalls")));
        }
        JsonArray collectors = array(p.use("gcByCollector"));
        for (int i = 0; i < collectors.size(); i++) {
            JsonArray a = array(collectors.get(i));
            p.row(i == 0 ? "Collections since JVM start" : "", text(at(a, 0)) + " · " + count(number(at(a, 1), 0)) + " collections · " + decimal(number(at(a, 2), 0), 2) + " ms");
        }
        JsonArray causes = array(p.use("gcCauses"));
        for (int i = 0; i < causes.size(); i++) {
            JsonArray a = array(causes.get(i)); String cause = text(at(a, 0));
            p.row(i == 0 ? "GC causes" : "", cause + (cause.equals("Heap Inspection Initiated GC") ? " · Hybox heap walk" : "") + " · " + count(number(at(a, 1), 0)) + " collections · " + decimal(number(at(a, 2), 0), 1) + " ms pause");
        }
        JsonArray pools = array(p.use("memPools"));
        for (int i = 0; i < pools.size(); i++) {
            JsonArray a = array(pools.get(i));
            p.row(i == 0 ? "Memory pools" : "", text(at(a, 0)) + " · " + bytes(number(at(a, 1), -1)) + " used · " + bytes(number(at(a, 2), -1)) + " committed · max " + bytes(number(at(a, 3), -1)) + " · post-GC " + bytes(number(at(a, 4), -1)));
        }
        for (String key : List.of("byThread", "byClass")) {
            for (JsonElement element : array(alloc.get(key))) {
                JsonArray a = array(element); double amount = number(at(a, 1), 0);
                p.share("Allocation " + label(key).toLowerCase(Locale.ROOT) + " · " + text(at(a, 0)), bytes(amount) + " · " + percent(amount, total), amount / Math.max(1, total));
            }
        }
    }

    private static void config(Plan p) {
        p.data("Config files", "configs");
    }

    private static void mixins(Plan p) {
        JsonObject mixins = object(p.use("mixins"));
        if (mixins.isEmpty()) return;
        JsonObject hyinit = object(mixins.get("hyinit"));
        if (hyinit.isEmpty()) {
            legacyMixins(p, mixins);
            return;
        }
        JsonArray sources = array(mixins.get("sources")), configs = array(mixins.get("configs"));
        JsonArray problems = array(mixins.get("problems")), overlaps = array(mixins.get("overlaps"));
        Map<String, String> mods = new LinkedHashMap<>();
        for (JsonElement element : sources) {
            JsonArray s = array(element);
            if (present(at(s, 1))) mods.put(text(at(s, 0)), text(at(s, 1)).substring(text(at(s, 1)).indexOf(':') + 1));
        }
        p.card("Problems since startup");
        if (problems.isEmpty()) p.row("Mixin problems", "None. Every Mixin applied.");
        for (JsonElement element : problems) {
            JsonArray problem = array(element);
            String source = opt(at(problem, 2));
            p.table(("error".equals(text(at(problem, 0))) ? "ERROR" : "WARN") + (source.isEmpty() ? "" : " · " + source),
                text(at(problem, 1)), "", opt(at(problem, 3)));
        }
        double omitted = number(hyinit.get("problemsOmitted"), 0);
        if (omitted > 0) p.row("Problems not kept", count(omitted) + " more. The server log has them.");
        if (!problems.isEmpty()) p.row("Stack traces", "The server log has the full stack traces.");
        p.card("Versions");
        p.known("Hyinit", hyinit.get("version"));
        String mode = opt(hyinit.get("launchMode"));
        if (!mode.isEmpty()) p.row("Started", mode.equals("earlyPlugin") ? "From the early plugins folder" : mode.equals("launcher") ? "With java -jar" : mode);
        List<String> versions = new ArrayList<>();
        if (present(hyinit.get("mixin"))) versions.add("Mixin " + text(hyinit, "mixin"));
        if (present(hyinit.get("fabricMixin"))) versions.add("Fabric Mixin " + text(hyinit, "fabricMixin"));
        if (present(hyinit.get("mixinExtras"))) versions.add("MixinExtras " + text(hyinit, "mixinExtras"));
        if (!versions.isEmpty()) p.row("Mixin", String.join(" · ", versions));
        if (present(hyinit.get("classesTransformed"))) p.row("Classes transformed", count(number(hyinit.get("classesTransformed"), 0))
            + (present(hyinit.get("transformMs")) ? " in " + decimal(number(hyinit.get("transformMs"), 0), 1) + " ms" : ""));
        if (present(hyinit.get("patchedClasses"))) p.row("Classes changed by Mixins", count(number(hyinit.get("patchedClasses"), 0)));
        if (present(hyinit.get("bootstrapMs"))) p.row("Hyinit startup", count(number(hyinit.get("bootstrapMs"), 0)) + " ms before Hytale started");
        if (present(mixins.get("note"))) p.row("Note", text(mixins, "note"));
        p.row("Full list", "Every changed method is in extras/mixins.txt in the bundle.");
        p.card("Early plugin JARs");
        if (sources.isEmpty()) p.row("Early plugin JARs", "None");
        for (JsonElement element : sources) {
            JsonArray s = array(element);
            String file = text(at(s, 0)), sha = opt(at(s, 3));
            String name = mods.getOrDefault(file, file.replaceFirst("(?i)\\.jar$", ""));
            List<String> facts = new ArrayList<>();
            facts.add("Version " + (present(at(s, 2)) ? text(at(s, 2)) : "unknown"));
            facts.add(file);
            if (!sha.isEmpty()) facts.add("SHA-256 " + sha.substring(0, Math.min(8, sha.length())));
            if ("true".equals(text(at(s, 6)))) facts.add("Mixins skipped");
            p.table(name, String.join(" · ", facts), present(at(s, 8)) ? count(number(at(s, 8), 0)) + " methods" : "",
                array(at(s, 4)).isEmpty() ? "" : "Configs: " + join(array(at(s, 4)), ", "));
        }
        p.card("Methods changed by more than one mod");
        if (overlaps.isEmpty()) p.row("Shared methods", sources.size() >= 2 ? "None. No method is changed by more than one mod." : "None");
        int shown = 0;
        for (JsonElement element : overlaps) {
            if (shown++ >= 30) { p.row("More shared methods", (overlaps.size() - 30) + " more are in extras/mixins.txt."); break; }
            JsonArray o = array(element);
            List<String> patches = new ArrayList<>();
            for (JsonElement patch : array(at(o, 1))) {
                JsonArray pair = array(patch); String mixin = opt(at(pair, 1));
                patches.add(text(at(pair, 0)) + (mixin.isEmpty() ? "" : " (" + mixin.substring(mixin.lastIndexOf('.') + 1) + ")"));
            }
            p.table(text(at(o, 0)), String.join(", ", patches), "", "");
        }
        p.card("Mixin configs");
        for (JsonElement element : configs) {
            JsonArray c = array(element);
            String source = opt(at(c, 2));
            List<String> classes = new ArrayList<>();
            for (JsonElement mixin : array(at(c, 1))) classes.add(text(mixin).substring(text(mixin).lastIndexOf('.') + 1));
            p.table(text(at(c, 0)), "Mod " + mods.getOrDefault(source, source.isEmpty() ? "unknown" : source.replaceFirst("(?i)\\.jar$", ""))
                + " · " + classes.size() + " Mixins" + (present(at(c, 4)) ? " · " + count(number(at(c, 4), 0)) + " target classes" : ""),
                "", classes.isEmpty() ? "" : "Mixin classes: " + String.join(", ", classes));
        }
    }

    private static void legacyMixins(Plan p, JsonObject mixins) {
        p.card("Mixins");
        if (present(mixins.get("bootstrapper"))) p.row("Bootstrapper", text(mixins, "bootstrapper"));
        if (present(mixins.get("note"))) p.row("Details", text(mixins, "note"));
        JsonArray configs = array(mixins.get("configs"));
        int total = 0;
        for (JsonElement item : configs) {
            JsonArray a = array(item); int count = array(at(a, 1)).size(); total += count;
            p.row("Mixin config · " + text(at(a, 0)), count + " mixins · " + readable(at(a, 1)));
        }
        if (!configs.isEmpty() || !present(mixins.get("note"))) p.row("Mixins applied", total + " mixins across " + configs.size() + " config(s)");
        JsonArray conflicts = array(mixins.get("conflicts"));
        if (!conflicts.isEmpty()) p.row("Mixin overlap", conflicts.size() + " classes patched by more than one config. Overlapping mixins can interact in load-order-dependent ways; flag these mods first when debugging odd behaviour.");
        else if (present(mixins.get("conflicts")) && configs.size() >= 2) p.row("Mixin overlap", "No classes patched by more than one config.");
        else if (!present(mixins.get("conflicts")) && !configs.isEmpty()) p.row("Mixin overlap", "Conflict data was not captured.");
    }

    private static String mixinProblemSuffix(IncidentModel model) {
        JsonArray problems = array(model.object("mixins").get("problems"));
        if (problems.isEmpty()) return "";
        long errors = problems.asList().stream().filter(e -> "error".equals(text(at(array(e), 0)))).count();
        return errors > 0 ? " · " + errors + (errors == 1 ? " error" : " errors") : " · " + problems.size() + (problems.size() == 1 ? " warning" : " warnings");
    }

    private static void plugins(Plan p, String key, String title, boolean showCore) {
        JsonArray plugins = array(p.use(key)); int core = 0, incompatible = 0;
        for (JsonElement element : plugins) {
            JsonArray a = array(element);
            if ("true".equals(text(at(a, 3)))) core++;
            if ("INCOMPATIBLE".equals(text(at(a, 5)))) incompatible++;
        }
        p.row(title + " loaded", plugins.size() + (key.equals("plugins") ? " · " + core + " Hytale core modules · " + (plugins.size() - core) + " community plugins" : " from mods") + " · " + incompatible + " incompatible server ranges");
        if (key.equals("plugins") && core > 0) p.link((showCore ? "Hide " : "Show ") + core + " core modules", "", "toggle", "core");
        List<JsonArray> sorted = plugins.asList().stream().map(IncidentModel::array).sorted(java.util.Comparator.comparing(a -> "true".equals(text(at(a, 3))))).toList();
        for (JsonArray a : sorted) {
            boolean corePlugin = key.equals("plugins") && "true".equals(text(at(a, 3)));
            if (corePlugin && !showCore) continue;
            String range = text(at(a, 4)), compat = text(at(a, 5));
            String details = (!range.equals("*") || !compat.equals("COMPATIBLE")) ? "Compiled for: " + range + " · " + compat : "";
            if (!corePlugin) details += (details.isEmpty() ? "" : " · ") + "CurseForge search: " + text(at(a, 1));
            p.table(text(at(a, 1)), "Author: " + text(at(a, 0)) + " · Version: " + text(at(a, 2)), key.equals("plugins") ? corePlugin ? "CORE" : "COMMUNITY" : "Asset pack", details);
        }
    }

    private static void network(Plan p) {
        JsonObject net = object(p.use("net"));
        p.series("netIn", "netOut"); p.card("Network");
        p.known("Interface", net.get("iface"));
        for (JsonElement item : array(net.get("others"))) {
            JsonArray a = array(item);
            if (number(at(a, 1), 0) == 0 && number(at(a, 2), 0) == 0 && number(at(a, 3), 0) == 0 && number(at(a, 4), 0) == 0) continue;
            p.row(text(at(a, 0)), "in " + text(at(a, 1)) + "/" + text(at(a, 2)) + " · out " + text(at(a, 3)) + "/" + text(at(a, 4)) + " Mb/s");
        }
        if (net.isEmpty()) return;
        double window = number(p.model.object("incident").get("windowSec"), 0); p.use("incident");
        for (String direction : List.of("in", "out")) {
            String average = present(net.get(direction + "Avg")) ? decimal(number(net.get(direction + "Avg"), 0), 1) : "n/a";
            String peak = present(net.get(direction + "Peak")) ? decimal(number(net.get(direction + "Peak"), 0), 1) : "n/a";
            p.row(direction.equals("in") ? "Inbound avg / peak" : "Outbound avg / peak", average + " / " + peak + " Mb/s");
            if (window > 0 && present(net.get(direction + "Avg"))) {
                double mb = number(net.get(direction + "Avg"), 0) * window / 8;
                p.row("Transferred " + direction + " (window)", "≈ " + (mb >= 1000 ? decimal(mb / 1000, 1) + " GB" : count(mb) + " MB"));
            }
        }
    }

    private static void cpuFacts(Plan p) {
        JsonObject cpu = p.model.object("cpu"), sys = object(p.use("sys"));
        double total = number(p.model.object("incident").get("samples"), 0);
        if (!cpu.isEmpty()) p.row("Host CPU over capture window", "Machine average " + decimal(number(cpu.get("machAvg"), 0), 1) + "% · peak " + decimal(number(cpu.get("machMax"), 0), 1) + "% · process average " + decimal(number(cpu.get("jvmAvg"), 0), 1) + "% · peak " + decimal(number(cpu.get("jvmMax"), 0), 1) + "% · " + text(sys, "hwThreads") + " hardware threads.");
        List<JsonObject> worlds = array(p.use("worlds")).asList().stream().map(IncidentModel::object).sorted((a, b) -> Double.compare(number(b.get("samples"), 0), number(a.get("samples"), 0))).toList();
        int rank = 0;
        for (JsonObject w : worlds) {
            p.share("CPU samples by world thread · #" + ++rank + " " + text(w, "name"), text(w, "samples") + " samples · " + decimal(100 * number(w.get("samples"), 0) / Math.max(1, total), 1) + "%" + ("true".equals(text(w, "stalled")) ? " · stalled" : ""), number(w.get("samples"), 0) / Math.max(1, total));
        }
        if (total > 0 && total < 300) p.row("Limited CPU sample count", "Only " + count(total) + " execution samples; too few to rank threads reliably.");
    }

    private static void recordingFacts(Plan p) {
        JsonObject i = p.model.object("incident"), gc = p.model.object("gc"); p.use("incident"); p.use("sys");
        double window = number(i.get("windowSec"), 0);
        p.row("Capture window", duration(window * 1000));
        if (window > 0 && present(gc.get("totalPauseMs"))) p.row("GC overhead", decimal(100 * number(gc.get("totalPauseMs"), 0) / (window * 1000), 1) + "%");
        for (JsonElement event : array(p.use("events"))) {
            JsonArray a = array(event);
            p.row("Event time · " + clock(text(i, "recStart"), number(at(a, 0), 0) * window), text(at(a, 1)) + " · " + text(at(a, 2)));
        }
    }

    private static void comparison(Plan p) {
        JsonObject incident = p.model.object("incident"); p.use("incident");
        double marker = number(incident.get("markerFrac"), -1);
        if (marker < 0.2) return;
        JsonObject series = object(p.use("series"));
        String[] keys = {"tps", "mspt", "players", "entities", "chunks", "chunksGenerated", "chunksLoaded", "cpu", "heap", "rss", "hostMem", "disk", "diskWrite", "diskRead", "netOut", "netIn", "threads", "gcPause", "exceptions", "ping"};
        long available = java.util.Arrays.stream(keys).filter(key -> array(series.get(key)).size() >= 10).count();
        if (available < 3) return;
        double baselineEnd = Math.max(0.1, marker - 0.1), incidentStart = Math.max(0, marker - 0.02);
        p.row("Baseline vs incident window", "Calm: first " + count(baselineEnd * 100) + "% of recording. Incident: from just before marker" + (present(incident.get("recoverySec")) ? " through recovery." : " to the end of capture.") + " Averages and delta; below ±5% is neutral.");
        for (String key : keys) {
            JsonArray values = array(series.get(key));
            if (values.size() < 10) continue;
            comparisonRow(p, label(key), sliceAverage(values, 0, baselineEnd), sliceAverage(values, incidentStart, 1), unit(key), key.equals("gcPause") ? 2 : key.equals("ping") || Set.of("players", "entities", "chunks", "chunksGenerated", "chunksLoaded", "threads").contains(key) ? 0 : 1);
        }
        double minutes = number(incident.get("windowSec"), 0) / 60;
        if (minutes > 0) comparisonRow(p, "Log errors", errorCount(array(p.use("events")), 0, baselineEnd) / (baselineEnd * minutes), errorCount(p.model.array("events"), incidentStart, 1.0001) / ((1 - incidentStart) * minutes), "/min", 1);
        List<JsonObject> ticks = array(p.use("tickSeries")).asList().stream().map(IncidentModel::object).sorted((a, b) -> Double.compare(sliceAverage(array(b.get("series")), 0, 1), sliceAverage(array(a.get("series")), 0, 1))).limit(3).toList();
        for (JsonObject tick : ticks) comparisonRow(p, text(tick, "name"), sliceAverage(array(tick.get("series")), 0, baselineEnd), sliceAverage(array(tick.get("series")), incidentStart, 1), "ms", 1);
    }

    private static void comparisonRow(Plan p, String name, double baseline, double incident, String unit, int precision) {
        String delta = Math.abs(baseline) < 1e-9 ? "n/a" : (incident > baseline ? "+" : "") + decimal(100 * (incident - baseline) / Math.abs(baseline), Math.abs(100 * (incident - baseline) / Math.abs(baseline)) >= 100 ? 0 : 1) + "%";
        double factor = unit.equals("GiB") ? 1073741824.0 : unit.equals("GB") ? 1e9 : 1;
        double difference = Math.abs(incident - baseline) / factor;
        if (difference > 0) precision = Math.max(precision, Math.min(6, (int) Math.ceil(-Math.log10(difference))));
        p.row(name + " · baseline / incident / delta", decimal(baseline / factor, precision) + " / " + decimal(incident / factor, precision) + " " + unit + " · " + delta);
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
            var repeat = java.util.regex.Pattern.compile(" ×(\\d+)$").matcher(text(at(a, 2)));
            total += repeat.find() ? Double.parseDouble(repeat.group(1)) : 1;
        }
        return total;
    }

    private static void overview(Plan p) {
        JsonObject i = object(p.use("incident"));
        if (present(i.get("recoverySec"))) p.row("Recovery captured", decimal(number(i.get("recoverySec"), 0), 1) + " s");
        p.use("diag");
        String mixinProblems = mixinProblemSuffix(p.model);
        if (!mixinProblems.isEmpty()) p.row("Mixin problems", mixinProblems.substring(3) + " since startup. See Mixins.");
        if (present(i.get("note"))) p.row("Capture note", text(i, "note"));
        JsonElement unavailable = p.use("jfrUnavailableReason");
        if (present(unavailable)) p.row("Recording unavailable", text(unavailable));
        JsonElement version = p.use("hyboxVersion");
        p.row("Bundle", p.model.id() + (present(version) ? " · Hybox " + text(version) : ""));
    }

    private static String playerCount(JsonElement value) {
        if (!present(value)) return "Players not captured";
        double players = number(value, 0);
        return count(players) + (players == 1 ? " player" : " players");
    }

    static List<String> consoleSummary(IncidentModel model) {
        JsonObject i = model.object("incident");
        List<String> first = new ArrayList<>();
        for (String key : List.of("severity", "trigger", "world")) if (present(i.get(key))) first.add(text(i, key));
        List<String> second = new ArrayList<>();
        if (present(i.get("created"))) second.add(text(i, "created"));
        if (present(i.get("stallMs"))) second.add("stall " + count(number(i.get("stallMs"), 0)) + " ms");
        if (present(i.get("players"))) second.add(playerCount(i.get("players")));
        if (present(i.get("samples"))) second.add(count(number(i.get("samples"), 0)) + " samples");
        List<String> third = new ArrayList<>();
        String blocked = model.blockedIn();
        if (blocked != null && !blocked.isBlank() && !blocked.equals("Not captured")) third.add("Blocked in " + blocked);
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
        double cpuUsage = number(s.get("cpuUsageCores"), -1), cpuLimit = number(container.get("cpuLimit"), 0), cores = number(s.get("cpuCores"), 0);
        p.card("Processor");
        p.known("Brand", s.get("cpuBrand")); p.known("Cores", s.get("cpuCores")); p.known("Hardware threads", s.get("hwThreads")); p.known("Sockets", s.get("sockets"));
        if (cpuUsage >= 0) p.row("Container CPU", decimal(cpuUsage, 2) + " cores" + (cpuLimit > 0 && cpuLimit < cores ? " (" + decimal(100 * cpuUsage / cpuLimit, 0) + "% of limit)" : cores > 0 ? " (" + decimal(100 * cpuUsage / cores, 0) + "% of " + count(cores) + " cores)" : ""));
        p.known("CPU affinity (cpuset)", s.get("cpuset"));
        List<String> pressure = new ArrayList<>();
        for (String kind : List.of("Cpu", "Io", "Mem")) if (number(s.get("psi" + kind), -1) >= 0) pressure.add(kind.toLowerCase(Locale.ROOT) + " " + decimal(number(s.get("psi" + kind), 0), 1) + "%");
        if (!pressure.isEmpty()) p.row("Pressure (PSI some avg10)", String.join(" · ", pressure));
        double ramTotal = number(s.get("ramTotal"), 0), hostUsed = number(s.get("hostMemUsed"), -1);
        if (ramTotal > 0) p.row("Machine RAM (host)", decimal(ramTotal / 1e9, 1) + " GB");
        double throttled = number(s.get("throttledPeriods"), 0);
        if (throttled > 0) p.row("CPU throttling", count(throttled) + " periods" + (number(s.get("throttledMs"), 0) > 0 ? " · " + duration(number(s.get("throttledMs"), 0)) : "") + " in window");
        for (String key : List.of("cpuStealPct", "cpuIowaitPct")) if (number(s.get(key), -1) >= 0) p.row(key.equals("cpuStealPct") ? "CPU steal (host)" : "CPU iowait (host)", decimal(number(s.get(key), 0), 1) + "% of window");
        p.card("Operating system");
        p.known("OS", s.get("os")); p.known("Architecture", s.get("arch")); p.known("Server name", s.get("server"));
        if (present(s.get("hytale"))) p.row("Server software", "Hytale Dedicated " + text(s, "hytale"));
        if (number(s.get("uptimeMs"), 0) > 0) p.row("Uptime", duration(number(s.get("uptimeMs"), 0)));
        if (number(disk.get("total"), 0) > 0) p.row("Disk", decimal(number(disk.get("free"), 0) / 1e9, 1) + " GB free of " + decimal(number(disk.get("total"), 0) / 1e9, 1) + " GB");
        List<String> io = new ArrayList<>();
        for (String direction : List.of("Read", "Write")) if (number(s.get("io" + direction + "Limit"), -1) >= 0) io.add(direction.toLowerCase(Locale.ROOT) + " " + bytes(number(s.get("io" + direction + "Limit"), 0)) + "/s");
        if (!io.isEmpty()) p.row("Block I/O limit", String.join(" · ", io));
        if (hostUsed >= 0) p.row("Host memory", decimal(hostUsed / 1e9, 1) + " GB" + (ramTotal > 0 ? " of " + decimal(ramTotal / 1e9, 1) + " GB used" : " used"));
        if (number(swap.get("total"), 0) > 0) p.row("Swap (host)", bytes(Math.max(0, number(swap.get("total"), 0) - number(swap.get("free"), 0))) + " used of " + bytes(number(swap.get("total"), 0)));
        p.known("Runtime", container.get("runtime")); p.known("Container", container.get("type"));
        if (cpuLimit > 0) p.row("CPU limit", cores > 0 && cpuLimit >= cores ? "unlimited (quota " + count(cpuLimit) + ")" : decimal(cpuLimit, 1) + " cores");
        if (limit > 0) p.row("Memory limit", bytes(limit));
        double openFds = number(s.get("openFds"), -1), maxFds = number(s.get("maxFds"), 0);
        if (openFds >= 0 && maxFds > 0 && openFds >= maxFds * 0.8) p.row("Open files", count(openFds) + " of " + count(maxFds));
        if (number(s.get("containerMemUsed"), -1) >= 0) p.row("Container memory", bytes(number(s.get("containerMemUsed"), 0)) + (limit > 0 ? " of " + bytes(limit) + " (" + decimal(100 * number(s.get("containerMemUsed"), 0) / limit, 0) + "%)" : ""));
        if (number(s.get("containerSwapUsed"), -1) >= 0) p.row("Container swap", number(s.get("containerSwapMax"), -1) == 0 ? "disabled" : bytes(number(s.get("containerSwapUsed"), 0)) + (number(s.get("containerSwapMax"), -1) > 0 ? " of " + bytes(number(s.get("containerSwapMax"), 0)) : " used"));
        if (number(s.get("oomKills"), 0) > 0) p.row("OOM kills", count(number(s.get("oomKills"), 0)) + " (cumulative)");
        p.card("Java / JVM");
        p.known("Runtime", s.get("jvm")); p.known("Vendor", s.get("javaVendor")); p.known("Version", s.get("javaVersion")); p.known("java.home", s.get("javaHome"));
        for (JsonElement loader : array(s.get("loaders"))) { JsonArray a = array(loader); p.row(text(at(a, 0)) + " loader", text(at(a, 1))); }
        if (limit > 0 && xmx >= limit * 0.9) { p.card("Container memory risk"); p.row("Heap and container limit", "-Xmx " + gib(xmx) + " GiB is at or above " + gib(limit) + " GiB: the JVM can be OOM-killed before GC reacts."); }
        double free = number(disk.get("free"), -1), total = number(disk.get("total"), 0);
        if (free >= 0 && total > 0 && (free < total * 0.05 || free < 2 * 1073741824.0)) { p.card("Low disk space"); p.row("Free space", decimal(free / 1e9, 1) + " GB free of " + decimal(total / 1e9, 1) + " GB: the JFR recording and incident bundles can fail to write."); }
        if (present(s.get("startCommand"))) { p.card("Start command"); p.row("Command", text(s, "startCommand")); }
        p.card("Advanced");
        if (number(s.get("loadAvg"), -1) >= 0) p.row("Load average (1m, host)", decimal(number(s.get("loadAvg"), 0), 2));
        if (number(s.get("safepointCount"), 0) > 0) p.row("Safepoints", count(number(s.get("safepointCount"), 0)) + (number(s.get("safepointMs"), 0) > 0 ? " · " + decimal(number(s.get("safepointMs"), 0), 0) + " ms to-safepoint" : "") + " in window");
        p.known("Young GC", s.get("gcYoung")); p.known("Old GC", s.get("gcOld")); p.known("Parallel GC threads", s.get("gcParallel")); p.known("Concurrent GC threads", s.get("gcConc")); p.known("GC time ratio", s.get("gcTimeRatio"));
        JsonObject gc = object(p.use("gc"));
        if (present(gc.get("explicitDisabled"))) p.row("Explicit GC", gc.get("explicitDisabled").getAsBoolean() ? "disabled" : "true".equals(text(gc, "explicitConcurrent")) ? "concurrent" : "stop-the-world");
    }

    private enum Kind { CARD, TEXT, PARAGRAPH, LEGEND, SHARE, EVIDENCE, TABLE, THREAD, THREAD_AXIS, CHART, TIMELINE, LINK, FLAME }
    private record Block(Kind kind, String label, String value, JsonElement data, double share, String action, String arg, JsonArray timeline) {
        Block(Kind kind, String label, String value, JsonElement data, double share, String action, String arg) {
            this(kind, label, value, data, share, action, arg, null);
        }
    }
    private static final class Plan {
        final IncidentModel model;
        final Set<String> fields = new LinkedHashSet<>();
        final List<Block> blocks = new ArrayList<>();
        Plan(IncidentModel model) { this.model = model; }
        JsonElement use(String key) { fields.add(key); return model.field(key); }
        void card(String title) { blocks.add(new Block(Kind.CARD, title, "", JsonNull.INSTANCE, 0, "", "")); }
        void known(String label, JsonElement value) { if (present(value)) row(label, value.isJsonPrimitive() && value.getAsJsonPrimitive().isNumber() ? number(value, 0) == Math.rint(number(value, 0)) ? count(number(value, 0)) : decimal(number(value, 0), 2) : text(value)); }
        void paragraph(String value) { blocks.add(new Block(Kind.PARAGRAPH, "", value, JsonNull.INSTANCE, 0, "", "")); }
        void row(String label, String value) {
            int length = 1800;
            if (value.isEmpty()) value = "None";
            for (int from = 0; from < value.length(); from += length) blocks.add(new Block(Kind.TEXT, label + (from > 0 ? " · continued" : ""), value.substring(from, Math.min(value.length(), from + length)), JsonNull.INSTANCE, 0, "", ""));
        }
        void rich(String label, JsonElement spans) {
            if (spans(spans).length() > 1800) { row(label, spans(spans)); return; }
            blocks.add(new Block(Kind.TEXT, label, spans(spans), spans == null ? JsonNull.INSTANCE : spans, 0, "", ""));
        }
        void textLines(String label, String value) { row(label, value); }
        void data(String title, String key) {
            JsonElement value = use(key);
            if (!present(value)) return;
            boolean scalar = value.isJsonPrimitive()
                || value.isJsonArray() && value.getAsJsonArray().asList().stream().allMatch(e -> e.isJsonPrimitive() || e.isJsonNull());
            if (!scalar) card(title);
            flatten(title, value);
        }
        void flatten(String prefix, JsonElement value) {
            if (!present(value)) return;
            if (value.isJsonObject()) {
                for (var entry : value.getAsJsonObject().entrySet()) {
                    if (!present(entry.getValue())) continue;
                    if (entry.getValue().isJsonObject() || entry.getValue().isJsonArray() && entry.getValue().getAsJsonArray().asList().stream().anyMatch(e -> e.isJsonObject() || e.isJsonArray())) {
                        card(label(entry.getKey())); flatten(label(entry.getKey()), entry.getValue());
                    } else if (entry.getValue().isJsonArray()) row(label(entry.getKey()), join(entry.getValue().getAsJsonArray(), " · "));
                    else row(label(entry.getKey()), display(entry.getValue(), prefix + " · " + label(entry.getKey())));
                }
            } else if (value.isJsonArray()) {
                JsonArray a = value.getAsJsonArray();
                if (a.asList().stream().allMatch(e -> e.isJsonPrimitive() || e.isJsonNull())) row(prefix, join(a, " · "));
                else for (JsonElement item : a) flatten(prefix, item);
            } else row(prefix, display(value, prefix));
        }
        void tuples(String title, String key, String... columns) {
            JsonArray rows = array(use(key));
            card(title);
            if (rows.isEmpty()) { row(title, "Not captured"); return; }
            int rank = 0;
            for (JsonElement value : rows) {
                JsonArray a = array(value); StringBuilder line = new StringBuilder();
                for (int i = 0; i < a.size(); i++) {
                    if (i > 0) line.append('\n');
                    String column = i < columns.length ? columns[i] : "Value " + (i + 1);
                    JsonElement cell = a.get(i);
                    String valueText = readable(cell);
                    if (present(cell) && cell.isJsonPrimitive() && cell.getAsJsonPrimitive().isNumber()) {
                        String lower = column.toLowerCase(Locale.ROOT);
                        double n = number(cell, 0);
                        if (lower.contains("bytes")) valueText = n < 0 ? "Not reported" : bytes(n) + " · " + count(n) + " B";
                        else if (lower.contains("(ms)") || lower.contains("pause") || lower.contains("time")) valueText = decimal(n, key.equals("slowIo") ? 0 : 2) + " ms";
                        else valueText = count(n);
                    }
                    line.append(column).append(": ").append(valueText);
                }
                table("#" + ++rank + " · " + text(at(a, 0)), columns.length > 1 ? columns[1] + ": " + readable(at(a, 1)) : "", "", line.toString());
            }
        }
        void shares(String title, JsonElement values, double total, String unit) {
            shares(title, values, total, unit, java.util.function.UnaryOperator.identity());
        }
        void shares(String title, JsonElement values, double total, String unit, java.util.function.UnaryOperator<String> name) {
            JsonArray a = array(values);
            card(title);
            if (a.isEmpty()) { row(title, "Not captured"); return; }
            for (JsonElement element : a) {
                JsonArray row = array(element); double amount = number(at(row, 1), 0);
                share(name.apply(text(at(row, 0))), (unit.equals("bytes") ? bytes(amount) : count(amount) + " " + unit) + " · " + percent(amount, total), amount / Math.max(1, total));
            }
        }
        void share(String title, String value, double ratio) { blocks.add(new Block(Kind.SHARE, title, value, JsonNull.INSTANCE, ratio, "", "")); }
        void table(String title, String facts, String amount, String details) {
            JsonObject cells = new JsonObject(); cells.addProperty("amount", amount); cells.addProperty("details", details);
            if (details.length() > 1800) { row(title, facts + " · " + amount + "\n" + details); return; }
            blocks.add(new Block(Kind.TABLE, title, facts, cells, 0, "", ""));
        }
        void link(String title, String value, String action, String arg) { blocks.add(new Block(Kind.LINK, title, value, JsonNull.INSTANCE, 0, action, arg)); }
        void chart(String title, JsonElement data, String unit, String key) {
            if (!dataIsSeries(data)) { row(title, "Not captured"); return; }
            blocks.add(new Block(Kind.CHART, title, unit, data, 0, "", key));
        }
        void series(String... keys) {
            JsonObject series = object(use("series"));
            for (String key : keys) chart(label(key), series.get(key), unit(key), key);
        }
        void namedCharts(String title, String key, String unit) {
            JsonArray list = array(use(key));
            if (list.isEmpty()) row(title, "Not captured");
            for (JsonElement element : list) { JsonObject o = object(element); chart(title + " · " + text(o, "name"), o.get("series"), unit, key); }
        }
    }

    private static List<List<Block>> pages(Plan plan, String section) {
        List<Block> blocks = plan.blocks;
        if (section.equals("threads")) {
            List<Block> common = new ArrayList<>(), rows = new ArrayList<>(), last = new ArrayList<>();
            boolean trailing = false;
            for (Block block : blocks) {
                if (block.kind == Kind.THREAD) rows.add(block);
                else {
                    if (block.kind == Kind.CARD && !rows.isEmpty()) trailing = true;
                    (trailing ? last : common).add(block);
                }
            }
            if (rows.isEmpty()) return List.of(blocks);
            List<List<Block>> result = new ArrayList<>();
            for (int first = 0; first < rows.size(); first += 15) {
                int end = Math.min(rows.size(), first + 15);
                List<Block> page = new ArrayList<>(common);
                page.addAll(rows.subList(first, end));
                if (end == rows.size()) page.addAll(last);
                result.add(page);
            }
            return result;
        }
        boolean tableSection = Set.of("methods", "plugins", "assetpacks", "log", "flame").contains(section);
        List<List<Block>> result = new ArrayList<>();
        if (!tableSection) {
            List<Block> page = new ArrayList<>(); int nonCharts = 80;
            for (Block block : blocks) {
                int cost = switch (block.kind) { case TABLE -> 6; case TEXT, SHARE -> 5; case CARD -> 3; case EVIDENCE -> 3; case CHART -> 45; default -> 0; };
                if (nonCharts + cost > 1320 && !page.isEmpty()) { result.add(page); page = new ArrayList<>(); nonCharts = 80; }
                page.add(block); nonCharts += cost;
            }
            if (!page.isEmpty()) result.add(page);
        } else {
            List<Block> common = blocks.stream().filter(b -> b.kind != Kind.TABLE && !(section.equals("flame") && b.kind == Kind.LINK && b.action.equals("zoom") && !b.arg.isEmpty())).toList();
            List<Block> rows = blocks.stream().filter(b -> b.kind == Kind.TABLE || section.equals("flame") && b.kind == Kind.LINK && b.action.equals("zoom") && !b.arg.isEmpty()).toList();
            if (rows.isEmpty()) result.add(blocks);
            else for (int first = 0; first < rows.size(); first += PAGE_COST) {
                List<Block> page = new ArrayList<>(common); page.addAll(rows.subList(first, Math.min(rows.size(), first + PAGE_COST))); result.add(page);
            }
        }
        return result.isEmpty() ? List.of(List.of()) : result;
    }

    private static int[] budget(List<Block> blocks) {
        int base = 80, charts = 0, timelines = 0, threadStrips = 0;
        for (Block b : blocks) {
            if (b.kind == Kind.CHART) charts++;
            else if (b.kind == Kind.TIMELINE) timelines++;
            else if (b.kind == Kind.THREAD) {
                base += 17;
                if (b.timeline != null && !b.timeline.isEmpty()) threadStrips++;
            } else base += switch (b.kind) { case TABLE -> 6; case CARD, EVIDENCE -> 3; case TEXT, SHARE -> 5; case PARAGRAPH -> 2; case LEGEND -> 21; case THREAD_AXIS -> 6; case FLAME -> 1353; default -> 1; };
        }
        int elementTarget = timelines + threadStrips > 0 ? 1100 : 1400;
        int free = elementTarget - base - charts * 25 - timelines * 23, users = charts + timelines + threadStrips;
        int cells = users == 0 ? 0 : Math.max(0, Math.min(HyboxChartRenderer.HOVER_CELLS, free / (3 * users)));
        int columns = Math.max(1, Math.min(CHART_COLUMNS, (free - users * cells) / Math.max(1, charts * 2 + timelines + threadStrips)));
        return new int[] {columns, cells};
    }

    private static int elements(Block b, int[] budget, IncidentModel model, ViewState state) {
        int columns = budget[0], cells = budget[1];
        return switch (b.kind) {
            case CHART -> 25 + Math.min(cells, array(b.data).size()) + 2 * Math.min(columns, array(b.data).size());
            case TIMELINE -> 23 + Math.min(cells, array(b.data).size()) + Math.min(columns, array(b.data).size());
            case THREAD -> 17 + (b.timeline == null ? 0 : Math.min(cells, b.timeline.size()) + Math.min(columns, b.timeline.size()));
            case THREAD_AXIS -> 6;
            case FLAME -> 3 + flameSegments(model, state).size() * 3;
            case LINK -> 1; case TABLE -> 6; case CARD, EVIDENCE -> 3; case TEXT, SHARE -> 5; case PARAGRAPH -> 2; case LEGEND -> 21;
        };
    }

    private static void chart(IncidentModel model, ViewState state, Block block, String parent, String selector, int columnCap, int hoverCells, ChartImages images, UICommandBuilder cmd) {
        cmd.append(parent, "Pages/HyboxIncidentChart.ui");
        JsonArray series = array(block.data); int[] range = range(model, state, series.size());
        double min = Double.POSITIVE_INFINITY, max = Double.NEGATIVE_INFINITY, sum = 0, now = 0; int samples = 0;
        for (int i = range[0]; i < range[1]; i++) {
            if (!present(series.get(i))) continue;
            double n = number(series.get(i), 0); min = Math.min(min, n); max = Math.max(max, n); sum += n; now = n; samples++;
        }
        if (samples == 0) { min = 0; max = 0; }
        double average = samples == 0 ? 0 : sum / samples;
        if (state.timeRangeId().equals("all")) {
            JsonObject cpu = model.object("cpu"), heap = model.object("heap"), ram = model.object("ram"), net = model.object("net"), gc = model.object("gc"), tps = model.object("tps");
            switch (block.arg) {
                case "cpu" -> { average = number(cpu.get("machAvg"), average); max = number(cpu.get("machMax"), max); }
                case "heap" -> { min = number(heap.get("floor"), min); max = number(heap.get("peak"), max); }
                case "rss" -> max = number(ram.get("rssPeak"), max);
                case "tps" -> { min = number(tps.get("min"), min); average = number(tps.get("avg"), average); }
                case "players" -> now = number(model.object("incident").get("players"), now);
                case "threads" -> now = number(model.field("threadTotal"), now);
                case "gcPause" -> max = number(gc.get("maxPauseMs"), max);
                case "netOut" -> { average = number(net.get("outAvg"), average); max = number(net.get("outPeak"), max); }
                case "netIn" -> { average = number(net.get("inAvg"), average); max = number(net.get("inPeak"), max); }
                default -> { }
            }
        }
        String extra = "";
        if (Set.of("exceptions", "connects", "joins", "leaves", "chunksGenerated", "chunksLoaded").contains(block.arg)) extra += " · total " + count(sum);
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
        if (block.arg.equals("mspt")) {
            double markerFraction = number(model.object("incident").get("markerFrac"), -1);
            int markerIndex = (int) (markerFraction * series.size());
            if (present(model.object("incident").get("stallMs")) && markerIndex >= range[0] && markerIndex < range[1]) extra += " · stall " + text(model.object("incident"), "stallMs") + " ms";
            String tickWorld = text(model.object("tps"), "world");
            if (state.timeRangeId().equals("all")) for (JsonElement w : model.array("worlds")) if (text(object(w), "name").equals(tickWorld)) extra += " · worst tick " + decimal(number(object(w).get("msptMax"), max), 1) + " ms";
        }
        String title = block.label;
        if (Set.of("tps", "mspt").contains(block.arg) && present(model.object("tps").get("world"))) title += " · " + text(model.object("tps"), "world");
        if (Set.of("netIn", "netOut").contains(block.arg) && present(model.object("net").get("iface"))) title += " · " + text(model.object("net"), "iface");
        cmd.set(selector + " #ChartTitle.Text", title);
        int precision = Set.of("count", "players", "entities", "chunks").contains(block.value) || rate && max >= 100 ? 0 : block.arg.equals("gcPause") ? 2 : 1;
        double factor = block.value.equals("GiB") ? 1073741824.0 : block.value.equals("GB") ? 1e9 : 1;
        double span = Math.abs(max - min) / factor;
        if (span > 0) precision = Math.max(precision, Math.min(6, (int) Math.ceil(-Math.log10(span))));
        String stats = rate
            ? "avg " + chartValue(average, block.value, precision) + " · max " + chartValue(max, block.value, precision) + extra
            : "min " + chartValue(min, block.value, precision) + " · avg " + chartValue(average, block.value, precision) + " · max " + chartValue(max, block.value, precision) + " · now " + chartValue(now, block.value, precision) + extra;
        cmd.set(selector + " #ChartStats.Text", stats);
        axes(model, selector, range, series.size(), cmd);
        int count = range[1] - range[0];
        double[] values = new double[count];
        for (int i = 0; i < count; i++) {
            JsonElement value = series.get(range[0] + i);
            values[i] = present(value) ? Math.max(0, number(value, 0)) : Double.NaN;
        }
        double marker = number(model.object("incident").get("markerFrac"), -1) * Math.max(0, series.size() - 1);
        double[] markers = marker >= range[0] && marker <= range[1] - 1
            ? new double[] {marker - range[0]} : new double[0];
        HyboxChartRenderer.plot(selector, values, markers, columnCap, block.value, precision, max, true, images, cmd);
        int cells = Math.min(hoverCells, count);
        List<String> tips = new ArrayList<>();
        for (int c = 0; c < cells; c++) {
            int from = c * count / cells, to = (c + 1) * count / cells;
            double cellSum = 0; int n = 0;
            for (int i = from; i < to; i++) if (!Double.isNaN(values[i])) { cellSum += values[i]; n++; }
            tips.add(timeAt(model, range[0] + from, series.size()) + " · " + (n == 0 ? "no data" : chartValue(cellSum / n, block.value, precision)));
        }
        HyboxChartRenderer.hover(cmd, selector + " #Hover", CHART_WIDTH, 96, tips);
    }

    private static void timeline(IncidentModel model, ViewState state, Block block, String parent, String selector, int columnCap, int hoverCells, ChartImages images, UICommandBuilder cmd) {
        cmd.append(parent, "Pages/HyboxIncidentChart.ui");
        JsonArray series = array(block.data); int[] range = range(model, state, series.size());
        cmd.set(selector + " #ChartTitle.Text", block.label);
        cmd.set(selector + " #ChartStats.Visible", false);
        cmd.set(selector + " #ChartLegend.Visible", false);
        cmd.set(selector + " #YAxis.Visible", false);
        cmd.setObject(selector + " #Plot.Anchor", anchor(0, 0, CHART_WIDTH, 18));
        for (String grid : List.of("#GridMax", "#GridMid", "#Baseline")) cmd.set(selector + " " + grid + ".Visible", false);
        axes(model, selector, range, series.size(), cmd);
        HyboxChartRenderer.hover(cmd, selector + " #Hover", CHART_WIDTH, 18, stateTips(model, series, range, hoverCells));
        if (images != null && range[1] > range[0]) {
            int[] codes = stateCodes(series, range);
            images.request(new ChartRequest(() -> HyboxChartImage.states(codes, CHART_WIDTH, 18), (path, swap) -> {
                swap.clear(selector + " #Bars");
                swap.setObject(selector + " #PlotImage.Anchor", anchor(0, 0, CHART_WIDTH, 18));
                swap.set(selector + " #PlotImage.AssetPath", path);
                swap.set(selector + " #PlotImage.Visible", true);
            }));
        }
        int columns = Math.min(columnCap, range[1] - range[0]);
        for (int column = 0; column < columns; column++) {
            int from = range[0] + column * (range[1] - range[0]) / columns;
            int to = range[0] + (column + 1) * (range[1] - range[0]) / columns;
            int[] counts = new int[5];
            for (int sample = from; sample < to; sample++) counts[Math.max(0, Math.min(4, (int) number(series.get(sample), 0)))]++;
            int value = 0; for (int candidate = 1; candidate < counts.length; candidate++) if (counts[candidate] > counts[value]) value = candidate;
            int left = column * CHART_WIDTH / Math.max(1, columns), width = Math.max(1, (column + 1) * CHART_WIDTH / Math.max(1, columns) - left);
            cmd.appendInline(selector + " #Bars", "Group { Anchor: (Left: " + left + ", Top: 0, Width: " + width + ", Height: 18); Background: " + stateColor(value) + "; }");
        }
    }
    static String stateColor(int code) {
        return HyboxChartImage.hex(HyboxChartImage.STATES[code >= 1 && code < HyboxChartImage.STATES.length ? code : 0]);
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
            String name = switch (value) { case 1 -> "RUNNABLE"; case 2 -> "WAITING"; case 3 -> "TIMED_WAITING"; case 4 -> "BLOCKED"; default -> "no samples"; };
            tips.add(timeAt(model, from, series.size()) + "–" + timeAt(model, to - 1, series.size()) + " · " + name);
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
            cmd.set(s + ".TooltipText", text(at(segment.node, 0)) + " · " + count(number(at(segment.node, 1), 0)) + " samples · self " + flameSelf(segment.node) + ownerSuffix(segment.node) + flameLabel(segment.node));
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
            try { JsonArray children = array(at(node, 2)); int i = Integer.parseInt(index); if (i < 0 || i >= children.size()) break; node = array(children.get(i)); text.append(" → ").append(text(at(node, 0))); }
            catch (NumberFormatException ignored) { break; }
        }
        return text.toString();
    }
    private static String childPath(String parent, int child) { return parent.isEmpty() ? Integer.toString(child) : parent + "/" + child; }
    private static String parentPath(String path) { int slash = path.lastIndexOf('/'); return slash < 0 ? "" : path.substring(0, slash); }
    private static String flameSelf(JsonArray node) { return count(Math.max(0, number(at(node, 1), 0) - array(at(node, 2)).asList().stream().mapToDouble(c -> number(at(array(c), 1), 0)).sum())); }

    private static Map<String, String> presets(IncidentModel model) {
        Map<String, String> presets = new LinkedHashMap<>();
        double window = number(model.object("incident").get("windowSec"), 0);
        if (present(model.object("incident").get("markerFrac"))) presets.put("incident", "Incident");
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
        int seconds = rangeId.startsWith("d") ? Integer.parseInt(rangeId.substring(1)) : rangeId.equals("5m") ? 300 : 60;
        return "Last " + (seconds >= 86400 ? seconds / 86400 + " d" : seconds >= 3600 ? seconds / 3600 + " h" : seconds / 60 + " min");
    }

    private static int[] range(IncidentModel model, ViewState state, int size) {
        if (size == 0) return new int[]{0, 0};
        double window = number(model.object("incident").get("windowSec"), 0);
        if (state.timeRangeId().equals("all") || window <= 0) return new int[]{0, size};
        double seconds = state.timeRangeId().startsWith("d") ? Double.parseDouble(state.timeRangeId().substring(1)) : state.timeRangeId().equals("5m") ? 300 : 60;
        double marker = number(model.object("incident").get("markerFrac"), 1);
        double start = Math.max(0, (state.timeRangeId().equals("incident") ? marker : 1) - seconds / window);
        return new int[]{Math.max(0, Math.min(size - 1, (int) Math.floor(start * (size - 1)))), size};
    }
    private static String timeLabels(IncidentModel model, int[] range, int size) {
        JsonObject incident = model.object("incident"); double window = number(incident.get("windowSec"), 0);
        return clock(text(incident, "recStart"), window * range[0] / Math.max(1, size - 1)) + " → " + clock(text(incident, "recStart"), window * Math.max(0, range[1] - 1) / Math.max(1, size - 1));
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
    private static boolean dataIsSeries(JsonElement value) { return value != null && value.isJsonArray() && !value.getAsJsonArray().isEmpty(); }
    private static String join(JsonArray a, String separator) { return String.join(separator, a.asList().stream().map(IncidentModel::text).toList()); }
    private static String readable(JsonElement value) {
        if (value == null || value.isJsonNull()) return "Not captured";
        if (value.isJsonArray()) return String.join(" · ", value.getAsJsonArray().asList().stream().map(HyboxIncidentRenderer::readable).toList());
        if (value.isJsonObject()) return String.join(" · ", value.getAsJsonObject().entrySet().stream().map(e -> label(e.getKey()) + ": " + readable(e.getValue())).toList());
        return text(value);
    }
    private static String display(JsonElement value, String key) {
        if (!present(value)) return "Not captured";
        if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()) return readable(value);
        double n = number(value, 0);
        int separator = key.lastIndexOf(" · ");
        String lower = key.substring(separator < 0 ? 0 : separator + 3).toLowerCase(Locale.ROOT).replace(" ", "");
        boolean memory = key.startsWith("Heap") || key.startsWith("Process RSS") || key.contains("Non-heap") || Set.of("bytes", "rsslast", "rsspeak", "ramtotal", "machineram(host)", "hostmemused", "hostmemory", "free", "total", "memlimit", "xmxbytes", "maxheap(-xmx)", "used", "committed").contains(lower) || lower.endsWith("bytes");
        if (memory && !lower.equals("nmton")) return bytes(n);
        if (lower.endsWith("ms") || lower.endsWith("(ms)")) return (n == Math.rint(n) ? count(n) : decimal(n, 2)) + " ms";
        if (lower.equals("windowsec") || lower.equals("recoverysec")) return decimal(n, 1) + " s";
        return n == Math.rint(n) ? count(n) : decimal(n, 2);
    }
    private static String chartValue(double n, String unit, int precision) {
        if (unit.equals("GiB")) n /= 1073741824.0;
        if (unit.equals("GB")) n /= 1e9;
        return unit.equals("bytes") ? bytes(n) : decimal(n, precision) + HyboxChartRenderer.suffix(unit);
    }
    private static String unit(String key) {
        return switch (key) {
            case "cpu", "cpuJvm" -> "%";
            case "heap", "heapCommitted" -> "GiB";
            case "rss", "hostMem", "disk" -> "GB";
            case "diskRead", "diskWrite" -> "MB/s";
            case "mspt", "gcPause", "ping" -> "ms";
            case "netIn", "netOut" -> "Mb/s";
            case "tps" -> "tps";
            default -> "count";
        };
    }
    private static String bytes(double n) { return Units.bytes(Math.round(n)); }
    private static String gib(double n) { return decimal(n / 1073741824.0, 1); }
    private static String duration(double ms) { long seconds = Math.max(0, (long) ms / 1000); return seconds >= 3600 ? seconds / 3600 + "h " + seconds % 3600 / 60 + "m" : seconds >= 60 ? seconds / 60 + "m " + seconds % 60 + "s" : seconds + "s"; }
    private static String percent(double amount, double total) { return total > 0 ? decimal(100 * amount / total, 2) + "%" : "n/a"; }
    private static String count(double n) { return Units.number(Math.round(n)); }
    private static String decimal(double n, int places) { return String.format(Locale.US, "%1$." + places + "f", n); }
    private static String threadGroup(String name) {
        int i = name.length() - 1;
        while (i >= 0 && Character.isDigit(name.charAt(i))) i--;
        return i >= 0 && i < name.length() - 1 && (name.charAt(i) == '-' || name.charAt(i) == '#') ? name.substring(0, i) : name;
    }
    private static String label(String key) { return LABELS.getOrDefault(key, key.replaceAll("([a-z])([A-Z])", "$1 $2").replace('_', ' ')); }
    private static Map<String, String> labels() {
        Map<String, String> labels = new LinkedHashMap<>();
        String[][] pairs = {{"jvmAvg","JVM CPU average (%)"},{"jvmMax","JVM CPU peak (%)"},{"machAvg","Machine CPU average (%)"},{"machMax","Machine CPU peak (%)"},{"cpu","Machine CPU"},{"cpuJvm","JVM CPU"},{"heap","Heap used"},{"heapCommitted","Heap committed"},{"rss","Process RSS"},{"hostMem","Host memory used"},{"mspt","Tick interval"},{"tps","Tick rate"},{"gcPause","GC pause per bucket"},{"netIn","Network inbound"},{"netOut","Network outbound"},{"players","Players online"},{"exceptions","Exceptions"},{"ping","Player ping"},{"disk","Disk free"},{"diskRead","Disk read"},{"diskWrite","Disk write"},{"chunksGenerated","Chunks generated"},{"chunksLoaded","Chunks loaded"},{"bySubsystem","By subsystem"},{"byThread","By thread"},{"byClass","By class"},{"byMod","By mod"},{"cpuBrand","Brand"},{"cpuCores","Cores"},{"hwThreads","Hardware threads"},{"sockets","Sockets"},{"os","OS"},{"arch","Architecture"},{"loadAvg","Load average (1m, host)"},{"openFds","Open files"},{"maxFds","Open files limit"},{"cpuset","CPU affinity (cpuset)"},{"ramTotal","Machine RAM (host)"},{"hostMemUsed","Host memory"},{"safepointCount","Safepoints"},{"safepointMs","Time to safepoint (ms)"},{"gcYoung","Young collector"},{"gcOld","Old collector"},{"gcParallel","Parallel GC threads"},{"gcConc","Concurrent GC threads"},{"gcTimeRatio","GC time ratio"},{"jvm","Runtime"},{"javaVendor","Vendor"},{"javaVersion","Version"},{"javaHome","java.home"},{"hytale","Server software"},{"server","Host"},{"xmxBytes","Max heap (-Xmx)"},{"msptP50","Tick interval p50 (ms)"},{"msptP95","Tick interval p95 (ms)"},{"msptMax","Worst single tick (ms)"}};
        for (String[] pair : pairs) labels.put(pair[0], pair[1]);
        labels.putAll(Map.ofEntries(
            Map.entry("peak", "Peak used"), Map.entry("floor", "Post-GC floor"), Map.entry("max", "Maximum"),
            Map.entry("committed", "Committed"), Map.entry("rssLast", "Process RSS at capture"), Map.entry("rssPeak", "Peak process RSS"),
            Map.entry("nmtOn", "Native memory tracking"), Map.entry("nativeCommitted", "Native memory committed"),
            Map.entry("collections", "Collections"), Map.entry("maxPauseMs", "Longest pause"), Map.entry("totalPauseMs", "Total pause time"),
            Map.entry("explicitDisabled", "Explicit GC disabled"), Map.entry("explicitConcurrent", "Explicit GC concurrent"), Map.entry("explicitCalls", "Explicit GC calls"),
            Map.entry("world", "World"), Map.entry("name", "Name"), Map.entry("samples", "Execution samples"), Map.entry("target", "Target TPS"), Map.entry("avg", "Average TPS"), Map.entry("min", "Minimum TPS"),
            Map.entry("iface", "Interface"), Map.entry("inAvg", "Inbound average (Mb/s)"), Map.entry("inPeak", "Inbound peak (Mb/s)"), Map.entry("outAvg", "Outbound average (Mb/s)"), Map.entry("outPeak", "Outbound peak (Mb/s)"), Map.entry("others", "Other interfaces"),
            Map.entry("playerNames", "Connected players"), Map.entry("entities", "Entities"), Map.entry("chunks", "Chunks"), Map.entry("stalled", "Stalled world"), Map.entry("mspt", "Mean tick interval"),
            Map.entry("title", "File"), Map.entry("content", "Contents")
        ));
        return labels;
    }
    private static void bind(UIEventBuilder ev, String selector, String action, String arg) {
        ev.addEventBinding(CustomUIEventBindingType.Activating, selector, EventData.of("Action", action).append("Arg", arg));
    }
}
