package io.github.xytronix.hybox.hytale;

import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.codec.KeyedCodec;
import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.protocol.packets.interface_.CustomPageLifetime;
import com.hypixel.hytale.server.core.command.system.AbstractCommand;
import com.hypixel.hytale.server.core.command.system.CommandContext;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.entity.entities.player.pages.InteractiveCustomUIPage;
import com.hypixel.hytale.server.core.ui.builder.UICommandBuilder;
import com.hypixel.hytale.server.core.ui.builder.UIEventBuilder;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import io.github.xytronix.hybox.core.capture.CaptureResult;
import java.io.IOException;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.atomic.AtomicBoolean;

final class HyboxDashboardPage extends InteractiveCustomUIPage<HyboxDashboardPage.PageData> {
    private final HyboxRuntime runtime;
    private final World world;
    private final AbstractCommand captureCommand;
    private final AbstractCommand profileCommand;
    private final AbstractCommand settingsCommand;
    private final AbstractCommand incidentCommand;
    private final AbstractCommand trendCommand;
    private final AbstractCommand uiCommand;
    private final AbstractCommand rootCommand;
    private final AbstractCommand histogramCommand;
    private final AtomicBoolean refreshQueued = new AtomicBoolean();
    private String tab;
    private String feedback = "";
    private volatile boolean dismissed;
    private boolean capturing;
    private boolean profileSaving;
    private boolean cancelConfirming;
    private boolean opening;
    private boolean wasCapturing;
    private CaptureResult observedOutcome;
    private boolean wasRunning;
    private String observedIncident;
    private int incidentRequest;
    private List<HyboxIncidents.Incident> incidents = List.of();
    private String incidentsMessage = "Loading recent incidents…";
    private ScheduledFuture<?> refresh;
    private String shownShape;
    private String shownTimer;
    private List<String> shownValues = List.of();
    private int profileMinutes = 5;
    private boolean profileKeepBuffer = true;
    private boolean profileStarting;
    private boolean heapConfirming;
    private boolean heapWalking;
    private String heapMessage;
    private HyboxRuntime.HeapView heapView;

    private HyboxDashboardPage(HyboxRuntime runtime, PlayerRef playerRef, World world, AbstractCommand rootCommand, String tab) {
        super(playerRef, CustomPageLifetime.CanDismiss, PageData.CODEC);
        this.runtime = runtime;
        this.world = world;
        this.captureCommand = rootCommand.getSubCommand("dump");
        this.profileCommand = rootCommand.getSubCommand("profile");
        this.settingsCommand = rootCommand.getSubCommand("settings");
        this.incidentCommand = rootCommand.getSubCommand("incident");
        this.trendCommand = rootCommand.getSubCommand("trend");
        this.uiCommand = rootCommand.getSubCommand("ui");
        this.histogramCommand = rootCommand.getSubCommand("histogram");
        this.rootCommand = rootCommand;
        this.tab = tab;
        this.heapView = runtime.lastHeap();
        this.heapConfirming = tab.equals("heap") && histogramCommand.hasPermission(playerRef);
    }

    static CompletableFuture<Void> open(CommandContext context, HyboxRuntime runtime, AbstractCommand rootCommand) {
        return open(context, runtime, rootCommand, "overview");
    }

    static CompletableFuture<Void> open(CommandContext context, HyboxRuntime runtime, AbstractCommand rootCommand, String tab) {
        if (!context.isPlayer()) {
            context.sendMessage(HyboxChatFormatter.message(HyboxChatFormatter.Tone.WARN,
                "The dashboard opens in game only. From the console, use /hybox status or /hybox status --verbose."));
            return CompletableFuture.completedFuture(null);
        }
        try {
            Ref<EntityStore> ref = context.senderAsPlayerRef();
            if (ref == null || !ref.isValid()) {
                fallback(context, runtime);
                return CompletableFuture.completedFuture(null);
            }
            Store<EntityStore> store = ref.getStore();
            World world = store.getExternalData().getWorld();
            return CompletableFuture.runAsync(() -> {
                if (!open(runtime, ref, store, world, rootCommand, tab)) fallback(context, runtime);
            }, world).exceptionally(failure -> {
                fallback(context, runtime);
                return null;
            });
        } catch (RuntimeException | LinkageError failure) {
            fallback(context, runtime);
            return CompletableFuture.completedFuture(null);
        }
    }

