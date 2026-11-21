# Troubleshooting

Start with `/hybox status --verbose`. `<...>` marks a part that changes. Messages that explain themselves are not listed.

## Startup

| Message | Cause and fix |
| --- | --- |
| `Hybox failed to start.` | The stack trace that follows names the cause, often one of the messages below. |
| `Failed to load Hybox config from <path>. The existing configuration was not replaced.` | `hybox.json` is not valid. Fix it, or move it away so Hybox writes a new default file. |
| `Failed to write default Hybox config to <path>` | Make `mods/Xytronix_Hybox/` writable by the user that runs the server. |
| `Skipping pack at Xytronix_Hybox: missing or invalid manifest.json` | Harmless. Hytale checks every folder under `mods/` for an asset pack, including Hybox's data folder. |
| `Failed to load JFR configurations. Falling back to an unconfigured recording.` | Neither `Jfr.Configuration` nor `profile` loaded. Set `Jfr.Configuration` to `default` and restart. |
| `JFR recording has no age or size cap (both unlimited). <...>` | Set `Jfr.MaxAge` or `Jfr.MaxSizeBytes` above 0 and restart. |
| `Universe.get() failed. Heartbeats are off until restart.`, `Failed to subscribe to the server log. Log-error captures are off.`, `Failed to start the network sampler. Net-saturation captures are off.` | The named detectors stay off. Restart. `/hybox reload` also retries the network sampler. If it repeats, report it with the stack trace. |
| `Failed to start Prometheus metrics endpoint.` | The port is in use, or `Metrics.Prometheus.Bind` is not a local address. Change it and reload. |
| `Prometheus cannot read mod JFR events, so it exports no mod metrics until the server restarts.` | Hybox could not read its own JFR recording. The endpoint still serves everything else. Restart. If it repeats, report it with the stack trace. |

## Configuration

