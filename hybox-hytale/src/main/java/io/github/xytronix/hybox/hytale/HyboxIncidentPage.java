package io.github.xytronix.hybox.hytale;

import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.codec.KeyedCodec;
import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.protocol.packets.interface_.CustomPageLifetime;
import com.hypixel.hytale.protocol.packets.interface_.CustomUIEventBindingType;
import com.hypixel.hytale.server.core.command.system.CommandContext;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.entity.entities.player.pages.InteractiveCustomUIPage;
import com.hypixel.hytale.server.core.ui.builder.EventData;
import com.hypixel.hytale.server.core.ui.builder.UICommandBuilder;
import com.hypixel.hytale.server.core.ui.builder.UIEventBuilder;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import java.io.IOException;
import java.util.List;
import java.util.concurrent.CompletableFuture;

final class HyboxIncidentPage extends InteractiveCustomUIPage<HyboxIncidentPage.PageData> {
    private final World world;
    private final HyboxRuntime runtime;
    private final IncidentModel model;
    private final Runnable onBack;
    private HyboxIncidentRenderer.ViewState state = HyboxIncidentRenderer.ViewState.initial();
    private boolean dismissed;
    private final HyboxChartAssets chartImages;

    private HyboxIncidentPage(HyboxRuntime runtime, PlayerRef player, World world, IncidentModel model, Runnable onBack) {
        super(player, CustomPageLifetime.CanDismiss, PageData.CODEC);
        this.runtime = runtime;
        this.world = world;
        this.model = model;
        this.onBack = onBack;
        System.Logger logger = System.getLogger(HyboxIncidentPage.class.getName());
        this.chartImages = new HyboxChartAssets(new HyboxChartAssets.Delivery() {
            @Override public void work(Runnable task) {
                runtime.submitWorker(task, () -> { });
            }
            @Override public void send(com.hypixel.hytale.protocol.ToClientPacket[] packets) {
                playerRef.getPacketHandler().write(packets);
            }
            @Override public void update(Runnable onWorldThread) {
                World current = currentWorld();
                if (current != null) current.execute(onWorldThread);
            }
            @Override public void update(UICommandBuilder commands) {
                if (!dismissed) sendUpdate(commands, false);
            }
        }, failure -> logger.log(System.Logger.Level.WARNING, "Chart images failed to render. Charts are shown as bars.", failure));
    }

    private World currentWorld() {
        try {
            var uuid = playerRef.getWorldUuid();
            World current = uuid == null ? null : com.hypixel.hytale.server.core.universe.Universe.get().getWorld(uuid);
            return current != null ? current : world;
        } catch (RuntimeException | LinkageError failure) {
            return world;
        }
    }

