# Changelog

## 1.3.1

### Fixed

- On Hytale 0.8 Mixin code by mod also counts the Mixin code that ran on the server work pool threads, where Hytale 0.8 runs world generation. It has a table of its own in the report and one line per mod on the in-game CPU page, both labelled as not world thread time. Before, this time was in no table.

## 1.3.0

### Upgrading

- Mixin code by mod, exact overloads, Mixin apply time and the count of Mixins off by the mod's settings need Hyinit 0.5.0 or newer.

### Added

- Mixin code by mod in the report and on the in-game CPU page shows the share of world thread samples that ran in the code each early plugin JAR adds to Hytale with its Mixins.
- Each mod there also shows at most how much it costs with the Hytale code its Mixin code called, how often a method it changed was on the stack and its busiest Mixin methods.
- Mixin code by mod also lists the Mixin code that ran on common pool threads in a second table that is labelled as not world thread time.
- The Mixins section in the report and in game shows the Mixin apply time of each early plugin JAR, which is spent once when a class loads and is not CPU while the server runs.
- The Mixins section shows how many Mixins of each early plugin JAR are off by the mod's settings.
- Mixin labels and Mixin code by mod follow the exact overload a Mixin changed, matched by method descriptor in recordings and by line number in stack traces and thread dumps.

### Privacy

- `extras/mixins.txt` also lists the Mixin apply time and the Mixins off by settings of each early plugin JAR and the descriptor and line range of each changed method.

## 1.2.0

### Upgrading

