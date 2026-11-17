# Using Hybox

Hybox starts recording when the server starts and saves a bundle when a [trigger](triggers.md) fires. No setup is needed.

## Commands

Nodes are relative to `xytronix.hybox.command.hybox`. See [Permissions](installing.md#permissions).

| Command | Nodes | What it does |
| --- | --- | --- |
| `/hybox`, `/hybox ui` | `.ui` | Opens the dashboard. Bare `/hybox` in the console prints the usage line. |
| `/hybox dump` | `.dump` | Saves a bundle now. |
| `/hybox incident [id]` | `.incident` | Opens the latest incident, or the one given. The id is the full id, or the part after its last dash, for example `000001`. |
| `/hybox profile [--minutes=5] [--keep-buffer=true]` | `.profile` | Starts a [profile](#profiling). |
| `/hybox profile stop`, `/hybox profile cancel` | `.profile` plus `.profile.stop` or `.profile.cancel` | Ends the profile and saves it, or ends it without saving. |
| `/hybox status [--verbose]` | `.status` | Recorder health, last capture outcome, storage and plugin callback problems. `--verbose` adds paths and settings. |
| `/hybox list [--count=5] [--details]` | `.list` | Players: the Incidents tab, which also needs `.ui`. Console: 1 to 50 recent incidents, with paths if `--details`. |
| `/hybox triggers [--details]` | `.triggers` | Players: the Triggers tab, which also needs `.ui`. Console: armed and disabled detectors, with thresholds if `--details`. |
| `/hybox histogram` | `.histogram` | Players: the Heap tab, which also needs `.ui`. Console: the 10 largest heap classes. Walking the heap pauses the server, often for seconds. |
| `/hybox trend [--days=7]` | `.trend` | 1 to 30 days of health metrics. Players: a chart. Console: `trend-report.html`. Needs `Metrics.Enabled`. |
| `/hybox settings` | `.settings` | Opens the settings page. Players only. |
| `/hybox reload` | `.reload` | Reloads `hybox.json`. |

## Dashboard

The Overview tab shows recorder health and the running capture or profile, with buttons for capture, profile, trends and settings. The Incidents, Triggers and Heap tabs match `/hybox list`, `triggers` and `histogram`. The player who starts a capture sees its progress on screen and a message when it ends.

## Incidents

Each capture writes `incidents/incident-<id>.zip`. The id is the capture time and a counter that restarts with the server, for example `20261117-142530.123+0000-000001`. Open it with `/hybox incident`, or unzip it and open `report.html` in a browser.

After a crash, the next start rebuilds a bundle from the crashed run's JFR repository or `live/rolling.jfr`, with the trigger `UNCLEAN_SHUTDOWN`.

## Profiling

A profile switches the recording to JFR's `profile` preset for 1 to 30 minutes, then saves one bundle and returns to the normal preset. With `--keep-buffer=true` (default) the bundle covers the time before and during the profile. With `false`, Hybox first saves the current recording as a bundle, then starts a fresh recording, so the final bundle covers only the profile.

## Trends

With `Metrics.Enabled` on, Hybox writes one row of health metrics per `Jfr.SampleInterval`, and `/hybox trend` charts them.

## Sharing a bundle

1. Read [Privacy](privacy.md).
2. Unzip the bundle and check the text files, especially `extras/server-log.txt`, config sections in `report.html`, and files from other plugins.
3. Decide whether to keep `recording.jfr`. See [The recording](privacy.md#the-recording).
