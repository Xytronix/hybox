package io.github.xytronix.hybox.core.jfr;

import jdk.jfr.Event;
import jdk.jfr.Label;
import jdk.jfr.Name;

/**
 * Lightweight marker event for verifying JFR captures in tests.
 */
@Name("io.github.xytronix.hybox.marker")
public final class HyboxMarkerEvent extends Event {
    @Label("Message")
    public String message;
}
