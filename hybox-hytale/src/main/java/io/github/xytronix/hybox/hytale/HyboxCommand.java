package io.github.xytronix.hybox.hytale;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.command.system.AbstractCommand;
import com.hypixel.hytale.server.core.command.system.CommandContext;
import com.hypixel.hytale.server.core.command.system.arguments.system.DefaultArg;
import com.hypixel.hytale.server.core.command.system.arguments.system.FlagArg;
import com.hypixel.hytale.server.core.command.system.arguments.types.ArgTypes;
import com.hypixel.hytale.server.core.command.system.basecommands.AbstractAsyncCommand;
import com.hypixel.hytale.server.core.command.system.basecommands.CommandBase;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import io.github.xytronix.hybox.core.text.Units;
import io.github.xytronix.hybox.hytale.HyboxChatFormatter.Tone;

final class HyboxCommand extends AbstractAsyncCommand {
    private final HyboxRuntime runtime;

    HyboxCommand(HyboxRuntime runtime) {
        super("hybox", "Hybox incident recorder");
        this.runtime = Objects.requireNonNull(runtime, "runtime");

        addSubCommand(new DumpCommand(runtime));
        addSubCommand(new StatusCommand(runtime));
        addSubCommand(new ListCommand(runtime, this));
        addSubCommand(new TriggersCommand(runtime, this));
        addSubCommand(new HistogramCommand(runtime, this));
        addSubCommand(new ProfileCommand(runtime));
        addSubCommand(new TrendCommand(runtime, this));
        addSubCommand(new ReloadCommand(runtime));
        addSubCommand(new SettingsCommand(runtime, this));
        addSubCommand(new UiCommand(runtime, this));
        addSubCommand(new IncidentCommand(runtime));
    }

    @Override
    protected CompletableFuture<Void> executeAsync(CommandContext context) {
        if (context.isPlayer()) {
            if (!getSubCommand("ui").hasPermission(context.sender())) {
                send(context, Tone.ERROR, "The dashboard requires permission to use /hybox ui.");
                return CompletableFuture.completedFuture(null);
            }
            return HyboxDashboardPage.open(context, runtime, this);
        }
        send(context, Tone.OK, usage());
        return CompletableFuture.completedFuture(null);
    }

    static String usage() {
        return "Usage: /hybox ui|settings|incident [id]|status [--verbose]|list [--count=5] [--details]"
            + "|triggers [--details]|dump|histogram|profile [--" + ProfileCommand.MINUTES + "=" + ProfileCommand.DEFAULT_MINUTES
            + "] [--" + ProfileCommand.KEEP_BUFFER + "=" + ProfileCommand.DEFAULT_KEEP_BUFFER + "] [stop|cancel]|trend|reload";
    }

    private static void send(CommandContext context, Tone tone, String text) {
        context.sendMessage(HyboxChatFormatter.message(tone, text));
    }

    private static final class UiCommand extends AbstractAsyncCommand {
        private final HyboxRuntime runtime;
        private final AbstractCommand rootCommand;

        private UiCommand(HyboxRuntime runtime, AbstractCommand rootCommand) {
            super("ui", "Open the Hybox dashboard");
            this.runtime = runtime;
            this.rootCommand = rootCommand;
        }

        @Override
        protected CompletableFuture<Void> executeAsync(CommandContext context) {
            return HyboxDashboardPage.open(context, runtime, rootCommand);
        }
    }

    private static final class SettingsCommand extends AbstractAsyncCommand {
        private final HyboxRuntime runtime;
        private final AbstractCommand rootCommand;

        private SettingsCommand(HyboxRuntime runtime, AbstractCommand rootCommand) {
            super("settings", "Open the Hybox settings page");
            this.runtime = runtime;
            this.rootCommand = rootCommand;
        }

        @Override
        protected CompletableFuture<Void> executeAsync(CommandContext context) {
            if (!context.isPlayer()) {
                send(context, Tone.WARN, "The settings page needs a player; edit hybox.json and use /hybox reload.");
                return CompletableFuture.completedFuture(null);
            }
            Ref<EntityStore> ref = context.senderAsPlayerRef();
            if (ref == null || !ref.isValid()) return CompletableFuture.completedFuture(null);
            Store<EntityStore> store = ref.getStore();
            World world = store.getExternalData().getWorld();
            return CompletableFuture.runAsync(() -> {
                if (!ref.isValid() || ref.getStore() != store) return;
                PlayerRef playerRef = store.getComponent(ref, PlayerRef.getComponentType());
                if (playerRef == null) return;
                if (!hasPermission(playerRef)) {
                    send(context, Tone.ERROR, "Settings require permission to use /hybox settings.");
                    return;
                }
                HyboxSettingsPage.open(runtime, ref, store, world, this,
                    () -> HyboxDashboardPage.open(context, runtime, rootCommand));
            }, world);
        }
    }

