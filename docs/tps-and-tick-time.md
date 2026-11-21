# TPS and tick time

[TPS](/mod/hybox/reading-a-report#tps) and [tick time](/mod/hybox/reading-a-report#tick-time) are the first two numbers in every Hybox report.

## One loop per world

Each world runs its own loop on its own [world thread](/mod/hybox/reading-a-report#world-thread), named `WorldThread - <world>`. A world with no players still ticks.

Each pass of the loop is a [tick](/mod/hybox/reading-a-report#tick). A world aims for 30 ticks per second, its [target TPS](/mod/hybox/reading-a-report#target-tps), so each tick has a [tick budget](/mod/hybox/reading-a-report#tick-budget) of 1000 / 30 = 33.3 ms.

When a tick finishes early, the world sleeps for the rest of its 33.3 ms. When a tick takes longer, the next one starts right away. Hytale does not run extra ticks later to catch up. The lost time is gone, so players see the world slow down.

The rate can be changed per world with `/world tps <rate>`. The charts draw the budget line for the server's own rate, so a server at 20 TPS gets a 50 ms line.

## How TPS is calculated

Hytale keeps the length of every tick for the last 5 minutes. TPS is one second divided by the average tick time of the last 10 seconds, and never more than the target.

| Average tick time | TPS at a 30 TPS target |
| --- | --- |
| 12 ms | 30 |
| 33.3 ms | 30 |
| 40 ms | 25 |
| 66.7 ms | 15 |
| 100 ms | 10 |

So TPS stays at 30 until the average passes the budget. A server at 30 TPS can be using 5% of its budget or 99%. Tick time tells you how much room is left. TPS does not.

`/world perf` shows Hytale's own numbers for the last 10 seconds, 1 minute and 5 minutes. Hybox reads the same data.

## Average, median, p95 and worst

The number row and the tick chart show the average. Worlds shows three more tick times per world, from the last 5 minutes before capture.

| Number | What it is | Use it for |
| --- | --- | --- |
| Average | Mean of the ticks in each interval | Steady lag. Near or over 33.3 ms means the world cannot keep up. |
| Median | Half the ticks were faster than this | A normal tick, without spikes pulling it up. |
| [p95](/mod/hybox/reading-a-report#p95) | 95% of ticks were this fast or faster | Regular hitches that the average hides. |
| Worst | The slowest single tick | Freezes and one-off spikes. |

Two examples from the same 5 minutes:

| Median | p95 | Worst | What it means |
| --- | --- | --- | --- |
| 9 ms | 14 ms | 2,400 ms | Healthy world with one freeze. Look at what happened at that moment. |
| 41 ms | 58 ms | 95 ms | Steady lag. Every tick is too slow. TPS is about 24. |

One 2.4 s tick among 9,000 normal ones barely moves the average. That is why a [freeze](/mod/hybox/reading-a-report#freeze) can show 30 TPS and why the tick chart draws the [worst tick](/mod/hybox/reading-a-report#worst-tick) of each interval as a lighter line. See [Tick time and TPS](/mod/hybox/reading-a-report#tick-time-and-tps).

[Triggers](/mod/hybox/triggers) lists when Hybox saves a bundle.

## Why one world can lag alone

Every world has its own thread, so a slow world does not hold the others back while the machine has CPU to spare. A farm of 2,000 entities in `default` makes `default` slow. `creative` keeps its 30 TPS.

Use this to narrow the cause:

- **One world slow, others fine**: the cause is inside that world. Look at its entities, chunks and players in Worlds, and at Engine systems for that world.
- **All worlds slow at the same time**: the cause is shared. Look at [GC pauses](/mod/hybox/reading-a-report#gc-pause) in Memory & GC and at machine CPU, [swap](/mod/hybox/reading-a-report#swap) and [load](/mod/hybox/reading-a-report#load-average) in System & hardware. A GC pause stops every thread, and a busy machine slows every world.

A large ["Not tied to one world"](/mod/hybox/reading-a-report#not-tied-to-one-world) share next to a slow world can mean that chunk generation or a plugin's background work used the CPU the world thread needed. CPU by thread shows which threads it was.

## Quick checks

- TPS under 30 and average over 33.3 ms: steady lag. Read [Freezes and lag spikes](/mod/hybox/freezes-and-lag-spikes#slow-ticks-and-low-tps).
- TPS 30 but players report freezes: read the worst tick, not the average.
- Every world slow at once: start with [Memory and GC](/mod/hybox/memory-and-gc).
