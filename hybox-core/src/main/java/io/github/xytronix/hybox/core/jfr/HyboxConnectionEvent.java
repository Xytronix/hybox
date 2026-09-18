package io.github.xytronix.hybox.core.jfr;

import jdk.jfr.Category;
import jdk.jfr.Event;
import jdk.jfr.Label;
import jdk.jfr.Name;
import jdk.jfr.StackTrace;

@Name(HyboxConnectionEvent.NAME)
@Label("Connection")
@Category("Hybox")
@StackTrace(false)
public final class HyboxConnectionEvent extends Event {
    public static final String NAME = "io.github.xytronix.hybox.Connection";

    @Label("Player")
    public String player;

    /** connect | join | leave | setup disconnect */
    @Label("Phase")
    public String phase;

    /** World name on join, disconnect reason on leave/setup disconnect. */
    @Label("Detail")
    public String detail;
}
