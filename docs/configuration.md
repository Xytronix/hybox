# Configuration

Hybox reads `hybox.json` from its plugin data folder. It writes the file with defaults on first start. `/hybox status --verbose` shows the path.

## Editing

- Edit `hybox.json`, then run `/hybox reload`. The reply lists what changed, and which changes need a restart.
- Or use `/hybox settings` in game. It saves only the fields you changed, then reloads.

Settings marked "restart" in the tables below are saved by a reload but take effect at the next server start. Everything else applies on reload. A reload is refused while a capture or profile is running.

Durations are ISO-8601 strings, for example `"PT10S"` (10 seconds), `"PT15M"` (15 minutes) or `"PT168H"` (7 days). Sizes are in bytes.

```json
{
  "Jfr": {
    "MaxAge": "PT30M"
  },
  "Metrics": {
    "Enabled": true
  }
}
```

### Invalid values

| Problem | What Hybox does |
| --- | --- |
| A negative value where the tables say ≥ 0, or a value of 0 or less where they say > 0 | Logs a warning and uses the default. |
| A percent outside 0 to 100 | Logs a warning and uses the default. |
| A blank string that must not be blank | Logs a warning and uses the default. |
| A critical threshold below its degraded threshold | Logs a warning and raises the critical threshold to the degraded one. |
| An unknown name in `Trigger.CooldownExemptKinds` | Logs a warning and ignores that name. |
| An invalid regular expression | Logs a warning and skips that pattern. |
| Any other invalid combination | Logs `Invalid Hybox config; falling back to defaults.` and uses the defaults for everything. |
| A file that cannot be parsed | At startup Hybox fails to start. On reload Hybox keeps the previous settings. |

