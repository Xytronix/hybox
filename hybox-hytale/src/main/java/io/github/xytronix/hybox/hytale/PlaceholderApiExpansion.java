package io.github.xytronix.hybox.hytale;

import at.helpch.placeholderapi.events.ExpansionsLoadedEvent;
import at.helpch.placeholderapi.expansion.PlaceholderExpansion;
import com.hypixel.hytale.event.EventRegistration;
import com.hypixel.hytale.server.core.HytaleServer;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import java.util.List;
import java.util.function.Function;

public final class PlaceholderApiExpansion extends PlaceholderExpansion implements AutoCloseable {
    private final Function<String, String> values;
    private final String version;
    private final List<String> placeholders;
    private volatile EventRegistration<?, ?> reloads;

    private PlaceholderApiExpansion(Function<String, String> values, String version, List<String> placeholders) {
        this.values = values;
        this.version = version;
        this.placeholders = List.copyOf(placeholders);
    }

    public static AutoCloseable open(Function<String, String> values, String version, List<String> placeholders) {
        PlaceholderApiExpansion expansion = new PlaceholderApiExpansion(values, version, placeholders);
        expansion.reloads = HytaleServer.get().getEventBus()
            .registerGlobal(ExpansionsLoadedEvent.class, event -> expansion.reregister());
        expansion.register();
        return expansion;
    }

    private void reregister() {
        if (!isRegistered()) register();
    }

    @Override
    public String getIdentifier() {
        return "hybox";
    }

    @Override
    public String getAuthor() {
        return "Xytronix";
    }

    @Override
    public String getVersion() {
        return version;
    }

    @Override
    public List<String> getPlaceholders() {
        return placeholders;
    }

    @Override
    public boolean persist() {
        return true;
    }

    @Override
    public String onPlaceholderRequest(PlayerRef player, String params) {
        return values.apply(params);
    }

    @Override
    public void close() {
        EventRegistration<?, ?> registration = reloads;
        reloads = null;
        if (registration != null) registration.unregister();
        if (isRegistered()) unregister();
    }
}
