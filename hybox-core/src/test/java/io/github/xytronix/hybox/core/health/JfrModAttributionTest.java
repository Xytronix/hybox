package io.github.xytronix.hybox.core.health;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.List;
import java.util.Map;
import java.util.function.UnaryOperator;

import org.junit.jupiter.api.Test;

class JfrModAttributionTest {
    private static final String TICKING = "com.hypixel.hytale.server.core.util.thread.TickingThread";
    private static final String SAVING = "com.hypixel.hytale.server.core.universe.world.storage.component.ChunkSavingSystems$Ticking";
    private static final String REFIXES = "IroriPowered:Refixes";

    private record Frame(String type, String method, String loader, String label) {}

    private static Frame f(String type, String method) {
        return new Frame(type, method, null, null);
    }

    private static JfrModAttribution.Owners owners(JfrModAttribution mods, Frame... frames) {
        String[] types = new String[frames.length];
        String[] names = new String[frames.length];
        for (int i = 0; i < frames.length; i++) {
            types[i] = frames[i].type();
            names[i] = frames[i].method();
        }
        return mods.owners(types, names, i -> frames[i].label(), i -> frames[i].loader());
    }

    private static JfrModAttribution.Owners chunkSave(JfrModAttribution mods) {
        return owners(mods,
            f("java.nio.ByteBuffer", "limit"),
            f("org.bson.io.BasicOutputBuffer", "ensure"),
            f("com.hypixel.hytale.server.core.universe.world.chunk.section.BlockSection", "serialize"),
            f("com.hypixel.hytale.server.core.universe.world.storage.IndexedStorageFile", "saveHolder"),
            f("cc.irori.refixes.early.util.IndexedStorageLifetime", "child"),
            f(SAVING, "wrapOperation$zzk001$refixesjar$refixes$acknowledgeColumnTransfers"),
            f(TICKING, "run$mixinextras$wrapped$39"),
            f(TICKING, "wrapMethod$zfi000$refixesjar$refixes$settleFailedStartup"),
            new Frame(TICKING, "run", null, "changed by Mixin from Refixes (MixinTickingThread)"),
            f("java.lang.Thread", "run"));
    }

    @Test
    void modWrapperAboveEngineWorkGetsTotalButNoSelfTime() {
        JfrModAttribution mods = new JfrModAttribution();
        JfrModAttribution.Owners saving = chunkSave(mods);
        JfrModAttribution.Owners ownCode = owners(mods,
            f("java.util.HashMap", "get"),
            new Frame("cc.irori.refixes.service.AiTickThrottlerService", "processWorld", REFIXES, null),
            f("com.hypixel.hytale.server.core.universe.world.World", "tick"));
        JfrModAttribution.Owners mixinLeaf = owners(mods,
            f("com.hypixel.hytale.codec.ExtraInfo", "redirect$zgm000$refixeseajar$refixes$shareDelegatedMetadata"),
            f("com.hypixel.hytale.codec.ExtraInfo", "<init>"));
        JfrModAttribution.Owners engine = owners(mods,
            f("java.lang.String", "charAt"),
            f("com.hypixel.hytale.server.core.universe.world.World", "tick"),
            f("java.lang.Thread", "run"));

        assertEquals("cc.irori.refixes", saving.nearest());
        assertNull(saving.self());
        assertEquals(List.of("cc.irori.refixes", "#refixesjar"), saving.present());
        assertEquals(REFIXES, ownCode.self());
        assertEquals("cc.irori.refixes.service.AiTickThrottlerService.processWorld", ownCode.selfMethod());
        assertNull(mixinLeaf.nearest());
        assertEquals("#refixeseajar", mixinLeaf.self());
        assertEquals(JfrModAttribution.Owners.NONE, engine);

        for (int i = 0; i < 90; i++) {
            mods.cpu(saving, "ChunkSaving");
        }
        for (int i = 0; i < 7; i++) {
            mods.cpu(ownCode, "WorldThread - default");
        }
        for (int i = 0; i < 3; i++) {
            mods.cpu(mixinLeaf, "WorldThread - default");
        }
        mods.cpu(engine, "WorldThread - default");
        mods.alloc(saving, "default", 1_000);
        mods.alloc(ownCode, "default", 24);

        List<JfrTimeline.ModCpu> cpu = mods.cpu(20);
        assertEquals(1, cpu.size());
        JfrTimeline.ModCpu refixes = cpu.getFirst();
        assertEquals(REFIXES, refixes.id());
        assertEquals(97, refixes.samples());
        assertEquals("cc.irori.refixes.early.util.IndexedStorageLifetime.child", refixes.method());
        assertEquals(10, refixes.self());
        assertEquals(100, refixes.total());
        assertEquals("cc.irori.refixes.service.AiTickThrottlerService.processWorld", refixes.selfMethod());
        assertEquals("cc.irori.refixes.early.util.IndexedStorageLifetime.child", refixes.totalMethod());
        assertEquals(List.of(new JfrTimeline.ThreadShare("WorldThread - default", 10)), refixes.selfThreads());
        assertEquals(List.of(new JfrTimeline.ThreadShare("ChunkSaving", 90),
            new JfrTimeline.ThreadShare("WorldThread - default", 7)), refixes.threads());

        assertEquals(Map.of(REFIXES, new JfrTimeline.ModShare(1_024, 24, 1_024)), mods.alloc(10));
        assertEquals(Map.of("ChunkSaving", Map.of(REFIXES, new JfrTimeline.ModShare(90, 0, 90)),
                "WorldThread - default", Map.of(REFIXES, new JfrTimeline.ModShare(7, 10, 10))),
            mods.cpuBy(UnaryOperator.identity(), 5));
    }

