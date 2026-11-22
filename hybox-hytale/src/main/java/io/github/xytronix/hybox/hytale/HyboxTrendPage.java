package io.github.xytronix.hybox.hytale;

import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.codec.KeyedCodec;
import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.protocol.packets.interface_.CustomPageLifetime;
import com.hypixel.hytale.server.core.command.system.AbstractCommand;
import com.hypixel.hytale.server.core.command.system.CommandContext;
import com.hypixel.hytale.server.core.command.system.CommandSender;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.entity.entities.player.pages.InteractiveCustomUIPage;
import com.hypixel.hytale.server.core.ui.builder.UICommandBuilder;
import com.hypixel.hytale.server.core.ui.builder.UIEventBuilder;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import java.io.IOException;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import io.github.xytronix.hybox.core.text.Units;

final class HyboxTrendPage extends InteractiveCustomUIPage<HyboxTrendPage.PageData> {
    private static final System.Logger LOGGER = System.getLogger(HyboxTrendPage.class.getName());
    private final HyboxRuntime runtime;
    private final World world;
    private final AbstractCommand trendCommand;
    private final AbstractCommand settingsCommand;
    private final AbstractCommand uiCommand;
    private final Runnable onBack;
    private final HyboxChartAssets chartImages;
    private HyboxTrendModel model;
    private int request;
    private volatile boolean dismissed;
    private boolean loading;
    private boolean saving;
    private String feedback = "";

    private HyboxTrendPage(HyboxRuntime runtime, PlayerRef player, World world, HyboxTrendModel model,
                           AbstractCommand trendCommand, AbstractCommand settingsCommand, AbstractCommand uiCommand,
                           Runnable onBack) {
        super(player, CustomPageLifetime.CanDismiss, PageData.CODEC);
        this.runtime = runtime;
        this.world = world;
        this.model = model;
        this.trendCommand = trendCommand;
        this.settingsCommand = settingsCommand;
        this.uiCommand = uiCommand;
        this.onBack = onBack;
        chartImages = new HyboxChartAssets(new HyboxChartAssets.Delivery() {
            @Override public void work(Runnable task) { runtime.submitWorker(() -> { if (!dismissed) task.run(); }, () -> { }); }
            @Override public void send(com.hypixel.hytale.protocol.ToClientPacket[] packets) {
                if (!dismissed) playerRef.getPacketHandler().write(packets);
            }
            @Override public void update(Runnable task) { onWorld(task); }
            @Override public void update(UICommandBuilder commands) {
                Ref<EntityStore> ref = playerRef.getReference();
                if (ref != null && ref.isValid() && active(ref, ref.getStore())) sendUpdate(commands, false);
            }
        }, failure -> LOGGER.log(System.Logger.Level.WARNING, "Chart images failed to render. Charts are shown as bars.", failure));
    }

    static CompletableFuture<Void> open(CommandContext context, HyboxRuntime runtime, int days, AbstractCommand rootCommand) {
        if (!context.isPlayer()) return CompletableFuture.completedFuture(null);
        Ref<EntityStore> ref = context.senderAsPlayerRef();
        if (ref == null || !ref.isValid()) return CompletableFuture.completedFuture(null);
        Store<EntityStore> store = ref.getStore();
        World world = store.getExternalData().getWorld();
        CompletableFuture<Void> result = new CompletableFuture<>();
        world.execute(() -> open(runtime, ref, store, world, days, rootCommand.getSubCommand("trend"),
            rootCommand.getSubCommand("settings"), rootCommand.getSubCommand("ui"),
            () -> HyboxDashboardPage.open(context, runtime, rootCommand), null)
            .whenComplete((ignored, failure) -> {
                if (failure == null) result.complete(null); else result.completeExceptionally(failure);
            }));
        return result;
    }

