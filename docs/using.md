# Using Hybox

Hybox starts recording when the server starts and saves a bundle when a [trigger](triggers.md) fires. No setup is needed.

## Commands

Nodes are relative to `xytronix.hybox.command.hybox`. See [Permissions](installing.md#permissions).

| Command | Nodes | What it does |
| --- | --- | --- |
| `/hybox`, `/hybox ui` | `.ui` | Opens the dashboard. Bare `/hybox` in the console lists the commands, one per line. |
| `/hybox dump` | `.dump` | Saves a bundle now. |
| `/hybox incident [id]` | `.incident` | Opens the latest incident, or the one given. The id is the full id, or the part after its last dash, for example `000001`. |
| `/hybox profile [--minutes=5] [--keep-buffer=true]` | `.profile` | Starts a [profile](#profiling). |
| `/hybox profile stop`, `/hybox profile cancel` | `.profile` plus `.profile.stop` or `.profile.cancel` | Ends the profile and saves it, or ends it without saving. |
| `/hybox status [--verbose]` | `.status` | Recorder health, then a line with TPS, tick time, server CPU and heap, for example `TPS 30.0, tick 3.9 ms, CPU 9%, heap 0.9 of 4.0 GiB`. Then the last capture outcome, bundle storage against the retention limits and free disk space, and plugin callback problems. `--verbose` adds paths and settings. |
| `/hybox list [--count=5] [--details]` | `.list` | Players: the Incidents tab, which also needs `.ui`. Console: 1 to 50 recent incidents, with paths if `--details`. |
| `/hybox triggers [--details]` | `.triggers` | Players: the Triggers tab, which also needs `.ui`. Console: which triggers are on and off, with thresholds if `--details`. |
| `/hybox histogram` | `.histogram` | Players: the Heap tab, which also needs `.ui`. Console: the 10 largest heap classes. Walking the heap pauses the server, often for seconds. |
| `/hybox trend [--days=7]` | `.trend` | 1 to 30 days of health metrics. Players: a chart. Console: `trend-report.html`. Needs `Metrics.Enabled`. |
| `/hybox settings` | `.settings` | Opens the settings page. Players only. |
| `/hybox reload` | `.reload` | Reloads `hybox.json`. |

## Dashboard

The Overview tab starts with the server now: TPS against the world's target, players online, Java heap in use and free disk space, and how many triggers Hybox is watching. Below that it shows recorder health and the running capture or profile, with buttons for capture, profile, trends and settings. Cancel on a running profile asks first, because it throws the profile away. "Open newest incident" opens the newest bundle in one click. The Incidents, Triggers and Heap tabs match `/hybox list`, `triggers` and `histogram`. The Incidents tab shows how long ago each incident was saved. The Triggers tab shows which triggers are on, when each one last saved an incident, and links to Settings. The player who starts a capture sees its progress on screen and a message when it ends: the time, the bundle size and folder, then the sharing note. Errors from opening an incident or trends show on the page.

The Storage tile compares the size of the bundle folder with `Retention.MaxTotalBytes` and checks free disk space. It shows "Near limit" at 90% of the limit and "Low disk space" when less than 2 GiB or 5% of the disk is free. A warning lists bundles older than `Retention.MaxAge`, which are deleted after the next capture.

## Incidents

Each capture writes `incidents/incident-<id>.zip`. The id is the capture time and a counter that restarts with the server, for example `20261117-142530.123+0000-000001`. Open it with `/hybox incident`, or unzip it and open `report.html` in a browser. In game the incident page leads with the same headline as the report, and its Overview shows the first findings and next steps from Diagnosis.

After a crash, the next start rebuilds a bundle from the crashed run's JFR repository or `live/rolling.jfr`, with the trigger `UNCLEAN_SHUTDOWN`.

### Staff alerts

When a trigger saves an incident, Hybox tells online players who may open incidents (`.incident`) with a toast and a chat line that start with the severity and the headline, for example "Critical incident saved: World default stopped responding for 4 min 20 s." A burst gives one alert: after an alert, Hybox waits 5 minutes before the next, unless a later incident is more severe. The next alert says how many were saved in between. Manual captures and profiles are not announced. The server log gets one line for every automatic incident with the severity, headline, bundle and size, and how many staff were alerted.

## Profiling

A profile switches the recording to JFR's `profile` preset for 1 to 30 minutes, then saves one bundle and returns to the normal preset. With `--keep-buffer=true` (default) the bundle covers the time before and during the profile. With `false`, Hybox first saves the current recording as a bundle, then starts a fresh recording, so the final bundle covers only the profile.

## Trends

With `Metrics.Enabled` on, Hybox writes one row of health metrics per `Jfr.SampleInterval`, and `/hybox trend` charts them.

In `trend-report.html` each incident is a red dashed line on every chart. Hover or tap it to see its time, severity, headline, trigger and bundle file. Click or tap keeps the details open, and the file name links to the bundle in `incidents/`. The incidents button shows or hides the lines.

## Sharing a bundle

1. Read [Privacy](privacy.md).
2. Unzip the bundle and check the text files, especially `extras/server-log.txt`, config sections in `report.html`, and files from other plugins.
3. `recording.jfr` is already cleaned of paths, player names, IP addresses and environment variables. See [The recording](privacy.md#the-recording) for what it still holds.
