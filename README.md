# Hybox

Hybox helps you investigate lag, freezes and crashes on Hytale servers. It keeps a rolling recording and saves incident bundles containing an HTML report, Java Flight Recorder (JFR) data and thread dumps.

## Installation

Build with Java 25:

```sh
./gradlew clean build --no-daemon
```

Stop your server, copy `hybox-hytale/build/libs/Hybox-1.0.0.jar` into its `mods/` folder, then start it again.

Players can run `/hybox` to open the in-game dashboard, or `/hybox dump` to save a bundle immediately. `/hybox status` shows recorder health; add `--verbose` for details and file locations.

## Commands

| Command | What it does |
| --- | --- |
| `/hybox` or `/hybox ui` | Open the player dashboard: health tiles, a native countdown and progress bar while profiling, the numbered capture steps while a bundle is being written, and incident and trigger tables. Click an incident to open it; **Capture incident** saves the rolling recording as a bundle and offers **Open incident** when it is done. When nothing is running, the Overview can start a profile: pick 1, 5, 15 or 30 minutes and whether to include the recording from before the start. While a profile runs, **Save now** ends it early and saves the bundle, and **Cancel** ends it without saving. While a capture waits for the server to recover, **Skip wait** writes the bundle straight away. The **Heap** tab walks the heap after a confirmation and lists the 20 largest classes with their share of the heap; the last result stays until the next walk or a restart. |
| `/hybox settings` | Open the player-only form for triggers, timing, capture and storage, metrics, incident evidence and recording. Save writes `hybox.json` and reloads; recording changes require restart. If capture or profiling is busy, the page reports that a later `/hybox reload` is needed. |
| `/hybox incident [id]` | Open the in-game incident viewer for the latest or given incident. It has the same sections and data as the HTML report: diagnosis, metrics charts with time and value axes, flame graph, ranked hot threads with per-thread state strips, memory, plugins, log and config. Charts, state strips and the fine detail of the flame graph appear as simple bars first, then switch to sharper images that the server draws in the background and sends only to you; if sending fails, the bars stay. From the console, print a short summary and the bundle path. |
| `/hybox dump` | Save an incident bundle. The player who started it sees a small on-screen progress panel while it runs and is notified when it finishes. |
| `/hybox status [--verbose]` | Show compact recorder health, or detailed status and file locations. |
| `/hybox list [--count=5] [--details]` | Players get the dashboard's **Incidents** tab. From the console, list the five most recent bundles by default; optionally show more detail. |
| `/hybox triggers [--details]` | Players get the dashboard's **Triggers** tab. From the console, show enabled detectors; optionally include thresholds and settings. |
| `/hybox profile [minutes] [keep-buffer] [stop\|cancel]` | Record more detail for five minutes by default (1–30). The player who started it sees an on-screen countdown until the capture, progress shows on the dashboard, and they are notified when the bundle is saved. `stop` ends the running profile now and saves what was recorded; `cancel` ends it without saving. |
| `/hybox trend` | Open the in-game Trends page for players; use **Save HTML report** to export it. From the console, write an HTML report. Requires metrics to be enabled. |
| `/hybox histogram` | Players get the dashboard's **Heap** tab, already asking to confirm the heap walk. From the console, list the ten largest heap classes. The walk pauses the server. |
| `/hybox reload` | Reload settings and report whether a restart is needed. |

The console root command shows compact usage. Dump and trend results show filenames to players and full paths in the console. If the dashboard cannot open, Hybox falls back to chat.

Opening bare `/hybox` requires the same UI permission as `/hybox ui`, and so do `list`, `triggers` and `histogram` when a player runs them, because they open the dashboard. Dashboard buttons follow their subcommand permissions: starting a profile needs `/hybox profile`, saving and cancelling need `profile stop` and `profile cancel`, and the Heap tab needs `/hybox histogram`.

When JFR data is unavailable, reports still show the warning and the remaining diagnostics. Reports rebuilt after an unclean shutdown carry a recovery note. Recording-wide aggregates are labelled when viewing a narrower time range, and trend charts keep observed lows and highs with min–max bucket envelopes.

## Configuration

Use `/hybox settings` for trigger switches, thresholds and windows, timing, capture and storage, metrics, incident evidence and recording controls. Saves update `hybox.json` and apply immediately; recording changes take effect after restart. Discord, Prometheus, bundle contents/privacy, log filters and advanced recording/storage options stay file-only: edit `hybox.json` in the plugin data folder, then run `/hybox reload` (or restart for recording changes).

| Section or setting | What you can change |
| --- | --- |
| `Jfr` | Recording history. Defaults to **15 minutes**, up to **256 MiB**. |
| `Trigger` | When Hybox should capture incidents automatically. |
| `Retention` | Bundle storage. Defaults to **25 bundles**, **1 GiB** total and **7 days**. |
| `Capture` | What goes into each bundle. Automatic log and configuration collection is **off by default**. |
| `Metrics.Enabled` | Turn on history for `/hybox trend`. **Off by default**. |
| `Metrics.Cpu` / `Metrics.Allocation` | Collect CPU / allocation samples for history and live metrics. Defaults: **on** / **off**. |
| `Metrics.Prometheus` | Export metrics at `127.0.0.1:9099/metrics`. **Off by default**. |
| `Discord.WebhookUrl` | Add a webhook to receive incident notifications. **Disabled until set**. |

In the config file, `PT15M` means 15 minutes and size limits are written in bytes. Recordings needed for recovery are kept beyond the retention limits.

Historical metrics attachments keep up to **1 MiB of complete rows per file** from the two most recent CSVs. Null entries in `Jfr.DisabledEvents` and `Capture.RedactPatterns` are ignored without discarding the other settings.

Review bundles before sharing: recordings can contain sensitive data. Prometheus has no authentication, so keep its endpoint private.

## Plugin API

Use Hybox as a compile-only dependency and declare `Xytronix:Hybox` as a plugin dependency.

```java
import io.github.xytronix.hybox.hytale.HyboxApi;

HyboxApi.recordEvent("my-plugin", "cache", "Cache rebuilt");
```

Keep callbacks quick and thread-safe. Close registration handles when your plugin stops.

[MIT license](LICENSE). Continued fork of [Blackbox](https://github.com/ZECHEESELORD/blackbox).