    static CompletableFuture<Void> open(HyboxRuntime runtime, Ref<EntityStore> ref, Store<EntityStore> store,
                                       World world, String incidentId, Runnable onBack,
                                       java.util.function.Consumer<String> onError) {
        CompletableFuture<Void> result = new CompletableFuture<>();
        if (!ref.isValid() || ref.getStore() != store) return CompletableFuture.completedFuture(null);
        Player player = store.getComponent(ref, Player.getComponentType());
        PlayerRef playerRef = store.getComponent(ref, PlayerRef.getComponentType());
        if (player == null || playerRef == null) return CompletableFuture.completedFuture(null);
        var previousPage = player.getPageManager().getCustomPage();
        runtime.submitWorker(() -> {
            try {
                List<HyboxIncidents.Incident> incidents = HyboxIncidents.recent(runtime.incidentDir(), Integer.MAX_VALUE);
                IncidentModel model = IncidentModel.load(incidents.get(select(incidents, incidentId)).path());
                world.execute(() -> {
                    try {
                        if (ref.isValid() && ref.getStore() == store) {
                            Player current = store.getComponent(ref, Player.getComponentType());
                            if (current != null && current.getPageManager().getCustomPage() == previousPage) {
                                current.getPageManager().openCustomPage(ref, store,
                                    new HyboxIncidentPage(runtime, playerRef, world, model, onBack));
                            }
                        }
                        result.complete(null);
                    } catch (RuntimeException failure) { result.completeExceptionally(failure); }
                });
            } catch (IOException | RuntimeException failure) {
                world.execute(() -> {
                    failed(ref, playerRef, onError, "Unable to open incident: " + failure.getMessage());
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

    static CompletableFuture<Void> open(CommandContext context, HyboxRuntime runtime, String id) {
        if (!context.isPlayer()) return console(context, runtime, id);
        Ref<EntityStore> ref = context.senderAsPlayerRef();
        if (ref == null || !ref.isValid()) return CompletableFuture.completedFuture(null);
        Store<EntityStore> store = ref.getStore();
        World world = store.getExternalData().getWorld();
        CompletableFuture<Void> result = new CompletableFuture<>();
        world.execute(() -> open(runtime, ref, store, world, id, null, null).whenComplete((ignored, failure) -> {
            if (failure == null) result.complete(null); else result.completeExceptionally(failure);
        }));
        return result;
    }

    private static CompletableFuture<Void> console(CommandContext context, HyboxRuntime runtime, String id) {
        CompletableFuture<Void> result = new CompletableFuture<>();
        runtime.submitWorker(() -> {
            try {
                List<HyboxIncidents.Incident> incidents = HyboxIncidents.recent(runtime.incidentDir(), Integer.MAX_VALUE);
                IncidentModel model = IncidentModel.load(incidents.get(select(incidents, id)).path());
                for (String line : HyboxIncidentRenderer.consoleSummary(model)) context.sendMessage(HyboxChatFormatter.message(HyboxChatFormatter.Tone.OK, line));
                context.sendMessage(HyboxChatFormatter.message(HyboxChatFormatter.Tone.OK, "Bundle: " + model.bundle()));
                result.complete(null);
            } catch (IOException | RuntimeException failure) {
                context.sendMessage(HyboxChatFormatter.message(HyboxChatFormatter.Tone.WARN, "Unable to read incident: " + failure.getMessage()));
                result.complete(null);
            }
        }, () -> {
            context.sendMessage(HyboxChatFormatter.message(HyboxChatFormatter.Tone.WARN, "Hybox is busy. Try again in a moment."));
            result.complete(null);
        });
        return result;
    }

    static int select(List<HyboxIncidents.Incident> incidents, String id) throws IOException {
        if (incidents.isEmpty()) throw new IOException("No incident bundles found");
        if (id == null || id.isBlank() || id.equals("latest")) return 0;
        String requested = id;
        if (requested.startsWith("incident-")) requested = requested.substring(9);
        if (requested.endsWith(".zip")) requested = requested.substring(0, requested.length() - 4);
        int selected = -1;
        for (int i = 0; i < incidents.size(); i++) {
            String candidate = incidents.get(i).id();
            if (candidate.equals(requested)) return i;
            if (candidate.endsWith("-" + requested)) {
                if (selected >= 0) throw new IOException("Incident id matches more than one incident. Use the full id");
                selected = i;
            }
        }
        if (selected < 0) throw new IOException("Incident not found: " + id);
        return selected;
    }

    @Override
    public void build(Ref<EntityStore> ref, UICommandBuilder cmd, UIEventBuilder ev, Store<EntityStore> store) {
        cmd.append("Pages/HyboxIncident.ui");
        for (String ignored : HyboxIncidentRenderer.SECTIONS.keySet()) cmd.append("#SectionNav", "Pages/HyboxIncidentButton.ui");
        chartImages.begin();
        HyboxIncidentRenderer.render(model, state, cmd, ev, chartImages);
        cmd.set("#BackButton.Visible", onBack != null);
        bind(ev, "#DismissButton", "close"); bind(ev, "#BackButton", "back");
        chartImages.flush();
    }

    private static void bind(UIEventBuilder ev, String selector, String action) {
        ev.addEventBinding(CustomUIEventBindingType.Activating, selector, EventData.of("Action", action).append("Arg", ""));
    }

    @Override
    public void handleDataEvent(Ref<EntityStore> ref, Store<EntityStore> store, PageData data) {
        if (dismissed || data.action == null) return;
        switch (data.action) {
            case "close" -> { dismissed = true; close(); return; }
            case "back" -> { if (onBack != null) { dismissed = true; onBack.run(); } return; }
            case "section" -> state = new HyboxIncidentRenderer.ViewState(data.arg, 0, state.flameZoomPath(), state.timeRangeId(), state.showCore());
            case "range" -> state = new HyboxIncidentRenderer.ViewState(state.sectionId(), 0, state.flameZoomPath(), data.arg, state.showCore());
            case "page" -> {
                try {
                    int page = Math.min(Integer.parseInt(data.arg), HyboxIncidentRenderer.pageCount(model, state) - 1);
                    state = new HyboxIncidentRenderer.ViewState(state.sectionId(), page, state.flameZoomPath(), state.timeRangeId(), state.showCore());
                } catch (NumberFormatException ignored) { return; }
            }
            case "zoom" -> state = new HyboxIncidentRenderer.ViewState("flame", 0, data.arg, state.timeRangeId(), state.showCore());
            case "toggle" -> {
                if (!"core".equals(data.arg)) return;
                state = new HyboxIncidentRenderer.ViewState(state.sectionId(), 0, state.flameZoomPath(), state.timeRangeId(), !state.showCore());
            }
            default -> { return; }
        }
        UICommandBuilder cmd = new UICommandBuilder(); UIEventBuilder ev = new UIEventBuilder();
        chartImages.begin();
        HyboxIncidentRenderer.render(model, state, cmd, ev, chartImages);
        sendUpdate(cmd, ev, false);
        chartImages.flush();
    }

    @Override
    public void onDismiss(Ref<EntityStore> ref, Store<EntityStore> store) { dismissed = true; }

    public static final class PageData {
        public static final BuilderCodec<PageData> CODEC = BuilderCodec.builder(PageData.class, PageData::new)
            .append(new KeyedCodec<>("Action", Codec.STRING), (data, action) -> data.action = action, data -> data.action).add()
            .append(new KeyedCodec<>("Arg", Codec.STRING), (data, arg) -> data.arg = arg, data -> data.arg).add()
            .build();
        private String action;
        private String arg;
    }
}