| Message | Cause and fix |
| --- | --- |
| `Config <key> must be <rule>. Using default <value>.` | Hybox ignored the value. Fix the key. |
| `Config Trigger.StallCriticalMs (<n>) is < Trigger.StallDegradedMs (<n>). Clamping.` | Critical is raised to degraded. The same applies to `TickAvgCriticalMs` when both slow-tick limits are fixed. |
| `Config <key> (<setting>) is <value>, outside the allowed range <min> to <max>. Using <value>. hybox.json was not changed.` | Hybox uses the nearest [allowed value](/mod/hybox/configuration#allowed-ranges). Change the value in `hybox.json` or on the settings page to stop the warning. |
| `Skipping invalid Trigger.LogErrorIgnore pattern: <regex>`, `Skipping invalid redact pattern: <regex>` | Fix the regular expression. A skipped redact pattern hides nothing. |
| `Reload failed. hybox.json could not be read, so the old settings stay. The server log has the error.` | Fix `hybox.json` and reload. |
| `<setting> must be between <min> and <max>.`, `<setting> must be a whole number between <min> and <max>.`, `<setting> must be 0 for Auto, or between <min> and <max>.`, `<group>: Critical must be at least Degraded.` | The settings page rejected a value. Nothing was saved. |
| `Unable to read hybox.json. The settings were not changed.` | The settings page could not open. The dashboard shows this line, and the server log has `Unable to read Hybox settings.` with the cause. Fix `hybox.json`. |
| `Unable to save hybox.json: <error>` | `hybox.json` could not be read or written. Check file and folder permissions. |

## Captures and storage

| Message | Cause and fix |
| --- | --- |
| `Capture skipped. Another capture ran too recently. See the cooldown in /hybox settings.` | Another trigger was accepted within `Trigger.Cooldown`. Manual captures count. Wait and retry. |
| `Capture skipped. Recording storage is full. Run /hybox status --verbose.` | A storage budget is full. See the two rows below. |
| `Combined staging byte budget reached at <directories>. <...>` | Recordings in `temp/` and `live/recover/` exceed `Retention.StagingMaxTotalBytes`. Inspect and delete old ones, or raise the limit. |
| `Retained JFR repository age or byte budget reached. <...>` | JFR repositories of crashed runs exceed `Retention.RepositoryMaxTotalBytes` or `Retention.RepositoryMaxAge`. Their paths are in `live/jfr-session`. Inspect and delete them, or raise the limits. |
| `Capture failed. Run /hybox status --verbose for the reason.` | The server log has `Capture pipeline failed.` with the cause. |
| `Preserved recording <path> at <path> for recovery or inspection.` | A recording could not be bundled. Hybox keeps it in `temp/failed/` and retries at the next start. |
| `Rolling snapshot writer disabled to preserve an unrecovered recording at <path>` | `live/rolling.jfr` from an earlier run could not be moved for recovery. Check permissions on `live/`, then restart. |
| `Failed to write rolling JFR snapshot.` | Check disk space and permissions on `live/`. |
| `Retention removed <file> (<size>): <rule>.` | Retention deleted that bundle after a capture. The rule is `age`, `count` or `size`, with the limit that applied. A summary line follows. Raise `Retention.MaxAge`, `MaxCount` or `MaxTotalBytes` to keep more. |
| `Retention removed failed recording <file> (<size>): <rule>.` | An old recording in `temp/failed/` went over `Retention.FailedRecordingMaxCount`, `StagingMaxAge` or `StagingMaxTotalBytes`. |
| `Retention would remove the newest incident. Stopping deletions.` | The newest bundle alone is larger than `Retention.MaxTotalBytes`. Raise the limit, or lower `Jfr.MaxSizeBytes`. |
| `Failed to delete incident <path>` | Check permissions on `incidents/`. |
| `Skipping duplicate bundle entry <path> from plugin '<owner>'. The first entry is kept.` | Two attachments share a path. Report it to that plugin's author. |

## Commands and profiling

| Message | Cause and fix |
| --- | --- |
| `<action> require(s) permission to use /hybox <command>.` | The player lacks that node. See [Permissions](/mod/hybox/installing-hybox#permissions). |
| `Profile lead-up capture: <status>. The recording is unchanged.` | With `--keep-buffer=false`, the first capture was not saved, for example because capture is off or storage is full. The profile did not start. |
| `Failed to switch to the profile settings. The server log has the error.` | JFR could not apply the profile settings. The log line `Profile session not started:` has the cause. |
| `Unknown profile mode "<mode>". Use lag or memory.` | Only `lag` and `memory` exist. |
| `A profile is already queued. It starts once the incident is saved.` | One profile can wait at a time. Cancel it with `/hybox profile cancel` to start another. |
| `The last profile is still being saved. Try again in a moment.` | The previous profile's bundle is not written yet. |
| `The profile bundle is being written now and can no longer be canceled.` | Cancel works until the bundle starts writing. Delete the bundle afterwards if you do not want it. |

## Discord

A failed or refused message is logged once, and again only after a message goes through.

| Message | Cause and fix |
| --- | --- |
| `Could not reach the Discord webhook, so the alert was not delivered.` | The request failed or timed out. The stack trace shows why. |
| `Discord refused the webhook with HTTP <status>. Check Discord.WebhookUrl, the webhook may have been deleted.` | Discord answered 401, 403 or 404. Copy the webhook URL again from the channel settings into `Discord.WebhookUrl` and run `/hybox reload`. |
| `Discord answered the webhook with HTTP <status>, so the alert was not delivered.` | Another error from Discord, often a 5xx while Discord has problems. Later alerts are sent as usual. |
| `Discord rate limited the webhook again after Hybox waited, so the alert was dropped.` | Discord answered 429 twice. Too many messages go to this webhook, for example from several servers. Give each server its own webhook or raise `Discord.Cooldown`. |
| `Discord rate limited the webhook and asked Hybox to wait <n> s, longer than 60 s, so the alert was dropped.` | Hybox waits at most 1 minute for a retry. Same fix as above. |

## Plugin callbacks

`/hybox status` lists these under `Warnings:`. `--verbose` shows `Last failure: <label>: <reason>` at the end of the `Callbacks:` line, where `<label>` is `<owner>/diagnostics/<title>`, `<owner>/gauge/<name>` or `<owner>/extras`.

| Warning | Cause and fix |
| --- | --- |
| `<n> callback failures` | A plugin callback threw. Report it to that plugin's author. |
| `<n> callback timeouts`, `<n> callbacks quarantined` | A callback ran past its deadline and is no longer called. Report it to that plugin's author. |
| `<n> callbacks rejected` | Calls were skipped because earlier ones were still running. |
| `callback workers exhausted` | Both callback threads are busy right now. |
