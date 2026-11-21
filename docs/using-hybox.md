# Using Hybox

Hybox starts recording when the server starts and saves a bundle when a [trigger](/mod/hybox/triggers) fires. No setup is needed.

## Commands

Nodes are relative to `xytronix.hybox.command.hybox`. See [Permissions](/mod/hybox/installing-hybox#permissions).

| Command | Nodes | What it does |
| --- | --- | --- |
| `/hybox`, `/hybox ui` | `.ui` | Opens the dashboard. Bare `/hybox` in the console lists the commands, one per line. |
| `/hybox dump` | `.dump` | Saves a bundle now. |
| `/hybox incident [id]` | `.incident` | Opens the latest incident, or the one given. The id is the full id, or the part after its last dash, for example `000001`. |
| `/hybox profile [lag\|memory] [--minutes=5] [--keep-buffer=true]` | `.profile` | Starts a [profile](#profiling). |
| `/hybox profile stop`, `/hybox profile cancel` | `.profile` plus `.profile.stop` or `.profile.cancel` | Saves the profile now, or drops the running or queued profile without saving. |
| `/hybox status [--verbose]` | `.status` | Recorder health, then a line with TPS, tick time, server CPU and heap, for example `TPS 30.0, tick 3.9 ms, CPU 9%, heap 0.9 of 4.0 GiB`. Then the last capture outcome, bundle storage against the retention limits and free disk space, and plugin callback problems. `--verbose` adds paths and settings. |
| `/hybox list [--count=5] [--details]` | `.list` | Players: the Incidents tab, which also needs `.ui`. Console: 1 to 50 recent incidents, with paths if `--details`. |
| `/hybox triggers [--details]` | `.triggers` | Players: the Triggers tab, which also needs `.ui`. Console: which triggers are on and off, with thresholds if `--details`. |
| `/hybox histogram` | `.histogram` | Players: the Heap tab, which also needs `.ui`. Console: the 10 largest heap classes. Walking the heap pauses the server, often for seconds. |
| `/hybox trend [--days=7]` | `.trend` | 1 to 30 days of health metrics. Players: a chart. Console: `trend-report.html`. Needs `Metrics.Enabled`. |
| `/hybox settings` | `.settings` | Opens the settings page. Players only. |
| `/hybox reload` | `.reload` | Reloads `hybox.json`. |

## Dashboard

The Incidents, Triggers and Heap tabs match `/hybox list`, `triggers` and `histogram`. The Incidents tab hides manual captures and profile sessions until you press "Show manual captures". It shows repeats of the same trigger in the same world as one row with a count, for example "Freeze, 4 times", that opens the newest one. A bundle that could not be cleaned has a "Not cleaned" mark. The player who starts a capture gets a message with the bundle size and folder when it ends.

The Storage tile compares the size of the bundle folder with `Retention.MaxTotalBytes` and checks free disk space. It shows "Near limit" at 90% of the limit and "Low disk space" when less than 2 GiB or 5% of the disk is free. A warning lists bundles older than `Retention.MaxAge`, which are deleted after the next capture.

## Incidents

Each capture writes `incidents/incident-<id>.zip`. The id is the capture time and a counter that restarts with the server, for example `20261117-142530.123+0000-000001`. Open it with `/hybox incident`, or unzip it and open `report.html` in a browser. The in-game page is a short version. The server log, asset packs, config files and mod settings are only in `report.html`.

After a crash, the next start rebuilds a bundle from the crashed run's JFR repository or `live/rolling.jfr`, with the trigger `UNCLEAN_SHUTDOWN`.

### Staff alerts

When a trigger is accepted, Hybox alerts online players with the `.incident` node right away, before the post-incident wait, with a toast and a chat line, for example "Degraded incident: World default stopped responding for 2.1 s. Hybox is saving the bundle now." When the bundle is saved, they get a second line with the final headline, for example "Critical incident saved: World default stopped responding for 4 min 20 s. Open it with /hybox incident." After an alert, Hybox waits 5 minutes before the next, unless a later incident is more severe, and the next alert says how many incidents came in between. Manual captures and profiles are not announced. Discord gets the same two messages. See [Privacy](/mod/hybox/privacy#discord).

If the capture fails or is canceled after the first line, the same players get "<Severity> incident: <headline>. Hybox could not save the bundle. See the server log." instead of the saved line.

## Profiling

A profile records more detail for 1 to 30 minutes, then saves one bundle and returns to the normal recording. There are two modes, and `memory` includes everything in `lag`.

| Mode | Records | Report opens on |
| --- | --- | --- |
| `lag`, the default | Everything in JFR's `profile` preset, CPU samples every 10 ms, and lock waits from 10 ms | Diagnosis |
| `memory` | Everything in `lag`, up to 1000 allocation samples per second instead of 300, and long-lived objects with the chain of references that keeps each one alive | Memory & GC |

Neither mode has a measurable cost on the server. The recording grows about three times as fast.

When a memory profile ends, JFR walks the heap to find those chains while the server is paused, for at most 1 second. Any bundle saved while a memory profile runs, including an incident and the crash backup, pauses the server the same way.

With `--keep-buffer=true`, the default, the bundle covers the time before and during the profile. With `false`, Hybox first saves the current recording as a bundle, then starts a fresh recording, so the final bundle covers only the profile.

### Incidents during a profile

- A trigger that fires during a profile saves its incident bundle at once from the profile recording, so it has the extra detail. The report marks it "During profile (lag)". Staff alerts and the cooldown work as usual. The profile keeps running, and its own bundle shows the incident on the charts.
- A profile started while an incident bundle is being written waits and starts once the bundle is saved. The command replies "Profile queued, starts once the incident is saved." and the dashboard shows the same.

### Canceling

`/hybox profile cancel` and the dashboard Cancel button drop a running profile, a queued start, or a profile bundle that waits behind an incident. The reply is "Profile canceled. Nothing was saved." or "Queued profile canceled. It never started." Canceling never drops or delays an incident bundle.

## Trends

With `Metrics.Enabled` on, Hybox writes one row of health metrics per `Jfr.SampleInterval`, and `/hybox trend` charts them. Each row also has the TPS target, the server `MaxViewRadius`, the median view and hot radius of the players and the sections they have loaded.

The charts split the range into fixed time slices. The time axis runs from the first to the last sample in the range. The line is the average of a slice and the band runs from its lowest to its highest value, so one spike still shows. A pause of more than three sample intervals breaks the line and is shaded as server off. When the samples cover less than a fifth of the range, the time with the server off is cut out and a dashed divider marks each gap. The game page and `trend-report.html` use the same slices and the same [axis rule](/mod/hybox/reading-a-report#charts), including the log scale for tick time, memory, allocation rate, queued packets and loaded sections when the highest value is at least 100 times the median. A metric that stays the same in the range is one line of text, such as "Players online: 1 the whole time." Process RSS is read on Linux only.

In `trend-report.html` each incident is a red dashed line that links to its bundle, and charts that can use a log scale have a Linear and Log switch.

## Placeholders

With [PlaceholderAPI](https://github.com/PlaceholderAPI/PlaceholderAPI/tree/hytale) installed, Hybox adds the `hybox` placeholders for scoreboards, chat and other plugins. PlaceholderAPI is optional. Hybox picks it up when it loads later.

| Placeholder | Shows |
| --- | --- |
| `%hybox_tps%` | TPS of the slowest world, as on the dashboard, for example `29.9` |
| `%hybox_tps_<world>%` | TPS of one world, for example `%hybox_tps_default%` |
| `%hybox_mspt%` | Tick time in ms of the slowest world, for example `12.3` |
| `%hybox_mspt_<world>%` | Tick time in ms of one world |
| `%hybox_status%` | `OK`, `Degraded` below 90% of the target TPS, `Critical` below 50% |
| `%hybox_heap%` | Java heap in use as a share of the maximum, for example `75%` |
| `%hybox_last_incident%` | Age of the newest saved incident, for example `12 min ago`, or `none` |
| `%hybox_incidents_today%` | Incidents saved since midnight in the server's time zone |

Values come from the last sample, taken every `Jfr.SampleInterval`. Before the first sample they show `Waiting`. Unknown placeholders and worlds stay unreplaced. To test one in game, run `/papi parse me %hybox_status%`.

## Sharing a bundle

1. Read [Privacy](/mod/hybox/privacy).
2. Unzip the bundle and check the text files, especially `extras/server-log.txt`, config sections in `report.html`, and files from other plugins.
3. `recording.jfr` is already cleaned of paths, player names, IP addresses and environment variables. See [The recording](/mod/hybox/privacy#the-recording) for what it still holds.
4. Send the whole incident zip, not a screenshot, and paste Copy summary from the report header.
