# Triggers

A trigger is a detector that asks Hybox to save a bundle. Detectors that poll start when the server's universe is ready. `/hybox triggers` lists which ones are armed. Thresholds and defaults are in [Configuration](configuration.md#trigger).

## Detectors

| Trigger kind | Measures | Fires when | Severity | Checked |
| --- | --- | --- | --- | --- |
| `HEARTBEAT_STALL` | Time since each world thread last ran a small task that Hybox posts every 50 ms. | A world has not run it for `StallDegradedMs`. | Critical if the stall is already `StallCriticalMs` or more, otherwise degraded. | Every 250 ms |
| `TICK_DEGRADED` | Average tick time of each world, as reported by Hytale. | The average reaches `TickAvgDegradedMs`. | Critical if the average is already `TickAvgCriticalMs` or more, otherwise degraded. | Every 250 ms |
| `WORLD_FAILURE` | Worlds that Hytale removes because of an exception. | A world is removed with an exceptional reason while the server is not shutting down. | Critical | On the event |
| `DEADLOCK` | Deadlocked threads, from the JVM. | The set of deadlocked threads changes and is not empty. | Critical | Every 5 s |
| `HEAP_PRESSURE` | Use of the old-generation heap pool after the last GC, as a share of its maximum. If no pool is named old or tenured, Hybox uses the first heap pool. | Use stays at `HeapPressurePct` or more for `HeapPressureSustain`. | Critical at 97 % or more, otherwise degraded. | Every 5 s |
| `GC_PRESSURE` | Share of wall time spent in stop-the-world GC pauses. Collectors with `cycles` or `concurrent` in their name are not counted. | The share over `GcPressureWindow` reaches `GcPressurePct`. Hybox waits until it has samples for 80 % of the window. | Degraded | Every 5 s |
| `CPU_SATURATION` | CPU use of the server process. | Use stays at `CpuSaturationPct` or more for `CpuSaturationSustain`. | Degraded | Every 5 s |
| `NET_SATURATION` | The highest read rate and the highest write rate of any network interface, from JFR, sampled every 5 s. Samples older than 15 s are ignored. JFR sampling runs only while this detector is armed. | Inbound traffic stays at `NetInMbps` or more, or outbound at `NetOutMbps` or more, for `NetSustain`. | Degraded | Every 5 s |
| `PLAYER_DROP` | Total players in all worlds, compared to the peak within `PlayerDropWindow`. | The peak is at least `PlayerDropMinPlayers` and the current count is `PlayerDropPct` percent or more below it. | Degraded | Every `Jfr.SampleInterval` |
| `LOG_ERROR` | Server log records at `SEVERE` level, except Hybox's own. | A record passes the filters below. | Degraded | On each record |

All settings except `Jfr.SampleInterval` are under `Trigger`.

Each detector fires once, then re-arms when the value recovers. Tick, heap, GC, CPU and network detectors re-arm when the value drops below 90 % of the threshold. The heartbeat detector re-arms when the world runs the task again. The player drop detector re-arms when the count is no longer a drop.

### Scope

Each trigger has a scope. It appears in the incident and in Discord messages.

| Trigger kind | Scope |
| --- | --- |
| `HEARTBEAT_STALL`, `TICK_DEGRADED`, `WORLD_FAILURE` | The world name |
| `DEADLOCK`, `HEAP_PRESSURE`, `GC_PRESSURE`, `CPU_SATURATION` | `jvm` |
| `NET_SATURATION` | `net` |
| `PLAYER_DROP`, `LOG_ERROR`, `MANUAL` | `server` |

### Log error filters

`LOG_ERROR` is off by default. Turn it on with `Trigger.Modules.LogError`. A `SEVERE` record fires the trigger only if all of these hold:

1. If `LogErrorRequireThrowable` is on, the record has an exception.
2. If `LogErrorSkipSentry` is on, Hytale has not marked the exception as one to skip for Sentry.
3. No pattern in `LogErrorIgnore` matches the error text. The text is `<exception class>: <message>`, or the first line of the log message, cut to 200 characters.
4. The same error has not fired within `LogErrorDedupeWindow`. Errors are the same when they have the same exception class, message and top stack frame, or the same logger and first line.

## Other trigger kinds

| Trigger kind | Source | Severity |
| --- | --- | --- |
| `MANUAL` | `/hybox dump`, the dashboard capture button, and profile captures | Info |
| `UNCLEAN_SHUTDOWN` | A bundle rebuilt at startup from a crashed run. See [After a crash](using.md#after-a-crash). | Degraded |

## Severity

Severity decides what happens before the bundle is written.

| Severity | Before the bundle is written |
| --- | --- |
| Degraded | Hybox waits for recovery, up to `Jfr.PostIncidentMaxWait`, and takes spaced thread dumps. Heartbeat stalls end the wait when the world runs again. Tick and GC pressure end it when they recover. Other degraded triggers wait the full time. |
| Critical, info | Hybox writes the bundle at once, with one thread dump if `Jfr.ThreadDumpCount` is above 0. |

## Cooldown and debounce

Hybox checks every trigger, including manual captures, before it captures:

1. Cooldown: if any trigger was accepted within `Trigger.Cooldown`, the new one is rejected. Kinds in `Trigger.CooldownExemptKinds` skip this check.
2. Debounce: if the same kind with the same scope was accepted within `Trigger.Debounce`, the new one is rejected.

The capture at the end of a profile skips both checks.

Separately, Hybox runs one capture at a time. A trigger that arrives during a capture, including its post-incident wait, is dropped.

## Turning a detector off

| Detector | Off when |
| --- | --- |
| Heartbeat stall, tick, deadlock, log error | Its `Trigger.Modules` key is `false`. |
| Heap pressure, CPU saturation | Its module is `false`, or its percent is `0`. |
| GC pressure, player drop | Its module is `false`, its percent is `0`, or its window is `0`. |
| Network saturation | Its module is `false`, or both `NetInMbps` and `NetOutMbps` are `0`. This is the default. |
| World failure | It cannot be turned off alone. `Capture.Enabled` set to `false` stops all bundles. |
