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
}
