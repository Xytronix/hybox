# Triggers

A trigger asks Hybox to save a bundle. Defaults are in [Configuration](/mod/hybox/configuration#trigger). Keys are under `Trigger` unless they start with `Jfr`.

## Detectors

| Kind | Measures | Fires when | Severity |
| --- | --- | --- | --- |
| `HEARTBEAT_STALL` | How long a task that Hybox posts to each world thread has waited to run. Hybox posts it every 250 ms, or every quarter of `StallDegradedMs` when that is below 1 second. | The oldest task that has not run yet has waited for `StallDegradedMs`. | Critical at `StallCriticalMs` or more, otherwise degraded. The saved bundle and alert use the final stall length and turn critical once it reaches `StallCriticalMs`. A world that has still not run the task is described as "still frozen when the bundle was saved". |
| `TICK_DEGRADED` | Average tick time of each world, from Hytale, against the target TPS of that world. | The average reaches the degraded limit. With `TickAvgDegradedMs` at `0`, the Auto default, that is 3 times the world [tick budget](/mod/hybox/reading-a-report#tick-budget), so 100 ms at 30 TPS. A value above `0` is a fixed limit in ms. | Critical at the critical limit or more, otherwise degraded. Auto puts it at 7.5 times the budget, so 250 ms at 30 TPS, and `TickAvgCriticalMs` above `0` fixes it in ms. |
| `WORLD_FAILURE` | Worlds that Hytale removes because of an exception, outside shutdown. | Such a world is removed. It has no switch. | Critical |
| `DEADLOCK` | Deadlocked threads, from the JVM. | The set of deadlocked threads changes and is not empty. | Critical |
| `HEAP_PRESSURE` | Old-generation heap pool use after the last GC, as a share of its maximum. Without a pool named old or tenured, the first heap pool. | Use stays at `HeapPressurePct` or more for `HeapPressureSustain`. | Critical at 97% or more, otherwise degraded. |
| `GC_PRESSURE` | Share of wall time in stop-the-world GC pauses. Collectors named with `cycles` or `concurrent` are not counted. | The share over `GcPressureWindow` reaches `GcPressurePct`, once samples cover 80% of the window. | Degraded |
| `CPU_SATURATION` | CPU use of the server process. | Use stays at `CpuSaturationPct` or more for `CpuSaturationSustain`. | Degraded |
| `NET_SATURATION` | Highest read rate and highest write rate of any network interface, from JFR every 5 s, ignoring samples older than 15 s. | Inbound stays at `NetInMbps` or more, or outbound at `NetOutMbps` or more, for `NetSustain`. | Degraded |
| `PLAYER_DROP` | Players online, from the server's count of connected players, against the peak within `PlayerDropWindow`. Players moving between worlds or still loading count. | The peak is at least `PlayerDropMinPlayers` and the count is `PlayerDropPct` percent or more below it. | Degraded |
| `LOG_ERROR` | `SEVERE` server log records, except Hybox's own. | A record passes the [filters](#log-error-filters). | Degraded |
| `MANUAL` | `/hybox dump`, the dashboard and profiles. | Always | Info |
| `UNCLEAN_SHUTDOWN` | A bundle rebuilt at startup from a crashed run. | Always | Degraded |

Deadlock, heap, GC, CPU and network are checked every 5 s. Player drop is checked every `Jfr.SampleInterval`.

Each detector fires once, then re-arms: tick, heap, GC, CPU and network below 90% of the threshold, heartbeat when the world runs the task again, player drop when the count is no longer a drop.

The scope groups triggers for debounce:

| Scope | Triggers |
| --- | --- |
| The world name | `HEARTBEAT_STALL`, `TICK_DEGRADED`, `WORLD_FAILURE` |
| `jvm` | `DEADLOCK`, `HEAP_PRESSURE`, `GC_PRESSURE`, `CPU_SATURATION` |
| `net` | `NET_SATURATION` |
| `server` | All others |

### Log error filters

A `SEVERE` record fires `LOG_ERROR` only if all of these hold:

1. If `LogErrorRequireThrowable` is on, the record has an exception.
2. If `LogErrorSkipSentry` is on, Hytale has not marked the exception to skip Sentry.
3. No pattern in `LogErrorIgnore` matches the error text: `<exception class>: <message>`, or the first log line, cut to 200 characters.
4. The same error did not fire within `LogErrorDedupeWindow`. Errors are the same when exception class, message and top stack frame match, or logger and first line match.

## Before a capture

Hybox checks each trigger in this order. The two captures of a profile, the lead-up with `--keep-buffer=false` and the bundle at the end, skip the first two checks.

| Check | Rejects when |
| --- | --- |
| Cooldown | Any trigger was accepted within `Cooldown`, unless the kind is in `CooldownExemptKinds`. Manual captures count. |
| Debounce | The same kind and scope was accepted within `Debounce`. |
| One at a time | A capture, including its post-incident wait, is running. Automatic triggers are dropped. Manual captures reply `A capture is already running. Try again when it ends.` A profile started now is queued and starts once the bundle is saved. |

## Severity

| Severity | Before the bundle is written |
| --- | --- |
| Degraded | Hybox keeps recording until the problem clears or `Jfr.PostIncidentMaxWait` passes, and takes up to `Jfr.ThreadDumpCount` thread dumps, `Jfr.ThreadDumpInterval` apart. Heartbeat, tick and GC triggers end the wait when they recover. Others wait the full time. A player with `.dump` can skip the wait from the dashboard. |
| Critical, info | Hybox writes the bundle at once, with one thread dump if `Jfr.ThreadDumpCount` is above 0. |