    private static boolean open(HyboxRuntime runtime, Ref<EntityStore> ref, Store<EntityStore> store, World world,
                                AbstractCommand rootCommand, String tab) {
        if (!ref.isValid() || ref.getStore() != store) return false;
        Player player = store.getComponent(ref, Player.getComponentType());
        PlayerRef playerRef = store.getComponent(ref, PlayerRef.getComponentType());
        if (player == null || playerRef == null) return false;
        if (!rootCommand.getSubCommand("ui").hasPermission(playerRef)) {
            playerRef.sendMessage(HyboxChatFormatter.message(HyboxChatFormatter.Tone.ERROR,
                "The dashboard requires permission to use /hybox ui."));
            player.getPageManager().closePage(ref, store);
            return true;
        }
        HyboxDashboardPage page = new HyboxDashboardPage(runtime, playerRef, world, rootCommand, tab);
        try {
            player.getPageManager().openCustomPage(ref, store, page);
            return true;
        } catch (RuntimeException | LinkageError failure) {
            page.stopRefreshing();
            if (player.getPageManager().getCustomPage() == page) {
                try {
                    player.getPageManager().closePage(ref, store);
                } catch (RuntimeException | LinkageError closeFailure) {
                    failure.addSuppressed(closeFailure);
                }
            }
            return false;
        }
    }

    private static void fallback(CommandContext context, HyboxRuntime runtime) {
        context.sendMessage(HyboxChatFormatter.message(HyboxChatFormatter.Tone.WARN,
            "The dashboard could not open. Use /hybox status."));
        HyboxChatFormatter.statusMessages(runtime.health(), runtime.liveStatus(), Instant.now(),
            runtime.profileActive() ? runtime.profileRemainingSeconds() : 0).forEach(context::sendMessage);
    }

    @Override
    public void build(Ref<EntityStore> ref, UICommandBuilder commands, UIEventBuilder events,
                      Store<EntityStore> store) {
        commands.append("Pages/HyboxDashboard.ui");
        HyboxDashboardRenderer.bindNavigation(events);
        DashboardModel model = model();
        HyboxDashboardRenderer.render(model, tab, commands, events);
        remember(model);
        loadIncidents(ref, store);
        var initialHealth = runtime.health();
        observedIncident = initialHealth.lastIncident();
        observedOutcome = initialHealth.lastOutcome();
        wasCapturing = initialHealth.captureBusy();
        wasRunning = runtime.profileActive() || wasCapturing;
        if (refresh == null) {
            refresh = runtime.scheduleDashboardRefresh(() -> {
                if (dismissed || !refreshQueued.compareAndSet(false, true)) return;
                if (!onWorld(() -> {
                    refreshQueued.set(false);
                    Ref<EntityStore> liveRef = playerRef.getReference();
                    if (liveRef == null || !liveRef.isValid()) return;
                    Store<EntityStore> liveStore = liveRef.getStore();
                    if (!active(liveRef, liveStore)) {
                        if (dismissed || !playerRef.isValid()) stopRefreshing();
                        return;
                    }
                    var health = runtime.health();
                    boolean running = runtime.profileActive() || health.captureBusy();
                    boolean newIncident = !java.util.Objects.equals(observedIncident, health.lastIncident());
                    boolean newOutcome = !java.util.Objects.equals(observedOutcome, health.lastOutcome());
                    boolean captureFinished = !health.captureBusy() && (wasCapturing || newOutcome || newIncident);
                    if (captureFinished && health.lastOutcome() != null) {
                        feedback = DashboardModel.captureFeedback(health.lastOutcome(), runtime.incidentDir());
                    }
                    if (newOutcome || (wasCapturing && !health.captureBusy())) profileSaving = false;
                    if (!runtime.profileActive()) cancelConfirming = false;
                    update();
                    if (captureFinished || newIncident) loadIncidents(liveRef, liveStore);
                    observedIncident = health.lastIncident();
                    observedOutcome = health.lastOutcome();
                    wasCapturing = health.captureBusy();
                    wasRunning = running;
                })) refreshQueued.set(false);
            });
        }
    }

