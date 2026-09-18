package io.github.xytronix.hybox.hytale;

import com.hypixel.hytale.server.core.plugin.JavaPlugin;
import com.hypixel.hytale.server.core.plugin.JavaPluginInit;
import java.util.Objects;

public final class HyboxPlugin extends JavaPlugin {
    private final System.Logger logger = System.getLogger(HyboxPlugin.class.getName());
    private HyboxRuntime runtime;

    public HyboxPlugin(JavaPluginInit init) {
        super(Objects.requireNonNull(init, "init"));
    }

    @Override
    protected synchronized void start() {
        if (runtime != null) {
            throw new IllegalStateException("Hybox is already running.");
        }
        try {
            runtime = HyboxRuntime.start(this);
            HyboxApi.init(runtime);
        } catch (Exception | Error failure) {
            HyboxApi.shutdown();
            if (runtime != null) {
                try {
                    runtime.close();
                } catch (Throwable cleanupFailure) {
                    failure.addSuppressed(cleanupFailure);
                } finally {
                    runtime = null;
                }
            }
            logger.log(System.Logger.Level.ERROR, "Hybox failed to start.", failure);
            if (failure instanceof Error error) {
                throw error;
            }
            throw new IllegalStateException("Hybox failed to start.", failure);
        }
    }

    @Override
    protected synchronized void shutdown() {
        HyboxApi.shutdown();
        if (runtime == null) {
            return;
        }
        try {
            runtime.close();
        } catch (Exception e) {
            logger.log(System.Logger.Level.WARNING, "Hybox shutdown failed.", e);
        } finally {
            runtime = null;
        }
    }
}

