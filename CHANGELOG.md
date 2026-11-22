# Changelog

## Unreleased

### Added

- Reports read JDK Flight Recorder events from any mod, with no Hybox dependency. Events in the category `Mods`, `<mod name>` with the fields `name`, `kind` and `value` become Plugin metrics charts, with `category` and `message` they become markers with one legend entry per mod and rows in the in-game event list, and with `name` and `value` they become a settings card per mod under Captured diagnostics. A mod named like a loaded plugin shows with the plugin id, such as `IroriPowered:Refixes`. See [Plugin API](docs/plugin-api.md#jfr-events).

### Privacy

- `recording.jfr` in a bundle is now a cleaned copy. Paths, known player names and UUIDs, IP addresses with their port, private start arguments and your OS user name are masked in place with `*` of the same length. Samples of the network threads, thread dumps, the JVM start command and native library events stay. Hybox leaves out environment variables, system and security properties, the process list, started processes, Java agents, socket events and TLS handshakes. If masking fails, it leaves out every event with private text instead and logs why. The report is still built from the full recording.
- Text files in a bundle show the server folder as `.` and home folders as `~`, and keep only the last part of other absolute paths. This covers `java.home` in `env/jvm.txt` and the report, and the Start command.
- The Start command hides the owner name and UUID, session and identity tokens, the bind address, boot commands, and arguments named like tokens, secrets, passwords or API keys.
- A port right after a redacted IP address is removed too.
- `/hybox status --verbose` in game shows paths the same way. The console still shows full paths.
- The capture result in chat and on the dashboard says what is hidden and what to check before sharing.

### Fixed

- CPU and allocation by mod no longer blame a mod for engine work that it only wraps. A sample counts as a mod's own time only when the mod's code was running. JDK and library frames count for the code that called them, like filtered classes in YourKit. Each mod also gets its total: every sample with the mod anywhere on the stack, with the frame it entered through. In a real recording Refixes dropped from 25% of CPU and 80% of allocation to 2.7% and 0.8% own time, because its `IndexedStorageLifetime.child` wrapper sits above Hytale's chunk saving. Hot threads list the mods on each thread with both numbers.
- Players online, entities and chunks no longer count a world again after it stops reporting. A player who left an instance was counted twice.
- The Diagnosis compares peak process memory (RSS) with peak heap, both in GiB, and explains when RSS is lower than the heap.
- Slow file operations state the limit the recording really used instead of a fixed 20 ms.
- System & hardware calls the server name "Server name" instead of "Host", and hides the socket count when the JVM only repeats the core count (all ARM machines).
- The Heap tab shows each class as a share of the objects found, not of the heap.
- The incident viewer badge shows what the charts cover, such as "Last 1 min", instead of "Whole recording" when a shorter range is picked.
- Manual captures no longer show "stall Not captured", "Blocked in Not captured" or the world "server" in the header.
- Redaction no longer hides version numbers that look like IP addresses. The running JVM's versions and the Hytale and plugin versions are kept, so the report shows "Temurin 25.0.4.1+1-LTS" instead of "Temurin [REDACTED]+1-LTS".

### Mixins

- With a Hyinit that has the diagnostics API (`cc.irori.hyinit.shared.HyinitDiagnostics`), the report and the in-game viewer have a Mixins section after Plugins: Hyinit, Mixin, Fabric Mixin and MixinExtras versions, each early plugin JAR with its version and the first 8 characters of its SHA-256 (the full value on hover in the report), each Mixin config, Mixin problems since startup, and methods changed by more than one mod. Hybox reads Hyinit by reflection and needs no Hyinit at runtime.
- Mixin errors and warnings show without opening a section: a "Mixin problems" chip in the report header, a count on the Mixins entry in the in-game navigation and in the Overview, a Diagnosis line, and a dashboard line such as "Mixins: 1 error since startup".
- Frames that a Mixin added or changed carry the mod and Mixin, such as "changed by Mixin from Refixes (MixinWorld)", in captured stack lines, hot methods and their callers, the top method of hot threads, flame graph tooltips, the Diagnosis top frame and `extras/threads.txt`. Code a Mixin added counts for its mod, and methods a Mixin only changed still count as engine time.
- New `extras/mixins.txt` lists every method a Mixin added or changed, with the Mixin, config and JAR, and the full SHA-256 of each early plugin JAR. It follows the `plugins` entry of `Capture.Artifacts`.
- Hybox saves Hyinit's startup snapshot to `live/hyinit.json`, so a bundle rebuilt after a crash still has the Mixins section of the run that crashed.
- With Hyinit 0.3.1 or older the Mixins section shows the Hyinit version and "Update Hyinit for Mixin details". Other Mixin loaders keep the old config list and class overlap check.
- With the new Hyinit data, the "Hyinit installed" Environment row and the "Hyinit loader" row in System & hardware are gone, because the Mixins section shows the version. "Config files & mixins" is now "Config files".

### Retention

- Retention no longer deletes bundles silently. Each deleted bundle gets a server log line with its file name and the rule that removed it (age, count or size), followed by a summary line. Deleted failed recordings are logged the same way.
- The capture message in chat, the notification and the dashboard add a line such as "Removed 15 bundles older than 7 days" when a capture deleted old bundles. A profile with `--keep-buffer=false` sends this message when its first bundle deletes old ones.
- `/hybox status` shows bundle storage next to the limits and the free disk space, such as "16 of 25 bundles · 640 MiB of 1 GiB · kept for 7 days · 8.4 GiB free on disk", and names bundles that the next capture will delete. `--verbose` shows the limits in the same units instead of raw bytes and ISO durations, and calls the staging and JFR repository check "Recording storage".
- The Settings page shows the same storage use under the retention limits.
- The Storage tile now compares the bundle folder with `Retention.MaxTotalBytes` and checks free disk space. It shows "Low disk space" or "Near limit" instead of "Healthy" in those cases, and the dashboard warns about bundles older than `Retention.MaxAge`.
- The Last incident tile shows the age in a neutral colour. Only the severity badge is coloured, so an old CRITICAL incident no longer looks like a current problem.
- The retention summary log line is two sentences, such as "Retention removed 4 bundles (3 by age, 1 by count, 0 by size) and freed 40 B. 2 bundles and 20 B remain."

### Cleanup

- Each fact shows once. The in-game Overview drops the "Incident details" card and the CPU samples tile, the header shows only the capture time, the Stall tile shows only for stalls, and a last "Bundle" row gives the id and Hybox version.
- In-game Captured diagnostics shows the same cards as the report. Plugins, Asset packs, Tick systems, Memory pools and the other cards that have their own section are no longer repeated there.
- Mod CPU uses one rule. The "Mod hot-path contribution (JFR)" card and its second pass over the recording are gone. The report and the viewer show one "by mod" list.
- Tick rate is shown in Key metrics only, with one line per world. CPU & load and Worlds link to it.
- The Diagnosis drops the Severity row (the header badge has it), the generic "Investigation direction" and caveat, and the GC pause and deadlock tags that repeated evidence lines. "0 threads were BLOCKED" no longer shows. A blocked thread now reads "1 thread was waiting for a lock at capture."
- Key metrics drops the sample count, the Live threads chart (now in Thread states) and the Network out chart (Network has it). In game, missing series and the second Events list are gone.
- Hybox labels its own work: threads and thread pools named `hybox-`, its CPU row ("Hybox itself") and the "Heap Inspection Initiated GC" cause ("Hybox heap walk"). Hybox's own asset pack is no longer listed.
- JVM internals moved behind an "Advanced" fold in the report and to the end of the section in game: memory pools, GC causes, collections since JVM start, native memory, explicit GC, allocating threads and classes, load average, safepoints and the garbage collector settings. The thread state timeline and the thread state chart are folded too.
- Hot threads drop the State column, which was always RUNNABLE. Thread states lead with "No thread was waiting for a lock" and "No deadlock found". Network hides idle interfaces and the data source row. System & hardware shows Open files only near the limit and Max heap only in Memory & GC. Engine systems show one row per system in game.
- The in-game viewer hides empty sections in the navigation, like the report. The dashboard shows the Capture tile only while a capture runs or after one failed, and the Callbacks tile only when a callback failed.
- The trend report hides charts with no data and lists them in one line. Its aggregation note moved into a tooltip.
- Byte sizes, counts and plurals come from one shared formatter, so chat, the dashboard and the viewer write "2 GiB" and "1 day" the same way.

### Changed

- The HTML report and the trend report use the new black and yellow look. The Hybox logo sits in the page header and is the browser tab icon.
- Yellow marks the brand and what you can click or have chosen: links, the current section, the chosen time range and filters, the focus ring and the first line in each chart. Warnings are orange and always come with a word.
- The severity badge is red for CRITICAL, orange for DEGRADED and blue for INFO.
- Charts share one set of line colours that never reuses the warning or error colours. In the flame graph, mod frames are yellow and engine frames are blue.
- Every text colour reaches at least 4.5:1 contrast on its background, including search placeholders and severity chips.
- Baseline deltas say "better" or "worse" and flagged stats say "low" or "high", so the meaning does not rely on colour. Event toggles in chart legends show their names.
- "Copy as Markdown" says "Copy failed" when the browser blocks copying.
- The report footer says that player names, paths and IP addresses are hidden.
- The in-game dashboard, incident viewer, trends, settings and the progress HUD use the same black and yellow look. Panels stay as opaque as Hytale's own pages, so text stays readable over the world.
- The Hybox logo sits in the dashboard and incident viewer headers. It ships in Hybox's asset pack.
- The open tab, the chosen profile length and the chosen time range show in yellow with a bar under them, instead of looking greyed out. The main action button is yellow.
- Chat messages start with a yellow "[Hybox]". Errors are red and warnings orange in chat, on tiles and in banners. A trigger that is off shows in grey, not as a warning.
- In-game charts draw the first line in yellow, mark incidents with a red dashed line, and their legends name what the marks mean instead of their colour.
- Shrinking labels in game no longer go below 10 px.
- The HTML report no longer adds a "Hybox itself" or "Hybox heap walk" label in CPU by mod, hot threads, thread pools and GC causes.
- Every report colour comes from the palette variables, including tints, shadows and the flame graph. Class names, code, log sources and config files use the warm text colours. The third chart colour is now lavender `#c2b0f5`, also in game. Native controls and scrollbars are dark, and printing keeps the dark page.
- Chart hover shows one tooltip beside the pointer on the chart you point at, flips at the window edges and closes when the pointer leaves, on Escape and on scroll. Other charts show only the crosshair. On touch, a tap pins a sample, dragging sideways moves it and a tap elsewhere closes it.
- One marker legend under the time range row controls every chart: incident, events and GC pauses. It lists only the event types the recording has, with their counts, and says "no events" when there are none.
- Event lines sit on a real sample, have a dot at the top and show their events when the pointer is within 10 px.
- The trend report has the same crosshair and tooltip, and its incident toggle row is always shown.
- Evidence in the report Diagnosis lists only results over a threshold and names the threshold in the line, for example "GC paused the server for up to 210 ms, above the 50 ms threshold." The trigger line at the top is gone because the header already says it. Next steps name the report section to open and what it shows there, instead of "Inspect", "Review" or "Profile in JDK Mission Control".
- The report header shows the capture time as `2026-11-19 00:46:49 UTC`, no longer shows a `recovered true` pill, and names trigger details in words, for example "Tick time 120 ms". The report data has a short OS name (`osShort`) and a `vitals` row with tick time, TPS, CPU and heap. The OS no longer shows the kernel of the machine that built the report.
- Headlines use the owner's words, for example "World default stopped responding for 4 min 20 s" and "2 threads are deadlocked". They appear in the report title, the dashboard, `/hybox list` and Discord. Discord messages read `DEGRADED: <headline>` without the "(scope: x)" suffix.
- Thread dump progress, the report's missing recording and crash recovery notes, and Hybox's storage and recording log lines are shorter and have no semicolons.
- A new docs page, [Reading a report](docs/reading-a-report.md), lists each Evidence threshold and explains tick time, TPS, CPU samples, the flame graph and the memory sections. The README and install docs name `Hybox-1.1.0.jar`.
- `/hybox status` has a second line with live numbers from the health sampler, for example "TPS 30.0, tick 3.9 ms, CPU 9%, heap 0.9 of 4.0 GiB". Chat and console replies use plain sentences instead of `key=value`, ISO durations and " · " joins: "Triggers skipped: 1" replaces "Admission", trigger thresholds read "Heap pressure (armed): 90% after GC for 60 s" in both the console and the dashboard, each "Capture skipped" reply says why, all "worker is busy" replies are now "Hybox is busy. Try again in a moment.", and bare `/hybox` in the console lists one command per line.
- The in-game incident viewer drops the "Memory capture semantics" glossary row, the doubled byte values, "0 incompatible server ranges", "COMPATIBLE" and "CurseForge search" on plugin rows. CPU shows a Machine CPU row and a Server CPU row. Counts use the shared plural helper ("1 sample"), byte sizes are GiB or MiB, network rates are Mbit/s, and tick metrics are called "Tick time" and "TPS".
- The dashboard, trends, settings and progress HUD use shorter wording: "Crash backup saved 2m ago", "1 trigger skipped", "2 failed" on the callbacks tile, "Detailed profile", "Waiting for the server to recover", "Saving the recording", "Attention", "Hybox profile". Trend stats print the unit once in the chart title ("min 1.6 · avg 8.8 · max 75.0 · last 1.9"), and a trend view with metrics off says "Trends are off. Turn on Metrics history in /hybox settings."
- In-game, chat and log text in `hybox-hytale` has no semicolons and no en dashes.
- The HTML report opens on one row of four numbers under the headline: tick time, TPS, CPU and heap. A number turns red or orange only when it crosses its target, for example tick time at or over the 33.3 ms budget at 30 TPS. The tick time chart draws that budget as a labelled dashed line.
- Diagnosis comes right after the overview, before Captured diagnostics. Evidence links use the section titles without an arrow, and the Flame graph next step opens the busy world thread in the flame graph. The OS chip shows a short name and keeps the full string in its hover.
- The Baseline table lists the metrics that got better or worse first. The others sit behind "All metrics".
- Leads that repeated the table under them are gone from CPU & load, Hot methods, Hot threads, Memory & GC, Network and Worlds, so the busiest world share shows in Evidence and the bars only. The Plugin metrics lead is four short sentences, section subtitles that repeated the title are gone, and the nav footer links to Reading a report.
- Chart notes and hovers in the HTML report no longer name JFR events or hedge, and chart stats read "min, avg, max, last" with the unit once at the end.
- Both HTML reports use one unit and one name per metric: GiB and MiB for memory and disk, Mbit/s for network, "Tick time", "TPS", "CPU samples", "Machine" for the computer and "Server" for the Hytale process. Counts per chart point name their interval, for example "per 2.1 s", and the Baseline table uses "/min".
- The trend report header reads "Last 1 day, 71 samples, 1 incident. Times are UTC." and a visible "Line: average. Band: lowest to highest." replaces its long hover. Each card labels its big number "last", values have one decimal and memory shows in GiB. With no data it points to Metrics history in `/hybox settings`.
- The in-game incident viewer no longer adds a "HYBOX" pill, "Hybox itself" or "Hybox heap walk" to Hybox threads, thread pools, mods and GC causes. Its engine systems, allocation and Mixin overlap notes use the same words as the HTML report.
- The HTML report and the trend report have no semicolons in their text.
- The HTML reports and the game no longer use blue, teal or violet. Info and waiting states are warm grey with a neutral outline, chart lines use yellow, warm white, gold and warm greys with dash patterns where lines are close, events are warm white and sand, and flame graph engine frames are sand.
- Thread state strips leave gaps where there are no samples, so they no longer look like the grey waiting state.
- The trigger shows in plain words in the report and in game, for example "Freeze", "Unclean shutdown" or "Manual". Severity shows as "Critical", "Degraded" or "Info".
- "Blocked in" shows the original method, for example `TickingThread.run`, with the Mixin label when Hyinit names the mod. Generated MixinExtras names are stripped in the report and in game, and `threads.txt` adds the original method beside generated frames.
- "Copy as Markdown" stays on the header row next to the incident ID on desktop.
- Headings, labels and table headers in the report and in game are in sentence case at normal letter spacing.
- The stalled world evidence drops "#1 of 1 worlds by CPU" when there is one world.
- Count charts such as Players online use whole-number axes. A count that never changes gets an axis from 0 to at least the value plus one.
- The Connections stats read "1 connect, 1 join" when counts are small, otherwise one line with the unit once.
- The trend report incidents toggle works again. A page-wide icon layer covered it. Each incident marker has a dot at the top, and hovering or tapping it shows the time, severity, headline, trigger and bundle file. Click or tap keeps it open so the bundle link can be used.
- The online player count uses the server's count of connected players, so players moving between worlds or still loading are no longer missed. The header reads "3 players online at capture". The players chart, the trend players line, the `hybox_players` gauge and the player drop trigger use the same count. Older recordings still add up the worlds.
- Plugin names in the Plugins and Early plugin JARs tables link to the mod's website, or to a CurseForge search when it has none. Only http and https websites are linked. Early plugin JARs without a manifest take the website from Hytale's plugin list when the plugin ID matches.
- CPU by mod and Allocated memory by mod show Self and Total columns, sorted by self, in the report, Copy as Markdown and the in-game viewer. The bar and the main number are self. A line under each mod names the method it was entered through and where its own code ran. Mods with nothing in either column are hidden. Hot threads add "Mods on this thread" with both numbers. Older bundles keep their one number with a note on the older rule.
- Diagnosis names a mod when its own code ran in 10% or more of the CPU samples or allocated 10% or more of the sampled memory, with a next step to check its settings or report it to its author. It needs at least 300 CPU or allocation samples. Hytale and the JDK never get this line. Hybox's work for a capture after its trigger is its own "Hybox, this capture" row in the tables and is left out of the line.