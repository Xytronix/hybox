package io.github.xytronix.hybox.core.env;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class PathMaskerTest {

    @Test
    void masksTheMacStartCommandFromARealBundle() {
        PathMasker masker = new PathMasker("/Users/michael", "/Users/michael/Library/Application Support/Hytale/UserData/Saves/Test");
        String command = "java -Djava.library.path=/Users/michael/Library/Java/Extensions:/usr/lib/java "
            + "/Users/michael/Library/Application Support/Hytale/install/release/package/game/latest/Server/HytaleServer.jar "
            + "--assets=/Users/michael/Library/Application Support/Hytale/install/release/package/game/latest/Assets.zip";

        assertEquals("java -Djava.library.path=~/Library/Java/Extensions:.../java "
                + "~/Library/Application Support/Hytale/install/release/package/game/latest/Server/HytaleServer.jar "
                + "--assets=~/Library/Application Support/Hytale/install/release/package/game/latest/Assets.zip",
            masker.apply(command));
    }

    @Test
    void masksServerFolderOtherHomesAndOtherAbsolutePaths() {
        PathMasker masker = new PathMasker("/home/hytale", "/srv/hytale/server");

        assertEquals("./universe/worlds/default", masker.apply("/srv/hytale/server/universe/worlds/default"));
        assertEquals("\"~/.config\"", masker.apply("\"/home/otheruser/.config\""));
        assertEquals("~ and ~\\Mods", masker.apply("/root and C:\\Users\\Bob\\Mods"));
        assertEquals("java.home=.../jdk-25", masker.apply("java.home=/opt/java/jdk-25"));
        assertEquals("{\"p\":\"~\\\\AppData\"}", masker.apply("{\"p\":\"C:\\\\Users\\\\Bob\\\\AppData\"}"));
        assertEquals("file://~/Server.jar file:.../x.jar", masker.apply("file:///home/other/Server.jar file:/opt/mods/x.jar"));
    }

    @Test
    void leavesUrlsClassNamesAndRelativePathsAlone() {
        PathMasker masker = new PathMasker("/home/hytale", "/srv/hytale/server");
        String text = "https://discord.com/api/webhooks/1 java.base/java.lang.Thread.run "
            + "at Hyinit//com.hypixel.hytale.Main universe/worlds/default/chunks/0.0.region.bin "
            + "AES/GCM/NoPadding /hybox status data:image/png;base64,AA+/BB/CC= 11/18/2026";

        assertEquals(text, masker.apply(text));
    }

    @Test
    void masksHomesInAnyCaseAndWithSpacesInTheUserName() {
        PathMasker masker = new PathMasker("/home/hytale", "/srv/hytale/server");

        assertEquals("~", masker.apply("c:\\users\\bob"));
        assertEquals("~", masker.apply("/mnt/c/Users/bob"));
        assertEquals("~/Library/x", masker.apply("/Users/Bob Jones/Library/x"));
        assertEquals("~\\AppData\\x", masker.apply("C:\\Users\\Bob Jones\\AppData\\x"));
        assertEquals("~ is here and ~ went home", masker.apply("/home/bob is here and /Users/bob went home"));
    }

    @Test
    void exactHomeAndServerFolderMatchInAnyCase() {
        assertEquals("~\\AppData\\x", new PathMasker("C:\\Users\\Alice Smith", "D:\\Servers\\hytale")
            .apply("c:\\users\\alice smith\\AppData\\x"));
        assertEquals(".\\mods\\a.jar", new PathMasker("C:\\Users\\Alice Smith", "D:\\Servers\\hytale")
            .apply("d:\\servers\\hytale\\mods\\a.jar"));
    }

    @Test
    void masksNetworkSharesVolumesWithSpacesAndPathsAfterQuotingCharacters() {
        PathMasker masker = new PathMasker("/home/hytale", "/srv/hytale/server");

        assertEquals(".../x.jar", masker.apply("\\\\nas01\\share\\bob\\servers\\x.jar"));
        assertEquals(".../x.jar", masker.apply("/Volumes/Bob Disk/Servers/hytale/mods/x.jar"));
        assertEquals(".../java.exe -jar x", masker.apply("C:\\Program Files\\Java\\jdk-25\\bin\\java.exe -jar x"));
        assertEquals("{~/x} `~/x` @~/args.txt -I~/inc",
            masker.apply("{/Users/bob/x} `/Users/bob/x` @/Users/bob/args.txt -I/Users/bob/inc"));
    }

    @Test
    void maskedTextStaysTheSame() {
        PathMasker masker = new PathMasker("/home/hytale", "/srv/hytale/server");
        String masked = "~/Library/x, ./mods/a.jar and .../java";

        assertEquals(masked, masker.apply(masked));
    }
}