    private static final class IncidentCommand extends AbstractAsyncCommand {
        private final HyboxRuntime runtime;

        private IncidentCommand(HyboxRuntime runtime) {
            super("incident", "Open a complete incident report (latest by default)");
            this.runtime = runtime;
            addUsageVariant(new IncidentByIdCommand(runtime));
        }

        @Override
        protected CompletableFuture<Void> executeAsync(CommandContext context) {
            return HyboxIncidentPage.open(context, runtime, null);
        }
    }

    private static final class IncidentByIdCommand extends AbstractAsyncCommand {
        private final HyboxRuntime runtime;
        private final com.hypixel.hytale.server.core.command.system.arguments.system.RequiredArg<String> idArg =
            withRequiredArg("id", "Full incident id or numeric suffix", ArgTypes.STRING);

        private IncidentByIdCommand(HyboxRuntime runtime) {
            super("Open an incident report by id");
            this.runtime = runtime;
        }

        @Override
        protected CompletableFuture<Void> executeAsync(CommandContext context) {
            return HyboxIncidentPage.open(context, runtime, context.get(idArg));
        }
    }

    private abstract static class RuntimeAsyncCommand extends AbstractAsyncCommand {
        protected final HyboxRuntime runtime;

        protected RuntimeAsyncCommand(String name, String description, HyboxRuntime runtime) {
            super(name, description);
            this.runtime = Objects.requireNonNull(runtime, "runtime");
        }

        protected final CompletableFuture<Void> run(CommandContext context, Runnable work) {
            CompletableFuture<Void> result = new CompletableFuture<>();
            runtime.submitWorker(() -> {
                try {
                    work.run();
                    result.complete(null);
                } catch (Throwable failure) {
                    result.completeExceptionally(failure);
                }
            }, () -> {
                send(context, Tone.WARN, "Worker busy or stopping; try again later.");
                result.complete(null);
            });
            return result;
        }
    }

    private static final class DumpCommand extends RuntimeAsyncCommand {
        private DumpCommand(HyboxRuntime runtime) {
            super("dump", "Trigger a manual incident capture", runtime);
        }

        @Override
        protected CompletableFuture<Void> executeAsync(CommandContext context) {
            return runtime.captureManual(context.isPlayer()
                ? context.senderAs(com.hypixel.hytale.server.core.universe.PlayerRef.class) : null)
                .thenAccept(result -> send(context, HyboxChatFormatter.captureTone(result),
                    HyboxChatFormatter.captureLine(result, runtime.incidentDir(), context.isPlayer())));
        }
    }

    private static final class StatusCommand extends CommandBase {
        private final FlagArg verboseArg = withFlagArg("verbose", "Show paths and full diagnostic details");
        private final HyboxRuntime runtime;

        private StatusCommand(HyboxRuntime runtime) {
            super("status", "Show compact Hybox status (--verbose for details)");
            this.runtime = runtime;
        }

        @Override
        protected void executeSync(CommandContext context) {
            HyboxRuntime.Health health = runtime.health();
            Instant now = Instant.now();
            long profileSeconds = runtime.profileActive() ? runtime.profileRemainingSeconds() : 0;
            HyboxChatFormatter.statusMessages(health, now, profileSeconds).forEach(context::sendMessage);
            var usage = runtime.storageUsage();
            send(context, HyboxChatFormatter.storageTone(usage), HyboxChatFormatter.storageLine(usage));
            if (Boolean.TRUE.equals(context.get(verboseArg))) {
                HyboxChatFormatter.statusDetails(health, runtime.config(), runtime.configPath(), runtime.incidentDir(), now,
                        context.isPlayer())
                    .forEach(line -> send(context, Tone.OK, line));
            }
        }
    }

    private static final class ListCommand extends RuntimeAsyncCommand {
        private final DefaultArg<Integer> countArg =
            withDefaultArg("count", "Number of recent incidents (1-50)", ArgTypes.INTEGER, 5, "5");
        private final FlagArg detailsArg = withFlagArg("details", "Include incident bundle paths");

        private final AbstractCommand rootCommand;

        private ListCommand(HyboxRuntime runtime, AbstractCommand rootCommand) {
            super("list", "Open the Incidents tab; from the console, list recent incidents", runtime);
            this.rootCommand = rootCommand;
        }

