# Reading a report

`report.html` opens on the incident: what fired, when, and in which world. This page explains what the report does not say on screen.

Under the headline is a row of four numbers: tick time, TPS, CPU and heap. A number turns red or orange only at its target: tick time at or over 33.3 ms, TPS under 90% of 30, machine CPU at 90% or more, or heap at 90% or more of its maximum. Tick time and TPS describe the incident world. If that world stayed at or above 90% of the target while another world averaged at least 1 TPS lower and fell under it, they describe that other world and the label names it, for example "TPS in default".

## Sharing a report

The header shows the size of the incident zip. Copy summary copies a short text that fits one Discord message: the headline, the four numbers, the diagnosis and the three mods that used the most CPU in their own code. Copy full report as text, at the foot of the sidebar, copies the whole report for a GitHub issue.

## Diagnosis

Diagnosis comes right after the number row. For an automatic incident it names one cause, with up to two lines on why and up to three next steps. Every other finding is one line under Other findings, folded until you open it. A manual capture or profile has no cause. It lists up to three notable findings instead, or says that nothing crossed a threshold.

The cause carries how sure Hybox is: clear, likely, or no clear cause. With no clear cause the line names the two strongest signals, and the next step is to capture the next freeze with `/hybox profile lag`. The game shows the same diagnosis in its Diagnosis tab.

Hybox tries the rules for the trigger in order and stops at the first that fits:

| Trigger | Rules, first match wins |
| --- | --- |
| Freeze | 1. GC pauses cover 50% or more of the freeze. 2. Java found a deadlock. 3. The world thread was running: one method that is not a thread's run loop has 20 or more of the world thread's CPU samples in the freeze and at least half of them. 4. The world thread waited on a lock for 50% or more of the freeze. 5. The process got no CPU: no CPU sample from any thread during the freeze, or a load average of 2 or more per core. 6. No clear cause. |
| Slow ticks | 1. GC pauses take 50% or more of the worst tick. 2. One engine system, or one mod's own code on the world thread, takes 30% or more of the tick budget over the last minute. For a single very slow tick Diagnosis names that tick, such as "One WorldConfigSaveSystem tick took 4 s". 3. Chunk generation or loading jumps to 5 times its average at the worst tick. 4. The machine is short of CPU at 90% or more, of RAM with 256 MiB or more through swap, or of cores at a load of 2 or more per core. 5. No clear cause. |
| GC or heap pressure | The mod whose own code allocated 10% or more of the sampled memory, or else the engine subsystem that allocated the most. |

