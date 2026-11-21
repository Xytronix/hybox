package io.github.xytronix.hybox.hytale;

import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.codec.KeyedCodec;
import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.protocol.packets.interface_.CustomPageLifetime;
import com.hypixel.hytale.protocol.packets.interface_.CustomUICommand;
import com.hypixel.hytale.protocol.packets.interface_.CustomUICommandType;
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
import io.github.xytronix.hybox.core.jfr.ProfileMode;
import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
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
    private boolean showManual;
    private boolean opening;
    private boolean wasCapturing;
    private CaptureResult observedOutcome;
    private String observedIncident;
    private int incidentRequest;
    private List<HyboxIncidents.Incident> incidents = List.of();
    private String incidentsMessage = "Loading recent incidents...";
    private volatile ScheduledFuture<?> refresh;
    private volatile State state;
    private int profileMinutes = 5;
    private boolean profileKeepBuffer = true;
    private boolean profileStarting;
    private boolean heapConfirming;
    private boolean heapWalking;
    private String heapMessage;
    private HyboxRuntime.HeapView heapView;

    private HyboxDashboardPage(HyboxRuntime runtime, PlayerRef playerRef, World world, AbstractCommand rootCommand, String tab, boolean showManual) {
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
        this.showManual = showManual;
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
                if (!open(runtime, ref, store, world, rootCommand, tab, false)) fallback(context, runtime);
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
                                AbstractCommand rootCommand, String tab, boolean showManual) {
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
        HyboxDashboardPage page = new HyboxDashboardPage(runtime, playerRef, world, rootCommand, tab, showManual);
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
        Inputs inputs = inputs();
        DashboardModel model = model(inputs, Instant.now());
        HyboxDashboardRenderer.render(model, tab, commands, events);
        state = new State(inputs, shown(model, tab));
        loadIncidents(ref, store);
        var initialHealth = runtime.health();
        observedIncident = initialHealth.lastIncident();
        observedOutcome = initialHealth.lastOutcome();
        wasCapturing = initialHealth.captureBusy();
        if (refresh == null) refresh = runtime.scheduleDashboardRefresh(this::tick);
    }

    private void tick() {
        if (dismissed || !playerRef.getPacketHandler().stillActive()) {
            stopRefreshing();
            return;
        }
        if (!refreshQueued.compareAndSet(false, true)) return;
        if (!runtime.offerWorker(this::refreshOffWorld)) refreshQueued.set(false);
    }

    private void refreshOffWorld() {
        boolean handedOff = false;
        try {
            if (dismissed) return;
            var health = runtime.health();
            boolean busy = health.captureBusy();
            boolean newIncident = !Objects.equals(observedIncident, health.lastIncident());
            boolean newOutcome = !Objects.equals(observedOutcome, health.lastOutcome());
            State current = state;
            if (newIncident || newOutcome || (wasCapturing && !busy)
                || (current.inputs().cancelConfirming() && !profileHoldsData())) {
                handedOff = onWorld(() -> settle(health));
                return;
            }
            wasCapturing = busy;
            Frame frame = frame(current.inputs(), current.shown(), Instant.now());
            if (frame != null) handedOff = onWorld(() -> apply(current, frame));
        } finally {
            if (!handedOff) refreshQueued.set(false);
        }
    }

    private void settle(HyboxRuntime.Health health) {
        try {
            Ref<EntityStore> liveRef = playerRef.getReference();
            if (liveRef == null || !liveRef.isValid()) return;
            Store<EntityStore> liveStore = liveRef.getStore();
            if (!active(liveRef, liveStore)) {
                if (dismissed) stopRefreshing();
                return;
            }
            boolean newIncident = !Objects.equals(observedIncident, health.lastIncident());
            boolean newOutcome = !Objects.equals(observedOutcome, health.lastOutcome());
            boolean captureFinished = !health.captureBusy() && (wasCapturing || newOutcome || newIncident);
            if (captureFinished && health.lastOutcome() != null) {
                feedback = DashboardModel.captureFeedback(health.lastOutcome(), runtime.incidentDir());
            }
            if (newOutcome || (wasCapturing && !health.captureBusy())) profileSaving = false;
            if (!profileHoldsData()) cancelConfirming = false;
            update();
            if (captureFinished || newIncident) loadIncidents(liveRef, liveStore);
            observedIncident = health.lastIncident();
            observedOutcome = health.lastOutcome();
            wasCapturing = health.captureBusy();
        } finally {
            refreshQueued.set(false);
        }
    }

    private void apply(State expected, Frame frame) {
        try {
            Ref<EntityStore> liveRef = playerRef.getReference();
            if (liveRef == null || !liveRef.isValid()) return;
            if (!active(liveRef, liveRef.getStore())) {
                if (dismissed) stopRefreshing();
                return;
            }
            if (state != expected) return;
            state = new State(expected.inputs(), frame.shown());
            send(frame);
        } finally {
            refreshQueued.set(false);
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
            case "incidents:manual" -> {
                showManual = !showManual;
                update();
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
            case "profile:start:lag" -> startProfile(ProfileMode.LAG, data.keepBuffer);
            case "profile:start:memory" -> startProfile(ProfileMode.MEMORY, data.keepBuffer);
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

    private Inputs inputs() {
        return new Inputs(tab, incidents, incidentsMessage, feedback, capturing, profileSaving, profileMinutes, profileKeepBuffer,
            profileStarting, heapConfirming, heapWalking, heapMessage, heapView, cancelConfirming, showManual);
    }

    private DashboardModel model(Inputs in, Instant now) {
        HyboxRuntime.HeapView view = in.heapView();
        return DashboardModel.snapshot(runtime, now, in.incidents(), in.incidentsMessage(), in.feedback(),
            !in.capturing() && captureCommand.hasPermission(playerRef), incidentCommand.hasPermission(playerRef),
            profileCommand.getSubCommand("stop").hasPermission(playerRef),
            profileCommand.getSubCommand("cancel").hasPermission(playerRef), in.profileSaving(), trendCommand.hasPermission(playerRef),
            new DashboardModel.ProfileForm(profileCommand.hasPermission(playerRef), in.profileMinutes(), in.profileKeepBuffer(),
                in.profileStarting()),
            new DashboardModel.Heap(histogramCommand.hasPermission(playerRef), in.heapConfirming(), in.heapWalking(),
                view == null ? null : view.snapshot(), view == null ? null : view.takenAt(), in.heapMessage()),
            in.cancelConfirming(), in.showManual());
    }

    private Frame frame(Inputs in, Shown shown, Instant now) {
        DashboardModel model = model(in, now);
        String tab = in.tab();
        String shape = HyboxDashboardRenderer.shape(model, tab);
        if (!shape.equals(shown.shape())) {
            UICommandBuilder commands = new UICommandBuilder();
            UIEventBuilder events = new UIEventBuilder();
            HyboxDashboardRenderer.render(model, tab, commands, events);
            return new Frame(commands, events, shown(model, tab));
        }
        String timer = HyboxDashboardRenderer.timerKey(model);
        boolean timerChanged = !timer.equals(shown.timer());
        UICommandBuilder values = new UICommandBuilder();
        HyboxDashboardRenderer.refresh(model, tab, values, false);
        CustomUICommand[] sets = values.getCommands();
        Map<String, String> next = new HashMap<>(shown.values());
        if (!remember(next, sets) && !timerChanged) return null;
        CustomUICommand[] source = sets;
        if (timerChanged) {
            UICommandBuilder all = new UICommandBuilder();
            HyboxDashboardRenderer.refresh(model, tab, all, true);
            source = all.getCommands();
        }
        return new Frame(new Picked(changed(shown.values(), source)), null, new Shown(shape, timer, next));
    }

    private static Shown shown(DashboardModel model, String tab) {
        UICommandBuilder values = new UICommandBuilder();
        HyboxDashboardRenderer.refresh(model, tab, values, false);
        Map<String, String> shown = new HashMap<>();
        remember(shown, values.getCommands());
        return new Shown(HyboxDashboardRenderer.shape(model, tab), HyboxDashboardRenderer.timerKey(model), shown);
    }

    static boolean remember(Map<String, String> shown, CustomUICommand[] commands) {
        boolean changed = false;
        for (CustomUICommand command : commands) {
            if (command.type != CustomUICommandType.Set) continue;
            if (!Objects.equals(shown.put(command.selector, command.data), command.data)) changed = true;
        }
        return changed;
    }

    static CustomUICommand[] changed(Map<String, String> shown, CustomUICommand[] commands) {
        Set<String> selectors = new HashSet<>();
        for (CustomUICommand command : commands) {
            if (command.type == CustomUICommandType.Set && !selectors.add(command.selector)) return commands;
        }
        List<CustomUICommand> changed = new ArrayList<>();
        for (CustomUICommand command : commands) {
            if (command.type != CustomUICommandType.Set || !shown.containsKey(command.selector)
                || !Objects.equals(shown.get(command.selector), command.data)) changed.add(command);
        }
        return changed.toArray(CustomUICommand[]::new);
    }

    private void update() {
        State current = state;
        Inputs inputs = inputs();
        Frame frame = frame(inputs, current.shown(), Instant.now());
        state = new State(inputs, frame == null ? current.shown() : frame.shown());
        if (frame != null) send(frame);
    }

    private void send(Frame frame) {
        if (frame.events() != null) sendUpdate(frame.commands(), frame.events(), false);
        else sendUpdate(frame.commands(), false);
    }

    private record Inputs(String tab, List<HyboxIncidents.Incident> incidents, String incidentsMessage, String feedback,
                          boolean capturing, boolean profileSaving, int profileMinutes, boolean profileKeepBuffer,
                          boolean profileStarting, boolean heapConfirming, boolean heapWalking, String heapMessage,
                          HyboxRuntime.HeapView heapView, boolean cancelConfirming, boolean showManual) {}

    private record Shown(String shape, String timer, Map<String, String> values) {}

    private record State(Inputs inputs, Shown shown) {}

    private record Frame(UICommandBuilder commands, UIEventBuilder events, Shown shown) {}

    private static final class Picked extends UICommandBuilder {
        private final CustomUICommand[] commands;

        Picked(CustomUICommand[] commands) {
            this.commands = commands;
        }

        @Override
        public CustomUICommand[] getCommands() {
            return commands;
        }
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

    private boolean profileHoldsData() {
        return runtime.profileActive() || runtime.profileFinalQueued();
    }

    private void askCancel() {
        if (!profileCommand.getSubCommand("cancel").hasPermission(playerRef)) {
            feedback = "Canceling a profile requires permission to use /hybox profile cancel.";
        } else if (profileHoldsData()) {
            cancelConfirming = true;
        } else if (runtime.queuedProfile() != null) {
            stopProfile(false);
            return;
        }
        update();
    }

    private void stopProfile(boolean save) {
        if (!profileCommand.getSubCommand(save ? "stop" : "cancel").hasPermission(playerRef)) {
            feedback = save ? "Saving a profile requires permission to use /hybox profile stop."
                : "Canceling a profile requires permission to use /hybox profile cancel.";
        } else if (save) {
            String error = runtime.finishProfileNow();
            profileSaving = error == null;
            cancelConfirming = false;
            feedback = error != null ? error : "Profile stopped. Saving the bundle.";
        } else {
            feedback = runtime.cancelProfile(playerRef).message();
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

    private void startProfile(ProfileMode mode, Boolean keepBuffer) {
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
        feedback = "Starting profile...";
        update();
        runtime.startProfileSession(mode, minutes, keep, playerRef).whenComplete((start, failure) -> onWorld(() -> {
            profileStarting = false;
            Ref<EntityStore> liveRef = playerRef.getReference();
            if (liveRef == null || !liveRef.isValid() || !active(liveRef, liveRef.getStore())) return;
            feedback = failure != null ? "Profile failed to start. The server log has the error."
                : start.queued() ? HyboxCommand.QUEUED_MESSAGE
                : start.error() == null ? HyboxRuntime.startedMessage(mode, minutes)
                : start.error().startsWith("Failed to switch") ? "Failed to switch to the profile settings. The server log has the error."
                : start.error();
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
                loaded = HyboxIncidents.recent(runtime.incidentDir(), HyboxDashboardRenderer.ROWS);
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
        open(runtime, liveRef, liveStore, liveStore.getExternalData().getWorld(), rootCommand, tab, showManual);
    }

    private void openIncident(Ref<EntityStore> ref, Store<EntityStore> store, String index) {
        if (!incidentCommand.hasPermission(playerRef)) {
            feedback = "Opening incidents requires permission to use /hybox incident.";
            update();
            return;
        }
        String id;
        if (index.equals("latest")) {
            if (incidents.isEmpty()) return;
            id = incidents.getFirst().id();
        } else {
            if (!tab.equals("incidents")) return;
            List<DashboardModel.IncidentRow> rows = DashboardModel.incidentRows(incidents, showManual);
            int split = index.indexOf(':');
            if (split < 0 || !index.substring(0, split).equals(DashboardModel.listVersion(rows))) return;
            int selected;
            try {
                selected = Integer.parseInt(index.substring(split + 1));
            } catch (NumberFormatException invalid) {
                return;
            }
            if (selected < 0 || selected >= Math.min(HyboxDashboardRenderer.ROWS, rows.size())) return;
            id = rows.get(selected).incident().id();
        }
        if (opening) return;
        opening = true;
        feedback = "Opening incident...";
        update();
        HyboxIncidentPage.open(runtime, ref, store, store.getExternalData().getWorld(), id,
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
        feedback = "Opening trends...";
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
        feedback = "Capturing incident...";
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
        ScheduledFuture<?> current = refresh;
        if (current != null) {
            current.cancel(false);
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
