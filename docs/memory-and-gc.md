# Memory and GC

Most memory questions come down to one choice: add RAM, or fix what uses it. This page shows how to tell from a Hybox report. Linked words are explained under [Terms](/mod/hybox/reading-a-report#terms).

## Heap goes up and down

The [heap](/mod/hybox/reading-a-report#heap) climbs as the server allocates and drops when GC runs. The [Java 25 GC Tuning Guide](https://docs.oracle.com/en/java/javase/25/gctuning/) explains how the collectors work. The [post-GC floor](/mod/hybox/reading-a-report#post-gc-floor), which Hybox shows as the lowest heap use in the window, says what the server keeps.

| Peak | Floor | Max | What it means |
| --- | --- | --- | --- |
| 5.6 GiB | 2.1 GiB | 6 GiB | Fine. GC has plenty of room. |
| 5.9 GiB | 5.3 GiB | 6 GiB | Tight. GC runs often and frees little. Pauses get longer. |

## GC pauses

At 30 [TPS](/mod/hybox/reading-a-report#tps) a [tick](/mod/hybox/reading-a-report#tick) has a [budget](/mod/hybox/reading-a-report#tick-budget) of 33.3 ms, so a 50 ms [GC pause](/mod/hybox/reading-a-report#gc-pause) costs more than one tick in every world at once.

- **Few long pauses with a high floor**: the heap is too full. GC works hard to find free space.
- **Many short pauses with a low floor**: something allocates too fast. This is [allocation churn](/mod/hybox/reading-a-report#allocation-churn). The heap is not full, but GC runs all the time.

## When more RAM helps

Raise [`-Xmx`](/mod/hybox/reading-a-report#xmx) when all of these are true:

- The floor is high and flat, such as 5.3 of 6 GiB, and it stays there over days.
- The load grew to match, such as more players or more loaded chunks.
- The machine has free RAM for it. Keep `-Xmx` well below the free RAM.

In a container, keep `-Xmx` at about 75% of the container limit. Java needs memory outside the heap too. Diagnosis warns when `-Xmx` is over 90% of the limit, because the container can kill the server.

## When more RAM does not help

| Situation | How it shows | Why RAM does not fix it |
| --- | --- | --- |
| [Leak](/mod/hybox/reading-a-report#leak) | The floor climbs and never comes back down | A bigger heap only delays the crash |
| Allocation churn | High [allocation rate](/mod/hybox/reading-a-report#allocation-rate), low floor, many short pauses | The heap was never full. GC keeps running as often |
| No free RAM on the machine | [Swap](/mod/hybox/reading-a-report#swap) traffic, [RSS](/mod/hybox/reading-a-report#rss) at 80% or more of RAM | A bigger heap pushes more into swap |

Swap is worse than a full heap. When part of the heap sits in swap, GC has to read it back from disk, and every thread waits for it.

## Leaks

1. Run `/hybox trend --days=7` and look at the heap chart. It needs `Metrics.Enabled`, on by default. A leak shows as low points that rise day after day.
2. Run `/hybox profile memory --minutes=30` while the heap climbs. It records more allocation samples and, when it ends, the chain of references that keeps each long-lived object alive.
3. Open Long-lived allocations in the new bundle. The path to the GC root names the field and the mod that holds the object.
4. Send the bundle to that mod's author. See [Sharing a bundle](/mod/hybox/using-hybox#sharing-a-bundle).

The end of a memory profile pauses the server for up to about 1 second while Java walks the heap. `/hybox histogram` also pauses the server, often for seconds.

See [Memory](/mod/hybox/reading-a-report#memory) and [CPU and memory by mod](/mod/hybox/reading-a-report#cpu-and-memory-by-mod) for every field.
