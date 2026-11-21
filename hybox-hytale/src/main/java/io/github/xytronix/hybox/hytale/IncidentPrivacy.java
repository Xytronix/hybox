package io.github.xytronix.hybox.hytale;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import com.google.gson.stream.JsonReader;
import io.github.xytronix.hybox.core.bundle.BundleAttachment;
import io.github.xytronix.hybox.core.bundle.BundleMasker;
import io.github.xytronix.hybox.core.env.PathMasker;
import io.github.xytronix.hybox.core.env.TextRedactor;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.AccessDeniedException;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.Collections;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

final class IncidentPrivacy {
    private static final Pattern DATA = Pattern.compile("\\bconst\\s+D\\s*=\\s*");
    private static final Masking FALLBACK = new Masking(
        new BundleMasker(new TextRedactor(TextRedactor.DEFAULT_PATTERNS), null, PathMasker.local()), null);

    private record Masking(BundleMasker masker, PlayerNameMasker names) {
    }

    private static volatile Masking current = FALLBACK;

    private IncidentPrivacy() {
    }

    static void install(BundleMasker masker, PlayerNameMasker names) {
        current = new Masking(masker, names);
    }

    static void reset() {
        current = FALLBACK;
    }

    static String text(String text) {
        return current.masker().value(text);
    }

    static void mask(JsonElement... roots) {
        Masking masking = current;
        if (masking.names() != null) {
            for (JsonElement root : roots) {
                learn(root, null, masking.names());
            }
        }
        for (JsonElement root : roots) {
            mask(root, masking.masker());
        }
    }

    static String failure(Throwable failure) {
        if (failure instanceof NoSuchFileException || failure instanceof FileNotFoundException) {
            return "the bundle file is missing.";
        }
        if (failure instanceof AccessDeniedException) {
            return "Hybox cannot read the bundle file.";
        }
        String message = failure.getMessage();
        return message == null || message.isBlank() ? failure.getClass().getSimpleName() : text(message);
    }

    static void learn(Path bundle, PlayerNameMasker names) throws IOException {
        try (ZipFile zip = new ZipFile(bundle.toFile(), StandardCharsets.UTF_8)) {
            for (ZipEntry entry : Collections.list(zip.entries())) {
                String name = entry.getName();
                if (entry.isDirectory() || !BundleAttachment.isText(name)) {
                    continue;
                }
                String text;
                try (InputStream input = zip.getInputStream(entry)) {
                    text = new String(input.readAllBytes(), StandardCharsets.UTF_8);
                }
                names.learnFromLog(text);
                names.learnFromCommand(text);
                if (name.equals("incident.json")) {
                    learnJson(text, names);
                } else if (name.equals("report.html")) {
                    Matcher data = DATA.matcher(text);
                    if (data.find()) {
                        learnJson(text.substring(data.end()), names);
                    }
                }
            }
        }
    }

    private static void learnJson(String json, PlayerNameMasker names) throws IOException {
        try (JsonReader reader = new JsonReader(new StringReader(json))) {
            learn(JsonParser.parseReader(reader), null, names);
        } catch (JsonParseException | IllegalStateException ignored) {
            return;
        }
    }

    private static void learn(JsonElement element, String key, PlayerNameMasker names) {
        if (element == null || element.isJsonNull()) {
            return;
        }
        if (element.isJsonObject()) {
            for (Map.Entry<String, JsonElement> field : element.getAsJsonObject().entrySet()) {
                learn(field.getValue(), field.getKey(), names);
            }
        } else if (element.isJsonArray()) {
            for (JsonElement item : element.getAsJsonArray()) {
                if ("playerNames".equals(key) && item.isJsonPrimitive()) {
                    names.learn(item.getAsString());
                } else {
                    learn(item, key, names);
                }
            }
        } else if (element.getAsJsonPrimitive().isString()) {
            String text = element.getAsString();
            names.learnFromLog(text);
            names.learnFromCommand(text);
        }
    }

    private static JsonElement mask(JsonElement element, BundleMasker masker) {
        if (element == null || element.isJsonNull()) {
            return element;
        }
        if (element.isJsonObject()) {
            JsonObject object = element.getAsJsonObject();
            for (Map.Entry<String, JsonElement> field : object.entrySet()) {
                JsonElement masked = mask(field.getValue(), masker);
                if (masked != field.getValue()) {
                    field.setValue(masked);
                }
            }
            return element;
        }
        if (element.isJsonArray()) {
            JsonArray array = element.getAsJsonArray();
            for (int i = 0; i < array.size(); i++) {
                JsonElement masked = mask(array.get(i), masker);
                if (masked != array.get(i)) {
                    array.set(i, masked);
                }
            }
            return element;
        }
        JsonPrimitive primitive = element.getAsJsonPrimitive();
        if (!primitive.isString()) {
            return element;
        }
        String text = primitive.getAsString();
        String masked = masker.value(text);
        return masked == null || masked.equals(text) ? element : new JsonPrimitive(masked);
    }
}
