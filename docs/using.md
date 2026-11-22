# Using Hybox

Hybox needs no setup after [installing](installing.md). It starts recording when the server starts and saves a bundle when a [trigger](triggers.md) fires.

## Dashboard

Run `/hybox` in game. The dashboard has four tabs:

| Tab | Shows |
| --- | --- |
| Overview | Recorder health, the capture or profile that is running, and buttons for a manual capture, a profile, trends and settings. |
| Incidents | Recent bundles. Select one to open its report. |
| Triggers | Every detector, whether it is armed, and its threshold. |
| Heap | The largest classes on the heap. Walking the heap pauses the whole server, often for several seconds on large heaps. |

Each button needs the permission of the matching command. See [Permissions](installing.md#permissions).

From the console, `/hybox status`, `/hybox list`, `/hybox triggers` and `/hybox histogram` print the same information as text.

## Incidents

### How a capture runs

1. A trigger fires, or someone runs `/hybox dump`.
2. Hybox checks the [cooldown and debounce](triggers.md#cooldown-and-debounce). A rejected trigger saves nothing.
3. For a trigger of degraded severity, Hybox keeps recording until the problem clears or `Jfr.PostIncidentMaxWait` (default 2 minutes) passes. It takes up to `Jfr.ThreadDumpCount` thread dumps, `Jfr.ThreadDumpInterval` apart. A player with the `.dump` permission can skip the wait from the dashboard.
4. Hybox dumps the recording and writes `incidents/incident-<id>.zip`.

Hybox runs one capture at a time. A trigger that fires while a capture is running is dropped. `/hybox status --verbose` counts these under `Admission`. The player who started a capture sees its progress on screen and a message when it ends.

The incident id is the capture time followed by a counter, for example `20261117-142530.123+0000-000001`.

### Reading an incident

- In game: `/hybox incident` opens the latest incident. `/hybox incident <id>` opens another one.
- Outside the game: unzip the bundle and open `report.html` in a browser. `recording.jfr` opens in any tool that reads JFR files.

[Privacy](privacy.md#what-a-bundle-contains) lists every file in a bundle.

### After a crash

If the server stopped without a clean shutdown, Hybox rebuilds a bundle from the previous run's JFR data on the next start. It uses the JVM's JFR repository of the crashed process, or `live/rolling.jfr`, which Hybox refreshes every `Jfr.SnapshotInterval`. These bundles have the trigger `UNCLEAN_SHUTDOWN` and the headline `Recovered after an unclean shutdown`.

### Retention

After each new bundle, Hybox deletes the oldest bundles until all `Retention` limits hold. The newest bundle is always kept. See [Retention](configuration.md#retention).

## Profiling

A profile switches the recording to JFR's `profile` preset for 1 to 30 minutes, then saves one bundle.

```text
/hybox profile --minutes=10
/hybox profile --minutes=10 --keep-buffer=false
```

| `keep-buffer` | Effect |
| --- | --- |
| `true` (default) | The live recording keeps its history. The bundle covers the time before and during the profile. |
| `false` | Hybox first saves the current recording as a bundle, then starts a fresh recording. The final bundle covers only the profile. |

`/hybox profile stop` ends the profile early and saves it. `/hybox profile cancel` ends it without saving. In both cases the recording returns to its normal preset. The final profile capture ignores cooldown and debounce. The dashboard offers the same controls.

## Trends

Turn on `Metrics.Enabled` to record health metrics to `metrics/health-<yyyyMMdd>.csv` once per `Jfr.SampleInterval`. `/hybox trend` charts them in game. From the console it writes `trend-report.html`. Bundles include the two newest metrics files.

For live monitoring, turn on `Metrics.Prometheus.Enabled`. See [Prometheus](configuration.md#metrics).

## Sharing a bundle

A bundle can hold more than you expect. Before you send one:

1. Read [Privacy](privacy.md).
2. Unzip the bundle and check the text files, especially `extras/server-log.txt`, config sections in `report.html`, and any files that other plugins added.
3. Hybox does not redact or mask `recording.jfr`. Remove it from the zip if you do not trust the recipient with it. `report.html` keeps its charts, but the recipient can no longer inspect the raw recording.

To change what future bundles contain, use the `Capture` settings in [Configuration](configuration.md#capture).
