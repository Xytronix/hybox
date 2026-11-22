# Changelog

## Unreleased

### Privacy

- `recording.jfr` in a bundle is now a cleaned copy. Paths, known player names and UUIDs, IP addresses with their port, private start arguments and your OS user name are masked in place with `*` of the same length. Samples of the network threads, thread dumps, the JVM start command and native library events stay. Hybox leaves out environment variables, system and security properties, the process list, started processes, Java agents, socket events and TLS handshakes. If masking fails, it leaves out every event with private text instead and logs why. The report is still built from the full recording.
- Text files in a bundle show the server folder as `.` and home folders as `~`, and keep only the last part of other absolute paths. This covers `java.home` in `env/jvm.txt` and the report, and the Start command.
- The Start command hides the owner name and UUID, session and identity tokens, the bind address, boot commands, and arguments named like tokens, secrets, passwords or API keys.
- A port right after a redacted IP address is removed too.
- `/hybox status --verbose` in game shows paths the same way. The console still shows full paths.
- The capture result in chat and on the dashboard says what is hidden and what to check before sharing.

### Fixed

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
- Frames that a Mixin added or changed carry the mod and Mixin, such as "changed by Mixin from Refixes (MixinWorld)", in captured stack lines, hot methods and their callers, the top method of hot threads, flame graph tooltips, the Diagnosis top frame and `extras/threads.txt`. Their CPU time still counts as engine time.
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