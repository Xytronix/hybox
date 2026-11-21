package io.github.xytronix.hybox.core.bundle;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.UnaryOperator;
import java.util.regex.Pattern;
import io.github.xytronix.hybox.core.env.PathMasker;
import io.github.xytronix.hybox.core.env.PrivateArguments;
import io.github.xytronix.hybox.core.env.StructuredText;
import io.github.xytronix.hybox.core.env.TextRedactor;
import io.github.xytronix.hybox.core.jfr.RecordingScrubber;

public final class BundleMasker {
    private static final TextRedactor NETWORK_ADDRESSES = new TextRedactor(TextRedactor.NETWORK_PATTERNS);

    private final TextRedactor redactor;
    private final UnaryOperator<String> names;
    private final PathMasker paths;

    public BundleMasker(TextRedactor redactor, UnaryOperator<String> names, PathMasker paths) {
        this.redactor = redactor;
        this.names = names;
        this.paths = Objects.requireNonNull(paths, "paths");
    }

    public String text(String text) {
        if (text == null || text.isEmpty()) {
            return text;
        }
        return paths.apply(names(PrivateArguments.mask(redact(text))));
    }

    public byte[] text(byte[] data) {
        if (data == null || data.length == 0) {
            return data;
        }
        String text = new String(data, StandardCharsets.UTF_8);
        String masked = text(text);
        return text.equals(masked) ? data : masked.getBytes(StandardCharsets.UTF_8);
    }

    public String value(String text) {
        if (text == null || text.isEmpty()) {
            return text;
        }
        return paths.apply(names(PrivateArguments.mask(NETWORK_ADDRESSES.redact(redact(text)))));
    }

    public String json(String json) {
        if (json == null || json.isEmpty()) {
            return json;
        }
        String redacted = redact(json);
        if (names != null) {
            StructuredText.jsonValues(redacted, names::apply);
        }
        return StructuredText.json(redacted, value -> paths.apply(names(PrivateArguments.mask(value))));
    }

    public String html(String html) {
        if (html == null || html.isEmpty()) {
            return html;
        }
        return StructuredText.html(paths.apply(redact(html)), text -> names(PrivateArguments.mask(text)));
    }

    public String report(String part) {
        return StructuredText.isJson(part) ? json(part) : html(part);
    }

    boolean sensitive(String text) {
        if (text.isEmpty()) {
            return false;
        }
        return !paths.apply(names(text)).equals(text)
            || !NETWORK_ADDRESSES.redact(text).equals(text)
            || (redactor != null && !redactor.redact(text).equals(text));
    }

    RecordingScrubber.Result scrub(Path recording, Path target) throws IOException {
        List<UnaryOperator<String>> maskers = new ArrayList<>();
        maskers.add(text -> paths.apply(names(text)));
        maskers.add(NETWORK_ADDRESSES::redact);
        maskers.add(PrivateArguments::mask);
        if (redactor != null) {
            maskers.add(redactor::redact);
        }
        String user = System.getProperty("user.name", "");
        if (user.length() >= 3) {
            Pattern userName = Pattern.compile("(?<![\\w-])" + Pattern.quote(user) + "(?![\\w-])",
                Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
            maskers.add(text -> userName.matcher(text).replaceAll("user"));
        }
        return new RecordingScrubber(this::sensitive, maskers).write(recording, target);
    }

    private String redact(String text) {
        return redactor == null ? text : redactor.redact(text);
    }

    private String names(String text) {
        return names == null ? text : names.apply(text);
    }
}