    @Test
    void hyinitLabelsNameTheModOfMergedMixinCodeAndEngineTargetsStayEngine() {
        JfrModAttribution mods = new JfrModAttribution();
        String world = "com.hypixel.hytale.server.core.universe.world.World";
        JfrModAttribution.Owners merged = owners(mods,
            new Frame(world, "refixes$tickBudget", null, "Mixin code from Refixes (MixinWorld)"),
            new Frame(world, "tick", null, "changed by Mixin from Refixes (MixinWorld)"));
        JfrModAttribution.Owners target = owners(mods,
            new Frame(world, "tick", null, "changed by Mixin from Refixes (MixinWorld)"),
            new Frame("cc.irori.refixes.listener.WorldListener", "onTick", REFIXES, null));

        assertEquals("@Refixes", merged.self());
        assertNull(target.self());
        assertEquals(REFIXES, target.nearest());
        mods.cpu(merged, "WorldThread - default");
        mods.cpu(target, "WorldThread - default");

        JfrTimeline.ModCpu row = mods.cpu(20).getFirst();
        assertEquals(REFIXES, row.id());
        assertEquals(1, row.samples());
        assertEquals(1, row.self());
        assertEquals(2, row.total());
        assertEquals(world + ".refixes$tickBudget", row.selfMethod());
    }

    @Test
    void unknownMixinOwnerKeepsItsNameAndSharedPackagesStayUnmerged() {
        JfrModAttribution mods = new JfrModAttribution();
        JfrModAttribution.Owners mixin = owners(mods,
            f("com.hypixel.hytale.server.core.universe.world.World", "handler$zza000$othermod$onTick"));
        JfrModAttribution.Owners truncated = owners(mods,
            f("com.hypixel.hytale.server.core.command.CommandManager", "redirect$zzd000$RegionManage$intercept"),
            new Frame("com.github.razorplay.regionmanager.RegionManagerPlugin", "start", "RazorPlay:RegionManager", null));
        JfrModAttribution.Owners first = owners(mods,
            new Frame("com.shared.library.First", "run", "Vendor:First", null));
        JfrModAttribution.Owners second = owners(mods,
            new Frame("com.shared.library.Second", "run", "Vendor:Second", null));
        JfrModAttribution.Owners early = owners(mods, f("com.shared.library.Early", "run"));
        mods.cpu(mixin, "t");
        mods.cpu(truncated, "t");
        mods.cpu(first, "t");
        mods.cpu(second, "t");
        mods.cpu(early, "t");

        Map<String, JfrTimeline.ModShare> shares = mods.cpuBy(UnaryOperator.identity(), 10).get("t");
        assertEquals(Map.of("othermod", new JfrTimeline.ModShare(0, 1, 1),
            "RegionManage", new JfrTimeline.ModShare(0, 1, 1),
            "RazorPlay:RegionManager", new JfrTimeline.ModShare(1, 0, 1),
            "Vendor:First", new JfrTimeline.ModShare(1, 1, 1),
            "Vendor:Second", new JfrTimeline.ModShare(1, 1, 1),
            "com.shared.library", new JfrTimeline.ModShare(1, 1, 1)), shares);
    }

    @Test
    void hyboxCaptureWorkGetsItsOwnRowAndSteadyWorkStaysHybox() {
        JfrModAttribution mods = new JfrModAttribution();
        JfrModAttribution.Owners pump = owners(mods,
            new Frame("io.github.xytronix.hybox.hytale.HytaleHeartbeatPump", "tick", "Xytronix:Hybox", null));
        JfrModAttribution.Owners capture = owners(mods, f("java.lang.Thread", "getAllStackTraces"),
            new Frame("io.github.xytronix.hybox.hytale.HyboxRuntime", "performCapture", "Xytronix:Hybox", null));
        mods.cpu(pump, "hybox-scheduler");
        mods.cpu(mods.capture(capture), "hybox-capture");
        mods.cpu(mods.capture(capture), "hybox-capture");

        assertEquals(Map.of("Xytronix:Hybox", new JfrTimeline.ModShare(1, 1, 1),
            JfrTimeline.CAPTURE_MOD, new JfrTimeline.ModShare(2, 2, 2)), merged(mods));
    }

    private static Map<String, JfrTimeline.ModShare> merged(JfrModAttribution mods) {
        Map<String, JfrTimeline.ModShare> out = new java.util.HashMap<>();
        for (JfrTimeline.ModCpu row : mods.cpu(10)) out.put(row.id(), new JfrTimeline.ModShare(row.samples(), row.self(), row.total()));
        return out;
    }
}
