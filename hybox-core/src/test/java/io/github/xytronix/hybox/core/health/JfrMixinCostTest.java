package io.github.xytronix.hybox.core.health;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import io.github.xytronix.hybox.core.health.FrameLabels.Label;
import io.github.xytronix.hybox.core.health.FrameLabels.Origin;
import io.github.xytronix.hybox.core.health.JfrTimeline.MixinCost;
import io.github.xytronix.hybox.core.health.JfrTimeline.MixinHit;

class JfrMixinCostTest {
    private static final String TICKING = "com.hypixel.hytale.server.core.util.thread.TickingThread";
    private static final String SAVER = "com.hypixel.hytale.server.core.universe.world.storage.provider."
        + "IndexedStorageChunkStorageProvider$IndexedStorageChunkSaver";
    private static final String BUFFER = "com.hypixel.hytale.component.CommandBuffer";
    private static final String STORE = "com.hypixel.hytale.component.Store";
    private static final String WORLD = "com.hypixel.hytale.server.core.universe.world.World";
    private static final String SETTLE = "wrapMethod$zfg000$refixesjar$refixes$settleFailedStartup";
    private static final String CLONE = "md447b79$refixesjar$lambda$saveChunkColumn$1$c";
    private static final String REMOVE = "redirect$zzc000$refixesjar$refixes$safeRemoveComponent";
    private static final String OTHER = "handler$zza000$otherjar$other$onTick";
    private static final String REFIXES_JAR = "refixes-0.6.1.jar";
    private static final String OTHER_JAR = "other.jar";

    private static final String COUNTERS = "cc.irori.refixes.early.util.PatchCounters$Counter";
    private static final String WAIT = "redirect$zfj000$refixesjar$refixes$hybridWait";

    private record Frame(String type, String method, Label label, String loader) {
        Frame(String type, String method, Label label) {
            this(type, method, label, null);
        }
    }

    private static Frame f(String type, String method) {
        return new Frame(type, method, null);
    }

    private static Label label(boolean merged, Origin... origins) {
        return new Label(merged, List.of(origins));
    }

    private static Origin refixes(String mixin) {
        return new Origin("Refixes", REFIXES_JAR, List.of(mixin));
    }

    private static Origin other(String... mixins) {
        return new Origin("Other (Mod)", OTHER_JAR, List.of(mixins));
    }

    private static JfrMixinCost.Stack bare(Frame... frames) {
        String[] types = new String[frames.length];
        String[] names = new String[frames.length];
        for (int i = 0; i < frames.length; i++) {
            types[i] = frames[i].type();
            names[i] = frames[i].method();
        }
        return JfrMixinCost.stack(types, names, i -> frames[i].label(), i -> frames[i].loader());
    }

    private static JfrMixinCost.Stack stack(Frame... leafFirst) {
        Frame[] frames = new Frame[leafFirst.length + 6];
        System.arraycopy(leafFirst, 0, frames, 0, leafFirst.length);
        int at = leafFirst.length;
        frames[at++] = f(WORLD, "tick");
        frames[at++] = f(TICKING, "run$mixinextras$wrapped$39");
        frames[at++] = f(TICKING, "mixinextras$bridge$run$mixinextras$wrapped$39$40");
        frames[at++] = f(TICKING, SETTLE);
        frames[at++] = f(TICKING, "run");
        frames[at] = f("java.lang.Thread", "run");
        return bare(frames);
    }

    private static Map<String, Long> totals(MixinCost cost, String kind) {
        Map<String, Long> out = new HashMap<>();
        for (MixinHit hit : cost.hits()) {
            if (hit.kind().equals(kind)) {
                out.merge(hit.key(), hit.samples(), Long::sum);
            }
        }
        return out;
    }

    private static long method(MixinCost cost, String kind, String method) {
        return cost.hits().stream().filter(h -> h.kind().equals(kind) && method.equals(h.method()))
            .mapToLong(MixinHit::samples).sum();
    }

    @Test
    void wrapperOnEveryWorldStackIsReachButNotCost() {
        JfrMixinCost cost = new JfrMixinCost();
        JfrMixinCost.Stack engine = stack(f("java.util.HashMap", "get"), f(STORE, "tick"));
        for (int i = 0; i < 90; i++) {
            cost.add(engine);
        }
        JfrMixinCost.Stack own = stack(f("java.util.ArrayList", "add"), f(SAVER, CLONE), f(SAVER, "saveChunkColumn"));
        for (int i = 0; i < 6; i++) {
            cost.add(own);
        }
        JfrMixinCost.Stack started = stack(f(STORE, "tryRemoveComponent"), f(BUFFER, REMOVE),
            f(BUFFER, "lambda$removeComponent$0"));
        for (int i = 0; i < 4; i++) {
            cost.add(started);
        }

        MixinCost built = cost.build();
        assertEquals(100, built.samples());
        assertEquals(Map.of("#refixesjar", 6L), totals(built, MixinCost.OWN));
        assertEquals(Map.of("#refixesjar", 10L), totals(built, MixinCost.STARTED));
        assertEquals(Map.of("#refixesjar", 100L), totals(built, MixinCost.REACH));
        assertEquals(6, method(built, MixinCost.OWN, SAVER + "." + CLONE));
        assertEquals(4, method(built, MixinCost.STARTED, BUFFER + "." + REMOVE));
        assertEquals(100, method(built, MixinCost.CHANGED, TICKING + ".run"));
        assertEquals(4, method(built, MixinCost.CHANGED, BUFFER + ".lambda$removeComponent$0"));
    }

