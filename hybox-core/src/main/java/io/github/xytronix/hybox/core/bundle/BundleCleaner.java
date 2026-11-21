package io.github.xytronix.hybox.core.bundle;

import java.io.IOException;
import java.io.InputStream;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;
import io.github.xytronix.hybox.core.env.StructuredText;
import io.github.xytronix.hybox.core.json.IncidentJson;
import io.github.xytronix.hybox.core.json.JsonScanner;
import io.github.xytronix.hybox.core.json.JsonWriter;

public final class BundleCleaner {
    private static final Pattern DATA = Pattern.compile("\\bconst\\s+D\\s*=\\s*");
    private static final Pattern RAW_ELEMENT = Pattern.compile("(<(script|style)\\b[^>]*>)(.*?)(</\\2\\s*>)",
        Pattern.CASE_INSENSITIVE | Pattern.DOTALL);

    private final BundleMasker masker;
    private final System.Logger logger;

    public BundleCleaner(BundleMasker masker, System.Logger logger) {
        this.masker = Objects.requireNonNull(masker, "masker");
        this.logger = Objects.requireNonNull(logger, "logger");
    }

    public static boolean cleaned(Path bundle) throws IOException {
        try (ZipFile zip = new ZipFile(bundle.toFile(), StandardCharsets.UTF_8)) {
            ZipEntry entry = zip.getEntry("incident.json");
            return entry != null && schema(read(zip, entry)) >= IncidentJson.CLEANED_SCHEMA;
        }
    }

    static int schema(String incidentJson) {
        try {
            Object meta = new JsonScanner(incidentJson, 0).object(Set.of("meta")).get("meta");
            return meta instanceof Map<?, ?> fields && fields.get(IncidentJson.CLEANED) instanceof Number schema
                ? schema.intValue() : 0;
        } catch (IOException unreadable) {
            return 0;
        }
    }

    public void clean(Path bundle) throws IOException {
        Path destination = bundle.toAbsolutePath();
        Path directory = destination.getParent();
        Path pending = Files.createTempFile(directory, ".hybox-", ".tmp");
        try {
            try (ZipFile source = new ZipFile(destination.toFile(), StandardCharsets.UTF_8);
                 ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(pending))) {
                if (source.getEntry("incident.json") == null) {
                    throw new IOException("the bundle has no incident.json");
                }
                for (ZipEntry entry : Collections.list(source.entries())) {
                    copy(source, entry, out, directory);
                }
            }
            if (!Files.exists(destination)) {
                return;
            }
            try {
                Files.move(pending, destination, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(pending, destination, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(pending);
        }
    }

    private void copy(ZipFile source, ZipEntry entry, ZipOutputStream out, Path directory) throws IOException {
        ZipEntry copy = new ZipEntry(entry.getName());
        copy.setTime(entry.getTime());
        out.putNextEntry(copy);
        String name = entry.getName();
        if (entry.isDirectory()) {
            out.closeEntry();
            return;
        }
        if (name.equals("incident.json")) {
            out.write(incidentJson(read(source, entry)).getBytes(StandardCharsets.UTF_8));
        } else if (name.equals("report.html")) {
            out.write(report(read(source, entry)).getBytes(StandardCharsets.UTF_8));
        } else if (name.equals("recording.jfr")) {
            recording(source, entry, out, directory);
        } else if (BundleAttachment.isText(name)) {
            try (InputStream input = source.getInputStream(entry)) {
                out.write(masker.text(input.readAllBytes()));
            }
        } else {
            try (InputStream input = source.getInputStream(entry)) {
                input.transferTo(out);
            }
        }
        out.closeEntry();
    }

    String incidentJson(String json) throws IOException {
        Object root = new JsonScanner(masker.json(json), 0).value();
        if (!(root instanceof Map<?, ?> fields) || !(fields.get("meta") instanceof Map<?, ?> meta)) {
            throw new IOException("incident.json has no meta object");
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> marked = (Map<String, Object>) meta;
        marked.put(IncidentJson.CLEANED, IncidentJson.CLEANED_SCHEMA);
        StringWriter text = new StringWriter();
        write(new JsonWriter(text), root);
        return text.toString();
    }

    String report(String html) throws IOException {
        StringBuilder out = new StringBuilder(html.length());
        Matcher raw = RAW_ELEMENT.matcher(html);
        int at = 0;
        while (raw.find()) {
            out.append(StructuredText.html(html.substring(at, raw.start()), masker::text));
            out.append(raw.group(1)).append(script(raw.group(2), raw.group(3))).append(raw.group(4));
            at = raw.end();
        }
        out.append(StructuredText.html(html.substring(at), masker::text));
        return out.toString();
    }

    private String script(String tag, String body) throws IOException {
        Matcher data = DATA.matcher(body);
        if (!tag.equalsIgnoreCase("script") || !data.find()) {
            return body;
        }
        int start = data.end();
        int end = StructuredText.jsonEnd(body, start);
        if (end < 0) {
            throw new IOException("the report data is cut short");
        }
        return body.substring(0, start) + masker.json(body.substring(start, end)).replace("</", "<\\/")
            + body.substring(end);
    }

    private void recording(ZipFile source, ZipEntry entry, ZipOutputStream out, Path directory) throws IOException {
        Path original = Files.createTempFile(directory, ".hybox-", ".jfr.tmp");
        Path cleaned = Files.createTempFile(directory, ".hybox-", ".jfr.tmp");
        try {
            try (InputStream input = source.getInputStream(entry)) {
                Files.copy(input, original, StandardCopyOption.REPLACE_EXISTING);
            }
            BundleBuilder.logScrub(logger, masker.scrub(original, cleaned));
            if (!Files.isRegularFile(cleaned)) {
                throw new NoSuchFileException(cleaned.toString());
            }
            Files.copy(cleaned, out);
        } finally {
            Files.deleteIfExists(original);
            Files.deleteIfExists(cleaned);
        }
    }

    private static String read(ZipFile zip, ZipEntry entry) throws IOException {
        try (InputStream input = zip.getInputStream(entry)) {
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static void write(JsonWriter json, Object value) throws IOException {
        if (value == null) {
            json.nullValue();
        } else if (value instanceof Map<?, ?> map) {
            json.beginObject();
            for (Map.Entry<?, ?> field : map.entrySet()) {
                json.name(String.valueOf(field.getKey()));
                write(json, field.getValue());
            }
            json.endObject();
        } else if (value instanceof List<?> list) {
            json.beginArray();
            for (Object item : list) {
                write(json, item);
            }
            json.endArray();
        } else if (value instanceof Boolean flag) {
            json.value(flag);
        } else if (value instanceof Double number && number == Math.rint(number) && Math.abs(number) < 1e15) {
            json.value(number.longValue());
        } else if (value instanceof Number number) {
            json.value(number);
        } else {
            json.value(value.toString());
        }
    }
}
