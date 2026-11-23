package com.moddingfromamod.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;

/** F1: every new kit and every style builds real boxes, hung where they belong, within the caps. */
class SculptKitsStylesTest {

    private static List<CreatureSculpt.Part> creature() {
        List<CreatureSculpt.Part> out = new ArrayList<>();
        out.add(new CreatureSculpt.Part("body", null, new float[]{0, 6, 0}, new float[]{-4, 6, -6}, new float[]{4, 12, 6}, 0xFF8844));
        out.add(new CreatureSculpt.Part("head", "body", new float[]{0, 12, 6}, new float[]{-3, 11, 5}, new float[]{3, 17, 10}, 0xFF8844));
        return out;
    }

    private static List<CreatureSculpt.Part> expand(String kit, String on, String style, int count) {
        JsonObject o = new JsonObject();
        o.addProperty("kit", kit);
        o.addProperty("on", on);
        if (style != null) {
            o.addProperty("style", style);
        }
        if (count > 0) {
            o.addProperty("count", count);
        }
        JsonArray a = new JsonArray();
        a.add(o);
        return SculptKits.expand(a, creature());
    }

    @Test
    void everyNewKitAndEveryStyleBuildsBoxes() {
        for (String kit : List.of("leg_row", "ears", "snout", "beak", "trunk", "tusk", "mane")) {
            assertTrue(SculptKits.available().contains(kit), kit);
            List<CreatureSculpt.Part> made = expand(kit, kit.equals("leg_row") ? "body" : "head", null, 0);
            assertFalse(made.isEmpty(), kit + " builds nothing");
            assertTrue(made.stream().allMatch(p -> p.name().startsWith(SculptKits.MARK)), kit + " parts are marked");
        }
        SculptKits.STYLES.forEach((kit, styles) -> {
            for (String style : styles) {
                List<CreatureSculpt.Part> made = expand(kit, kit.equals("ears") || kit.equals("horn") ? "head" : "body", style, 0);
                assertFalse(made.isEmpty(), kit + " " + style + " builds nothing");
                assertTrue(made.size() <= SculptKits.MAX_BOXES_PER_KIT, kit + " " + style + " is over the cap: " + made.size());
                for (CreatureSculpt.Part p : made) {
                    for (int i = 0; i < 3; i++) {
                        assertTrue(p.to()[i] - p.from()[i] >= 1f, kit + " " + style + " made a flat box");
                    }
                }
            }
        });
    }

    @Test
    void aSpiderGetsAllEightLegs() {
        // legs from one point, four pairs — sixteen boxes, past the ordinary per-kit cap.
        List<CreatureSculpt.Part> legs = expand("leg_row", "body", null, 4);
        assertEquals(16, legs.size());
        assertTrue(legs.stream().allMatch(p -> p.name().contains("leg")), "named legs, so the rig walks them");
    }

    @Test
    void aStyleCanRideInTheKitsName() {
        assertEquals(10, expand("antlers", "head", null, 0).size(), "'antlers' is the antler horn");
        assertEquals("antler", SculptKits.style("horn", "deer"));
        assertEquals("curved", SculptKits.style("horn", "nonsense"), "an unknown style is the default look");
        assertEquals("horn", SculptKits.sanitize("ram_horns"));
    }
}
