package io.github.xytronix.hybox.core.bundle;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.NoSuchFileException;
import java.nio.file.StandardCopyOption;
import java.nio.file.Path;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.UnaryOperator;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import io.github.xytronix.hybox.core.env.EnvCollector;
import io.github.xytronix.hybox.core.env.PathMasker;
import io.github.xytronix.hybox.core.env.PrivateArguments;
import io.github.xytronix.hybox.core.env.TextRedactor;
import io.github.xytronix.hybox.core.health.JfrTimeline;
import io.github.xytronix.hybox.core.incident.IncidentReport;
import io.github.xytronix.hybox.core.jfr.RecordingScrubber;
import io.github.xytronix.hybox.core.json.IncidentJson;
import io.github.xytronix.hybox.core.report.ReportHtml;

/**
 * Builds deterministic incident bundles.
 */
public final class BundleBuilder {
    private static final TextRedactor NETWORK_ADDRESSES =
        new TextRedactor(List.of(TextRedactor.IPV4, TextRedactor.IPV6));

    private final Clock clock;
    private final System.Logger logger;
    private final TextRedactor redactor;
    private final UnaryOperator<String> textMasker;
    private final PathMasker pathMasker;

    public BundleBuilder(Clock clock) {
        this(clock, System.getLogger(BundleBuilder.class.getName()));
    }

    public BundleBuilder(Clock clock, System.Logger logger) {
        this(clock, logger, null);
    }

    public BundleBuilder(Clock clock, System.Logger logger, TextRedactor redactor) {
        this(clock, logger, redactor, null);
    }

    public BundleBuilder(Clock clock, System.Logger logger, TextRedactor redactor,
                         UnaryOperator<String> textMasker) {
        this(clock, logger, redactor, textMasker, PathMasker.local());
    }

    public BundleBuilder(Clock clock, System.Logger logger, TextRedactor redactor,
                         UnaryOperator<String> textMasker, PathMasker pathMasker) {
        this.clock = Objects.requireNonNull(clock, "clock");
        this.logger = Objects.requireNonNull(logger, "logger");
        this.redactor = redactor;
        this.textMasker = textMasker;
        this.pathMasker = Objects.requireNonNull(pathMasker, "pathMasker");
    }

    public Path build(
        IncidentReport report,
        Path recordingJfr,
        Path outputZip,
        List<BundleAttachment> extras
    ) throws IOException {
        return build(report, recordingJfr, outputZip, extras, BundleArtifacts.ALL);
    }

    public Path build(
        IncidentReport report,
        Path recordingJfr,
        Path outputZip,
        List<BundleAttachment> extras,
        Set<String> artifacts
    ) throws IOException {
        Objects.requireNonNull(report, "report");
        Objects.requireNonNull(recordingJfr, "recordingJfr");
        Objects.requireNonNull(outputZip, "outputZip");
        Set<String> enabled = artifacts == null ? BundleArtifacts.ALL : artifacts;

        List<BundleAttachment> sortedExtras = new ArrayList<>(extras == null ? List.of() : extras);
        sortedExtras.sort(Comparator.comparing(BundleAttachment::pathInZip));

        Path destination = outputZip.toAbsolutePath();
        Files.createDirectories(destination.getParent());
        Path pending = Files.createTempFile(destination.getParent(), ".hybox-", ".tmp");
        try {
            try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(pending))) {
                writeIncidentJson(report, zip);
                if (enabled.contains(BundleArtifacts.REPORT)) {
                    writeReportHtml(report, recordingJfr, zip);
                }
                if (enabled.contains(BundleArtifacts.JFR)) {
                    writeRecording(recordingJfr, destination.getParent(), zip);
                }
                if (enabled.contains(BundleArtifacts.ENV)) {
                    writeEnvFiles(zip);
                }
                writeExtras(sortedExtras, zip, enabled);
            }
            try {
                Files.move(pending, destination, StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(pending, destination, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            try {
                Files.deleteIfExists(pending);
            } catch (IOException e) {
                logger.log(System.Logger.Level.WARNING, "Failed to remove incomplete bundle " + pending, e);
            }
        }

        return outputZip;
    }

    private void writeIncidentJson(IncidentReport report, ZipOutputStream zip) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        IncidentJson.write(report, buffer);
        ZipEntry entry = zipEntry("incident.json");
        zip.putNextEntry(entry);
        zip.write(maybeRedact(buffer.toByteArray()));
        zip.closeEntry();
    }

    private byte[] maybeRedact(byte[] data) {
        return maybeMask(redactor == null ? data : redactor.redact(data), true);
    }

    private byte[] maybeMask(byte[] data, boolean paths) {
        if (data == null || data.length == 0) {
            return data;
        }
        String text = new String(data, StandardCharsets.UTF_8);
        String masked = textMasker == null ? text : textMasker.apply(text);
        if (paths) {
            masked = pathMasker.apply(masked);
        }
        return text.equals(masked) ? data : masked.getBytes(StandardCharsets.UTF_8);
    }

    private String mask(String text) {
        return pathMasker.apply(textMasker == null ? text : textMasker.apply(text));
    }