The exact messages are in [Troubleshooting](troubleshooting.md#configuration).

## Version

| Key | Type | Default | Meaning |
| --- | --- | --- | --- |
| `Version` | integer | `1` | File format version. |

## Jfr

The rolling recording and the evidence taken around an incident.

| Key | Type | Default | Applies | Meaning |
| --- | --- | --- | --- | --- |
| `MaxAge` | duration ≥ 0 | 15 minutes | restart | How much history the recording keeps. `0` means no age limit. |
| `MaxSizeBytes` | integer ≥ 0 | `268435456` (256 MiB) | restart | Size limit of the recording on disk. `0` means no size limit. If both limits are `0`, Hybox warns that the recording can fill the disk. |
| `RecordingName` | string | `"hybox"` | restart | Name of the JFR recording. |
| `DisabledEvents` | list of strings | `[]` | restart | JFR event names to turn off, for example `"jdk.JVMInformation"`. |
| `Configuration` | string | `"default"` | restart | JFR settings preset for normal recording. If it cannot be loaded, Hybox tries `profile`, then an unconfigured recording. |
| `SampleInterval` | duration | 10 seconds | restart | How often Hybox samples world ticks, players, plugin gauges and health metrics. Clamped to 5 seconds to 5 minutes. |
| `SnapshotInterval` | duration ≥ 0 | 10 minutes | restart | How often Hybox writes `live/rolling.jfr` for crash recovery. `0` turns snapshots off. |
| `OldObjectSampling` | boolean | `false` | restart | Records old-object samples with stack traces, to help find memory leaks. |
| `PostIncidentMaxWait` | duration ≥ 0 | 2 minutes | reload | Longest wait for a degraded problem to clear before the bundle is written. `0` writes it at once. |
| `ThreadDumpCount` | integer | `3` | reload | Thread dumps per incident. `0` takes none. |
| `ThreadDumpInterval` | duration ≥ 0 | 10 seconds | reload | Time between thread dumps during the wait. |

## Trigger

How detectors fire. [Triggers](triggers.md) explains each detector.

| Key | Type | Default | Meaning |
| --- | --- | --- | --- |
| `Cooldown` | duration ≥ 0 | 30 seconds | After a capture, other triggers are rejected for this long. |
| `Debounce` | duration ≥ 0 | 2 seconds | A repeat of the same trigger in the same scope is rejected for this long. |
| `CooldownExemptKinds` | list of strings | `["DEADLOCK", "HEARTBEAT_STALL", "WORLD_FAILURE"]` | Trigger kinds that ignore the cooldown. |
| `StallDegradedMs` | integer > 0 | `2000` | Heartbeat stall that fires the trigger. |
| `StallCriticalMs` | integer > 0 | `10000` | Heartbeat stall rated critical. |
| `TickAvgDegradedMs` | integer > 0 | `100` | Average tick time that fires the trigger. |
| `TickAvgCriticalMs` | integer > 0 | `250` | Average tick time rated critical. |
| `HeapPressurePct` | percent | `90` | Old-generation heap use after GC. `0` disables. |
| `HeapPressureSustain` | duration ≥ 0 | 60 seconds | How long heap use must stay above the threshold. |
| `GcPressurePct` | percent | `5` | Share of time spent in stop-the-world GC pauses. `0` disables. |
| `GcPressureWindow` | duration ≥ 0 | 60 seconds | Window for the GC share. `0` disables. |
| `CpuSaturationPct` | percent | `95` | Process CPU use. `0` disables. |
| `CpuSaturationSustain` | duration ≥ 0 | 60 seconds | How long CPU use must stay above the threshold. |
| `NetInMbps` | integer ≥ 0 | `0` | Inbound Mbit/s. `0` disables this direction. |
| `NetOutMbps` | integer ≥ 0 | `0` | Outbound Mbit/s. `0` disables this direction. |
| `NetSustain` | duration ≥ 0 | 30 seconds | How long traffic must stay above the threshold. |
| `PlayerDropPct` | percent | `50` | Drop from the recent peak player count. `0` disables. |
| `PlayerDropWindow` | duration ≥ 0 | 60 seconds | Window for the peak. `0` disables. |
| `PlayerDropMinPlayers` | integer ≥ 0 | `8` | Smallest peak that can fire the trigger. |
| `LogErrorRequireThrowable` | boolean | `true` | Only log records with an exception count. |
| `LogErrorSkipSentry` | boolean | `true` | Ignore exceptions that Hytale marks as not reported to Sentry. |
| `LogErrorDedupeWindow` | duration ≥ 0 | 15 minutes | The same error fires again only after this long. `0` turns this off. |
| `LogErrorIgnore` | list of regular expressions | `[]` | Errors whose text matches any pattern are ignored. |

All `Trigger` keys apply on reload.

### Trigger.Modules

Turns detectors on or off. All apply on reload.

| Key | Type | Default |
| --- | --- | --- |
| `HeartbeatStall` | boolean | `true` |
| `TickDegraded` | boolean | `true` |
| `Deadlock` | boolean | `true` |
| `HeapPressure` | boolean | `true` |
| `GcPressure` | boolean | `true` |
| `CpuSaturation` | boolean | `true` |
| `NetSaturation` | boolean | `true` |
| `PlayerDrop` | boolean | `true` |
| `LogError` | boolean | `false` |

## Retention

Limits for bundles and for recordings kept for recovery. All apply on reload.

| Key | Type | Default | Meaning |
| --- | --- | --- | --- |
| `MaxCount` | integer ≥ 0 | `25` | Most bundles kept. `0` means no limit. |
| `MaxTotalBytes` | integer ≥ 0 | `1073741824` (1 GiB) | Most total bundle size. `0` means no limit. |
| `MaxAge` | duration ≥ 0 | 7 days | Bundles older than this are deleted. |
| `FailedRecordingMaxCount` | integer ≥ 0 | `5` | Most recordings kept in `temp/failed/`. The newest is always kept. |
| `StagingMaxTotalBytes` | integer ≥ 0 | `536870912` (512 MiB) | Size limit for recordings in `temp/` and `live/recover/`. When reached, Hybox refuses new captures. `0` means no limit. |
| `StagingMaxAge` | duration ≥ 0 | 7 days | Failed recordings older than this are deleted. |
| `RepositoryMaxTotalBytes` | integer ≥ 0 | `1073741824` (1 GiB) | Size limit for JFR repositories of crashed runs that Hybox could not recover. When reached, Hybox refuses new captures. `0` means no limit. |
| `RepositoryMaxAge` | duration ≥ 0 | 7 days | If such a repository is older than this, Hybox refuses new captures. |

The newest bundle is never deleted by retention.

## Capture

What goes into a bundle. All apply on reload. [Privacy](privacy.md) explains the effect of each option.

| Key | Type | Default | Meaning |
| --- | --- | --- | --- |
| `Enabled` | boolean | `true` | `false` stops all bundles, including manual, profile and crash recovery bundles. |
| `Artifacts` | list of strings | all nine | Which parts to write: `jfr`, `report`, `env`, `threads`, `serverLog`, `plugins`, `worlds`, `heartbeats`, `server`. |
| `LogTailLines` | integer ≥ 0 | `500` | Lines of the server log to include, up to 256 KiB. |
| `IncludeServerLog` | boolean | `false` | Include the tail of the newest file in `logs/`. |
| `IncludeServerConfig` | boolean | `false` | Include the server's `config.json`, with passwords and the auth credential store removed. |
| `IncludeModConfigs` | boolean | `false` | Include config files found in folders under `mods/`. |
| `AllowModConfigOptIn` | boolean | `true` | Let plugins add their own config files through the API. |
| `AllowPluginExtras` | boolean | `true` | Let plugins add files to bundles through the API. |
| `HeapHistogram` | boolean | `false` | Add a heap histogram to every report. Walking the heap pauses the server. |
| `SanitizeLog` | boolean | `true` | Mask player names in the server log section of the report. |
| `RedactPatterns` | list of regular expressions | four built-in patterns | Text matching any pattern becomes `[REDACTED]`. Setting this list replaces the built-in patterns. |

## Discord

Posts a short message for each new bundle. All apply on reload. [Privacy](privacy.md#discord) shows what is sent.

| Key | Type | Default | Meaning |
| --- | --- | --- | --- |
| `WebhookUrl` | string | `""` | Discord webhook URL. Empty turns Discord off. |
| `Cooldown` | duration ≥ 0 | 1 minute | At most one message per cooldown. Others are not sent. |
| `RequestTimeout` | duration ≥ 0 | 10 seconds | HTTP timeout per message. |
| `Username` | string | `"Hybox"` | Name shown on the message. |

## Metrics

Health history and live metrics. All apply on reload. Changing a `Prometheus` key restarts the endpoint.

| Key | Type | Default | Meaning |
| --- | --- | --- | --- |
| `Enabled` | boolean | `false` | Write `metrics/health-<yyyyMMdd>.csv` once per `Jfr.SampleInterval`. Needed for `/hybox trend`. |
| `RetentionDays` | integer | `7` | Days of metrics files to keep. `0` or less uses `7`. |
| `Cpu` | boolean | `true` | Record process CPU use. |
| `Allocation` | boolean | `false` | Record the heap allocation rate. |
| `Prometheus.Enabled` | boolean | `false` | Serve metrics at `http://<Bind>:<Port>/metrics`. It has no authentication. |
| `Prometheus.Port` | integer | `9099` | Port. Values outside 1 to 65535 use `9099`. |
| `Prometheus.Bind` | string | `"127.0.0.1"` | Address to listen on. Blank uses `127.0.0.1`. |

The endpoint answers `GET` only. Metric names start with `hybox_`. Plugin metrics appear as `hybox_plugin_gauge` and `hybox_plugin_counter` with a `name` label.

## Settings page

`/hybox settings` edits these keys. It shows sizes in MiB and durations in the unit below, and refuses values outside the range.

| Key | Range |
| --- | --- |
| `Trigger.StallDegradedMs`, `Trigger.StallCriticalMs`, `Trigger.TickAvgDegradedMs`, `Trigger.TickAvgCriticalMs` | 1 ms or more. Critical must be at least degraded. |
| `Trigger.HeapPressurePct`, `Trigger.GcPressurePct`, `Trigger.CpuSaturationPct`, `Trigger.PlayerDropPct` | 1 to 100 percent |
| `Trigger.HeapPressureSustain`, `Trigger.CpuSaturationSustain`, `Trigger.NetSustain` | 0 to 3600 seconds |
| `Trigger.GcPressureWindow`, `Trigger.PlayerDropWindow` | 1 to 3600 seconds |
| `Trigger.NetInMbps`, `Trigger.NetOutMbps` | 0 to 100000 Mbit/s |
| `Trigger.PlayerDropMinPlayers` | 0 to 10000 |
| `Trigger.LogErrorDedupeWindow` | 0 to 86400 seconds |
| `Trigger.Cooldown`, `Trigger.Debounce` | 0 to 3600 seconds |
| `Trigger.Modules.*`, `Capture.Enabled`, `Metrics.Enabled`, `Metrics.Cpu`, `Metrics.Allocation`, `Jfr.OldObjectSampling` | on or off |
| `Retention.MaxCount` | 1 to 1000 |
| `Retention.MaxTotalBytes` | 1 to 1048576 MiB |
| `Retention.MaxAge` | 1 to 3650 days |
| `Metrics.RetentionDays` | 1 to 365 days |
| `Jfr.PostIncidentMaxWait` | 0 to 3600 seconds |
| `Jfr.ThreadDumpCount` | 0 to 20 |
| `Jfr.ThreadDumpInterval` | 1 to 3600 seconds |
| `Jfr.MaxAge` | 1 to 1440 minutes |
| `Jfr.MaxSizeBytes` | 16 to 8192 MiB |
| `Jfr.SampleInterval` | 5 to 300 seconds |
| `Jfr.SnapshotInterval` | 0 to 1440 minutes |

Other keys can only be changed in the file.