        @Override
        protected CompletableFuture<Void> executeAsync(CommandContext context) {
            if (context.isPlayer()) return openTab(context, runtime, rootCommand, "incidents");
            Integer requested = context.get(countArg);
            int count = Math.max(1, Math.min(50, requested == null ? 5 : requested));
            boolean details = Boolean.TRUE.equals(context.get(detailsArg));
            return run(context, () -> {
                try {
                    var recent = HyboxIncidents.recent(runtime.incidentDir(), count);
                    if (recent.isEmpty()) {
                        send(context, Tone.OK, "No incidents yet.");
                        return;
                    }
                    Instant now = Instant.now();
                    for (var incident : recent) {
                        send(context, Tone.OK, HyboxChatFormatter.incidentLine(incident, now, details));
                    }
                } catch (IOException failure) {
                    send(context, Tone.ERROR, "Unable to read incident history.");
                }
            });
        }
    }

    private static final class TriggersCommand extends AbstractAsyncCommand {
        private final FlagArg detailsArg = withFlagArg("details", "Show trigger thresholds and timing");
        private final HyboxRuntime runtime;
        private final AbstractCommand rootCommand;

        private TriggersCommand(HyboxRuntime runtime, AbstractCommand rootCommand) {
            super("triggers", "Open the Triggers tab; from the console, show armed and disabled triggers");
            this.runtime = runtime;
            this.rootCommand = rootCommand;
        }

        @Override
        protected CompletableFuture<Void> executeAsync(CommandContext context) {
            if (context.isPlayer()) return openTab(context, runtime, rootCommand, "triggers");
            var policy = runtime.config().triggerPolicy();
            var triggers = HyboxChatFormatter.triggers(policy, HyboxDashboardPage.armedTriggers(runtime));
            send(context, Tone.OK, HyboxChatFormatter.triggerSummary(triggers));
            if (Boolean.TRUE.equals(context.get(detailsArg))) {
                send(context, Tone.OK, "Cooldown=" + policy.cooldown() + ", debounce=" + policy.debounce());
                for (var trigger : triggers) send(context, trigger.armed() ? Tone.OK : Tone.WARN, trigger.details());
            }
            return CompletableFuture.completedFuture(null);
        }
    }

    private static CompletableFuture<Void> openTab(CommandContext context, HyboxRuntime runtime, AbstractCommand rootCommand,
                                                   String tab) {
        if (!rootCommand.getSubCommand("ui").hasPermission(context.sender())) {
            send(context, Tone.ERROR, "This opens the dashboard, which requires permission to use /hybox ui.");
            return CompletableFuture.completedFuture(null);
        }
        return HyboxDashboardPage.open(context, runtime, rootCommand, tab);
    }

    private static final class HistogramCommand extends RuntimeAsyncCommand {
        private final AbstractCommand rootCommand;

        private HistogramCommand(HyboxRuntime runtime, AbstractCommand rootCommand) {
            super("histogram", "Open the Heap tab; from the console, print the top heap classes", runtime);
            this.rootCommand = rootCommand;
        }

        @Override
        protected CompletableFuture<Void> executeAsync(CommandContext context) {
            if (context.isPlayer()) return openTab(context, runtime, rootCommand, "heap");
            return run(context, () -> {
                send(context, Tone.WARN, "Walking the heap can pause the server for seconds on large heaps.");
                var view = runtime.walkHeap();
                if (view == null) {
                    send(context, Tone.WARN, "Heap histogram unavailable on this JVM.");
                    return;
                }
                long base = view.snapshot().shareBase();
                for (var entry : view.snapshot().entries().subList(0, Math.min(10, view.snapshot().entries().size()))) {
                    send(context, Tone.OK, HyboxChatFormatter.histogramLine(entry, base));
                }
            });
        }
    }

    private static final class ProfileCommand extends RuntimeAsyncCommand {
        private static final String MINUTES = "minutes";
        private static final int DEFAULT_MINUTES = 5;
        private static final String KEEP_BUFFER = "keep-buffer";
        private static final boolean DEFAULT_KEEP_BUFFER = true;

        private final DefaultArg<Integer> minutesArg = withDefaultArg(MINUTES, "Profiling duration in minutes (1-30)",
            ArgTypes.INTEGER, DEFAULT_MINUTES, Integer.toString(DEFAULT_MINUTES));
        private final DefaultArg<Boolean> keepBufferArg =
            withDefaultArg(KEEP_BUFFER, "Keep the rolling buffer so one bundle spans before + after (default)",
                ArgTypes.BOOLEAN, DEFAULT_KEEP_BUFFER, Boolean.toString(DEFAULT_KEEP_BUFFER));

