package io.github.xytronix.hybox.hytale;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.UUID;
import org.junit.jupiter.api.Test;

class ServerLogMaskerTest {
    private static final String INFO = "[2026/11/19 10:00:00   INFO]                 ";

    private static ServerLogMasker maskerKnowing(String... names) {
        PlayerNameMasker nameMasker = new PlayerNameMasker();
        for (String name : names) {
            nameMasker.remember(name, UUID.nameUUIDFromBytes(name.getBytes()));
        }
        return new ServerLogMasker(nameMasker);
    }

    @Test
    void defaultChatFormatIsHiddenWithPlayerToken() {
        ServerLogMasker masker = maskerKnowing("Steve");

        assertEquals(INFO + "[Hytale] [chat line hidden, player-1]\n",
            masker.mask(INFO + "[Hytale] Steve: meet me at the base 120 64 -300\n"));
    }

    @Test
    void defaultChatFormatFromBeforeStartIsHiddenWithoutToken() {
        ServerLogMasker masker = maskerKnowing();

        assertEquals(INFO + "[Hytale] [chat line hidden]",
            masker.mask(INFO + "[Hytale] Alex: my password is hunter2"));
    }

    @Test
    void customChatFormatWithAnsiCodesIsHiddenByRecentContent() {
        ServerLogMasker masker = maskerKnowing("lucko");
        masker.rememberChat("hello! see you at spawn");
        String line = INFO + "[Hytale] \u001B[31m[ADMIN] \u001B[mlucko\u001B[37m: \u001B[mhello! see you at spawn\u001B[m";
        String world = INFO + "[Hytale] (\u001B[32mdefault\u001B[m) \u001B[33m~Luc\u001B[m >> hello! see you at spawn";

        assertEquals(INFO + "[Hytale] [chat line hidden, player-1]\n" + INFO + "[Hytale] [chat line hidden]",
            masker.mask(line + "\n" + world));
    }

    @Test
    void customChatFormatFromBeforeStartIsHiddenWhenTheSpeakerIsKnown() {
        ServerLogMasker masker = maskerKnowing("lucko");

        assertEquals(INFO + "[Hytale] [chat line hidden, player-1]",
            masker.mask(INFO + "[Hytale] \u001B[31m[ADMIN] \u001B[mlucko\u001B[37m: \u001B[mhello!\u001B[m"));
    }

    @Test
    void commandArgumentsAreHidden() {
        ServerLogMasker masker = maskerKnowing("Steve");

        assertEquals("[2026/11/19 10:00:01   INFO]          [CommandManager] player-1 executed command: msg [arguments hidden]",
            masker.mask("[2026/11/19 10:00:01   INFO]          [CommandManager] Steve executed command: msg Alex the code is 4417"));
    }

    @Test
    void luckPermsCommandKeepsOnlyTheRootCommand() {
        ServerLogMasker masker = maskerKnowing();

        assertEquals(INFO + "[CommandManager] CONSOLE executed command: lp [arguments hidden]\n"
                + "Command not found! lp [arguments hidden]",
            masker.mask(INFO + "[CommandManager] CONSOLE executed command: lp user Steve permission set admin.*\n"
                + "Command not found! lp user Steve permission set admin.*"));
    }

    @Test
    void hyboxCommandKeepsItsArguments() {
        ServerLogMasker masker = maskerKnowing();
        String line = INFO + "[CommandManager] CONSOLE executed command: hybox capture --reason lag";

        assertEquals(line, masker.mask(line));
    }

    @Test
    void lineWithAColonIsNotHidden() {
        ServerLogMasker masker = maskerKnowing("Steve");
        String text = INFO + "[Hytale] Booting world: default\n"
            + INFO + "[Universe|P] Loaded: 3 worlds\n"
            + "[2026/11/19 10:00:02   WARN]       [PluginManager] Config error at line 4: unknown key\n"
            + INFO + "[CommandManager] CONSOLE executed command: stop";

        assertEquals(text, masker.mask(text));
    }
}
