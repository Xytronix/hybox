package io.github.xytronix.hybox.hytale;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Stream;

import com.hypixel.hytale.server.core.HytaleServer;
import com.hypixel.hytale.server.core.plugin.PluginBase;
import com.hypixel.hytale.server.core.plugin.PluginManager;
import com.hypixel.hytale.server.core.universe.Universe;
import com.hypixel.hytale.server.core.universe.world.World;

import io.github.xytronix.hybox.core.bundle.BundleAttachment;
import io.github.xytronix.hybox.core.capture.BundleExtrasProvider;
import io.github.xytronix.hybox.core.env.ThreadDumper;
import io.github.xytronix.hybox.core.incident.IncidentReport;
import io.github.xytronix.hybox.core.incident.DiagnosticSection;
import io.github.xytronix.hybox.core.trigger.TriggerEvent;
import io.github.xytronix.hybox.core.trigger.heartbeat.HeartbeatRegistry;

final class HytaleBundleExtrasProvider implements BundleExtrasProvider {
    private static final System.Logger LOGGER = System.getLogger(HytaleBundleExtrasProvider.class.getName());
    private static final String NITRADO_WEB_SERVER_CLASS = "de.nitrado.hytale.NitradoWebServer";
    private static final int MAX_LOG_TAIL_BYTES = 256 * 1024;
    private static final int MAX_METRICS_TAIL_BYTES = 1024 * 1024;

    private final HeartbeatRegistry heartbeatRegistry;
    private final java.util.function.IntSupplier logTailLines;
    private final java.util.function.BooleanSupplier includeServerLog;
    private final Path metricsDir;
    private final PlayerNameMasker nameMasker;
    private final java.util.function.Supplier<List<String>> spacedDumps;
    private final java.util.function.BooleanSupplier liveDumpAllowed;

    HytaleBundleExtrasProvider(HeartbeatRegistry heartbeatRegistry,
                               java.util.function.IntSupplier logTailLines,
                               java.util.function.BooleanSupplier includeServerLog, Path metricsDir,
                               PlayerNameMasker nameMasker,
                               java.util.function.Supplier<List<String>> spacedDumps,
                               java.util.function.BooleanSupplier liveDumpAllowed) {
        this.heartbeatRegistry = heartbeatRegistry;
        this.logTailLines = logTailLines;
        this.includeServerLog = includeServerLog;
        this.metricsDir = metricsDir;
        this.nameMasker = nameMasker;
        this.spacedDumps = spacedDumps;
        this.liveDumpAllowed = liveDumpAllowed;
    }

    @Override
    public List<BundleAttachment> extras(IncidentReport report, TriggerEvent triggerEvent) {
        List<BundleAttachment> extras = new ArrayList<>(configExtras());

        extras.add(new BundleAttachment("extras/worlds.txt",
            buildWorldsText().getBytes(StandardCharsets.UTF_8)));
        extras.add(new BundleAttachment("extras/heartbeats.txt",
            buildHeartbeatsText().getBytes(StandardCharsets.UTF_8)));
        extras.addAll(threadDumpAttachments(spacedDumps.get(), liveDumpAllowed.getAsBoolean()));
        String mixins = HytaleMixins.methodMapText();
        if (mixins != null) {
            extras.add(new BundleAttachment("extras/mixins.txt", mixins.getBytes(StandardCharsets.UTF_8)));
        }

        if (includeServerLog.getAsBoolean() && logTailLines.getAsInt() > 0) {
            String logTail = nameMasker.maskText(buildServerLogTail(report));
            if (!logTail.isEmpty()) {
                extras.add(new BundleAttachment("extras/server-log.txt",
                    logTail.getBytes(StandardCharsets.UTF_8)));
            }
        }

        extras.addAll(historicalExtras());
        return extras;
    }

    @Override
    public List<BundleAttachment> configExtras() {
        List<BundleAttachment> out = new ArrayList<>();
        out.add(new BundleAttachment("extras/server.txt",
            buildServerText().getBytes(StandardCharsets.UTF_8)));
        out.add(new BundleAttachment("extras/plugins.txt",
            buildPluginsText().getBytes(StandardCharsets.UTF_8)));
        return out;
    }

    @Override
    public List<BundleAttachment> historicalExtras() {
        List<BundleAttachment> out = new ArrayList<>();
        if (metricsDir == null || !Files.isDirectory(metricsDir)) {
            return out;
        }
        try (Stream<Path> files = Files.list(metricsDir)) {
            List<Path> recent = files
                .filter(Files::isRegularFile)
                .filter(p -> {
                    String name = p.getFileName().toString();
                    return name.startsWith("health-") && name.endsWith(".csv");
                })
                .sorted(Comparator.comparing((Path p) -> p.getFileName().toString()).reversed())
                .limit(2)
                .toList();
            for (Path file : recent) {
                out.add(new BundleAttachment("extras/metrics/" + file.getFileName(),
                    readTail(file, Integer.MAX_VALUE, MAX_METRICS_TAIL_BYTES).getBytes(StandardCharsets.UTF_8)));
            }
        } catch (IOException e) {
            LOGGER.log(System.Logger.Level.WARNING, "Failed to attach metrics CSV.", e);
        }
        return out;
    }

    static List<BundleAttachment> threadDumpAttachments(List<String> dumps, boolean liveDumpAllowed) {
        List<BundleAttachment> out = new ArrayList<>();
        if (dumps.isEmpty()) {
            if (liveDumpAllowed) {
                out.add(new BundleAttachment("extras/threads.txt",
                    ThreadDumper.dump().getBytes(StandardCharsets.UTF_8)));
            }
        } else if (dumps.size() == 1) {
            out.add(new BundleAttachment("extras/threads.txt",
                dumps.get(0).getBytes(StandardCharsets.UTF_8)));
        } else {
            int index = 1;
            for (String dump : dumps) {
                out.add(new BundleAttachment("extras/threads-" + index + ".txt",
                    dump.getBytes(StandardCharsets.UTF_8)));
                index++;
            }
        }
        return out;
    }

