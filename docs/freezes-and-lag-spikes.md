# Freezes and lag spikes

Diagnosis names the most likely cause of a freeze, of slow ticks and of GC pressure. This page says what to do for each one. Open the newest incident with `/hybox incident` and read Diagnosis first. If the cause is unclear or the bundle missed the moment, capture again with `/hybox profile lag`. See [Profiling](/mod/hybox/using-hybox#profiling).

## Freezes

### GC pause covering the stall

- **What to do**: all worlds stopped, not just one. Read [Memory and GC](/mod/hybox/memory-and-gc). The fix is [heap](/mod/hybox/reading-a-report#heap) size or the code that allocates the most, not the code that ran during the [GC pause](/mod/hybox/reading-a-report#gc-pause).
- **Capture again**: `/hybox profile memory` if the top allocator is unclear.

### Deadlock

- **What to do**: the [deadlocked](/mod/hybox/reading-a-report#deadlock) threads never recover. Restart the server.
- **Report it**: send the bundle to the author of the mod whose code holds the locks. A deadlock between two mods goes to both.

### World thread busy in one frame

The [world thread](/mod/hybox/reading-a-report#world-thread) ran the whole time, stuck in one piece of code. Top methods as a table and the Flame graph show that method at the top.

- **What to do**: if a mod owns the method, check its settings. If it is Hytale's own code, check Worlds and Key metrics for what players were doing at that moment.
- **Report it**: send the bundle to the mod author. For Hytale code, report it to Hytale with the bundle.

### World thread waiting for a lock

- **What to do**: the cause is the thread that held the lock. Look it up in CPU by thread and Thread states.
- **Report it**: send the bundle to the mod that owns the holding thread or the waiting code.
- **Capture again**: `/hybox profile lag` records [lock waits](/mod/hybox/reading-a-report#lock-wait) from 10 ms, so shorter waits show up too.

### Process got no CPU

The server was ready to run but the machine did not let it.

- **In the report**: System & hardware shows [CPU steal](/mod/hybox/reading-a-report#cpu-steal), CPU throttling, [swap](/mod/hybox/reading-a-report#swap) traffic, or a [load average](/mod/hybox/reading-a-report#load-average) of 2 or more per core. The world thread has few CPU samples. Often every world froze together.
- **What to do**: this is the host, not a mod. Ask your host about CPU limits, stop other heavy programs, or add RAM so the machine stops swapping.
- **Report it**: to your host, with the System & hardware section.

## Slow ticks and low TPS

Every [tick](/mod/hybox/reading-a-report#tick) costs too much. See [TPS and tick time](/mod/hybox/tps-and-tick-time) for the numbers. For GC pauses see [Memory and GC](/mod/hybox/memory-and-gc#gc-pauses). For machine CPU or swap see [Process got no CPU](#process-got-no-cpu).

### One engine system or mod over 30% of the budget

At 30 [TPS](/mod/hybox/reading-a-report#tps) that is 10 ms of the 33.3 ms [tick budget](/mod/hybox/reading-a-report#tick-budget).

- **In the report**: Engine systems shows the system and its share of the tick. CPU & load shows the mod and its [own share](/mod/hybox/reading-a-report#own-share).
- **What to do**: for an engine system, find what feeds it. NPC systems grow with mob count, entity systems with entity count. Worlds shows which world has the most. For a mod, check its settings or compare on a test server without it.
- **Report it**: a mod over 30% with default settings is worth a report to its author.

### Chunk generation or loading spike

- **In the report**: the chunks generated or loaded chart in Key metrics jumps at the stall. In CPU & load, World generation leads CPU by engine subsystem and ["Not tied to one world"](/mod/hybox/reading-a-report#not-tied-to-one-world) is large, because Hytale's chunk generator runs on its own worker threads.
- **What to do**: lower `MaxViewRadius` in the server's `config.json`, 32 by default, so each player needs fewer chunks. Spread out teleports and mass travel to new areas.

## GC pressure

- **Top allocator**: Memory & GC names the code that allocated the most. Report it to its mod author if it is a mod.
- **Rising [post-GC floor](/mod/hybox/reading-a-report#post-gc-floor)**: a heap that never comes back down can be a [leak](/mod/hybox/reading-a-report#leak). Run `/hybox profile memory --minutes=30` and read [Memory and GC](/mod/hybox/memory-and-gc#leaks).
