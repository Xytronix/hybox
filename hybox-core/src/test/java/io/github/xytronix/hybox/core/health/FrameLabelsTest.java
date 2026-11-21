package io.github.xytronix.hybox.core.health;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class FrameLabelsTest {
    private static final String TICKING = "com.hypixel.hytale.server.core.util.thread.TickingThread";
    private static final String LABEL = "changed by Mixin from Refixes (MixinTickSleep)";

    @AfterEach
    void reset() {
        FrameLabels.install(null);
    }

    @Test
    void blockedInShowsTheOriginalMethodWithoutGeneratedSuffixes() {
        assertEquals("run", FrameLabels.originalMethod("run$mixinextras$wrapped$39"));
        assertEquals("run", FrameLabels.originalMethod("mixinextras$bridge$run$mixinextras$wrapped$39$40"));
        assertEquals("tick", FrameLabels.originalMethod("tick"));
        assertEquals(TICKING + ".run", FrameLabels.display("Hyinit//" + TICKING + ".run$mixinextras$wrapped$39"));
    }

    @Test
    void blockedInAddsTheMixinLabelWhenHyinitNamesTheMod() {
        FrameLabels.install((className, methodName) -> TICKING.equals(className) && "run".equals(methodName) ? LABEL : null);

        assertEquals(TICKING + ".run, " + LABEL, FrameLabels.display(TICKING + ".run$mixinextras$wrapped$39"));
        assertEquals(TICKING + ".run, " + LABEL,
            FrameLabels.display(TICKING + ".mixinextras$bridge$run$mixinextras$wrapped$39$40"));
    }

    @Test
    void mixinCodeIsNamedByTheHyinitLabelOrTheFabricJarDecorationButNotForChangedEngineMethods() {
        assertEquals("Refixes", FrameLabels.mixinCodeMod("Mixin code from Refixes (MixinWorld)"));
        assertEquals("Refixes", FrameLabels.mixinCodeMod("Mixin code from Refixes (MixinA), Other (MixinB)"));
        assertNull(FrameLabels.mixinCodeMod("changed by Mixin from Refixes (MixinTickSleep)"));
        assertNull(FrameLabels.mixinCodeMod(null));
        assertEquals("refixesjar", FrameLabels.mixinJar("wrapMethod$zfi000$refixesjar$refixes$settleFailedStartup"));
        assertEquals("refixesjar",
            FrameLabels.mixinJar("wrapOperation$zid000$refixesjar$refixes$iterateLocked$mixinextras$bridge$5"));
        assertEquals("mod", FrameLabels.mixinJar("modifyReturnValue$abc000$mod$fix"));
        assertEquals("refixesjar", FrameLabels.mixinJar("mdf6de99$refixesjar$lambda$refixes$queueSnapshot$0$1"));
        assertNull(FrameLabels.mixinJar("run"));
        assertNull(FrameLabels.mixinJar("run$mixinextras$wrapped$39"));
        assertNull(FrameLabels.mixinJar("mixinextras$bridge$run$mixinextras$wrapped$39$40"));
        assertNull(FrameLabels.mixinJar("handler$zza000$onTick"));
        assertNull(FrameLabels.mixinJar("lambda$run$0"));
    }

    @Test
    void threadDumpNamesTheOriginalMethodBesideGeneratedFrames() {
        String dump = "\tat Hyinit//" + TICKING + ".run$mixinextras$wrapped$39(TickingThread.java:114)\n"
            + "\tat " + TICKING + ".run(TickingThread.java)";

        assertEquals("\tat Hyinit//" + TICKING + ".run$mixinextras$wrapped$39(TickingThread.java:114)  [TickingThread.run]\n"
            + "\tat " + TICKING + ".run(TickingThread.java)", FrameLabels.labelDump(dump));

        FrameLabels.install((className, methodName) -> TICKING.equals(className) && "run".equals(methodName) ? LABEL : null);
        assertEquals("\tat Hyinit//" + TICKING + ".run$mixinextras$wrapped$39(TickingThread.java:114)  [TickingThread.run, " + LABEL + "]\n"
            + "\tat " + TICKING + ".run(TickingThread.java)  [" + LABEL + "]", FrameLabels.labelDump(dump));
    }
}