A small "i" after a line shows its threshold and how it was measured, for example "Pauses shown from 50 ms, total GC time from 5%." for GC pauses. Words with a dotted underline explain themselves on hover or keyboard focus and link to [Terms](#terms).

Lines that count CPU samples need at least 300 of them, or 300 allocation samples for memory. Mod lines use only the mod's [own code](#cpu-and-memory-by-mod). Hytale, the JDK and Hybox's work for a capture after its trigger, shown as "Hybox, this capture" in the tables, never get one.

Other findings only appear above a threshold:

| Line | Shown when |
| --- | --- |
| TPS dip | The lowest TPS was under 90% of the target, 27 at 30 TPS. |
| GC pause during the freeze | A GC pause overlaps a heartbeat stall, counted from 1 s before it, or the interval of the worst tick. |
| Lock wait during the freeze | A world thread waited on locks for 50% or more of its world's heartbeat stall, or of the interval of the worst tick. Only waits of 500 ms or more count. Time inside a GC pause does not count. |
| Swap | On Linux, the machine moved 256 MiB or more between RAM and swap in the window. On other systems, swap in use grew by 256 MiB or more in the window. |
| Load average | The 1-minute load average at capture was 2 or more per core. |
| Slow file operation | One file read or write took 250 ms or more. The line names the file and its kind, never the folder. |
| Disk nearly full | The server disk had under 5% or under 2 GiB free. |
| World CPU share | One world used more than 50% of the CPU samples, counting every thread of that world. Left out when that world is the only one with players. |
| Most sampled method | One method was in 10% or more of the CPU samples. No line when the top method is a thread's run loop, such as `TickingThread.run`. |
| Mod CPU | One mod's own code ran in 10% or more of the CPU samples. |
| Mod allocation | One mod's own code allocated 10% or more of the sampled memory. |
| GC pauses | The longest pause was 50 ms or more, or GC pauses took 5% or more of the time. |
| Machine CPU | The machine's CPU averaged 90% or more. |
| Server memory (RSS) | The server process held 80% or more of the machine RAM or container limit. |
| Heap | The heap peaked at 90% or more of its maximum. |
| Post-GC floor | The heap left after GC rose by 10% of the heap limit or more between the first and last third of the window, and ended at half the limit or more. |
| Exceptions | One interval near the end had more than 5 times the average number of exceptions. |
| Heap limit | `-Xmx` is more than 90% of the container memory limit. |

Some lines have no threshold because they are facts: deadlocked or blocked threads, mods built for another Hytale version and Mixin errors. The code that allocated the most memory only shows next to a GC or heap line.

### Compared with history

Diagnosis compares each bundle with this server's past.

- Usual for this server: with `Metrics.Enabled` on, the comparison table adds the usual value for this time of day over the last 7 days. A value is marked unusual when the incident is 25% or more past it. The column needs this time of day on 3 of the last 7 days. Until then the report says "Not enough history yet".
- Similar incidents: earlier bundles still in `incidents/` with the same trigger, or the same world for freezes, slow ticks and world failures, counted for the last 7 and 30 days. Manual captures are left out.

## Tick time and TPS

Read the average tick time for steady lag and the worst tick for freezes. [TPS and tick time](/mod/hybox/tps-and-tick-time) explains both. The tick chart draws the slowest tick of each interval as a lighter line, the 33.3 ms budget as a dashed line, and the TPS chart draws the 30 TPS target. When the number row moved to the slowest world, the tick chart becomes "Tick time by world", with a line for that world and one for the incident world. Charts whose value never changed in the whole window sit behind one "charts without change" line that names each value, for example "GC pause: 0 ms the whole time."

## Charts

Hover a chart to see the values at that time. Click to keep them open with the full text of every event, the four numbers at that moment and Busiest code here: the three methods with the most CPU samples in the 5 seconds around that point, each with its share and mod. With fewer than 20 samples the report says so. Drag across a chart to zoom in and double-click to zoom out. Zoom to ±30 s shows one minute around a point. Keys act on the chart under the pointer: + and - zoom, 0 shows the whole window, the arrow keys move through time and Enter keeps the values open.

The event row lists each kind of event with its count. Turn one kind off to hide its lines on every chart.

Every chart starts at 0. Its top follows the highest value, so a spike is drawn to scale, as long as that value is at most twice the limit. The limit is one and a half times the 98th percentile, or 15% above the budget or target line when that is higher. Only a value above twice the limit is cut off. The top then sits at the limit, the value gets a small triangle and it is named once, for example "117.2 ms off scale". The TPS chart always tops out 15% above its target. The tooltip always shows the real value.

Tick time, GC pause, allocation rate, network, heap, loaded chunks and sections and queued packets switch to a log scale when the highest value is at least 100 times the median of the values above 0. A 2000 ms freeze next to 5 ms ticks then fits with nothing cut off. Grid lines sit at 1, 2 and 5 in each decade, the budget line stays at its value, values of 0 sit on the bottom line and the axis says "log scale" next to the unit. TPS, CPU, shares, players online and entity counts always use a linear scale. In `report.html` and `trend-report.html` each of these charts has a Linear and Log switch that starts on the automatic choice. In game the choice is always automatic.

A chart whose value stays the same is shown as one line of text instead, for example "Players online: 1 the whole time." This applies in game and in `trend-report.html`.

Worlds lists the view radius, hot radius and loaded sections of each world, and Top players by loaded sections names up to five masked players per world.

## System

Java version, architecture, processor and operating system come from the recording, not from the machine that built the report. A bundle rebuilt after a crash leaves out load average, open files, CPU affinity, stall times and container memory, because they would describe the restart, not the incident.

## Captured diagnostics

Captured diagnostics holds the stalled thread, Thread states and other cards from the moment of capture. Heartbeats names each world that was a second or more late. Thread progress lists a thread only when it should have moved and did not.

## Baseline and incident

The Baseline table compares two windows. Baseline is the first 90% of the recording. Incident is from just before the marker to the end. A change of 5% or more in the direction that hurts is marked worse. TPS and disk free hurt when they drop, the rest when they rise. Players, chunks generated, chunks loaded and network traffic are never marked, and nothing is marked better.

## CPU samples

A [CPU sample](#cpu-sample) notes the method a running thread is in, taken at a fixed interval. Threads that wait or are blocked use no CPU, so they rarely appear in the method table or CPU by thread. Thread states shows them. CPU & load has CPU by engine subsystem, by mod and by thread.

A world's CPU counts the threads Hytale runs for that world: its world thread, its chunk lighting thread and its world map thread. Every other thread is listed as "Not tied to one world".

The flame graph shows where the CPU samples were spent. [Flame Graphs](https://www.brendangregg.com/flamegraphs.html) explains how to read one. Click a box to zoom into it. Top methods as a table, under the graph, lists the most sampled methods.

Engine systems shows the time Hytale measured for each engine system per tick. This is exact time, not sampling, and includes time spent waiting on locks and disk. The share of the 33.3 ms tick shows which system costs the most.

## Lock waits

Lock waits in CPU & load lists waits of 500 ms or more for a lock that another thread held: `synchronized` blocks, `ReentrantLock`, `ReentrantReadWriteLock` and `StampedLock`. For world threads it also lists waits for other work, such as a future. Rows show the thread, the lock, the total wait, the number of waits and the code that waited with its mod, by the same rule as self in CPU by mod. It covers the whole window, not only the moment of capture.

## CPU and memory by mod

Each mod shows one number, its cost: time or memory in the mod's own code, including the JDK and library calls it makes. Hovering the number, or opening Advanced under the table, gives one sentence on how often the mod's code was in the call chain, for example "Refixes code was in the call chain of 29.7% of samples, mostly Hytale's indexed storage chunk saver that runs through its code. This is not its cost." A Mixin wrapper around a thread's whole run loop, such as Refixes around `TickingThread.run`, does not count there. The line under a mod names the method its own code ran in. Rows are sorted by cost, and rows under 0.05% are left out.

Code that no plugin class loader ran is shown by its package, such as `cc.irori.refixes`. Hybox gives it to a plugin when that plugin's main class is in the same package, and shows Hyinit's own package as Hyinit, the loader. The line under a mod also names the threads its own code ran on.

## Memory

- **Allocated memory** is all memory created during the recording. GC frees most of it, so a high [allocation rate](#allocation-rate) means more GC work, not memory in use.
- **Long-lived objects** are sampled objects that stayed alive for a long time. A class that keeps growing there can be a leak. Counts are sampled, not exact. The allocation site is the first method on the stack that is not JDK or library code, the same rule as CPU by mod, with its mod. Sites need `Jfr.OldObjectSampling` on, otherwise the report says how to turn it on.
- **Heap histogram** lists every object on the heap at capture, by class. It is only there when `Capture.HeapHistogram` is on.
- **Server memory (RSS)** can be lower than the heap when the system compresses or swaps memory.

## Plugin metrics and mod events

Numbers that plugins and mods report through the Hybox API or as JFR events. See [Plugin API](/mod/hybox/plugin-api) for how counters and gauges are drawn. When the only series are Hybox's own, the section starts folded.

## Terms

Words with a dotted underline in Diagnosis link here. The report and the game show the start of each entry as a tooltip. The guides [TPS and tick time](/mod/hybox/tps-and-tick-time), [Freezes and lag spikes](/mod/hybox/freezes-and-lag-spikes) and [Memory and GC](/mod/hybox/memory-and-gc) use these words in context.

### Tick

One game update, in which a world moves entities, runs its systems and sends changes to players. Each world ticks on its own, one tick after another.

### Tick time

How long one tick took, in milliseconds. A tick that takes longer than the tick budget makes the world fall behind.

### Tick budget

The time one tick may take to keep the TPS target, 33.3 ms at 30 TPS. A world whose ticks often go over it loses TPS.

### TPS

Ticks per second, how many game updates a world finishes each second. Under the target, players see mobs, blocks and other players move late.

### Target TPS

The TPS a world aims for, 30 by default. A mod or `/world tps` can change it per world, so Hybox compares each world with its own target and draws the budget of the world shown. Worlds lists the target of each world when they differ.

### p95

The value 95% of the samples stayed under. It shows how bad the slow ticks were without letting one outlier decide.

### Worst tick

The slowest single tick in the recording. A freeze is one very long tick, so the worst tick shows it where the average hides it.

### World thread

The thread that runs all game logic of one world, one tick after another. When it is busy or waiting, that world freezes while other worlds may keep going.

### Freeze

A time when a world stopped ticking, so players saw nothing move. Hybox calls it a freeze when the world's heartbeat is late by more than the stall threshold.

### Not tied to one world

Threads Hybox cannot tie to one world from their name, such as chunk generator workers, network threads and plugin threads. Their CPU counts in no world's share, even when the work was for one world.

### CPU sample

A snapshot of what a running thread was doing, taken every few milliseconds. A share of the samples is a share of CPU time. Waiting threads use no CPU and rarely show up.

### Own share

The CPU or memory spent in a mod's own code, not in the engine code it calls. This is what the mod costs, and the number Diagnosis uses. See [CPU and memory by mod](#cpu-and-memory-by-mod).

### Call-chain share

The samples where a mod's code was anywhere in the call chain, including engine work it only passed through. It is not the mod's cost, so Diagnosis never names a mod for it. See [CPU and memory by mod](#cpu-and-memory-by-mod).

### Lock wait

Time a thread waited for a lock, a guard that lets one thread at a time use some data. A world thread that waits on a lock freezes until the holder lets go.

### Deadlock

Threads that each wait for a lock another one holds, so none of them can ever go on. The server does not recover from it and needs a restart.

### Heap

The memory Java keeps the server's objects in, capped by `-Xmx`. It fills up between garbage collections and drops after each one, so a high peak alone is normal.

### Xmx

The Java start option `-Xmx`, which sets the largest heap the server may use. Raising it helps when the heap is full, but only up to the free RAM of the machine.

### GC pause

A stop of every server thread while Java's garbage collector frees memory. Short pauses are normal. Long ones freeze every world at once.

### Post-GC floor

The heap still in use right after a garbage collection, the memory the server really holds. A floor that keeps rising can mean a leak.

### Allocation rate

How much new memory the server asks for to create objects, which GC frees later. A high rate means more GC work, not more memory in use.

### Allocation churn

Many short-lived objects created and thrown away, which makes GC run often. The code with the most churn is in the allocation tables of Memory & GC.

### Leak

Memory the server keeps holding after it no longer needs it, so the heap only grows. It ends in long GC pauses or an out of memory crash.

### RSS

All memory the server process holds in RAM, the heap and everything else. When it nears the RAM of the machine or the container limit, the system swaps or kills the server.

### Swap

Disk space the system uses as slow extra memory when RAM runs out. A server whose memory is swapped out stalls while the disk reads it back.

### CPU steal

Time the host of a virtual machine gave this machine's CPU to other guests. High steal means a busy host, and only the hosting provider can fix it.

### Load average

How many programs wanted a CPU core at the same time, averaged over one minute. At 2 or more per core, every program, the server too, waits for a free core.

### Mixin

A patch a mod applies to Hytale's own code when the server starts. Two mods that patch the same method can break each other.

### View radius

How far, in chunks, the server sends the world to a player. It is the smaller of the client setting and `MaxViewRadius` in the server `config.json`. A larger view radius loads and generates more chunks for each player.

### Hot radius

The distance, in chunks, around a player where blocks, fluids and farming tick. Hytale uses 8 and only mods lower it. A larger hot radius costs world thread time.

### Loaded sections

The chunk sections a player has loaded. A chunk section is a 32 block cube of a chunk column. The sum over all players shows how much of the world the players keep in memory.
