package io.github.xytronix.hybox.hytale;

import com.hypixel.hytale.event.EventPriority;
import com.hypixel.hytale.server.core.event.events.player.PlayerChatEvent;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class ServerLogMasker {
    private static final int MAX_RECENT = 256;
    private static final int MAX_CONTENT_CHARS = 1024;
    private static final String ROOT_LOGGER = "Hytale";
    private static final String HYBOX_COMMAND = "hybox";
    private static final String ARGUMENTS_HIDDEN = "[arguments hidden]";

    private static final Pattern ANSI = Pattern.compile("\u001B\\[[0-9;:?]*[A-Za-z]");
    private static final Pattern HEADER =
        Pattern.compile("^(\\[\\d{4}/\\d{2}/\\d{2} \\d{2}:\\d{2}:\\d{2} +[A-Z]+\\] *\\[([^\\]]*)\\] )(.*)$");
    private static final Pattern COMMAND =
        Pattern.compile("^(.+? (?:executed|sent) command: |Command not found! )(\\S+)(\\s+\\S.*)?$");
    private static final Pattern DEFAULT_CHAT = Pattern.compile("^(\\w[\\w-]{1,31}): \\S.*$");

    private final PlayerNameMasker nameMasker;
    private final ArrayDeque<String> recentChat = new ArrayDeque<>();

    ServerLogMasker(PlayerNameMasker nameMasker) {
        this.nameMasker = Objects.requireNonNull(nameMasker, "nameMasker");
    }

    void register(HyboxPlugin plugin, System.Logger logger) {
        try {
            plugin.getEventRegistry().<String, PlayerChatEvent>registerAsyncGlobal(EventPriority.LAST,
                PlayerChatEvent.class, future -> future.thenApply(event -> {
                    onChat(event);
                    return event;
                }));
        } catch (RuntimeException | LinkageError failure) {
            logger.log(System.Logger.Level.WARNING,
                "Cannot follow chat. Chat lines in the server log are hidden by their format only.", failure);
        }
    }

    private void onChat(PlayerChatEvent event) {
        try {
            PlayerRef sender = event.getSender();
            if (sender != null) {
                nameMasker.remember(sender.getUsername(), sender.getUuid());
            }
            rememberChat(event.getContent());
        } catch (RuntimeException ignored) {
        }
    }

    void rememberChat(String content) {
        if (content == null) {
            return;
        }
        for (String part : content.split("\n")) {
            String trimmed = ANSI.matcher(part).replaceAll("").strip();
            if (trimmed.isEmpty()) {
                continue;
            }
            if (trimmed.length() > MAX_CONTENT_CHARS) {
                trimmed = trimmed.substring(0, MAX_CONTENT_CHARS);
            }
            synchronized (recentChat) {
                recentChat.remove(trimmed);
                recentChat.addLast(trimmed);
                while (recentChat.size() > MAX_RECENT) {
                    recentChat.removeFirst();
                }
            }
        }
    }

    String mask(String text) {
        if (text == null || text.isEmpty()) {
            return text;
        }
        String plain = ANSI.matcher(text).replaceAll("");
        nameMasker.learnFromLog(plain);
        List<String> chat;
        synchronized (recentChat) {
            chat = List.copyOf(recentChat);
        }
        String[] lines = plain.split("\n", -1);
        StringBuilder out = new StringBuilder(plain.length());
        for (int i = 0; i < lines.length; i++) {
            if (i > 0) {
                out.append('\n');
            }
            String line = lines[i];
            boolean carriageReturn = line.endsWith("\r");
            if (carriageReturn) {
                line = line.substring(0, line.length() - 1);
            }
            out.append(maskLine(line, chat));
            if (carriageReturn) {
                out.append('\r');
            }
        }
        return nameMasker.maskText(out.toString());
    }

    private String maskLine(String line, List<String> chat) {
        Matcher header = HEADER.matcher(line);
        boolean hasHeader = header.matches();
        String prefix = hasHeader ? header.group(1) : "";
        String message = hasHeader ? header.group(3) : line;
        Matcher command = COMMAND.matcher(message);
        if (command.matches()) {
            if (command.group(3) == null || HYBOX_COMMAND.equals(rootName(command.group(2)))) {
                return line;
            }
            return prefix + command.group(1) + command.group(2) + " " + ARGUMENTS_HIDDEN;
        }
        if (hasHeader && ROOT_LOGGER.equals(header.group(2)) && isChat(message, chat)) {
            String token = nameMasker.tokenIn(message);
            return prefix + (token == null ? "[chat line hidden]" : "[chat line hidden, " + token + "]");
        }
        return line;
    }

    private boolean isChat(String message, List<String> chat) {
        if (DEFAULT_CHAT.matcher(message).matches() || nameMasker.hasSpeaker(message)) {
            return true;
        }
        for (String content : chat) {
            if (message.contains(content)) {
                return true;
            }
        }
        return false;
    }

    private static String rootName(String command) {
        String name = command.startsWith("/") ? command.substring(1) : command;
        return name.toLowerCase(Locale.ROOT);
    }
}
