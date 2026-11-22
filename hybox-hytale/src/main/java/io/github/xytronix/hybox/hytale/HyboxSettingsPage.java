package io.github.xytronix.hybox.hytale;

import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.codec.KeyedCodec;
import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.protocol.packets.interface_.CustomPageLifetime;
import com.hypixel.hytale.server.core.command.system.AbstractCommand;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.entity.entities.player.pages.InteractiveCustomUIPage;
import com.hypixel.hytale.server.core.ui.builder.UICommandBuilder;
import com.hypixel.hytale.server.core.ui.builder.UIEventBuilder;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import io.github.xytronix.hybox.hytale.HytaleHyboxConfig.HyboxSettings;
import io.github.xytronix.hybox.hytale.HyboxChatFormatter.Tone;
import io.github.xytronix.hybox.hytale.HyboxSettingsRenderer.Feedback;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class HyboxSettingsPage extends InteractiveCustomUIPage<HyboxSettingsPage.PageData> {
    private static final System.Logger LOGGER = System.getLogger(HyboxSettingsPage.class.getName());
    private final HyboxRuntime runtime;
    private final World world;
    private final AbstractCommand settingsCommand;
    private final Runnable back;
    private final String storage;
    private HyboxSettings settings;
    private HyboxSettings original;
    private Feedback feedback = new Feedback("", Tone.OK);
    private boolean saving;
    private boolean dismissed;

    private HyboxSettingsPage(HyboxRuntime runtime, PlayerRef playerRef, World world,
                              AbstractCommand settingsCommand, Runnable back, HyboxSettings settings, String storage) {
        super(playerRef, CustomPageLifetime.CanDismiss, PageData.CODEC);
        this.runtime = runtime;
        this.world = world;
        this.settingsCommand = settingsCommand;
        this.back = back;
        this.settings = settings;
        this.original = settings;
        this.storage = storage;
    }

    static void open(HyboxRuntime runtime, Ref<EntityStore> ref, Store<EntityStore> store,
                     World world, AbstractCommand settingsCommand, Runnable back) {
        if (!ref.isValid() || ref.getStore() != store) return;
        Player player = store.getComponent(ref, Player.getComponentType());
        PlayerRef playerRef = store.getComponent(ref, PlayerRef.getComponentType());
        if (player == null || playerRef == null) return;
        if (!settingsCommand.hasPermission(playerRef)) {
            playerRef.sendMessage(HyboxChatFormatter.message(Tone.ERROR,
                "Settings require permission to use /hybox settings."));
            return;
        }
        var previousPage = player.getPageManager().getCustomPage();
        runtime.submitWorker(() -> {
            try {
                HyboxSettings settings = HytaleHyboxConfig.read(runtime.dataDir(), LOGGER);
                String storage = HyboxSettingsRenderer.storage(runtime.storageUsage());
                world.execute(() -> {
                    if (!ref.isValid() || ref.getStore() != store) return;
                    Player current = store.getComponent(ref, Player.getComponentType());
                    if (current == null || current.getPageManager().getCustomPage() != previousPage) return;
                    if (!settingsCommand.hasPermission(playerRef)) return;
                    current.getPageManager().openCustomPage(ref, store,
                        new HyboxSettingsPage(runtime, playerRef, world, settingsCommand, back, settings, storage));
                });
            } catch (RuntimeException failure) {
                LOGGER.log(System.Logger.Level.WARNING, "Unable to read Hybox settings.", failure);
                world.execute(() -> {
                    if (ref.isValid()) playerRef.sendMessage(HyboxChatFormatter.message(Tone.ERROR,
                        "Unable to read hybox.json. The settings were not changed."));
                });
            }
        }, () -> world.execute(() -> {
            if (ref.isValid()) playerRef.sendMessage(HyboxChatFormatter.message(Tone.WARN,
                "Hybox is busy. Try again in a moment."));
        }));
    }

    @Override
    public void build(Ref<EntityStore> ref, UICommandBuilder commands, UIEventBuilder events,
                      Store<EntityStore> store) {
        commands.append("Pages/HyboxSettings.ui");
        HyboxSettingsRenderer.render(settings, storage, feedback, commands, events);
    }

    @Override
    public void handleDataEvent(Ref<EntityStore> ref, Store<EntityStore> store, PageData data) {
        if (dismissed || data.action == null) return;
        switch (data.action) {
            case "save" -> save(ref, store, data);
            case "back" -> {
                dismissed = true;
                close();
                back.run();
            }
            case "close" -> {
                dismissed = true;
                close();
            }
            default -> { }
        }
    }

    private void save(Ref<EntityStore> ref, Store<EntityStore> store, PageData data) {
        if (saving) return;
        if (!settingsCommand.hasPermission(playerRef)) {
            feedback = new Feedback("Settings require permission to use /hybox settings.", Tone.ERROR);
            updateFeedback();
            return;
        }
        List<String> problems = new ArrayList<>();
        HyboxSettings submitted = data.settings(settings, problems);
        problems.addAll(submitted.validate());
        if (!problems.isEmpty()) {
            feedback = new Feedback(String.join("\n", problems), Tone.ERROR);
            updateFeedback();
            return;
        }
        HyboxSettings baseline = original;
        settings = submitted;
        saving = true;
        feedback = new Feedback("Saving hybox.json and reloading…", Tone.WARN);
        updateFeedback();
        runtime.submitWorker(() -> {
            Feedback result;
            boolean persisted = false;
            try {
                if (!settingsCommand.hasPermission(playerRef)) {
                    result = new Feedback("Settings permission was revoked. hybox.json was not changed.", Tone.ERROR);
                } else {
                    HytaleHyboxConfig.write(runtime.dataDir(), baseline, submitted, LOGGER);
                    persisted = true;
                    String reloaded = runtime.reload();
                    result = new Feedback(reloaded.startsWith("Reload failed:")
                        ? "Saved hybox.json. " + reloaded + " Run /hybox reload when ready."
                        : "Saved hybox.json. " + reloaded,
                        reloaded.startsWith("Reload failed:") ? Tone.ERROR : Tone.OK);
                }
            } catch (RuntimeException failure) {
                LOGGER.log(System.Logger.Level.WARNING, "Unable to save Hybox settings.", failure);
                result = new Feedback("Unable to save hybox.json: "
                    + HyboxChatFormatter.singleLine(failure.getMessage()), Tone.ERROR);
            }
            Feedback completed = result;
            boolean completedSave = persisted;
            world.execute(() -> {
                saving = false;
                if (completedSave) original = submitted;
                if (!active(ref, store)) return;
                feedback = completed;
                updateFeedback();
            });
        }, () -> world.execute(() -> {
            saving = false;
            if (!active(ref, store)) return;
            feedback = new Feedback("Hybox is busy. Try again in a moment. Settings were not saved.", Tone.WARN);
            updateFeedback();
        }));
    }

    private void updateFeedback() {
        UICommandBuilder commands = new UICommandBuilder();
        HyboxSettingsRenderer.renderFeedback(feedback, commands);
        commands.set("#SaveButton.Disabled", saving);
        sendUpdate(commands, new UIEventBuilder(), false);
    }

    private boolean active(Ref<EntityStore> ref, Store<EntityStore> store) {
        if (dismissed || !ref.isValid() || ref.getStore() != store) return false;
        Player player = store.getComponent(ref, Player.getComponentType());
        return player != null && player.getPageManager().getCustomPage() == this;
    }

    @Override
    public void onDismiss(Ref<EntityStore> ref, Store<EntityStore> store) {
        dismissed = true;
    }

    public static final class PageData {
        public static final BuilderCodec<PageData> CODEC = codec();

        private String action;
        private final Map<String, Object> values = new LinkedHashMap<>();

        private static BuilderCodec<PageData> codec() {
            var builder = BuilderCodec.builder(PageData.class, PageData::new)
                .append(new KeyedCodec<>("Action", Codec.STRING), (d, v) -> d.action = v, d -> d.action).add();
            for (var field : HytaleHyboxConfig.SETTINGS_FIELDS) {
                if (field.kind() == HytaleHyboxConfig.SettingKind.TOGGLE) {
                    builder.append(new KeyedCodec<>(field.eventKey(), Codec.BOOLEAN),
                        (d, v) -> d.values.put(field.id(), v), d -> (Boolean) d.values.get(field.id())).add();
                } else {
                    builder.append(new KeyedCodec<>(field.eventKey(), Codec.DOUBLE),
                        (d, v) -> d.values.put(field.id(), v), d -> (Double) d.values.get(field.id())).add();
                }
            }
            return builder.build();
        }

        HyboxSettings settings(HyboxSettings current, List<String> problems) {
            Map<String, Object> submitted = new LinkedHashMap<>();
            for (var field : HytaleHyboxConfig.SETTINGS_FIELDS) {
                submitted.put(field.id(), field.parse(values.get(field.id()), current.value(field), problems));
            }
            return new HyboxSettings(submitted);
        }
    }
}
