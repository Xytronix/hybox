# Configuration

Settings live in `mods/Xytronix_Hybox/hybox.json`, written with defaults on first start. Edit it and run `/hybox reload`, or use `/hybox settings` in game, which saves the fields you changed and reloads. The reload reply lists what changed and what needs a restart. A reload is refused while a capture or profile runs.

Keys marked "restart" take effect at the next server start. All others apply on reload.

The settings page covers the numeric and on/off keys of `Trigger`, `Retention`, `Metrics` and `Jfr`, plus `Capture.Enabled`. Each number has an allowed range, listed in [Allowed ranges](#allowed-ranges) under the names the page uses. A value above `0` that is outside its range is clamped to the nearest allowed value at start and on reload. Hybox logs one warning per value with the old and new value, and leaves `hybox.json` as it is. `0` keeps its meaning from the tables below, so the page cannot set `0` for the `Retention` limits, `Jfr.MaxAge`, `Jfr.MaxSizeBytes` or the percent keys.

Durations are ISO-8601 strings such as `"PT10S"`, `"PT15M"` or `"PT168H"`. Sizes are bytes. Leave `Version` at `1`.

An invalid value logs a [warning](troubleshooting.md#configuration) and uses the default. A file that cannot be parsed stops Hybox at startup, or keeps the previous settings on reload.

## Upgrading

| Change | Action |
| --- | --- |
| `Capture.Artifacts` gained `metrics`. | None for the old default list of nine names: Hybox reads it as including `metrics`. A list you changed yourself is kept as written, so add `"metrics"` to keep metrics files in bundles. |
| `Capture.SanitizeLog` was removed. | A leftover key is ignored. The server log is always masked. |
| Settings now have [allowed ranges](#allowed-ranges). | None. A value outside its range is used as the nearest allowed value, with a warning in the server log. Change it in the file or on the settings page to silence the warning. |

## Allowed ranges

| Settings page name | Key | Range |
| --- | --- | --- |
| Heartbeat stall: Degraded, Critical | `Trigger.StallDegradedMs`, `Trigger.StallCriticalMs` | 500 ms to 10 minutes |
| Slow ticks: Degraded, Critical | `Trigger.TickAvgDegradedMs`, `Trigger.TickAvgCriticalMs` | 10 ms to 10 seconds |
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

The Recording settings apply after a restart. Once you save one on the settings page, the page shows "Needs a restart" with the setting names until the server restarts.

## Jfr

| Key | Type | Default | Applies | Meaning |
| --- | --- | --- | --- | --- |
| `MaxAge` | duration ≥ 0 | 15 minutes | restart | History the recording keeps. `0` means no limit. |
| `MaxSizeBytes` | integer ≥ 0 | `268435456` (256 MiB) | restart | Size limit on disk. `0` means no limit. |
| `RecordingName` | string | `"hybox"` | restart | Name of the JFR recording. |
| `DisabledEvents` | list of strings | `[]` | restart | JFR events to turn off, for example `"jdk.JVMInformation"`. Names are not checked, so a typo has no effect and no warning. |
| `Configuration` | string | `"default"` | restart | JFR preset. If it cannot be loaded, Hybox tries `profile`, then no preset. With `default`, Hybox turns off events the report never reads: thread park, sleep and monitor wait, native method samples, and the events every bundle drops for privacy (`jdk.InitialEnvironmentVariable`, `jdk.InitialSecurityProperty`, `jdk.JavaAgent`, `jdk.NativeAgent`, `jdk.ProcessStart`, `jdk.SocketRead`, `jdk.SocketWrite`, `jdk.SystemProcess`, `jdk.TLSHandshake`). Use `profile` to keep them. |
| `SampleInterval` | duration | 10 seconds | restart | How often Hybox samples world ticks, players, plugin gauges and health metrics. Clamped to 5 seconds to 5 minutes. |
| `SnapshotInterval` | duration ≥ 0 | 10 minutes | restart | How often Hybox writes `live/rolling.jfr` for crash recovery. `0` turns it off. |
| `OldObjectSampling` | boolean | `true` | restart | Records old-object samples with stack traces, to help find memory leaks. The stack traces show where each long-lived object was allocated. |
| `PostIncidentMaxWait` | duration ≥ 0 | 2 minutes | reload | Longest [post-incident wait](triggers.md#severity). `0` writes the bundle at once. |
| `ThreadDumpCount` | integer | `3` | reload | Thread dumps per incident. `0` takes none. |
| `ThreadDumpInterval` | duration ≥ 0 | 10 seconds | reload | Time between thread dumps during the wait. |

## Trigger

[Triggers](triggers.md) explains each detector. All keys apply on reload. Durations must be ≥ 0.

| Key | Type | Default | Meaning |
| --- | --- | --- | --- |
| `Modules.<Name>` | boolean | `true`, except `LogError` `false` | Turns a detector on or off. Names: `HeartbeatStall`, `TickDegraded`, `Deadlock`, `HeapPressure`, `GcPressure`, `CpuSaturation`, `NetSaturation`, `PlayerDrop`, `LogError`. |
| `Cooldown` | duration ≥ 0 | 30 seconds | After an accepted trigger, other triggers are rejected for this long. |
| `Debounce` | duration ≥ 0 | 2 seconds | The same trigger kind in the same scope is rejected for this long. |
| `CooldownExemptKinds` | list of strings | `["DEADLOCK", "HEARTBEAT_STALL", "WORLD_FAILURE"]` | Kinds that ignore the cooldown. |
| `StallDegradedMs`, `StallCriticalMs` | integer > 0 | `2000`, `10000` | Heartbeat stall that fires, and that is rated critical. |
| `TickAvgDegradedMs`, `TickAvgCriticalMs` | integer > 0 | `100`, `250` | Average tick time that fires, and that is rated critical. |
| `HeapPressurePct`, `HeapPressureSustain` | percent, duration | `90`, 60 seconds | Percent `0` disables. |
| `GcPressurePct`, `GcPressureWindow` | percent, duration | `5`, 60 seconds | Either `0` disables. |
| `CpuSaturationPct`, `CpuSaturationSustain` | percent, duration | `95`, 60 seconds | Percent `0` disables. |
| `NetInMbps`, `NetOutMbps`, `NetSustain` | integer ≥ 0, duration | `0`, `0`, 30 seconds | Mbit/s per direction. `0` disables that direction. |
| `PlayerDropPct`, `PlayerDropWindow`, `PlayerDropMinPlayers` | percent, duration, integer ≥ 0 | `50`, 60 seconds, `8` | Percent or window `0` disables. |
| `LogErrorRequireThrowable`, `LogErrorSkipSentry` | boolean | `true` | See [Log error filters](triggers.md#log-error-filters). |
| `LogErrorDedupeWindow` | duration ≥ 0 | 15 minutes | `0` turns deduplication off. |
| `LogErrorIgnore` | list of regular expressions | `[]` | |

## Retention

All keys apply on reload. Retention never deletes the newest bundle. For byte and count limits, `0` means no limit. Hitting a staging or repository limit makes Hybox refuse new captures.

Old bundles are deleted only right after a new bundle is saved, never at startup. Each deleted bundle gets a server log line with its file name and the rule that removed it, and the capture message tells the player, for example "Removed 15 bundles older than 7 days". `/hybox status`, the Settings page and the Storage tile show how much is in use next to these limits.

| Key | Type | Default | Meaning |
| --- | --- | --- | --- |
| `MaxCount` | integer ≥ 0 | `25` | Most bundles kept. |
| `MaxTotalBytes` | integer ≥ 0 | `1073741824` (1 GiB) | Most total bundle size. |
| `MaxAge` | duration ≥ 0 | 7 days | Older bundles are deleted. `0` deletes all but the newest. |
| `FailedRecordingMaxCount` | integer ≥ 0 | `5` | Most recordings kept in `temp/failed/`. |
| `StagingMaxTotalBytes`, `StagingMaxAge` | integer ≥ 0, duration ≥ 0 | `536870912` (512 MiB), 7 days | Limit for recordings in `temp/` and `live/recover/`. Older failed recordings are deleted. |
| `RepositoryMaxTotalBytes`, `RepositoryMaxAge` | integer ≥ 0, duration ≥ 0 | `1073741824` (1 GiB), 7 days | Limit for JFR repositories of crashed runs that Hybox could not recover. |

## Capture

[Privacy](privacy.md) shows what each option puts in a bundle. All keys apply on reload, but turning on `AllowModConfigOptIn` or `AllowPluginExtras` reaches plugins only after a restart.

| Key | Type | Default | Meaning |
| --- | --- | --- | --- |
| `Enabled` | boolean | `true` | `false` stops all bundles, including manual, profile and crash recovery bundles. |
| `Artifacts` | list of strings | all ten | Parts to write: `jfr`, `report`, `env`, `threads`, `serverLog`, `plugins`, `worlds`, `heartbeats`, `server`, `metrics`. |
| `LogTailLines` | integer ≥ 0 | `500` | Server log lines, up to 256 KiB. |
| `IncludeServerLog`, `IncludeServerConfig`, `IncludeModConfigs` | boolean | `false` | Include the server log tail, the server's `config.json`, or mod config files. |
| `AllowModConfigOptIn`, `AllowPluginExtras` | boolean | `true` | Let plugins add config files, or bundle files. |
| `HeapHistogram` | boolean | `false` | Add a heap histogram to every report. Walking the heap pauses the server. |
| `RedactPatterns` | list of regular expressions | four built-in patterns | Matching text becomes `[REDACTED]`. A list replaces the [built-in patterns](privacy.md#redaction). |

## Discord

All keys apply on reload. [Privacy](privacy.md#discord) shows what is sent.

| Key | Type | Default | Meaning |
| --- | --- | --- | --- |
| `WebhookUrl` | string | `""` | Webhook URL. Empty turns Discord off. |
| `Cooldown` | duration ≥ 0 | 1 minute | At most one message per cooldown. Others are dropped. |
| `RequestTimeout` | duration ≥ 0 | 10 seconds | HTTP timeout. |
| `Username` | string | `"Hybox"` | Name shown on the message. |

## Metrics

All keys apply on reload. Changing a `Prometheus` key restarts the endpoint.

| Key | Type | Default | Meaning |
| --- | --- | --- | --- |
| `Enabled` | boolean | `false` | Write `metrics/health-<yyyyMMdd>.csv`. Needed for `/hybox trend`. |
| `RetentionDays` | integer | `7` | Days of metrics files kept. `0` or less uses `7`. |
| `Cpu` | boolean | `true` | Record process CPU use. |
| `Allocation` | boolean | `false` | Record the heap allocation rate. |
| `Prometheus.Enabled` | boolean | `false` | Serve `GET http://<Bind>:<Port>/metrics`. No authentication. |
| `Prometheus.Port` | integer | `9099` | Values outside 1 to 65535 use `9099`. |
| `Prometheus.Bind` | string | `"127.0.0.1"` | Blank uses `127.0.0.1`. |

Prometheus metric names start with `hybox_`. Plugin metrics are `hybox_plugin_gauge` and `hybox_plugin_counter` with a `name` label.
