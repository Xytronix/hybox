package io.github.xytronix.hybox.core.capture;

import io.github.xytronix.hybox.core.trigger.TriggerEvent;

@FunctionalInterface
public interface PostIncidentWaiter {

    void awaitResolution(TriggerEvent event);

    static PostIncidentWaiter none() {
        return event -> { };
    }
}
