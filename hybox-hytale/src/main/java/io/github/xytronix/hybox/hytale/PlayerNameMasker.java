package io.github.xytronix.hybox.hytale;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class PlayerNameMasker {
    private static final int MAX_TRACKED = 4096;

    private static final String NAME_RE = "\\w[\\w-]{1,31}";
    private static final String UUID_RE =
        "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}";
    private static final String BEFORE = "(?<![A-Za-z0-9])";
    private static final String AFTER = "(?![A-Za-z0-9])";
    private static final Set<String> COMMON_WORDS = Set.of("info", "warn", "warning", "error", "severe", "fine",
        "finer", "finest", "config", "debug", "trace", "critical", "degraded", "default", "server", "world", "admin",
        "console", "system", "spawn", "lobby", "hub", "main", "test", "instance");

    private static final Pattern LOG_DISCONNECT_LINE = Pattern.compile("\\bDisconnecting (" + NAME_RE + ") at ");
    private static final Pattern LOG_PLAYER_LINE =
        Pattern.compile("\\bPlayer '(" + NAME_RE + ")' \\((" + UUID_RE + ")\\)");
    private static final Pattern LOG_LOGIN_NAME_UUID =
        Pattern.compile(", (" + NAME_RE + "), (" + UUID_RE + ")\\}");
    private static final Pattern LOG_LOGIN_UUID_NAME =
        Pattern.compile(", (" + UUID_RE + "), (" + NAME_RE + ")\\}");
    private static final Pattern OWNER_NAME = Pattern.compile("--owner-name(?:=|\\s+)[\"']?(" + NAME_RE + ")");
    private static final Pattern OWNER_UUID = Pattern.compile("--owner-uuid(?:=|\\s+)[\"']?(" + UUID_RE + ")");
    private static final Pattern NAME = Pattern.compile(NAME_RE);
    private static final Pattern RESERVED = Pattern.compile(
        "(?i)player(?:-\\d+|-\\?)?|unknown|true|false|null|\\d+|\\[?redacted\\]?");

    private record Masked(String token, Pattern pattern, String raw, List<String> probes) {
        boolean in(String lower) {
            for (String probe : probes) {
                if (lower.contains(probe)) {
                    return true;
                }
            }
            return false;
        }
    }

    private final ConcurrentHashMap<String, Masked> masked = new ConcurrentHashMap<>();
    private final AtomicInteger counter = new AtomicInteger();
    private volatile List<Masked> longestFirst = List.of();

    String maskName(String name) {
        if (name == null || name.isBlank()) {
            return "unknown";
        }
        Masked entry = entryFor(name.trim());
        return entry == null ? "player-?" : entry.token();
    }

    void remember(String name, UUID uuid) {
        if (name == null || name.isBlank()) {
            return;
        }
        learnPair(name.trim(), uuid == null ? null : uuid.toString());
    }

    void learn(String name) {
        if (name != null && NAME.matcher(name.trim()).matches()) {
            entryFor(name.trim());
        }
    }

    private void learnPair(String name, String uuid) {
        Masked entry = entryFor(name);
        if (entry != null && uuid != null) {
            String key = uuid.toLowerCase(Locale.ROOT);
            add(key, () -> new Masked(entry.token(), uuidPattern(key), uuid, List.of(key, key.replace("-", ""))));
        }
    }

    String maskText(String text) {
        if (text == null || text.isEmpty()) {
            return text;
        }
        learnFromLog(text);
        if (masked.isEmpty()) {
            return text;
        }
        String lower = text.toLowerCase(Locale.ROOT);
        for (Masked entry : longestFirst) {
            if (!entry.in(lower)) {
                continue;
            }
            text = entry.pattern().matcher(text).replaceAll(Matcher.quoteReplacement(entry.token()));
        }
        return text;
    }

    String tokenIn(String text) {
        if (text == null || text.isEmpty()) {
            return null;
        }
        String lower = text.toLowerCase(Locale.ROOT);
        String token = null;
        int first = Integer.MAX_VALUE;
        for (Masked entry : longestFirst) {
            if (!entry.in(lower)) {
                continue;
            }
            Matcher m = entry.pattern().matcher(text);
            if (m.find() && m.start() < first) {
                first = m.start();
                token = entry.token();
            }
        }
        return token;
    }

    boolean hasSpeaker(String text) {
        if (text == null || text.isEmpty()) {
            return false;
        }
        for (Masked entry : masked.values()) {
            String speaker = entry.raw() + ": ";
            for (int at = text.indexOf(speaker); at >= 0; at = text.indexOf(speaker, at + 1)) {
                if (at == 0 || !Character.isLetterOrDigit(text.charAt(at - 1)) && text.charAt(at - 1) != '_') {
                    return true;
                }
            }
        }
        return false;
    }

    void learnFromLog(String text) {
        Matcher m = LOG_DISCONNECT_LINE.matcher(text);
        while (m.find()) {
            entryFor(m.group(1));
        }
        m = LOG_PLAYER_LINE.matcher(text);
        while (m.find()) {
            learnPair(m.group(1), m.group(2));
        }
        m = LOG_LOGIN_NAME_UUID.matcher(text);
        while (m.find()) {
            learnPair(m.group(1), m.group(2));
        }
        m = LOG_LOGIN_UUID_NAME.matcher(text);
        while (m.find()) {
            learnPair(m.group(2), m.group(1));
        }
    }

    void learnFromCommand(String command) {
        if (command == null || command.isEmpty()) {
            return;
        }
        Matcher name = OWNER_NAME.matcher(command);
        Matcher uuid = OWNER_UUID.matcher(command);
        String owner = name.find() ? name.group(1) : null;
        String id = uuid.find() ? uuid.group(1) : null;
        if (owner != null) {
            learnPair(owner, id);
        } else if (id != null && masked.size() < MAX_TRACKED) {
            String key = id.toLowerCase(Locale.ROOT);
            add(key, () -> new Masked("player-" + counter.incrementAndGet(), uuidPattern(key), id,
                List.of(key, key.replace("-", ""))));
        }
    }

    private Masked entryFor(String name) {
        if (RESERVED.matcher(name).matches()) {
            return null;
        }
        String key = name.toLowerCase(Locale.ROOT);
        Masked existing = masked.get(key);
        if (existing != null) {
            return existing;
        }
        if (masked.size() >= MAX_TRACKED) {
            return null;
        }
        int flags = COMMON_WORDS.contains(key) ? 0 : Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE;
        return add(key, () -> new Masked("player-" + counter.incrementAndGet(),
            Pattern.compile(BEFORE + Pattern.quote(name) + AFTER, flags), name, List.of(key)));
    }

    private Masked add(String key, Supplier<Masked> entry) {
        boolean[] created = {false};
        Masked result = masked.computeIfAbsent(key, k -> {
            created[0] = true;
            return entry.get();
        });
        if (created[0]) {
            sortLongestFirst();
        }
        return result;
    }

    private synchronized void sortLongestFirst() {
        List<Masked> all = new ArrayList<>(masked.values());
        all.sort(Comparator.comparingInt((Masked entry) -> entry.raw().length()).reversed());
        longestFirst = List.copyOf(all);
    }

    private static Pattern uuidPattern(String lowerUuid) {
        return Pattern.compile(BEFORE + "(?:" + Pattern.quote(lowerUuid) + "|"
            + Pattern.quote(lowerUuid.replace("-", "")) + ")" + AFTER, Pattern.CASE_INSENSITIVE);
    }
}