        private ProfileCommand(HyboxRuntime runtime) {
            super("profile", "Record a high-fidelity bundle (keep-buffer=false starts a fresh recording)", runtime);
            addSubCommand(new ProfileStopCommand(runtime));
            addSubCommand(new ProfileCancelCommand(runtime));
        }

        @Override
        protected CompletableFuture<Void> executeAsync(CommandContext context) {
            Integer requested = context.get(minutesArg);
            int minutes = Math.max(1, Math.min(30, requested == null ? DEFAULT_MINUTES : requested));
            boolean keepBuffer = Boolean.TRUE.equals(context.get(keepBufferArg));
            return runtime.startProfileSession(minutes, keepBuffer, context.isPlayer()
                ? context.senderAs(com.hypixel.hytale.server.core.universe.PlayerRef.class) : null).thenAccept(error -> {
                if (error != null) {
                    send(context, Tone.ERROR, error.startsWith("Failed to switch")
                        ? "Failed to switch to the profile preset; check the server log." : error);
                } else {
                    send(context, Tone.OK, "Profile started · " + minutes + " min · "
                        + (keepBuffer ? "rolling buffer kept" : "fresh recording") + " · captures automatically at the end.");
                }
            });
        }
    }

    private static final class ProfileStopCommand extends RuntimeAsyncCommand {
        private ProfileStopCommand(HyboxRuntime runtime) {
            super("stop", "End the running profile now and save what was recorded", runtime);
        }

        @Override
        protected CompletableFuture<Void> executeAsync(CommandContext context) {
            String error = runtime.finishProfileNow();
            send(context, error == null ? Tone.OK : Tone.WARN, error == null ? "Profile ended · saving bundle." : error);
            return CompletableFuture.completedFuture(null);
        }
    }

    private static final class ProfileCancelCommand extends RuntimeAsyncCommand {
        private ProfileCancelCommand(HyboxRuntime runtime) {
            super("cancel", "Cancel the running profile without saving a bundle", runtime);
        }

        @Override
        protected CompletableFuture<Void> executeAsync(CommandContext context) {
            String error = runtime.cancelProfile();
            send(context, error == null ? Tone.OK : Tone.WARN, error == null ? "Profile cancelled · nothing saved." : error);
            return CompletableFuture.completedFuture(null);
        }
    }

    private static final class TrendCommand extends RuntimeAsyncCommand {
        private final AbstractCommand rootCommand;
        private final DefaultArg<Integer> daysArg =
            withDefaultArg("days", "How many days back to chart (1-30)", ArgTypes.INTEGER, 7, "7");

        private TrendCommand(HyboxRuntime runtime, AbstractCommand rootCommand) {
            super("trend", "View health metric trends in game or build an HTML report from the console", runtime);
            this.rootCommand = rootCommand;
        }

        @Override
        protected CompletableFuture<Void> executeAsync(CommandContext context) {
            Integer requested = context.get(daysArg);
            int days = HyboxTrendModel.days(requested == null ? 7 : requested);
            if (context.isPlayer()) return HyboxTrendPage.open(context, runtime, days, rootCommand);
            return run(context, () -> {
                try {
                    Path out = runtime.generateTrendReport(days);
                    if (out == null) {
                        send(context, Tone.WARN, !runtime.config().metricsEnabled()
                            ? "Metrics disabled; set Metrics.Enabled=true to record trends."
                            : "No health metrics recorded in the last " + Units.count(days, "day") + ".");
                    } else {
                        send(context, Tone.OK, "Trend (" + days + "d) · " + HyboxChatFormatter.outputPath(out, context.isPlayer()));
                    }
                } catch (IOException failure) {
                    send(context, Tone.ERROR, "Trend report failed; see server log.");
                }
            });
        }
    }

    private static final class ReloadCommand extends RuntimeAsyncCommand {
        private ReloadCommand(HyboxRuntime runtime) {
            super("reload", "Reload the active Hybox configuration without a restart", runtime);
        }

        @Override
        protected CompletableFuture<Void> executeAsync(CommandContext context) {
            return run(context, () -> {
                String result = runtime.reload();
                Tone tone = result.startsWith("Reload failed") ? Tone.ERROR
                    : result.contains("Restart required") ? Tone.WARN : Tone.OK;
                send(context, tone, result.startsWith("Reload failed:") && result.contains("keeping previous config")
                    ? "Reload failed; previous configuration kept. Check the active configuration." : result);
            });
        }
    }

}
