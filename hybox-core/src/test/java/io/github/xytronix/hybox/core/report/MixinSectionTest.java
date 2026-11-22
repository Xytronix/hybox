package io.github.xytronix.hybox.core.report;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import io.github.xytronix.hybox.core.json.JsonWriter;

class MixinSectionTest {
    private static final String SHA = "4f2d9c0b7e1a6d35c8b2e9f01a7d4c63b5e8f2a19d0c7b6e3f4a1d2c5b8e9f07";

    @Test
    void hyinitDataReachesReportJsonAndTheHeaderChip() throws Exception {
        Map<String, String> entries = MixinSection.entries("Hyinit 0.3.1",
            new MixinSection.Versions("0.3.1", "launcher", "0.8.7", "0.17.5", "0.5.5", 812L, 431_000_000L, 2L, 950L, 0L),
            List.of(new MixinSection.Source("refixes-0.6.1.jar", "IroriPowered:Refixes", "0.6.1", SHA,
                List.of("refixes.mixins.json"), true, false, null, 3L)),
            List.of(new MixinSection.Config("refixes.mixins.json", "refixes-0.6.1.jar", "cc.irori.refixes.early.mixin",
                List.of("cc.irori.refixes.early.mixin.MixinWorld"), 1, "0.16.0", 1000, true)),
            List.of(new MixinSection.Problem("error", "Mixin transformation of a.B failed", "x.jar", "boom"),
                new MixinSection.Problem("warn", "Mixin c.D was skipped", null, null)),
            List.of(new MixinSection.Overlap("a.B.tick", List.of(new MixinSection.Patch("Refixes", "c.M1"),
                new MixinSection.Patch("Other", "d.M2")))),
            null);

        StringBuilder out = new StringBuilder();
        JsonWriter json = new JsonWriter(out);
        json.beginObject();
        MixinSection.write(json, entries);
        json.endObject();
        String text = out.toString();

        assertTrue(text.contains("\"configs\":[[\"refixes.mixins.json\",[\"cc.irori.refixes.early.mixin.MixinWorld\"],"
            + "\"refixes-0.6.1.jar\",\"cc.irori.refixes.early.mixin\",1,\"0.16.0\",1000,true]]"), text);
        assertTrue(text.contains("\"conflicts\":null"), text);
        assertTrue(text.contains("\"version\":\"0.3.1\",\"launchMode\":\"launcher\",\"mixin\":\"0.8.7\""), text);
        assertTrue(text.contains("\"transformMs\":431.0"), text);
        assertTrue(text.contains("\"sources\":[[\"refixes-0.6.1.jar\",\"IroriPowered:Refixes\",\"0.6.1\",\"" + SHA
            + "\",[\"refixes.mixins.json\"],true,false,null,3]]"), text);
        assertTrue(text.contains("\"problems\":[[\"error\",\"Mixin transformation of a.B failed\",\"x.jar\",\"boom\"],"
            + "[\"warn\",\"Mixin c.D was skipped\",null,null]]"), text);
        assertTrue(text.contains("\"overlaps\":[[\"a.B.tick\",[[\"Refixes\",\"c.M1\"],[\"Other\",\"d.M2\"]]]]"), text);
        assertEquals("<a class=\"pill bad\" href=\"#mixins\">Mixin problems <b>1 error · 1 warning</b></a>",
            ReportHtml.mixinChip(entries));
    }

    @Test
    void oldHyinitKeepsTheBootstrapperAndAsksForAnUpdate() throws Exception {
        StringBuilder out = new StringBuilder();
        JsonWriter json = new JsonWriter(out);
        json.beginObject();
        MixinSection.write(json, Map.of("Bootstrapper", "Hyinit 0.3.1", MixinSection.NOTE, MixinSection.UPDATE_NOTE));
        json.endObject();

        assertTrue(out.toString().contains("\"bootstrapper\":\"Hyinit 0.3.1\""), out.toString());
        assertTrue(out.toString().contains("\"note\":\"Update Hyinit for Mixin details\""), out.toString());
        assertTrue(out.toString().contains("\"hyinit\":null"), out.toString());
        assertEquals("", ReportHtml.mixinChip(Map.of("Bootstrapper", "Hyinit 0.3.1")));
    }
}
