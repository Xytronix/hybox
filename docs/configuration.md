# Configuration

Settings live in `mods/Xytronix_Hybox/hybox.json`, written with defaults on first start. Edit it and run `/hybox reload`, or use `/hybox settings` in game, which saves the fields you changed and reloads. The reload reply lists what changed and what needs a restart. A reload is refused while a capture or profile runs.

Keys marked "restart" take effect at the next server start. All others apply on reload.

The settings page covers the keys in [Allowed ranges](#allowed-ranges), the detector switches and `Capture.Enabled`. Everything else is set in the file. A value above `0` outside its range is clamped to the nearest allowed value at start and on reload, with one warning per value giving the old and new value. `hybox.json` is not changed. `0` keeps its meaning from the tables below, so the page cannot set `0` for the `Retention` limits, `Jfr.MaxAge`, `Jfr.MaxSizeBytes` or the percent keys.

Durations are ISO-8601 strings such as `"PT10S"`, `"PT15M"` or `"PT168H"`. Sizes are bytes. Leave `Version` at `1`.

An invalid value logs a [warning](/mod/hybox/troubleshooting#configuration) and uses the default. A file that cannot be parsed stops Hybox at startup, or keeps the previous settings on reload.

## Allowed ranges

| Settings page name | Key | Range |
| --- | --- | --- |
| Heartbeat stall: Degraded, Critical | `Trigger.StallDegradedMs`, `Trigger.StallCriticalMs` | 500 ms to 10 minutes |
| Slow ticks: Degraded, Critical | `Trigger.TickAvgDegradedMs`, `Trigger.TickAvgCriticalMs` | 0 for Auto, or 10 ms to 10 seconds |
| Heap pressure: Heap used | `Trigger.HeapPressurePct` | 50% to 100% |
| Heap pressure: Sustain | `Trigger.HeapPressureSustain` | 0 seconds to 1 hour |
| GC pressure: GC time | `Trigger.GcPressurePct` | 1% to 90% |
| GC pressure: Window | `Trigger.GcPressureWindow` | 10 seconds to 1 hour |
| CPU saturation: CPU used | `Trigger.CpuSaturationPct` | 10% to 100% |
| CPU saturation: Sustain | `Trigger.CpuSaturationSustain` | 0 seconds to 1 hour |
| Network saturation: In, Out | `Trigger.NetInMbps`, `Trigger.NetOutMbps` | 0 to 100000 Mbit/s |
| Network saturation: Sustain | `Trigger.NetSustain` | 0 seconds to 1 hour |
| Player drop: Player loss | `Trigger.PlayerDropPct` | 10% to 100% |
| Player drop: Window | `Trigger.PlayerDropWindow` | 10 seconds to 1 hour |
| Player drop: Min players | `Trigger.PlayerDropMinPlayers` | 0 to 1000 |
| Log errors: Repeat window | `Trigger.LogErrorDedupeWindow` | 0 seconds to 1 day |
| Cooldown | `Trigger.Cooldown` | 0 seconds to 1 hour |
| Debounce | `Trigger.Debounce` | 0 seconds to 1 hour |
| Capture and storage: Max bundles | `Retention.MaxCount` | 1 to 1000 |
| Capture and storage: Total | `Retention.MaxTotalBytes` | 64 MiB to 1 TiB |
| Capture and storage: Age | `Retention.MaxAge` | 1 to 365 days |
| Metrics history: Keep | `Metrics.RetentionDays` | 1 to 365 days |
| Incident evidence: Max wait | `Jfr.PostIncidentMaxWait` | 0 seconds to 10 minutes |
| Incident evidence: Dump count | `Jfr.ThreadDumpCount` | 0 to 20 |
| Incident evidence: Dump every | `Jfr.ThreadDumpInterval` | 1 second to 2 minutes |
| Recording: History | `Jfr.MaxAge` | 5 minutes to 1 day |
| Recording: Size | `Jfr.MaxSizeBytes` | 16 MiB to 8 GiB |
| Recording: Sample every | `Jfr.SampleInterval` | 5 seconds to 5 minutes |
| Recording: Snapshot | `Jfr.SnapshotInterval` | 0 minutes to 1 day |

Recording settings apply after a restart. Once you save one, the settings page shows "Needs a restart" with the setting names until the server restarts.

## Jfr

| Key | Type | Default | Applies | Meaning |
| --- | --- | --- | --- | --- |
| `MaxAge` | duration ≥ 0 | 15 minutes | restart | History the recording keeps. `0` means no limit. |
| `MaxSizeBytes` | integer ≥ 0 | `268435456` (256 MiB) | restart | Size limit on disk. `0` means no limit. |
| `RecordingName` | string | `"hybox"` | restart | Name of the JFR recording. |
| `DisabledEvents` | list of strings | `[]` | restart | JFR events to turn off, for example `"jdk.JVMInformation"`. Names are not checked, so a typo has no effect and no warning. |
| `Configuration` | string | `"default"` | restart | JFR preset. If it cannot be loaded, Hybox tries `profile`, then no preset. `default` turns off events the report never reads: thread sleep, monitor wait, native method samples and the events every bundle leaves out for privacy, listed in [The recording](/mod/hybox/privacy#the-recording). It keeps thread parks and monitor enters only from 500 ms, for Lock waits in the report. `profile` keeps them all, with lock waits from 10 ms. |
| `SampleInterval` | duration | 10 seconds | restart | How often Hybox samples world ticks, players, plugin gauges and health metrics. |
| `SnapshotInterval` | duration ≥ 0 | 10 minutes | restart | How often Hybox writes `live/rolling.jfr` for crash recovery. `0` turns it off. |
| `OldObjectSampling` | boolean | `true` | restart | Records where each long-lived object was allocated, to help find memory leaks. |
| `PostIncidentMaxWait` | duration ≥ 0 | 2 minutes | reload | Longest [post-incident wait](/mod/hybox/triggers#severity). `0` writes the bundle at once. |
| `ThreadDumpCount` | integer | `3` | reload | Thread dumps per incident. `0` takes none. |
| `ThreadDumpInterval` | duration ≥ 0 | 10 seconds | reload | Time between thread dumps during the wait. |

## Trigger

[Triggers](/mod/hybox/triggers) explains each detector. All keys apply on reload. Durations must be ≥ 0.

| Key | Type | Default | Meaning |
| --- | --- | --- | --- |
| `Modules.<Name>` | boolean | `true`, except `LogError` `false` | Turns a detector on or off. Names: `HeartbeatStall`, `TickDegraded`, `Deadlock`, `HeapPressure`, `GcPressure`, `CpuSaturation`, `NetSaturation`, `PlayerDrop`, `LogError`. |
| `Cooldown` | duration | 30 seconds | After an accepted trigger, other triggers are rejected for this long. |
| `Debounce` | duration | 2 seconds | The same trigger kind in the same scope is rejected for this long. |
| `CooldownExemptKinds` | list of strings | `["DEADLOCK", "HEARTBEAT_STALL", "WORLD_FAILURE"]` | Kinds that ignore the cooldown. |
| `StallDegradedMs`, `StallCriticalMs` | integer > 0 | `2000`, `10000` | Heartbeat stall that fires, and that is rated critical. |
| `TickAvgDegradedMs`, `TickAvgCriticalMs` | integer ≥ 0 | `0`, `0` | Average tick time that fires, and that is rated critical. `0` is Auto. Auto follows the [tick budget](/mod/hybox/reading-a-report#tick-budget) of each world: degraded at 3 times the budget and critical at 7.5 times. At 30 TPS that is 100 ms and 250 ms, at 60 TPS 50 ms and 125 ms. Any other value is a fixed limit in ms for every world. `100` and `250` also count as Auto, because older versions wrote them as defaults. For a fixed limit use any other value, such as `110`. Critical is never below degraded. |
| `HeapPressurePct`, `HeapPressureSustain` | percent, duration | `90`, 60 seconds | Percent `0` disables. |
| `GcPressurePct`, `GcPressureWindow` | percent, duration | `5`, 60 seconds | Either `0` disables. |
| `CpuSaturationPct`, `CpuSaturationSustain` | percent, duration | `95`, 60 seconds | Percent `0` disables. |
| `NetInMbps`, `NetOutMbps`, `NetSustain` | integer ≥ 0, duration | `0`, `0`, 30 seconds | Mbit/s per direction. `0` disables that direction. |
| `PlayerDropPct`, `PlayerDropWindow`, `PlayerDropMinPlayers` | percent, duration, integer ≥ 0 | `50`, 60 seconds, `8` | Percent or window `0` disables. |
| `LogErrorRequireThrowable`, `LogErrorSkipSentry` | boolean | `true` | See [Log error filters](/mod/hybox/triggers#log-error-filters). |
| `LogErrorDedupeWindow` | duration | 15 minutes | `0` turns deduplication off. |
| `LogErrorIgnore` | list of regular expressions | `[]` | |

## Retention

All keys apply on reload. Retention never deletes the newest bundle. For byte and count limits, `0` means no limit. Hitting a staging or repository limit makes Hybox refuse new captures.

Old bundles are deleted only right after a new bundle is saved, never at startup. The capture message tells the player, for example "Removed 15 bundles older than 7 days".

| Key | Type | Default | Meaning |
| --- | --- | --- | --- |
| `MaxCount` | integer ≥ 0 | `25` | Most bundles kept. |
| `MaxTotalBytes` | integer ≥ 0 | `1073741824` (1 GiB) | Most total bundle size. |
| `MaxAge` | duration ≥ 0 | 7 days | Older bundles are deleted. `0` deletes all but the newest. |
| `FailedRecordingMaxCount` | integer ≥ 0 | `5` | Most recordings kept in `temp/failed/`. |
| `StagingMaxTotalBytes`, `StagingMaxAge` | integer ≥ 0, duration ≥ 0 | `536870912` (512 MiB), 7 days | Limit for recordings in `temp/` and `live/recover/`. Older failed recordings are deleted. |
| `RepositoryMaxTotalBytes`, `RepositoryMaxAge` | integer ≥ 0, duration ≥ 0 | `1073741824` (1 GiB), 7 days | Limit for JFR repositories of crashed runs that Hybox could not recover. |

## Capture

[Privacy](/mod/hybox/privacy) shows what each option puts in a bundle. All keys apply on reload, but turning on `AllowModConfigOptIn` or `AllowPluginExtras` reaches plugins only after a restart.

| Key | Type | Default | Meaning |
| --- | --- | --- | --- |
| `Enabled` | boolean | `true` | `false` stops all bundles, including manual, profile and crash recovery bundles. |
| `Artifacts` | list of strings | all ten | Parts to write: `jfr`, `report`, `env`, `threads`, `serverLog`, `plugins`, `worlds`, `heartbeats`, `server`, `metrics`. |
| `LogTailLines` | integer ≥ 0 | `500` | Server log lines, up to 256 KiB. |
| `IncludeServerLog` | boolean | `true` | Include the server log tail. Player names, IP addresses and paths are masked. |
| `IncludeServerConfig`, `IncludeModConfigs` | boolean | `false` | Include the server's `config.json`, or mod config files. |
| `AllowModConfigOptIn`, `AllowPluginExtras` | boolean | `true` | Let plugins add config files, or bundle files. |
| `HeapHistogram` | boolean | `false` | Add a heap histogram to every report. Walking the heap pauses the server. |
| `RedactPatterns` | list of regular expressions | seven built-in patterns | Matching text becomes `[REDACTED]`. A list replaces the [built-in patterns](/mod/hybox/privacy#redaction). |

## Discord

All keys apply on reload. [Privacy](/mod/hybox/privacy#discord) shows what is sent.

| Key | Type | Default | Meaning |
| --- | --- | --- | --- |
| `WebhookUrl` | string | `""` | Webhook URL. Empty turns Discord off. |
| `Cooldown` | duration ≥ 0 | 1 minute | At most one message per cooldown. Others are dropped, except critical incidents and the saved message of an incident that was already posted. |
| `RequestTimeout` | duration ≥ 0 | 10 seconds | HTTP timeout. |
| `Username` | string | `"Hybox"` | Name shown on the message. |
| `ServerName` | string | `""` | Name at the start of each message, so several servers can share a channel. Empty uses the server name from Hytale's `config.json`. |

## Metrics

All keys apply on reload. Changing a `Prometheus` key restarts the endpoint.

| Key | Type | Default | Meaning |
| --- | --- | --- | --- |
| `Enabled` | boolean | `true` | Write `metrics/health-<yyyyMMdd>.csv`, about 0.7 MiB per day. Needed for `/hybox trend` and for the usual values in incident reports. |
| `RetentionDays` | integer | `7` | Days of metrics files kept. `0` or less uses `7`. |
| `Cpu` | boolean | `true` | Record process CPU use. |
| `Allocation` | boolean | `false` | Record the heap allocation rate. |
| `Prometheus.Enabled` | boolean | `false` | Serve `GET http://<Bind>:<Port>/metrics`. No authentication. It also serves the metrics that mods write as [JFR events](/mod/hybox/plugin-api#jfr-events). Refixes writes them by default. Setting `Runtime.Diagnostics.JfrEvents` to `false` in its `config.json` turns them off. |
| `Prometheus.Port` | integer | `9099` | Values outside 1 to 65535 use `9099`. |
| `Prometheus.Bind` | string | `"127.0.0.1"` | Blank uses `127.0.0.1`. |

Prometheus metric names start with `hybox_`. Plugin metrics are `hybox_plugin_gauge` and `hybox_plugin_counter` with a `name` label. Metrics that mods write as JFR events are `hybox_mod_gauge` and `hybox_mod_counter` with the labels `mod` and `name`. `mod` is the plugin id when a loaded plugin has the mod's name, for example `IroriPowered:Refixes`. A mod counter is the sum of its events since the server started or Prometheus was turned on, so it starts again from 0 after either.

Reading mod events for Prometheus has no measurable cost.