    @Override
    public void handleDataEvent(Ref<EntityStore> ref, Store<EntityStore> store, PageData data) {
        if (dismissed || data.action == null) return;
        switch (data.action) {
            case "close" -> {
                dismissed = true;
                stopRefreshing();
                close();
            }
            case "overview", "incidents", "triggers", "heap" -> {
                tab = data.action;
                update();
                if (tab.equals("incidents")) loadIncidents(ref, store);
            }
            case "capture" -> capture(ref, store);
            case "capture:skip" -> skipWait();
            case "profile:stop" -> stopProfile(true);
            case "profile:cancel" -> askCancel();
            case "profile:cancel:yes" -> {
                cancelConfirming = false;
                stopProfile(false);
            }
            case "profile:cancel:no" -> {
                cancelConfirming = false;
                update();
            }
            case "profile:start" -> startProfile(data.keepBuffer);
            case "profile:keep" -> {
                if (data.keepBuffer != null && !profileStarting) profileKeepBuffer = data.keepBuffer;
                update();
            }
            case "heap:ask" -> askHeap();
            case "heap:cancel" -> {
                heapConfirming = false;
                update();
            }
            case "heap:walk" -> walkHeap();
            case "trends" -> trends(ref, store);
            case "settings" -> settings(ref, store);
            default -> {
                if (data.action.startsWith("incident:")) openIncident(ref, store, data.action.substring(9));
                else if (data.action.startsWith("profile:minutes:")) chooseMinutes(data.action.substring(16));
            }
        }
    }

    private DashboardModel model() {
        return DashboardModel.snapshot(runtime, Instant.now(), incidents, incidentsMessage, feedback,
            !capturing && captureCommand.hasPermission(playerRef), incidentCommand.hasPermission(playerRef),
            profileCommand.getSubCommand("stop").hasPermission(playerRef),
            profileCommand.getSubCommand("cancel").hasPermission(playerRef), profileSaving, trendCommand.hasPermission(playerRef),
            new DashboardModel.ProfileForm(profileCommand.hasPermission(playerRef), profileMinutes, profileKeepBuffer, profileStarting),
            new DashboardModel.Heap(histogramCommand.hasPermission(playerRef), heapConfirming, heapWalking,
                heapView == null ? null : heapView.snapshot(), heapView == null ? null : heapView.takenAt(), heapMessage),
            cancelConfirming);
    }

    private void remember(DashboardModel model) {
        shownShape = HyboxDashboardRenderer.shape(model, tab);
        shownTimer = HyboxDashboardRenderer.timerKey(model);
        UICommandBuilder values = new UICommandBuilder();
        HyboxDashboardRenderer.refresh(model, tab, values, false);
        shownValues = signature(values);
    }

    private static List<String> signature(UICommandBuilder commands) {
        return java.util.Arrays.stream(commands.getCommands())
            .map(command -> command.type + "|" + command.selector + "|" + command.data + "|" + command.text).toList();
    }

    private void update() {
        DashboardModel model = model();
        UICommandBuilder commands = new UICommandBuilder();
        if (!HyboxDashboardRenderer.shape(model, tab).equals(shownShape)) {
            UIEventBuilder events = new UIEventBuilder();
            HyboxDashboardRenderer.render(model, tab, commands, events);
            remember(model);
            sendUpdate(commands, events, false);
            return;
        }
        String timer = HyboxDashboardRenderer.timerKey(model);
        UICommandBuilder values = new UICommandBuilder();
        HyboxDashboardRenderer.refresh(model, tab, values, false);
        List<String> next = signature(values);
        boolean timerChanged = !timer.equals(shownTimer);
        if (next.equals(shownValues) && !timerChanged) return;
        HyboxDashboardRenderer.refresh(model, tab, commands, timerChanged);
        shownValues = next;
        shownTimer = timer;
        sendUpdate(commands, false);
    }

    private void skipWait() {
        if (!captureCommand.hasPermission(playerRef)) {
            feedback = "Skipping the wait requires permission to use /hybox dump.";
        } else {
            String error = runtime.skipRecoveryWait();
            feedback = error == null ? "Skipping the recovery wait. Writing the bundle now." : error;
        }
        update();
    }

    private void askCancel() {
        if (!profileCommand.getSubCommand("cancel").hasPermission(playerRef)) {
            feedback = "Cancelling a profile requires permission to use /hybox profile cancel.";
        } else if (runtime.profileActive()) {
            cancelConfirming = true;
        }
        update();
    }

    private void stopProfile(boolean save) {
        if (!profileCommand.getSubCommand(save ? "stop" : "cancel").hasPermission(playerRef)) {
            feedback = save ? "Saving a profile requires permission to use /hybox profile stop."
                : "Cancelling a profile requires permission to use /hybox profile cancel.";
        } else {
            String error = save ? runtime.finishProfileNow() : runtime.cancelProfile();
            profileSaving = save && error == null;
            if (save) cancelConfirming = false;
            feedback = error != null ? error : save ? "Profile stopped. Saving the bundle." : "Profile cancelled. Nothing was saved.";
        }
        update();
    }

