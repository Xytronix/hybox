package io.github.xytronix.hybox.core.bundle;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.StandardCopyOption;
import java.nio.file.Path;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.UnaryOperator;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import io.github.xytronix.hybox.core.env.EnvCollector;
import io.github.xytronix.hybox.core.env.TextRedactor;
import io.github.xytronix.hybox.core.health.JfrTimeline;
import io.github.xytronix.hybox.core.incident.IncidentReport;
import io.github.xytronix.hybox.core.json.IncidentJson;
import io.github.xytronix.hybox.core.report.ReportHtml;

/**
 * Builds deterministic incident bundles.
 */
public final class BundleBuilder {
    private final Clock clock;
    private final System.Logger logger;
    private final TextRedactor redactor;
    private final UnaryOperator<String> textMasker;

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
        this.clock = Objects.requireNonNull(clock, "clock");
        this.logger = Objects.requireNonNull(logger, "logger");
        this.redactor = redactor;
        this.textMasker = textMasker;
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
                    writeRecording(recordingJfr, zip);
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
        return maybeMask(redactor == null ? data : redactor.redact(data));
    }

    private byte[] maybeMask(byte[] data) {
        if (textMasker == null || data == null || data.length == 0) {
            return data;
        }
        String text = new String(data, StandardCharsets.UTF_8);
        String masked = textMasker.apply(text);
        return text.equals(masked) ? data : masked.getBytes(StandardCharsets.UTF_8);
    }

    private void writeRecording(Path recordingJfr, ZipOutputStream zip) throws IOException {
        ZipEntry entry = zipEntry("recording.jfr");
        zip.putNextEntry(entry);
        try (InputStream in = Files.newInputStream(recordingJfr)) {
            in.transferTo(zip);
        }
        zip.closeEntry();
    }

    private void writeReportHtml(IncidentReport report, Path recordingJfr, ZipOutputStream zip) throws IOException {
        JfrTimeline timeline = null;
        try {
            timeline = JfrTimeline.parse(recordingJfr);
        } catch (Exception e) {
            logger.log(System.Logger.Level.WARNING, "JFR timeline parse failed; report renders without series.", e);
        }
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        ReportHtml.write(report, timeline, buffer);
        ZipEntry entry = zipEntry("report.html");
        zip.putNextEntry(entry);
        zip.write(maybeRedact(buffer.toByteArray()));
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
        for (BundleAttachment extra : extras) {
            String key = BundleArtifacts.keyForPath(extra.pathInZip());
            if (key != null && !enabled.contains(key)) {
                continue;
            }
            ZipEntry entry = zipEntry(extra.pathInZip());
            zip.putNextEntry(entry);
            zip.write(extra.isText() ? maybeMask(extra.data()) : extra.data());
            zip.closeEntry();
        }
    }

    private static ZipEntry zipEntry(String name) {
        ZipEntry entry = new ZipEntry(name);
        entry.setTime(0L);
        return entry;
    }
}