    static CompletableFuture<Void> open(HyboxRuntime runtime, Ref<EntityStore> ref, Store<EntityStore> store,
                                       World world, int requestedDays, AbstractCommand trendCommand,
                                       AbstractCommand settingsCommand, AbstractCommand uiCommand, Runnable onBack,
                                       java.util.function.Consumer<String> onError) {
        CompletableFuture<Void> result = new CompletableFuture<>();
        if (!ref.isValid() || ref.getStore() != store) return CompletableFuture.completedFuture(null);
        Player player = store.getComponent(ref, Player.getComponentType());
        PlayerRef playerRef = store.getComponent(ref, PlayerRef.getComponentType());
        if (player == null || playerRef == null) return CompletableFuture.completedFuture(null);
        if (!trendCommand.hasPermission(playerRef)) {
            playerRef.sendMessage(HyboxChatFormatter.message(HyboxChatFormatter.Tone.ERROR,
                "Trends require permission to use /hybox trend."));
            return CompletableFuture.completedFuture(null);
        }
        var previousPage = player.getPageManager().getCustomPage();
        runtime.submitWorker(() -> {
            try {
                HyboxTrendModel loaded = runtime.readTrend(requestedDays);
                world.execute(() -> {
                    try {
                        if (ref.isValid() && ref.getStore() == store) {
                            Player current = store.getComponent(ref, Player.getComponentType());
                            if (current != null && current.getPageManager().getCustomPage() == previousPage
                                && trendCommand.hasPermission(playerRef)) {
                                current.getPageManager().openCustomPage(ref, store,
                                    new HyboxTrendPage(runtime, playerRef, world, loaded, trendCommand, settingsCommand, uiCommand, onBack));
                            }
                        }
                        result.complete(null);
                    } catch (RuntimeException failure) { result.completeExceptionally(failure); }
                });
            } catch (RuntimeException failure) {
                world.execute(() -> {
                    failed(ref, playerRef, onError, "Unable to open trends: " + failure.getMessage());
                    result.complete(null);
                });
            }
        }, () -> world.execute(() -> {
            failed(ref, playerRef, onError, "Hybox is busy. Try again in a moment.");
            result.complete(null);
        }));
        return result;
    }

    private static void failed(Ref<EntityStore> ref, PlayerRef playerRef, java.util.function.Consumer<String> onError, String message) {
        if (onError != null) onError.accept(message);
        else if (ref.isValid()) playerRef.sendMessage(HyboxChatFormatter.message(HyboxChatFormatter.Tone.WARN, message));
    }

    @Override
    public void build(Ref<EntityStore> ref, UICommandBuilder commands, UIEventBuilder events, Store<EntityStore> store) {
        commands.append("Pages/HyboxTrend.ui");
        HyboxTrendRenderer.bind(events);
        chartImages.begin();
        HyboxTrendRenderer.render(model, state(), commands, chartImages);
        chartImages.flush();
    }

    @Override
    public void handleDataEvent(Ref<EntityStore> ref, Store<EntityStore> store, PageData data) {
        if (dismissed || data.action == null) return;
        String denial = denial(data.action, playerRef, trendCommand, settingsCommand, uiCommand);
        if (denial != null) {
            feedback = denial;
            updateFeedback();
            return;
        }
        switch (data.action) {
            case "close" -> { dismiss(); close(); }
            case "back" -> { if (onBack != null) { dismiss(); onBack.run(); } }
            case "range" -> {
                int requested;
                try { requested = Integer.parseInt(data.arg); }
                catch (NumberFormatException ignored) { return; }
                if (requested != 1 && requested != 7 && requested != 30) return;
                load(ref, store, requested);
            }
            case "save" -> save(ref, store);
            case "settings" -> {
                if (model.metricsEnabled() || loading) return;
                HyboxSettingsPage.open(runtime, ref, store, store.getExternalData().getWorld(), settingsCommand, onBack, null);
            }
            default -> { }
        }
    }

    static String denial(String action, CommandSender sender, AbstractCommand trendCommand,
                         AbstractCommand settingsCommand, AbstractCommand uiCommand) {
        return switch (action) {
            case "range", "save" -> trendCommand.hasPermission(sender) ? null : "Trends require permission to use /hybox trend.";
            case "settings" -> settingsCommand.hasPermission(sender) ? null : "Settings require permission to use /hybox settings.";
            case "back" -> uiCommand.hasPermission(sender) ? null : "The dashboard requires permission to use /hybox ui.";
            default -> null;
        };
    }