    @Test
    void hyinitOriginsNameTheJarOfMergedAndChangedMethodsOfSeveralMods() {
        JfrMixinCost.Stack s = bare(
            new Frame(STORE, "refixes$helper", label(true, refixes("MixinStore"))),
            new Frame(STORE, "tick", label(false, refixes("MixinStore"))),
            new Frame(TICKING, "run", label(false, refixes("MixinTickingThread"), other("MixinA", "MixinB"))),
            f("java.lang.Thread", "run"));

        assertEquals("@" + REFIXES_JAR, s.own());
        assertEquals("@" + REFIXES_JAR, s.started());
        assertEquals(List.of("@" + REFIXES_JAR, "@" + OTHER_JAR), s.reach());
        assertEquals(List.of("@" + REFIXES_JAR, "@" + REFIXES_JAR, "@" + OTHER_JAR), s.changedKeys());
        assertEquals(List.of(STORE + ".tick", TICKING + ".run", TICKING + ".run"), s.changedMethods());
    }

    @Test
    void methodMergedByTwoModsGoesToTheModItsNameCarriesAndKeepsBothInReach() {
        Label both = label(true, refixes("MixinStore"), other("MixinA"));
        JfrMixinCost.Stack named = bare(new Frame(STORE, OTHER, both), f(STORE, "tick"));

        assertEquals("@" + OTHER_JAR, named.own());
        assertEquals("@" + OTHER_JAR, named.started());
        assertEquals(List.of("@" + OTHER_JAR, "@" + REFIXES_JAR), named.reach());

        JfrMixinCost.Stack plain = bare(f("java.lang.Math", "max"), new Frame(STORE, "sharedHelper", both),
            f(STORE, OTHER), f(STORE, "tick"));

        assertNull(plain.own());
        assertNull(plain.started());
        assertEquals(List.of("@" + REFIXES_JAR, "@" + OTHER_JAR, "#otherjar"), plain.reach());
    }

    @Test
    void classesOutsideHytaleAndPluginLoadersUnderMixinCodeAreKeptForTheOwnCount() {
        JfrMixinCost cost = new JfrMixinCost();
        JfrMixinCost.Stack helper = stack(f("java.util.concurrent.atomic.LongAdder", "add"), f(COUNTERS, "add"),
            f(COUNTERS, "record"), f(TICKING, WAIT));
        cost.add(helper);
        cost.add(helper);
        cost.add(stack(f("java.lang.Thread", "onSpinWait"), f(TICKING, WAIT)));

        assertEquals("#refixesjar", helper.own());
        assertEquals(TICKING + "." + WAIT, helper.ownMethod());
        assertEquals(List.of(COUNTERS), helper.ownClasses());
        List<MixinHit> own = cost.build().hits().stream().filter(h -> h.kind().equals(MixinCost.OWN)).toList();
        assertEquals(List.of(2L, 1L), own.stream().map(MixinHit::samples).toList());
        assertEquals(List.of(List.of(COUNTERS), List.of()), own.stream().map(MixinHit::ownClasses).toList());
    }

    @Test
    void hytaleCodeAndPluginLoaderClassesUnderMixinCodeAreNotOwn() {
        JfrMixinCost.Stack engine = stack(f(STORE, "tryRemoveComponent"), f(COUNTERS, "record"), f(BUFFER, REMOVE));
        assertNull(engine.own());
        assertEquals("#refixesjar", engine.started());

        JfrMixinCost.Stack plugin = stack(new Frame("cc.irori.refixes.service.ChunkLoaderService", "tick", null,
            "IroriPowered:Refixes"), f(TICKING, WAIT));
        assertNull(plugin.own());
        assertEquals(List.of(), plugin.ownClasses());
        assertEquals("#refixesjar", plugin.started());

        JfrMixinCost.Stack resumed = stack(f(COUNTERS, "record"), f(STORE, "tick$mixinextras$wrapped$7"), f(STORE, OTHER));
        assertNull(resumed.own());
        assertNull(resumed.started());
    }

    @Test
    void stackWithoutMixinFramesOnlyCountsAsAWorldSampleAndAHandlerOfAnotherJarKeepsBothJarsInReach() {
        JfrMixinCost cost = new JfrMixinCost();
        JfrMixinCost.Stack plain = bare(f(STORE, "tick"), f("java.lang.Thread", "run"));
        assertSame(JfrMixinCost.Stack.NONE, plain);
        cost.add(plain);
        assertNull(cost.build());

        cost.add(stack(f("java.lang.Math", "max"), f(STORE, OTHER), f(STORE, "tick")));

        MixinCost built = cost.build();
        assertEquals(2, built.samples());
        assertEquals(Map.of("#otherjar", 1L), totals(built, MixinCost.OWN));
        assertEquals(Map.of("#otherjar\n#refixesjar", 1L), totals(built, MixinCost.REACH));
    }
}
