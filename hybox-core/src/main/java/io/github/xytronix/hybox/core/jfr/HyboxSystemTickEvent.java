package io.github.xytronix.hybox.core.jfr;

import jdk.jfr.Category;
import jdk.jfr.Event;
import jdk.jfr.Label;
import jdk.jfr.Name;
import jdk.jfr.StackTrace;

@Name(HyboxSystemTickEvent.NAME)
@Label("System Tick Sample")
@Category("Hybox")
@StackTrace(false)
public final class HyboxSystemTickEvent extends Event {
    public static final String NAME = "io.github.xytronix.hybox.SystemTick";

    @Label("World")
    public String world;

    @Label("System")
    public String system;

    @Label("Avg ms")
    public double avgMs;
}