    private String buildServerText() {
        StringBuilder out = new StringBuilder(256);
        try {
            HytaleServer server = HytaleServer.get();
            out.append("server.name=").append(server.getServerName()).append('\n');
        } catch (Exception e) {
            out.append("server.name=<unavailable>\n");
        }
        try {
            out.append("hytale.version=").append(HytaleServerVersion.get()).append('\n');
        } catch (Exception e) {
            out.append("hytale.version=<unavailable>\n");
        }
        boolean hasNitrado = isClassPresent(NITRADO_WEB_SERVER_CLASS, HytaleBundleExtrasProvider.class.getClassLoader());
        out.append("nitrado.present=").append(hasNitrado).append('\n');
        return out.toString();
    }

    private String buildPluginsText() {
        StringBuilder out = new StringBuilder(512);
        try {
            List<PluginBase> plugins = PluginManager.get().getPlugins();
            out.append("count=").append(plugins.size()).append('\n');
            for (PluginBase plugin : plugins) {
                try {
                    out.append(plugin.getIdentifier())
                        .append('\t').append(plugin.getManifest().getVersion())
                        .append('\t').append(plugin.getName())
                        .append('\n');
                } catch (Exception e) {
                }
            }
        } catch (Exception e) {
            out.append("<unavailable>\n");
        }
        return out.toString();
    }

    private String buildWorldsText() {
        StringBuilder out = new StringBuilder(256);
        try {
            World defaultWorld = Universe.get().getDefaultWorld();
            if (defaultWorld != null) {
                out.append(defaultWorld.getName())
                    .append('\t').append("players=").append(defaultWorld.getPlayerCount())
                    .append('\n');
            } else {
                out.append("<no default world>\n");
            }
        } catch (Exception e) {
            out.append("<unavailable>\n");
        }
        return out.toString();
    }

    private String buildHeartbeatsText() {
        StringBuilder out = new StringBuilder(256);
        Map<String, Instant> snapshot = heartbeatRegistry.snapshot();
        new TreeMap<>(snapshot).forEach((scope, lastBeat) ->
            out.append(scope).append('\t').append(lastBeat).append('\n')
        );
        if (snapshot.isEmpty()) {
            out.append("<no heartbeats recorded>\n");
        }
        return out.toString();
    }

    private String buildServerLogTail(IncidentReport report) {
        if (report != null) {
            for (int i = report.diagnostics().size() - 1; i >= 0; i--) {
                DiagnosticSection section = report.diagnostics().get(i);
                if ("Server log".equals(section.title()) && section.preformatted() != null) {
                    return section.preformatted();
                }
            }
        }
        Path latestLog = latestServerLog();
        if (latestLog == null) {
            return "";
        }
        try {
            return readTail(latestLog, logTailLines.getAsInt());
        } catch (IOException e) {
            LOGGER.log(System.Logger.Level.WARNING, "Failed to read server log tail.", e);
            return "";
        }
    }

    static Path latestServerLog() {
        Path logsDir = Paths.get("logs");
        if (!Files.isDirectory(logsDir)) {
            return null;
        }
        try (Stream<Path> logFiles = Files.list(logsDir)) {
            return logFiles
                .filter(p -> p.getFileName().toString().endsWith(".log"))
                .max(Comparator.comparingLong(p -> {
                    try {
                        return Files.getLastModifiedTime(p).toMillis();
                    } catch (IOException e) {
                        return 0L;
                    }
                }))
                .orElse(null);
        } catch (IOException e) {
            LOGGER.log(System.Logger.Level.WARNING, "Failed to locate server log.", e);
            return null;
        }
    }

    static String readTail(Path file, int lines) throws IOException {
        return readTail(file, lines, MAX_LOG_TAIL_BYTES);
    }

    static String readTail(Path file, int lines, int maxBytes) throws IOException {
        if (lines <= 0 || maxBytes <= 0) {
            return "";
        }
        try (RandomAccessFile raf = new RandomAccessFile(file.toFile(), "r")) {
            long length = raf.length();
            if (length == 0) {
                return "";
            }

            long startPos = Math.max(0, length - maxBytes);
            boolean partialLine = false;
            if (startPos > 0) {
                raf.seek(startPos - 1);
                partialLine = raf.read() != '\n';
            }
            byte[] tailBytes = new byte[(int) (length - startPos)];
            raf.seek(startPos);
            raf.readFully(tailBytes);

            int start = 0;
            if (partialLine) {
                while (start < tailBytes.length && tailBytes[start] != '\n') {
                    start++;
                }
                if (start < tailBytes.length) {
                    start++;
                }
            }
            int newlineCount = 0;
            for (int pos = tailBytes.length - 2; pos >= start; pos--) {
                if (tailBytes[pos] == '\n' && ++newlineCount >= lines) {
                    start = pos + 1;
                    break;
                }
            }
            return new String(tailBytes, start, tailBytes.length - start, StandardCharsets.UTF_8);
        }
    }

    static boolean isClassPresent(String className, ClassLoader classLoader) {
        if (className == null || className.isBlank()) {
            return false;
        }
        try {
            // false => link/load only, do not run static initializers.
            Class.forName(className, false, classLoader);
            return true;
        } catch (ClassNotFoundException e) {
            return false;
        } catch (Throwable t) {
            LOGGER.log(System.Logger.Level.WARNING, "Class presence check failed for '" + className + "'.", t);
            return false;
        }
    }
}
