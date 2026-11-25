package io.github.xytronix.hybox.core.health;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class FrameLabelsTest {
    private static final String TICKING = "com.hypixel.hytale.server.core.util.thread.TickingThread";
    private static final FrameLabels.Origin REFIXES = new FrameLabels.Origin("Refixes", "refixes-0.6.1.jar",
        List.of("MixinTickSleep"));
    private static final FrameLabels.Origin PERFORMANCE = new FrameLabels.Origin("Better (Fast)",
        "BetterPerformance-2.0.jar", List.of("MixinA", "MixinB"));
    private static final FrameLabels.Label CHANGED = new FrameLabels.Label(false, List.of(REFIXES));
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
        FrameLabels.install((className, methodName, descriptor, line) -> TICKING.equals(className) && "run".equals(methodName)
            ? CHANGED : null);

        assertEquals(TICKING + ".run, " + LABEL, FrameLabels.display(TICKING + ".run$mixinextras$wrapped$39"));
        assertEquals(TICKING + ".run, " + LABEL,
            FrameLabels.display(TICKING + ".mixinextras$bridge$run$mixinextras$wrapped$39$40"));
    }

    @Test
    void mixinCodeIsNamedByTheHyinitOriginOrTheFabricJarDecorationButNotForChangedEngineMethods() {
        FrameLabels.Label merged = new FrameLabels.Label(true, List.of(REFIXES));
        assertEquals(REFIXES, FrameLabels.mergedBy(merged, "refixes$helper"));
        assertTrue(FrameLabels.mixinCode(merged, "refixes$helper"));
        assertNull(FrameLabels.mergedBy(CHANGED, "run"));
        assertFalse(FrameLabels.mixinCode(CHANGED, "run"));
        assertNull(FrameLabels.mergedBy(null, "run"));
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
    void mergedMethodOfSeveralModsGoesToTheModItsNameCarries() {
        FrameLabels.Label both = new FrameLabels.Label(true, List.of(REFIXES, PERFORMANCE));

        assertEquals("Mixin code from Refixes (MixinTickSleep), Better (Fast) (MixinA, MixinB)", both.text());
        assertEquals("BetterPerfor", FrameLabels.sourceId("BetterPerformance-2.0.jar"));
        assertEquals("refixesjar", FrameLabels.sourceId("refixes-0.6.1.jar"));
        assertEquals(PERFORMANCE, FrameLabels.mergedBy(both, "handler$zza000$BetterPerfor$better$onTick"));
        assertEquals(REFIXES, FrameLabels.mergedBy(both, "handler$zzb000$refixesjar$refixes$onTick"));
        assertNull(FrameLabels.mergedBy(both, "tick"));
        assertTrue(FrameLabels.mixinCode(both, "tick"));
    }

    @Test
    void stackTraceFramesAreLookedUpByTheirLineSoAnOverloadNoMixinChangedStaysPlain() {
        FrameLabels.install((className, methodName, descriptor, line) -> descriptor == null && "run".equals(methodName)
            && (line <= 0 || line >= 100 && line <= 120) ? CHANGED : null);

        assertEquals("  [" + LABEL + "]", FrameLabels.suffix(new StackTraceElement(TICKING, "run", "TickingThread.java", 114)));
        assertEquals("  [" + LABEL + "]", FrameLabels.suffix(new StackTraceElement(TICKING, "run", null, -1)));
        assertEquals("", FrameLabels.suffix(new StackTraceElement(TICKING, "run", "TickingThread.java", 300)));
        assertEquals(TICKING + ".run", FrameLabels.display(TICKING + ".run", 300));
        assertEquals(TICKING + ".run, " + LABEL, FrameLabels.display(TICKING + ".run", 114));
        assertEquals("\tat " + TICKING + ".run(TickingThread.java:300)\n\tat Hyinit//" + TICKING
            + ".run(TickingThread.java:114)  [" + LABEL + "]\n\tat " + TICKING + ".run(Native Method)  [" + LABEL + "]",
            FrameLabels.labelDump("\tat " + TICKING + ".run(TickingThread.java:300)\n\tat Hyinit//" + TICKING
                + ".run(TickingThread.java:114)\n\tat " + TICKING + ".run(Native Method)"));
    }

    @Test
    void threadDumpNamesTheOriginalMethodBesideGeneratedFrames() {
        String dump = "\tat Hyinit//" + TICKING + ".run$mixinextras$wrapped$39(TickingThread.java:114)\n"
            + "\tat " + TICKING + ".run(TickingThread.java)";

        assertEquals("\tat Hyinit//" + TICKING + ".run$mixinextras$wrapped$39(TickingThread.java:114)  [TickingThread.run]\n"
            + "\tat " + TICKING + ".run(TickingThread.java)", FrameLabels.labelDump(dump));

        FrameLabels.install((className, methodName, descriptor, line) -> TICKING.equals(className) && "run".equals(methodName)
            ? CHANGED : null);
        assertEquals("\tat Hyinit//" + TICKING + ".run$mixinextras$wrapped$39(TickingThread.java:114)  [TickingThread.run, " + LABEL + "]\n"
            + "\tat " + TICKING + ".run(TickingThread.java)  [" + LABEL + "]", FrameLabels.labelDump(dump));
    }
}
