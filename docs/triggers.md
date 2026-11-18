# Triggers

A trigger asks Hybox to save a bundle. Defaults are in [Configuration](configuration.md#trigger). Keys are under `Trigger` unless they start with `Jfr`.

## Detectors

| Kind | Measures | Fires when | Severity | Checked |
| --- | --- | --- | --- | --- |
| `HEARTBEAT_STALL` | Time since each world thread last ran a task that Hybox posts every 50 ms. | A world has not run it for `StallDegradedMs`. | Critical at `StallCriticalMs` or more, otherwise degraded. | Every 250 ms |
| `TICK_DEGRADED` | Average tick time of each world, from Hytale. | The average reaches `TickAvgDegradedMs`. | Critical at `TickAvgCriticalMs` or more, otherwise degraded. | Every 250 ms |
| `WORLD_FAILURE` | Worlds that Hytale removes because of an exception, outside shutdown. | Such a world is removed. It has no switch. | Critical | On the event |
| `DEADLOCK` | Deadlocked threads, from the JVM. | The set of deadlocked threads changes and is not empty. | Critical | Every 5 s |
| `HEAP_PRESSURE` | Old-generation heap pool use after the last GC, as a share of its maximum. Without a pool named old or tenured, the first heap pool. | Use stays at `HeapPressurePct` or more for `HeapPressureSustain`. | Critical at 97% or more, otherwise degraded. | Every 5 s |
| `GC_PRESSURE` | Share of wall time in stop-the-world GC pauses. Collectors named with `cycles` or `concurrent` are not counted. | The share over `GcPressureWindow` reaches `GcPressurePct`, once samples cover 80% of the window. | Degraded | Every 5 s |
| `CPU_SATURATION` | CPU use of the server process. | Use stays at `CpuSaturationPct` or more for `CpuSaturationSustain`. | Degraded | Every 5 s |
| `NET_SATURATION` | Highest read rate and highest write rate of any network interface, from JFR every 5 s, ignoring samples older than 15 s. | Inbound stays at `NetInMbps` or more, or outbound at `NetOutMbps` or more, for `NetSustain`. | Degraded | Every 5 s |
| `PLAYER_DROP` | Total players in all worlds against the peak within `PlayerDropWindow`. | The peak is at least `PlayerDropMinPlayers` and the count is `PlayerDropPct` percent or more below it. | Degraded | Every `Jfr.SampleInterval` |
| `LOG_ERROR` | `SEVERE` server log records, except Hybox's own. | A record passes the [filters](#log-error-filters). | Degraded | On each record |
| `MANUAL` | `/hybox dump`, the dashboard and profiles. | Always | Info | |
| `UNCLEAN_SHUTDOWN` | A bundle rebuilt at startup from a crashed run. | Always | Degraded | At startup |

Each detector fires once, then re-arms: tick, heap, GC, CPU and network below 90% of the threshold, heartbeat when the world runs the task again, player drop when the count is no longer a drop.

The scope is the world name for `HEARTBEAT_STALL`, `TICK_DEGRADED` and `WORLD_FAILURE`; `jvm` for deadlock, heap, GC and CPU; `net` for network; and `server` otherwise.

### Log error filters

A `SEVERE` record fires `LOG_ERROR` only if all of these hold:

1. If `LogErrorRequireThrowable` is on, the record has an exception.
2. If `LogErrorSkipSentry` is on, Hytale has not marked the exception to skip Sentry.
3. No pattern in `LogErrorIgnore` matches the error text: `<exception class>: <message>`, or the first log line, cut to 200 characters.
4. The same error did not fire within `LogErrorDedupeWindow`. Errors are the same when exception class, message and top stack frame match, or logger and first line match.

## Before a capture

Hybox checks each trigger in this order. The capture at the end of a profile skips the first two checks.

| Check | Rejects when |
| --- | --- |
| Cooldown | Any trigger was accepted within `Cooldown`, unless the kind is in `CooldownExemptKinds`. Manual captures count. |
| Debounce | The same kind and scope was accepted within `Debounce`. |
| One at a time | A capture, including its post-incident wait, is running. Automatic triggers are dropped; manual captures reply `Capture busy; try again shortly.` |

## Severity

| Severity | Before the bundle is written |
| --- | --- |
| Degraded | Hybox keeps recording until the problem clears or `Jfr.PostIncidentMaxWait` passes, and takes up to `Jfr.ThreadDumpCount` thread dumps, `Jfr.ThreadDumpInterval` apart. Heartbeat, tick and GC triggers end the wait when they recover. Others wait the full time. A player with `.dump` can skip the wait from the dashboard. |
| Critical, info | Hybox writes the bundle at once, with one thread dump if `Jfr.ThreadDumpCount` is above 0. |
