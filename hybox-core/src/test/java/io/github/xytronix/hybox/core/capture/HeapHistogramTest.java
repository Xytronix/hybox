package io.github.xytronix.hybox.core.capture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import org.junit.jupiter.api.Test;

class HeapHistogramTest {

    @Test
    void parsesJcmdHistogramOutput() {
        String raw = " num     #instances         #bytes  class name (module)\n"
            + "-------------------------------------------------------\n"
            + "   1:       1234567      987654321  [B (java.base@25)\n"
            + "   2:        234567       23456789  java.lang.String (java.base@25)\n"
            + "   3:         34567        3456789  com.hypixel.hytale.server.core.universe.world.chunk.Chunk\n"
            + "Total      1503701     1014567899\n";

        Map<String, String> entries = HeapHistogram.parse(raw);

        assertEquals(3, entries.size());
        assertEquals("1234567 987654321", entries.get("[B"));
        assertEquals("234567 23456789", entries.get("java.lang.String"));
        assertEquals("34567 3456789", entries.get("com.hypixel.hytale.server.core.universe.world.chunk.Chunk"));
        assertEquals("[B", entries.keySet().iterator().next());
    }

    @Test
    void capsAtThirtyRows() {
        StringBuilder raw = new StringBuilder();
        for (int i = 1; i <= 40; i++) {
            raw.append("   ").append(i).append(":       100      2000  com.example.Type").append(i).append('\n');
        }

        Map<String, String> entries = HeapHistogram.parse(raw.toString());

        assertEquals(30, entries.size());
        assertTrue(entries.containsKey("com.example.Type30"));
        assertFalse(entries.containsKey("com.example.Type31"));
    }

    @Test
    void emptyOnBlankOrMalformedInput() {
        assertTrue(HeapHistogram.parse(null).isEmpty());
        assertTrue(HeapHistogram.parse("").isEmpty());
        assertTrue(HeapHistogram.parse("no histogram here\njust text\n").isEmpty());
    }

    @Test
    void snapshotKeepsTopEntriesAndWholeHeapTotalsForShares() {
        String raw = " num     #instances         #bytes  class name (module)\n"
            + "   1:       1234567      600000000  [B (java.base@25)\n"
            + "   2:        234567      300000000  java.lang.String (java.base@25)\n"
            + "   3:           512            900  com.example.Tiny\n"
            + "Total      1469646     1000000000\n";

        var snapshot = HeapHistogram.snapshot(raw, 2);

        assertEquals(2, snapshot.entries().size());
        assertEquals(new HeapHistogram.Entry("[B", 1234567, 600000000), snapshot.entries().get(0));
        assertEquals(new HeapHistogram.Entry("java.lang.String", 234567, 300000000), snapshot.entries().get(1));
        assertEquals(1469646, snapshot.totalInstances());
        assertEquals(1000000000L, snapshot.shareBase());
    }

    @Test
    void shareBaseFallsBackToListedBytesWithoutATotalLine() {
        var snapshot = HeapHistogram.snapshot("   1:   10   300  a.B\n   2:   5   100  a.C\n", 10);

        assertEquals(0, snapshot.totalBytes());
        assertEquals(400, snapshot.shareBase());
        assertTrue(HeapHistogram.snapshot(null, 10).entries().isEmpty());
        assertEquals(1, HeapHistogram.snapshot(null, 10).shareBase());
    }
}
