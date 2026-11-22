# Troubleshooting

Start with `/hybox status --verbose`. `<...>` marks a part that changes. Messages that explain themselves are not listed.

## Startup

| Message | Cause and fix |
| --- | --- |
| `Hybox failed to start.` | The stack trace that follows names the cause, often one of the messages below. |
| `Failed to load Hybox config from <path>; the existing configuration was not replaced.` | `hybox.json` is not valid. Fix it, or move it away so Hybox writes a new default file. |
| `Failed to write default Hybox config to <path>` | Make `mods/Xytronix_Hybox/` writable by the user that runs the server. |
| `Skipping pack at Xytronix_Hybox: missing or invalid manifest.json` | Harmless. Hytale checks every folder under `mods/` for an asset pack, including Hybox's data folder. |
| `Failed to load JFR configurations. Falling back to an unconfigured recording.` | Neither `Jfr.Configuration` nor `profile` loaded. Set `Jfr.Configuration` to `default` and restart. |
| `JFR recording has no age or size cap (both unlimited). <...>` | Set `Jfr.MaxAge` or `Jfr.MaxSizeBytes` above 0 and restart. |
| `Universe.get() failed; heartbeats disabled until restart.`, `Failed to subscribe to the server log; log-error captures disabled.`, `Failed to start the network sampler; net-saturation captures disabled.` | The named detectors stay off until the next start. Restart. If it repeats, report it with the stack trace. |
| `Failed to start Prometheus metrics endpoint.` | The port is in use, or `Metrics.Prometheus.Bind` is not a local address. Change it and reload. |

## Configuration

| Message | Cause and fix |
| --- | --- |
| `Config <key> must be <rule>; using default <value>.` | Hybox ignored the value. Fix the key. |
| `Config Trigger.StallCriticalMs (<n>) is < Trigger.StallDegradedMs (<n>); clamping.` | Critical is raised to degraded. The same applies to `TickAvgCriticalMs`. |
| `Skipping invalid Trigger.LogErrorIgnore pattern: <regex>`, `Skipping invalid redact pattern: <regex>` | Fix the regular expression. A skipped redact pattern hides nothing. |
| `Reload failed; previous configuration kept. Check the active configuration.` | `hybox.json` could not be read. Fix it and reload. |
| `<field> must be between <min> and <max> <unit>.` | The settings page rejected a value. Nothing was saved. |
| `Unable to save hybox.json: <error>` | `hybox.json` could not be read or written. Check file and folder permissions. |

## Captures and storage

| Message | Cause and fix |
| --- | --- |
| `Capture skipped: cooldown active.` | Another trigger was accepted within `Trigger.Cooldown`. Manual captures count. Wait and retry. |
| `Capture skipped: storage pressure.` | A storage budget is full. See the two rows below. |
| `Combined staging byte budget reached at <directories>. <...>` | Recordings in `temp/` and `live/recover/` exceed `Retention.StagingMaxTotalBytes`. Inspect and delete old ones, or raise the limit. |
| `Retained JFR repository age or byte budget reached. <...>` | JFR repositories of crashed runs exceed `Retention.RepositoryMaxTotalBytes` or `Retention.RepositoryMaxAge`. Their paths are in `live/jfr-session`. Inspect and delete them, or raise the limits. |
| `Capture failed; use /hybox status --verbose for details.` | The server log has `Capture pipeline failed.` with the cause. |
| `Preserved recording <path> at <path> for recovery or inspection.` | A recording could not be bundled. Hybox keeps it in `temp/failed/` and retries at the next start. |
| `Rolling snapshot writer disabled to preserve an unrecovered recording at <path>` | `live/rolling.jfr` from an earlier run could not be moved for recovery. Check permissions on `live/`, then restart. |
| `Failed to write rolling JFR snapshot.` | Check disk space and permissions on `live/`. |
| `Retention removed <file> (<size>): <rule>.` | Retention deleted that bundle after a capture. The rule is `age`, `count` or `size`, with the limit that applied. A summary line follows. Raise `Retention.MaxAge`, `MaxCount` or `MaxTotalBytes` to keep more. |
| `Retention removed failed recording <file> (<size>): <rule>.` | An old recording in `temp/failed/` went over `Retention.FailedRecordingMaxCount`, `StagingMaxAge` or `StagingMaxTotalBytes`. |
| `Retention would remove the newest incident. Stopping deletions.` | The newest bundle alone is larger than `Retention.MaxTotalBytes`. Raise the limit, or lower `Jfr.MaxSizeBytes`. |
| `Failed to delete incident <path>` | Check permissions on `incidents/`. |
| `Skipping duplicate bundle entry <path> from plugin '<owner>'; the first entry is kept.` | Two attachments share a path. Report it to that plugin's author. |

## Commands and profiling

| Message | Cause and fix |
| --- | --- |
| `<action> require(s) permission to use /hybox <command>.` | The player lacks that node. See [Permissions](installing.md#permissions). |
| `Profile lead-up capture: <status>; recording unchanged.` | With `--keep-buffer=false`, the first capture was not saved, for example during the cooldown. The profile did not start. |
| `Failed to switch to the profile preset; check the server log.` | The JFR `profile` preset could not be applied. The log line `Profile session not started:` has the cause. |

## Discord

| Message | Cause and fix |
| --- | --- |
| `Discord webhook delivery failed.` | The request failed or timed out. The stack trace shows why. |

## Plugin callbacks

`/hybox status` lists these under `Warnings:`. `--verbose` shows `last failure: <label>: <reason>`, where `<label>` is `<owner>/diagnostics/<title>`, `<owner>/gauge/<name>` or `<owner>/extras`.

| Warning | Cause and fix |
| --- | --- |
| `callback failures=<n>` | A plugin callback threw. Report it to that plugin's author. |
| `callback timeouts=<n>`, `callbacks quarantined=<n>` | A callback ran past its deadline and is no longer called. Report it to that plugin's author. |
| `callbacks rejected=<n>`, `callback workers exhausted` | Calls were skipped because earlier ones were still running. |
