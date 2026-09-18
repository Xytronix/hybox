package io.github.xytronix.hybox.core.jfr;

import jdk.jfr.Category;
import jdk.jfr.Event;
import jdk.jfr.Label;
import jdk.jfr.Name;
import jdk.jfr.StackTrace;

@Name(HyboxHostCpuEvent.NAME)
@Label("Host CPU Sample")
@Category("Hybox")
@StackTrace(false)
public final class HyboxHostCpuEvent extends Event {
    public static final String NAME = "io.github.xytronix.hybox.HostCpu";

    @Label("Throttled periods")
    public long throttledPeriods;

    @Label("Throttled micros")
    public long throttledMicros;

    @Label("CPU total jiffies")
    public long cpuTotalJiffies;

    @Label("CPU steal jiffies")
    public long cpuStealJiffies;

    @Label("CPU iowait jiffies")
    public long cpuIowaitJiffies;

    @Label("Container CPU usage micros")
    public long cpuUsageUsec;
}