    private boolean sensitive(String text) {
        if (text.isEmpty()) {
            return false;
        }
        return !mask(text).equals(text)
            || !NETWORK_ADDRESSES.redact(text).equals(text)
            || (redactor != null && !redactor.redact(text).equals(text));
    }

    private void writeRecording(Path recordingJfr, Path workDir, ZipOutputStream zip) throws IOException {
        if (!Files.isRegularFile(recordingJfr)) {
            throw new NoSuchFileException(recordingJfr.toString());
        }
        List<UnaryOperator<String>> maskers = new ArrayList<>();
        maskers.add(this::mask);
        maskers.add(NETWORK_ADDRESSES::redact);
        maskers.add(PrivateArguments::mask);
        if (redactor != null) {
            maskers.add(redactor::redact);
        }
        String user = System.getProperty("user.name", "");
        if (user.length() >= 3) {
            Pattern userName = Pattern.compile("(?<![\\w-])" + Pattern.quote(user) + "(?![\\w-])");
            maskers.add(text -> userName.matcher(text).replaceAll("user"));
        }
        Path cleaned = Files.createTempFile(workDir, ".hybox-", ".jfr.tmp");
        try {
            RecordingScrubber.Result result;
            try {
                result = new RecordingScrubber(this::sensitive, maskers).write(recordingJfr, cleaned);
            } catch (IOException | RuntimeException e) {
                logger.log(System.Logger.Level.WARNING, "Left recording.jfr out of the bundle because it could "
                    + "not be cleaned of private data. The report still uses the full recording.", e);
                return;
            }
            if (result.fallback() != null) {
                logger.log(System.Logger.Level.WARNING, "Could not mask private data in recording.jfr in place "
                    + "because " + result.fallback() + ". Left out every event with private data instead.",
                    result.failure());
            }
            long total = result.dropped().values().stream().mapToLong(Long::longValue).sum();
            logger.log(System.Logger.Level.INFO, "Cleaned recording.jfr for sharing: masked private data in "
                + result.maskedStrings() + " strings and left out " + total + " events " + result.dropped() + ".");
            zip.putNextEntry(zipEntry("recording.jfr"));
            Files.copy(cleaned, zip);
            zip.closeEntry();
        } finally {
            Files.deleteIfExists(cleaned);
        }
    }

    private void writeReportHtml(IncidentReport report, Path recordingJfr, ZipOutputStream zip) throws IOException {
        JfrTimeline timeline = null;
        String unavailableReason = null;
        try {
            if (!Files.isRegularFile(recordingJfr)) {
                unavailableReason = "recording file is missing";
            } else if (Files.size(recordingJfr) == 0) {
                unavailableReason = "recording file is empty";
            } else {
                timeline = JfrTimeline.parse(recordingJfr, report.meta().createdAt());
                if (timeline == null) {
                    unavailableReason = "recording is empty, unreadable, or contains no usable events";
                }
            }
        } catch (Exception e) {
            unavailableReason = "recording could not be read: " + e.getMessage();
            logger.log(System.Logger.Level.WARNING, "JFR timeline parse failed. The report has no charts.", e);
        }
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        ReportHtml.write(report, timeline, unavailableReason, pathMasker, buffer);
        ZipEntry entry = zipEntry("report.html");
        zip.putNextEntry(entry);
        byte[] html = buffer.toByteArray();
        zip.write(maybeMask(redactor == null ? html : redactor.redact(html), false));
        zip.closeEntry();
    }

    private void writeEnvFiles(ZipOutputStream zip) throws IOException {
        ZipEntry jvm = zipEntry("env/jvm.txt");
        zip.putNextEntry(jvm);
        zip.write(maybeRedact(EnvCollector.jvmInfo().getBytes(StandardCharsets.UTF_8)));
        zip.closeEntry();

        ZipEntry os = zipEntry("env/os.txt");
        zip.putNextEntry(os);
        zip.write(maybeRedact(EnvCollector.osInfo().getBytes(StandardCharsets.UTF_8)));
        zip.closeEntry();
    }

    private void writeExtras(List<BundleAttachment> extras, ZipOutputStream zip, Set<String> enabled) throws IOException {
        Set<String> written = new HashSet<>(List.of("incident.json", "report.html", "recording.jfr",
            "env/jvm.txt", "env/os.txt"));
        for (BundleAttachment extra : extras) {
            String key = BundleArtifacts.keyForPath(extra.pathInZip());
            if (key != null && !enabled.contains(key)) {
                continue;
            }
            if (!written.add(extra.pathInZip())) {
                logger.log(System.Logger.Level.WARNING,
                    "Skipping duplicate bundle entry " + extra.pathInZip() + ". The first entry is kept.");
                continue;
            }
            ZipEntry entry = zipEntry(extra.pathInZip());
            zip.putNextEntry(entry);
            zip.write(extra.isText() ? maybeMask(extra.data(), true) : extra.data());
            zip.closeEntry();
        }
    }

    private static ZipEntry zipEntry(String name) {
        ZipEntry entry = new ZipEntry(name);
        entry.setTime(0L);
        return entry;
    }
}