- One jar runs on Hytale 0.7.7 and later and on 0.8.0 pre-releases from pre-release 5, including 0.8.0-pre.6. On 0.8 the server no longer warns that Hybox is out of date.
- New installs have `Capture.IncludeServerLog`, `Metrics.Enabled` and `Jfr.OldObjectSampling` on. An existing `hybox.json` keeps its values, so turn them on yourself for the masked server log in bundles, trends and usual values in reports, and where long-lived objects were allocated.
- The `default` JFR preset records less: no thread sleep, monitor wait or native method samples, and lock waits only from 500 ms. Recordings under load are about a third of the size. Set `Jfr.Configuration` to `"profile"` to record everything.
- Settings have [allowed ranges](https://wiki.hytalemodding.dev/mod/hybox/configuration#allowed-ranges). A value outside its range is used as the nearest allowed value, with one warning in the server log. `hybox.json` is not changed, and `0` still turns things off.
- `/hybox profile` takes a mode, `lag` or `memory`. Without one it records `lag`, which has everything the old profile had.
- Discord messages start with the server name and read `<Severity> incident: <headline>`, with the world, incident id, first evidence line and first next step on the next line. Scripts that matched `DEGRADED: <headline>` need the new form.
- Players online, the `hybox_players` gauge and the player drop trigger use the server's count of connected players, so players moving between worlds or still loading count.
- The built-in `Capture.RedactPatterns` list grew from four to seven patterns. A `hybox.json` that still lists the four earlier built-in patterns uses the new list. A list you changed yourself is kept as written.
- At startup Hybox cleans each bundle saved by an older version once, on its worker thread, and rewrites the zip. A bundle it cannot clean stays as it was and shows as "Not cleaned" in game.
- Slow-tick limits follow the tick budget of each world. `Trigger.TickAvgDegradedMs` and `Trigger.TickAvgCriticalMs` default to `0`, Auto, which fires at 3 times and rates critical at 7.5 times the budget: 100 ms and 250 ms at 30 TPS, 50 ms and 125 ms at 60 TPS. The old defaults `100` and `250` in an existing `hybox.json` count as Auto. Any other value is still a fixed limit in ms.
- The health metrics CSV has five more columns at the end: `targetTps`, `maxViewRadius`, `viewRadiusMedian`, `hotRadiusMedian` and `loadedSections`. Files from 1.1.0 are still read.

### Added

- Profile modes. `lag` samples CPU every 10 ms and records lock waits from 10 ms. `memory` adds more allocation samples and the chain of references that keeps each long-lived object alive, with the mod that holds it. Each costs about 0.3% of one core. A memory profile pauses the server for up to 1 s at the end. The dashboard has a button for each.
- An incident during a profile is saved at once and marked "During profile (lag)", and the profile keeps running. A profile started while an incident is being saved waits for it. Cancel also drops a waiting profile, asks first when data would be lost, and never touches an incident bundle.
- Lock waits table in CPU & load: waits of 500 ms or more, with the thread, the lock, the code that waited and its mod. Diagnosis names the lock when it held up a stalled world.
- "Usual for this server" column: each value next to this server's normal range for the same time of day over the last 7 days, with a Diagnosis line when it is far outside it.
- Diagnosis lists earlier incidents on disk with the same trigger or world, and whether the same mod or method was on top. Hybox reads only their report data.
- Staff alerts: players with the `.incident` permission get a toast and a chat line when Hybox saves an incident on its own, at most one per 5 minutes unless a later one is worse.
- PlaceholderAPI placeholders for TPS, tick time, status, heap and incidents. See [Placeholders](https://wiki.hytalemodding.dev/mod/hybox/using-hybox#placeholders).
- Mixins section, in the report and in game, with Hyinit 0.4.0: versions, early plugin JARs with SHA-256, Mixin configs, Mixin problems with stack traces, and methods changed by more than one mod. Problems also show as a header chip, a count in game and a dashboard line. Stack frames that a Mixin changed name the mod, and `extras/mixins.txt` lists every changed method. Older Hyinit versions get "Update Hyinit for Mixin details".
- Mods can add charts, markers and settings to reports by writing JFR events, with no Hybox dependency. See [Plugin API](https://wiki.hytalemodding.dev/mod/hybox/plugin-api#jfr-events). With Prometheus on, mod metrics are also served as `hybox_mod_gauge` and `hybox_mod_counter`. Refixes writes these events by default.
- Click a chart to keep its values open: full event texts, every chart over its threshold at that moment, the busiest code and thread with their mod, and "Zoom to ±30 s". With the pointer over a chart, +, - and 0 zoom.
- The report header shows the bundle size and has Copy summary, a short text for one Discord message. Copy full report as text is in the sidebar.
- Plugin names link to the mod's website, or to a CurseForge search.
- `/hybox status` shows live TPS, tick time, CPU and heap, bundle storage against the limits, free disk space and which bundles the next capture deletes.
- The settings page warns about unsaved changes, shows "Needs a restart" for recording settings, and gives each allowed range in plain units. If it cannot open, the dashboard says why.
- New docs page [Reading a report](https://wiki.hytalemodding.dev/mod/hybox/reading-a-report), and a LuckPerms example in [Installing](https://wiki.hytalemodding.dev/mod/hybox/installing-hybox#permissions).
- Staff in game and Discord are alerted when a trigger is accepted, before the post-incident wait, and again when the bundle is saved or could not be saved.
- `Discord.ServerName`, shown at the start of each Discord message. Empty uses the server name from Hytale's `config.json`.
- Discord messages show the world, incident id, first evidence line and first next step.
- Bundles saved by an older Hybox show a "Not cleaned" pill in the Incidents list and a warning on the incident page not to share the zip.
- Worlds shows the view radius and hot radius of the players in each world as median, lowest and highest, the chunk sections they load in total and for the busiest player, and Top players by loaded sections with up to five masked players per world. System shows the server `MaxViewRadius`.
- Diagnosis adds the view radius when chunk loading or world generation leads the slow ticks, the hot radius when block or fluid ticking leads, and a line when a mod lowered the hot radius. These are evidence lines, never triggers or Discord alerts of their own.
- Trends chart the view radius, hot radius and loaded sections.
- Log scale for tick time, GC pause, allocation rate, network, heap, loaded chunks and sections and queued packets when the highest value is at least 100 times the median, so a 2000 ms freeze next to 5 ms ticks shows to scale with the budget line. Grid lines sit at 1, 2 and 5 per decade, values of 0 sit on the bottom line and the axis says "log scale". `report.html` and `trend-report.html` have a Linear and Log switch on these charts, starting on the automatic choice. In game the choice is automatic. TPS, CPU, shares, players and entities always stay linear.

### Changed

- New black and yellow look for the HTML reports and every in-game page, with the Hybox logo. Yellow marks what you can click or have chosen. Warnings are orange with a word, errors and Critical are red. All text is easy to read on its background.
- The report opens on four numbers: tick time, TPS, CPU and heap. A number turns orange or red only past its target, such as the 33.3 ms tick budget. Diagnosis comes right after.
- Diagnosis is a short ranked answer, the same in `report.html` and in game: one cause with how sure Hybox is, up to two lines on why and up to three next steps with real numbers, such as how far to raise `-Xmx` for the free RAM. Every other finding is folded under Other findings, and manual captures list what is notable instead. Findings only show over a threshold, and the threshold sits behind a small "i" next to each line. Terms such as GC pause explain themselves on hover with a link to [Terms](https://wiki.hytalemodding.dev/mod/hybox/reading-a-report#terms). Findings cover GC pauses during a freeze, TPS dips, swap, load average, slow file access, a full disk, a rising post-GC floor, one slow engine system tick and a mod using 10% or more of CPU or memory in its own code.
- Headlines are plain, for example "World default stopped responding for 4 min 20 s", in the report, the dashboard, `/hybox list` and Discord. Triggers read "Freeze", "Manual" and so on.
- Fewer report sections: Worlds, Hot threads, Hot methods, Thread states and Network moved into the sections they belong to. JVM details sit behind "Advanced", empty sections are hidden, unchanged charts are folded, and each fact shows once.
- Charts show one tooltip beside the pointer and work on touch screens. One event legend lists the event types the recording has and turns them off on every chart. The tick chart shows the tick budget and the worst tick of each interval, and the TPS chart the target.
- One name and unit per metric everywhere: GiB and MiB, Mbit/s, "Tick time", "TPS", "Machine" for the computer and "Server" for Hytale.
- The Baseline table shows only what got worse first.
- The trend report has a plain header, the same chart tooltips, and incident markers you can hover or tap for the headline and bundle.
- The dashboard Overview shows TPS, players, heap, free disk and active triggers, opens the newest incident in one click, and shows the Capture and Callbacks tiles only when they matter. The Storage tile checks the size limit and free disk space.
- The in-game incident page leads with the report's headline, evidence and next steps. The incident list shows how long ago each was saved. The Triggers tab shows On and Off and when each last fired.
- The capture message gives the time, size and folder, and says when old bundles were deleted. Retention logs each deleted bundle and why.
- Chat and console replies are plain sentences. Each "Capture skipped" reply says why.
- Hybox uses less CPU and memory: heartbeats every 250 ms instead of 50 ms, no allocation on world threads, and a dashboard that sends only changed values and stops when the player leaves.
- After the post-incident wait Hybox measures a heartbeat stall again. The bundle, headline and alerts use the final length, turn critical past `Trigger.StallCriticalMs`, and say when the world was still frozen at save time.
- Critical incidents always reach Discord, whatever `Discord.Cooldown` is. Discord messages mention nobody.
- The in-game incident page is a quick triage view. It opens on the stall and four numbers (tick time, TPS, CPU and heap, orange past their targets), and for a freeze on what the stalled thread was doing: its state, the frame with its mod, the lock and its owner. Each section fits about one screen and ends with "More in report.html".
- The incident page sidebar groups sections under Incident, Evidence and Reference. The server log, asset packs, config files and mod settings are only in report.html. The page counts the log's errors and warnings and the mod settings.
- The Incidents list hides manual captures and profile sessions until you press "Show manual captures", and keeps that choice when you come back from an incident. It shows repeats of one trigger in one world as one row with a count, lists up to the newest 50 bundles and says when there are more. It shows the same headline as the incident page, the short world name with the full name on hover, and "Server" for captures without a world.
- Next steps about lock waits, slow file operations and the server log say the details are in report.html, and their button opens the closest in-game section.
- A world's CPU share counts every thread Hytale runs for that world and is the same number in the evidence line, CPU & load, the world tables and in game. The busiest world line is left out when that world is the only one with players.
- In game, sections, pages and time ranges open at the top. Percentages have one decimal, durations read "3.5 s" and "17 min 49 s", thread states read Running, Waiting, Timed waiting and Blocked, and chart axes use round values such as 40 ms and 20 ms.
- Every chart, in game and in both HTML reports, uses one axis rule. It starts at 0 and its top follows the highest value. Only a value more than twice the limit is cut off, with a triangle and one "117.2 ms off scale" label. The limit is one and a half times the 98th percentile, or 15% above the budget or target line when that is higher. The TPS chart tops out 15% above its target. The tooltip shows the real value.
- Each world is compared with its own TPS target. The dashboard shows the world furthest below its target, and reports use the target of the world they show. Worlds lists each target when worlds run at different tick rates.
- Trends use fixed time slices with the average as a line and the lowest to highest value as a band, in game too, so one spike or TPS drop still shows. Hover shows min, avg and max for the slice. The time axis runs from the first to the last sample.
- A chart whose value stays the same is one line of text, such as "Players online: 1 the whole time.", on the in-game trend and incident pages and in `trend-report.html`. The "charts without change" fold in `report.html` uses the same words.

### Fixed

- CPU and memory by mod no longer blame a mod for engine work it only wraps. Each mod shows Self (its own code) and Total. In a real recording Refixes dropped from 25% of CPU to 2.7% self.
- The Entities section is filled again. Counting runs on each world's thread and never holds up a capture during a freeze.
- Trends drew lines across the time the server was off. A gap of more than three sample intervals now breaks the line and is shaded. When samples cover less than a fifth of the range, that time is cut out and a divider marks each gap, the same in game and in `trend-report.html`.
- The in-game trend page fitted its axis to a spike it never drew and spread a few samples over the whole week, so the data looked like a flat line.
- Trends say "Process RSS: Linux only" instead of "No data" where RSS cannot be read.
- The server TPS target came from whichever world Hytale listed first. It is now the default world's target.
- Works on Hytale 0.8.0-pre.6, where NPCs changed.
- A world that stopped reporting no longer counts twice in players, entities and chunks.
- Icons in the report no longer break, and version numbers are no longer hidden as IP addresses.
- No false slow file reads from the console or pipes, and no false "Hybox allocated 11%" line on servers with a busy temp folder.
- Allocation charts no longer count memory allocated before the recording started.
- Manual captures no longer show "stall Not captured" or the world "server".
- Smaller fixes: RSS is compared with heap in GiB, System & hardware says "Server name" instead of "Host", the in-game Heap tab shows shares of the objects found, and the in-game range badge names the chosen range.
- `--keep-buffer=false` no longer waits for the trigger cooldown.
- Discord delivery failures were silent. HTTP errors are now logged once, and a rate limit is retried once.
- In-game text no longer shows "?" for characters the game font lacks.
- Link buttons on the Overview and Diagnosis no longer cut their captions, setting keys wrap instead of shrinking, and table rows no longer squeeze names into a narrow column.
- Long text in game breaks between items or lines, not in the middle of a word.
- Worlds show units on every number, Yes or No instead of true or false, the entity list once, and say which chunk counts are for the whole window.
- Threads with " - " in their name, such as "Document Writer - 1", no longer show up as a world.
- A plugin counter's total for the window no longer includes what it counted before the window. That first value counts under "since start".
- System & hardware shows the short OS name, such as macOS, with the full `uname` text in the hover.

### Privacy

- `recording.jfr` in a bundle is a cleaned copy: paths, player names, IP addresses, private start arguments and your OS user name are masked, and environment and process details are left out. The report still uses the full recording.
- Paths in bundle text files are shortened, and the Start command hides tokens, passwords, the owner and the bind address.
- The server log tail hides chat lines and command arguments, and removes color codes.
- The report footer and the capture message say what is hidden and what to check before sharing.
- Every new bundle carries `"cleaned": 2` in the `meta` of `incident.json`.
- The in-game viewer, Incidents list, `/hybox list`, `/hybox incident`, the trend report and error messages no longer show paths and names stored in older bundles.
- World failure and server error headlines no longer keep absolute paths in alerts and Discord.
- A player named like a JSON key or literal can no longer break `incident.json` and the report.
- Player names are matched in any case and next to `_` or other characters that are not letters or digits, longest name first. Names that are common words such as Default or Info are matched only in their own case. UUIDs are matched in any case, with or without dashes.
- Paths are shortened for lower-case Windows homes, user names with spaces, network shares, `/Volumes` paths with spaces, and paths after `{`, backtick, `@` or `-I`. Quoted `--owner-name=` values are hidden.
- Redaction covers IPv6 without brackets, host names with a port in any case that end in localhost or a common top-level domain, any host with a port after `://`, `.local` names, versioned Discord webhook URLs, and values of keys that contain password, secret, token, apikey or credential, except counts of 1 to 3 digits. Four-part version numbers, clock times and code locations such as `Thread.run:1583`, `Foo.java:123` or `timeout.ms:5000` stay.