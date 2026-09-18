package io.github.xytronix.hybox.core.jfr;

import jdk.jfr.Category;
import jdk.jfr.Event;
import jdk.jfr.Label;
import jdk.jfr.Name;
import jdk.jfr.StackTrace;

@Name(HyboxPluginEvent.NAME)
@Label("Plugin Event")
@Category("Hybox")
@StackTrace(false)
public final class HyboxPluginEvent extends Event {
    public static final String NAME = "io.github.xytronix.hybox.PluginEvent";

    @Label("Category")
    public String category;

    @Label("Message")
    public String message;
}
