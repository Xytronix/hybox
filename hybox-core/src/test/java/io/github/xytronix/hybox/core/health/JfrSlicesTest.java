package io.github.xytronix.hybox.core.health;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;

class JfrSlicesTest {
    private static final long START = 1_000_000L;

    @Test
    void busiestCodeCoversFiveSecondsAroundEachPointWithTheTopThreeSelfMethodsAndTopThread() {
        JfrSlices slices = new JfrSlices();
        for (int i = 0; i < 6000; i++) {
            int k = i % 20;
            String method = k < 10 ? "com.mod.Alpha.tick" : k < 16 ? "com.hypixel.hytale.World.tick" : k < 19 ? "com.mod.Beta.run" : "java.util.HashMap.resize";
            String owner = k < 10 ? "mod-alpha" : k >= 16 && k < 19 ? "mod-beta" : null;
            slices.add(START + i * 10L, method, owner, k < 16 ? "WorldThread - default" : "worker");
        }
        JfrTimeline.Slices built = slices.build(START, START + 59_990, 240, raw -> "Plugin:" + raw);

        assertEquals(5.0, built.seconds());
        int[] middle = built.buckets()[120];
        assertEquals(500, middle[0]);
        assertEquals("WorldThread - default", built.threads().get(middle[1]));
        assertEquals(400, middle[2]);
        assertEquals(new JfrTimeline.SliceFrame("com.mod.Alpha.tick", "Plugin:mod-alpha"), built.frames().get(middle[3]));
        assertEquals(250, middle[4]);
        assertEquals(new JfrTimeline.SliceFrame("com.hypixel.hytale.World.tick", null), built.frames().get(middle[5]));
        assertEquals(150, middle[6]);
        assertEquals(new JfrTimeline.SliceFrame("com.mod.Beta.run", "Plugin:mod-beta"), built.frames().get(middle[7]));
        assertEquals(75, middle[8]);
        assertEquals(9, middle.length);
        assertEquals(3, built.frames().size());
    }

    @Test
    void sparsePointsOnlyReportHowFewSamplesThereWere() {
        JfrSlices slices = new JfrSlices();
        for (int i = 0; i < 5; i++) {
            slices.add(START + 30_000 + i * 100L, "com.mod.Alpha.tick", "mod-alpha", "WorldThread - default");
        }
        slices.add(START + 60_000, "com.mod.Alpha.tick", "mod-alpha", "WorldThread - default");
        JfrTimeline.Slices built = slices.build(START, START + 60_000, 240, raw -> raw);

        assertArrayEquals(new int[] {5}, built.buckets()[120]);
        assertArrayEquals(new int[] {0}, built.buckets()[10]);
        assertEquals(JfrSlices.MIN_SAMPLES, built.minSamples());
        assertEquals(0, built.frames().size());
    }

    @Test
    void longBucketsAreTheirOwnSlice() {
        JfrSlices slices = new JfrSlices();
        slices.add(START + 1, "com.mod.Alpha.tick", null, null);
        JfrTimeline.Slices built = slices.build(START, START + 2_399_999, 240, raw -> raw);
        assertEquals(10.0, built.seconds());
        assertNull(new JfrSlices().build(START, START + 1000, 240, raw -> raw));
    }
}