    private void chooseMinutes(String value) {
        int minutes;
        try {
            minutes = Integer.parseInt(value);
        } catch (NumberFormatException invalid) {
            return;
        }
        if (profileStarting || java.util.Arrays.stream(HyboxDashboardRenderer.PROFILE_MINUTES).noneMatch(m -> m == minutes)) return;
        profileMinutes = minutes;
        update();
    }

    private void startProfile(Boolean keepBuffer) {
        if (!profileCommand.hasPermission(playerRef)) {
            feedback = "Starting a profile requires permission to use /hybox profile.";
            update();
            return;
        }
        if (profileStarting) return;
        if (keepBuffer != null) profileKeepBuffer = keepBuffer;
        int minutes = profileMinutes;
        boolean keep = profileKeepBuffer;
        profileStarting = true;
        feedback = "Starting profile…";
        update();
        runtime.startProfileSession(minutes, keep, playerRef).whenComplete((error, failure) -> onWorld(() -> {
            profileStarting = false;
            Ref<EntityStore> liveRef = playerRef.getReference();
            if (liveRef == null || !liveRef.isValid() || !active(liveRef, liveRef.getStore())) return;
            feedback = failure != null ? "Profile failed to start. The server log has the error."
                : error == null ? "Profile started for " + minutes + " min. A bundle is saved at the end."
                : error.startsWith("Failed to switch") ? "Failed to switch to the profile preset. The server log has the error." : error;
            update();
        }));
    }

    private void askHeap() {
        if (!histogramCommand.hasPermission(playerRef)) {
            feedback = "Walking the heap requires permission to use /hybox histogram.";
        } else if (!heapWalking) {
            heapConfirming = true;
        }
        update();
    }

    private void walkHeap() {
        heapConfirming = false;
        if (!histogramCommand.hasPermission(playerRef)) {
            feedback = "Walking the heap requires permission to use /hybox histogram.";
            update();
            return;
        }
        if (heapWalking) return;
        heapWalking = true;
        heapMessage = null;
        update();
        runtime.submitWorker(() -> {
            HyboxRuntime.HeapView view = null;
            String message = null;
            try {
                view = runtime.walkHeap();
                if (view == null) message = "Heap histogram unavailable on this JVM.";
            } catch (RuntimeException failure) {
                message = "Heap walk failed: " + failure.getClass().getSimpleName() + ".";
            }
            heapDone(view, message);
        }, () -> heapDone(null, "Hybox is busy. Try again in a moment."));
    }

    private void heapDone(HyboxRuntime.HeapView view, String message) {
        onWorld(() -> {
            heapWalking = false;
            if (view != null) heapView = view;
            heapMessage = message;
            Ref<EntityStore> liveRef = playerRef.getReference();
            if (liveRef != null && liveRef.isValid() && active(liveRef, liveRef.getStore())) update();
        });
    }

    static HyboxChatFormatter.ArmedTriggers armedTriggers(HyboxRuntime runtime) {
        return new HyboxChatFormatter.ArmedTriggers(runtime.deadlockArmed(), runtime.heapPressureArmed(),
            runtime.gcPressureArmed(), runtime.cpuSaturationArmed(), runtime.netSaturationArmed(), runtime.playerDropArmed());
    }

    private void loadIncidents(Ref<EntityStore> ref, Store<EntityStore> store) {
        int request = ++incidentRequest;
        runtime.submitWorker(() -> {
            List<HyboxIncidents.Incident> loaded;
            String message = "";
            try {
                loaded = HyboxIncidents.recent(runtime.incidentDir(), INCIDENT_HISTORY);
            } catch (IOException failure) {
                loaded = List.of();
                message = "Unable to read recent incidents. Select Incidents to retry.";
            }
            List<HyboxIncidents.Incident> result = loaded;
            String resultMessage = message;
            onWorld(() -> {
                if (!active(ref, store) || request != incidentRequest) return;
                incidents = result;
                incidentsMessage = resultMessage;
                update();
            });
        }, () -> onWorld(() -> {
            if (!active(ref, store) || request != incidentRequest) return;
            incidentsMessage = "Hybox is busy. Try again in a moment.";
            update();
        }));
    }

    private static final int INCIDENT_HISTORY = 50;

    private boolean onWorld(Runnable task) {
        World current = world;
        try {
            var uuid = playerRef.getWorldUuid();
            World found = uuid == null ? null : com.hypixel.hytale.server.core.universe.Universe.get().getWorld(uuid);
            if (found != null) current = found;
            current.execute(task);
            return true;
        } catch (RuntimeException | LinkageError failure) {
            return false;
        }
    }

