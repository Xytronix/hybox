package io.github.xytronix.hybox.core.jfr;

import jdk.jfr.Category;
import jdk.jfr.Event;
import jdk.jfr.Label;
import jdk.jfr.Name;
import jdk.jfr.StackTrace;

@Name(HyboxWorldTickEvent.NAME)
@Label("World Tick Sample")
@Category("Hybox")
@StackTrace(false)
public final class HyboxWorldTickEvent extends Event {
    public static final String NAME = "io.github.xytronix.hybox.WorldTick";

    @Label("World")
    public String world;

    @Label("TPS")
    public double tps;

    @Label("MSPT")
    public double mspt;

    @Label("Players")
    public int players;

    @Label("Entities")
    public int entities;

    @Label("Chunks")
    public int chunks;

    @Label("Chunks generated (total)")
    public long chunksGeneratedTotal;

    @Label("Chunks loaded (total)")
    public long chunksLoadedTotal;

    @Label("Avg ping")
    public double avgPingMs;
}