    private void load(Ref<EntityStore> ref, Store<EntityStore> store, int requestedDays) {
        int expected = ++request;
        loading = true;
        feedback = "Loading health metrics…";
        render();
        runtime.submitWorker(() -> {
            try {
                HyboxTrendModel loaded = runtime.readTrend(requestedDays);
                onWorld(() -> {
                    if (expected != request || !active(ref, store)) return;
                    loading = false;
                    if (!trendCommand.hasPermission(playerRef)) {
                        feedback = "Trends require permission to use /hybox trend.";
                    } else {
                        model = loaded;
                        feedback = "";
                    }
                    render();
                });
            } catch (RuntimeException failure) {
                loadFailed(ref, store, expected, "Unable to load trends: " + failure.getMessage());
            }
        }, () -> loadFailed(ref, store, expected, "Hybox is busy. Try again in a moment."));
    }

    private void loadFailed(Ref<EntityStore> ref, Store<EntityStore> store, int expected, String message) {
        onWorld(() -> {
            if (expected != request || !active(ref, store)) return;
            loading = false;
            feedback = message;
            render();
        });
    }

    private void save(Ref<EntityStore> ref, Store<EntityStore> store) {
        if (saving || loading) return;
        saving = true;
        int reportDays = model.days();
        feedback = "Saving HTML report…";
        updateFeedback();
        runtime.submitWorker(() -> {
            String message;
            try {
                if (!trendCommand.hasPermission(playerRef)) {
                    message = "Trends require permission to use /hybox trend.";
                } else {
                    Path out = runtime.generateTrendReport(reportDays);
                    message = out != null ? "Trend report for " + Units.count(reportDays, "day") + " saved: " + HyboxChatFormatter.outputPath(out, true)
                        : !runtime.config().metricsEnabled() ? "Trends are off. Turn on Metrics history in /hybox settings."
                        : "No health metrics recorded in the last " + Units.count(reportDays, "day") + ".";
                }
            } catch (IOException | RuntimeException failure) {
                LOGGER.log(System.Logger.Level.WARNING, "Trend report failed.", failure);
                message = "Trend report failed. The server log has the error.";
            }
            saved(ref, store, message);
        }, () -> saved(ref, store, "Hybox is busy. Try again in a moment."));
    }

    private void saved(Ref<EntityStore> ref, Store<EntityStore> store, String message) {
        onWorld(() -> {
            if (!active(ref, store)) return;
            saving = false;
            feedback = message;
            updateFeedback();
        });
    }

    private HyboxTrendRenderer.ViewState state() {
        return new HyboxTrendRenderer.ViewState(feedback, loading, saving, trendCommand.hasPermission(playerRef),
            settingsCommand.hasPermission(playerRef), uiCommand.hasPermission(playerRef));
    }

    private void render() {
        UICommandBuilder commands = new UICommandBuilder();
        chartImages.begin();
        HyboxTrendRenderer.render(model, state(), commands, chartImages);
        sendUpdate(commands, false);
        chartImages.flush();
    }

    private void updateFeedback() {
        UICommandBuilder commands = new UICommandBuilder();
        HyboxTrendRenderer.feedback(state(), commands);
        commands.set("#SaveButton.Disabled", saving || loading || !trendCommand.hasPermission(playerRef) || model.samples() == 0);
        sendUpdate(commands, false);
    }

    private boolean active(Ref<EntityStore> ref, Store<EntityStore> store) {
        if (dismissed || !ref.isValid() || ref.getStore() != store) return false;
        Player player = store.getComponent(ref, Player.getComponentType());
        return player != null && player.getPageManager().getCustomPage() == this;
    }

    private void onWorld(Runnable task) {
        World current = world;
        try {
            var uuid = playerRef.getWorldUuid();
            World found = uuid == null ? null : com.hypixel.hytale.server.core.universe.Universe.get().getWorld(uuid);
            if (found != null) current = found;
            current.execute(task);
        } catch (RuntimeException | LinkageError failure) {
            dismissed = true;
        }
    }

    private void dismiss() {
        dismissed = true;
        request++;
        chartImages.begin();
    }

    @Override
    public void onDismiss(Ref<EntityStore> ref, Store<EntityStore> store) { dismiss(); }

    public static final class PageData {
        public static final BuilderCodec<PageData> CODEC = BuilderCodec.builder(PageData.class, PageData::new)
            .append(new KeyedCodec<>("Action", Codec.STRING), (data, action) -> data.action = action, data -> data.action).add()
            .append(new KeyedCodec<>("Arg", Codec.STRING), (data, arg) -> data.arg = arg, data -> data.arg).add()
            .build();
        private String action;
        private String arg;
    }
}
