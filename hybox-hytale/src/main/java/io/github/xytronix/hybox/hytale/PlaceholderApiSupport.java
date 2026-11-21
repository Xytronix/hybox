package io.github.xytronix.hybox.hytale;

import com.hypixel.hytale.common.plugin.PluginIdentifier;
import com.hypixel.hytale.server.core.plugin.JavaPlugin;
import com.hypixel.hytale.server.core.plugin.PluginBase;
import com.hypixel.hytale.server.core.plugin.PluginManager;
import com.hypixel.hytale.server.core.plugin.event.PluginSetupEvent;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Objects;
import java.util.function.Function;

final class PlaceholderApiSupport implements AutoCloseable {
    static final PluginIdentifier PLACEHOLDER_API = new PluginIdentifier("HelpChat", "PlaceholderAPI");
    private static final String EXPANSION = "io.github.xytronix.hybox.hytale.PlaceholderApiExpansion";

    private final Function<String, String> values;
    private final String version;
    private final System.Logger logger;
    private PluginBase attachedTo;
    private AutoCloseable expansion;
    private boolean closed;

    private PlaceholderApiSupport(Function<String, String> values, String version, System.Logger logger) {
        this.values = Objects.requireNonNull(values, "values");
        this.version = Objects.requireNonNull(version, "version");
        this.logger = Objects.requireNonNull(logger, "logger");
    }

    static PlaceholderApiSupport start(JavaPlugin plugin, Function<String, String> values, System.Logger logger) {
        PlaceholderApiSupport support = new PlaceholderApiSupport(values,
            String.valueOf(plugin.getManifest().getVersion()), logger);
        try {
            plugin.getEventRegistry().registerGlobal(PluginSetupEvent.class, event -> support.attach(event.getPlugin()));
            PluginManager manager = PluginManager.get();
            if (manager != null) support.attach(manager.getPlugin(PLACEHOLDER_API));
        } catch (RuntimeException | LinkageError failure) {
            logger.log(System.Logger.Level.WARNING, "Hybox cannot watch for PlaceholderAPI. Its placeholders stay off.", failure);
        }
        return support;
    }

    synchronized void attach(PluginBase plugin) {
        if (closed || plugin == null || plugin == attachedTo || !PLACEHOLDER_API.equals(plugin.getIdentifier())) return;
        detach();
        try {
            Class<?> type = Class.forName(EXPANSION, true, new ExpansionLoader(plugin.getClass().getClassLoader()));
            expansion = (AutoCloseable) type.getMethod("open", Function.class, String.class, List.class)
                .invoke(null, values, version, HyboxPlaceholders.PLACEHOLDERS);
            attachedTo = plugin;
        } catch (ReflectiveOperationException | RuntimeException | LinkageError failure) {
            logger.log(System.Logger.Level.WARNING, "PlaceholderAPI is installed but Hybox could not add its placeholders.", failure);
        }
    }

    @Override
    public synchronized void close() {
        closed = true;
        detach();
    }

    private void detach() {
        AutoCloseable current = expansion;
        expansion = null;
        attachedTo = null;
        if (current == null) return;
        try {
            current.close();
        } catch (Exception | LinkageError failure) {
            logger.log(System.Logger.Level.DEBUG, "Removing the Hybox placeholders failed.", failure);
        }
    }

    private static final class ExpansionLoader extends ClassLoader {
        ExpansionLoader(ClassLoader parent) {
            super("hybox-placeholderapi", parent);
        }

        @Override
        protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
            if (!EXPANSION.equals(name)) return super.loadClass(name, resolve);
            synchronized (getClassLoadingLock(name)) {
                Class<?> loaded = findLoadedClass(name);
                if (loaded != null) return loaded;
                byte[] bytes;
                try (InputStream input = PlaceholderApiSupport.class.getResourceAsStream("PlaceholderApiExpansion.class")) {
                    if (input == null) throw new ClassNotFoundException(name);
                    bytes = input.readAllBytes();
                } catch (IOException failure) {
                    throw new ClassNotFoundException(name, failure);
                }
                return defineClass(name, bytes, 0, bytes.length, PlaceholderApiSupport.class.getProtectionDomain());
            }
        }
    }
}
