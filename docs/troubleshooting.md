# Troubleshooting

Hybox writes to the server log and replies in chat or the console. `<...>` marks a part that changes. A message marked "Warning" needs no action unless you see a problem.

Start with `/hybox status --verbose`. It shows the recorder state, the last capture outcome and error, storage pressure and plugin callback problems.

## Startup

| Message | Cause and fix |
| --- | --- |
| `Hybox failed to start.` | The stack trace that follows names the cause. Often it is one of the messages below. |
| `Failed to load Hybox config from <path>; the existing configuration was not replaced.` | `hybox.json` is not valid. Fix the file, or move it away so Hybox writes a new default file. |
| `Failed to write default Hybox config to <path>` | Hybox cannot write its plugin data folder. Make it writable by the user that runs the server. |
| `Failed to load JFR configurations. Falling back to an unconfigured recording.` | Neither `Jfr.Configuration` nor `profile` could be loaded. Set `Jfr.Configuration` to `default` or `profile` and restart. |
| `Failed to disable JFR event '<name>'.` | An entry in `Jfr.DisabledEvents` is not a valid event name. Correct or remove it. |
| `JFR recording has no age or size cap (both unlimited); the on-disk recording can grow until it fills the disk. Set Jfr.MaxAge or Jfr.MaxSizeBytes to a positive value to bound it.` | Set `Jfr.MaxAge` or `Jfr.MaxSizeBytes` above 0 and restart. |
| `Universe.get() failed; heartbeats disabled until restart.` | Polling detectors and metrics did not start. Restart the server. If it repeats, report it with the stack trace. |
| `Failed to subscribe to the server log; log-error captures disabled.` | Warning. `LOG_ERROR` cannot fire until the next start. |
| `Failed to subscribe to player connection events; connection timeline disabled.` | Warning. Joins and leaves are missing from the timeline. |
| `Failed to start the network sampler; net-saturation captures disabled.` | Warning. `NET_SATURATION` cannot fire. |
| `Failed to start Prometheus metrics endpoint.` | The port is in use or `Metrics.Prometheus.Bind` is not a local address. Change `Port` or `Bind` and run `/hybox reload`. |
| `Prometheus metrics on http://<bind>:<port>/metrics` | Information. The endpoint is running. |

## Configuration

