package io.github.xytronix.hybox.core.jfr;

import jdk.jfr.Category;
import jdk.jfr.Event;
import jdk.jfr.Label;
import jdk.jfr.Name;
import jdk.jfr.StackTrace;

@Name(HyboxPluginMetricEvent.NAME)
@Label("Plugin Metric")
@Category("Hybox")
@StackTrace(false)
public final class HyboxPluginMetricEvent extends Event {
    public static final String NAME = "io.github.xytronix.hybox.PluginMetric";

    @Label("Name")
    public String name;

    @Label("Value")
    public double value;

    @Label("Counter")
    public boolean counter;
}
