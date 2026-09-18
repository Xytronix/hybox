package io.github.xytronix.hybox.core.jfr;

import jdk.jfr.Category;
import jdk.jfr.Event;
import jdk.jfr.Label;
import jdk.jfr.Name;
import jdk.jfr.StackTrace;

@Name(HyboxDiskEvent.NAME)
@Label("Disk Space Sample")
@Category("Hybox")
@StackTrace(false)
public final class HyboxDiskEvent extends Event {
    public static final String NAME = "io.github.xytronix.hybox.Disk";

    @Label("Free bytes")
    public long freeBytes;

    @Label("Total bytes")
    public long totalBytes;

    @Label("Read bytes")
    public long readBytes;

    @Label("Write bytes")
    public long writeBytes;
}
