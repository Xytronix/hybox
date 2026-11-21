package io.github.xytronix.hybox.core.capture;

import io.github.xytronix.hybox.core.trigger.TriggerEvent;

@FunctionalInterface
public interface PostIncidentWaiter {

    void awaitResolution(TriggerEvent event);

    default TriggerEvent settle(TriggerEvent event) {
        return event;
    }

    static PostIncidentWaiter none() {
        return event -> { };
    }
}