| Message | Cause and fix |
| --- | --- |
| `Config <key> must be >= 0; using default <value>.` | Warning. The value is negative. |
| `Config <key> must be > 0; using default <value>.` | Warning. The value is 0 or negative. |
| `Config <key> must be within 0..100 (0 = disabled); using default <value>.` | Warning. A percent is out of range. |
| `Config <key> must be non-blank; using default <value>.` | Warning. A required string is empty. |
| `Config Trigger.StallCriticalMs (<n>) is < Trigger.StallDegradedMs (<n>); clamping.` | Warning. The critical value is raised to the degraded value. The same applies to `Trigger.TickAvgCriticalMs`. |
| `Config Retention.MaxAge is negative; using default <value>.` | Warning. |
| `Config Trigger.CooldownExemptKinds has unknown trigger kind '<name>'; ignoring.` | Use the kinds listed in [Triggers](triggers.md#detectors). |
| `Skipping invalid Trigger.LogErrorIgnore pattern: <regex>` | The regular expression does not compile. Fix it. |
| `Skipping invalid redact pattern: <regex>` | An entry in `Capture.RedactPatterns` does not compile. Fix it, or text it should hide stays in bundles. |
| `Invalid Hybox config; falling back to defaults.` | A combination of values was rejected. Hybox uses defaults for every setting. The stack trace names the setting. |

## Reload and settings

| Message | Cause and fix |
| --- | --- |
| `Reloaded. Changed: <sections>. Restart required for: <settings>` | The listed settings take effect at the next start. |
| `Reload failed; previous configuration kept. Check the active configuration.` | `hybox.json` could not be read. Fix it and reload again. |
| `Reload failed: capture or profile session is busy.` | Wait for the capture or profile to end, then reload. |
| `Reload failed: Hybox is not running.` | Hybox stopped or failed to start. Check the server log. |
| `Unable to save hybox.json: <problems>` | The settings page rejected a value. The problem names the field and its range, for example `Cooldown must be between 0 and 3600 seconds.` |
| `Heartbeat critical threshold must be at least the degraded threshold.` | Raise the critical value or lower the degraded one. The same applies to `Tick critical threshold`. |
| `Hybox worker is busy or stopping; settings were not saved.` | Try again in a moment. |
| `The settings page needs a player; edit hybox.json and use /hybox reload.` | `/hybox settings` was run from the console. |

## Captures

| Message | Cause and fix |
| --- | --- |
| `Capture disabled by configuration.` | `Capture.Enabled` is `false`. |
| `Capture skipped: cooldown active.` | Another capture was accepted within `Trigger.Cooldown`. Manual captures are subject to it too. Wait and retry. See [Cooldown and debounce](triggers.md#cooldown-and-debounce). |
| `Capture skipped: duplicate trigger (debounce).` | The same trigger fired within `Trigger.Debounce`. |
| `Capture busy; try again shortly.` | A capture or its post-incident wait is running. Hybox runs one at a time. |
| `Capture skipped: storage pressure.` | A storage budget is full. See [Storage](#storage). |
| `Capture failed; use /hybox status --verbose for details.` | The server log has `Capture pipeline failed.` with the cause. |
| `Capture stopped: Hybox is not running.` | Hybox is stopping or did not start. |
| `Worker busy or stopping; try again later.` | Hybox's worker queue is full. Retry. |
| `No incidents yet.` | No bundle has been saved. |

## Storage

| Message | Cause and fix |
| --- | --- |
| `Combined staging byte budget reached at <directories>; preserving pending/newest evidence and refusing new captures until space is available.` | Recordings in `temp/`, `temp/pending/`, `temp/failed/` and `live/recover/` exceed `Retention.StagingMaxTotalBytes`. Inspect and delete old recordings there, or raise the limit. |
| `Retained JFR repository age/byte budget reached; preserving unrecovered evidence and refusing new captures.` | JFR repositories of crashed runs that Hybox could not recover exceed `Retention.RepositoryMaxTotalBytes` or `Retention.RepositoryMaxAge`. Their paths are in `live/jfr-session`. Inspect and delete them, or raise the limits. |
| `Preserved recording <path> at <path> for recovery or inspection.` | Warning. A recording could not be bundled. Hybox keeps it in `temp/failed/` and retries at the next start. |
| `Shutdown during an in-flight capture; left a rolling snapshot at <path> for recovery on the next startup.` | Warning. The next start turns it into a bundle. |
| `Rolling snapshot writer disabled to preserve an unrecovered recording at <path>` | Warning. `live/rolling.jfr` from an earlier run could not be moved for recovery. Hybox stops refreshing it so the old data is not lost. Check permissions on `live/`, then restart. |
| `Failed to write rolling JFR snapshot.` | Hybox cannot write `live/`. Check disk space and permissions. |
| `Ignoring unsafe JFR repository pointer <path>.` | Warning. A path in `live/jfr-session` is outside the JFR repository folder or is a link. |
| `Retention maxAge exceeded but newest incident must be kept.` | Warning. The only bundle is older than `Retention.MaxAge`. |
| `Retention would remove newest incident; stopping deletions.` | Warning. Retention reached the newest bundle. This happens when a limit leaves room for only one bundle, or when one bundle is larger than `Retention.MaxTotalBytes`. |
| `Failed to delete incident <path>` | Hybox cannot delete an old bundle. Check file permissions. |

## Profiling

| Message | Cause and fix |
| --- | --- |
| `Cannot start profile: a capture is busy.` | Wait for the capture to end. |
| `A profile session is already running (<n>s remaining).` | Stop or cancel it first. |
| `Profile lead-up capture: <status>; recording unchanged.` | With `--keep-buffer=false`, the first capture was not saved, for example because of the cooldown. The profile did not start. |
| `Failed to switch to the profile preset; check the server log.` | The JFR `profile` preset could not be applied. The server log has `Profile session not started:` with the cause. |
| `No profile session is running.` | `stop` or `cancel` was used without a profile. |

## Other commands

| Message | Cause and fix |
| --- | --- |
| `Metrics disabled; set Metrics.Enabled=true to record trends.` | Turn on `Metrics.Enabled` and reload. |
| `No health metrics recorded in the last <n> day(s).` | Metrics are on but no file covers that period yet. |
| `Heap histogram unavailable on this JVM.` | The JVM does not offer a class histogram. |
| `<action> requires permission to use /hybox <command>.` | The player lacks that command's node. See [Permissions](installing.md#permissions). |

## Discord

| Message | Cause and fix |
| --- | --- |
| `Invalid Discord webhook URL.` | Correct `Discord.WebhookUrl`. |
| `Discord webhook delivery failed.` | The request failed or timed out. The stack trace shows why. |
| `Discord notification skipped: Hybox worker is busy or stopping.` | Warning. That message was not sent. |

## Plugin callbacks

`/hybox status` shows `Warnings:` with callback counters when another plugin's callback misbehaves.

| Warning | Cause and fix |
| --- | --- |
| `callback failures=<n>` | A callback threw. `--verbose` shows `last failure: <label>: <exception>`. Report it to that plugin's author. |
| `callback timeouts=<n>`, `callbacks quarantined=<n>` | A callback ran past its deadline. Hybox stopped calling it. `--verbose` shows `last failure: <label>: callback deadline exceeded`. Report it to that plugin's author. |
| `callbacks rejected=<n>`, `callback workers exhausted` | Callbacks were skipped because earlier ones were still running. |

`<label>` names the plugin and callback: `<owner>/diagnostics/<title>`, `<owner>/gauge/<name>` or `<owner>/extras`.

See [Callbacks](plugin-api.md#callbacks) for the limits.