    private void reopen(String tab) {
        Ref<EntityStore> liveRef = playerRef.getReference();
        if (liveRef == null || !liveRef.isValid()) return;
        Store<EntityStore> liveStore = liveRef.getStore();
        open(runtime, liveRef, liveStore, liveStore.getExternalData().getWorld(), rootCommand, tab);
    }

    private void openIncident(Ref<EntityStore> ref, Store<EntityStore> store, String index) {
        if (!incidentCommand.hasPermission(playerRef)) {
            feedback = "Opening incidents requires permission to use /hybox incident.";
            update();
            return;
        }
        if (!tab.equals("incidents") && !index.equals("0")) return;
        int selected;
        try {
            selected = Integer.parseInt(index);
        } catch (NumberFormatException invalid) {
            return;
        }
        if (selected < 0 || selected >= incidents.size() || opening) return;
        opening = true;
        feedback = "Opening incident…";
        update();
        HyboxIncidentPage.open(runtime, ref, store, store.getExternalData().getWorld(), incidents.get(selected).id(),
            () -> reopen("incidents"), this::pageError);
    }

    private void trends(Ref<EntityStore> ref, Store<EntityStore> store) {
        if (!trendCommand.hasPermission(playerRef)) {
            feedback = "Trends require permission to use /hybox trend.";
            update();
            return;
        }
        if (opening) return;
        opening = true;
        feedback = "Opening trends…";
        update();
        HyboxTrendPage.open(runtime, ref, store, store.getExternalData().getWorld(), 7, trendCommand, settingsCommand, uiCommand,
            () -> reopen("overview"), this::pageError);
    }

    private void pageError(String message) {
        onWorld(() -> {
            opening = false;
            Ref<EntityStore> liveRef = playerRef.getReference();
            if (liveRef != null && liveRef.isValid() && active(liveRef, liveRef.getStore())) {
                feedback = message;
                update();
            } else {
                playerRef.sendMessage(HyboxChatFormatter.message(HyboxChatFormatter.Tone.WARN, message));
            }
        });
    }

    private void settings(Ref<EntityStore> ref, Store<EntityStore> store) {
        if (!settingsCommand.hasPermission(playerRef)) {
            feedback = "Settings require permission to use /hybox settings.";
            update();
            return;
        }
        HyboxSettingsPage.open(runtime, ref, store, store.getExternalData().getWorld(), settingsCommand, () -> reopen("overview"),
            this::pageError);
    }

    private void capture(Ref<EntityStore> ref, Store<EntityStore> store) {
        if (!captureCommand.hasPermission(playerRef)) {
            feedback = "Capture requires permission to use /hybox dump.";
            update();
            return;
        }
        if (capturing) return;
        capturing = true;
        feedback = "Capturing incident…";
        update();
        runtime.captureManual(playerRef).whenComplete((result, failure) -> onWorld(() -> {
            capturing = false;
            Ref<EntityStore> liveRef = playerRef.getReference();
            if (liveRef == null || !liveRef.isValid() || !active(liveRef, liveRef.getStore())) return;
            feedback = DashboardModel.captureFeedback(failure == null ? result
                : CaptureResult.failed(failure.getClass().getSimpleName()), runtime.incidentDir());
            update();
            loadIncidents(liveRef, liveRef.getStore());
        }));
    }

    private boolean active(Ref<EntityStore> ref, Store<EntityStore> store) {
        if (dismissed || !ref.isValid() || ref.getStore() != store) return false;
        Player player = store.getComponent(ref, Player.getComponentType());
        return player != null && player.getPageManager().getCustomPage() == this;
    }

    private void stopRefreshing() {
        if (refresh != null) {
            refresh.cancel(false);
            refresh = null;
        }
    }

    @Override
    public void onDismiss(Ref<EntityStore> ref, Store<EntityStore> store) {
        dismissed = true;
        incidentRequest++;
        stopRefreshing();
    }

    public static final class PageData {
        public static final BuilderCodec<PageData> CODEC = BuilderCodec.builder(PageData.class, PageData::new)
            .append(new KeyedCodec<>("Action", Codec.STRING),
                (data, action) -> data.action = action, data -> data.action)
            .add()
            .append(new KeyedCodec<>(HyboxDashboardRenderer.KEEP_BUFFER_KEY, Codec.BOOLEAN),
                (data, keep) -> data.keepBuffer = keep, data -> data.keepBuffer)
            .add()
            .build();

        private String action;
        private Boolean keepBuffer;
    }
}
