# Reading a report

`report.html` opens on the incident: what fired, when, and in which world. This page explains what the report does not say on screen.

## Evidence and next steps

Evidence lists only results that crossed a threshold. Each line names its threshold, for example "above the 90% threshold". A result under its threshold is left out, so an empty Evidence card means nothing stood out.

| Evidence line | Shown when |
| --- | --- |
| World CPU share | One world used more than 50% of the CPU samples. |
| Most sampled method | One method was in 10% or more of the CPU samples. |
| GC pauses | The longest pause was 50 ms or more, or GC pauses took 5% or more of the time. |
| Machine CPU | The machine's CPU averaged 90% or more. |
| Server memory (RSS) | The server process held 80% or more of the machine RAM or container limit. |
| Heap | The heap peaked at 90% or more of its maximum. |
| Exceptions | One interval near the end had more than 5 times the average number of exceptions. |
| Heap limit | `-Xmx` is more than 90% of the container memory limit. |

Some lines have no threshold because they are facts: deadlocked or blocked threads, mods built for another Hytale version, Mixin errors, and the code that allocated the most memory.

Each next step names the section to open and what to look for there.

## Tick time and TPS

Hytale runs 30 ticks per second (TPS). One tick then has 33.3 ms. Tick time is how long a tick took.

- **Steady lag**: read the average tick time. An average near or over 33.3 ms means the server cannot keep up, and TPS drops below 30.
- **Freezes**: read the worst tick. Each point on the tick chart is an average over a short interval, so a single slow tick looks smaller on the chart than it was. The worst tick shows its real length.

## Baseline and incident

The Baseline table compares two windows. Baseline is the first 90% of the recording. Incident is from just before the marker to the end. A change under 5% is not marked.

## CPU samples

JFR looks at the running threads at a fixed interval and notes the method each one is in. Each note is a CPU sample. A method in many samples used a lot of CPU. Threads that wait or are blocked use no CPU, so they rarely appear in Hot methods or Hot threads. Thread states shows them.

## Flame graph

Each box is a method. Width is the share of CPU samples that passed through it. Each row is one call deeper. Click a box to zoom into it.

## Engine systems

Hytale measures the time each engine system took per tick. This is exact time, not sampling, and includes time spent waiting on locks and disk. The share of the 33.3 ms tick shows which system costs the most.

## Memory

- **Allocated memory** is all memory created during the recording. GC frees most of it. A high rate means more GC work, not memory in use.
- **Long-lived objects** are sampled objects that stayed alive for a long time. A class that keeps growing there can be a leak. Counts are sampled, not exact.
- **Heap histogram** lists every object on the heap at capture, by class. It is only there when `Capture.HeapHistogram` is on.
- **Server memory (RSS)** can be lower than the heap when the system compresses or swaps memory.

## Plugin metrics

Numbers that plugins report through the Hybox API. See [Plugin API](plugin-api.md) for how counters and gauges are drawn.
