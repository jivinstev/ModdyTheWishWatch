package com.moddingfromamod.reference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.moddingfromamod.runtime.CreatureSculpt;
import com.moddingfromamod.runtime.SculptCritique;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** lever A: the tool keeps a named character's main colour; the agent keeps its design. */
class StandInColourTest {

    private static ReferenceSpec ref(Object... shares) {
        java.util.List<ReferenceSpec.ColourShare> cs = new java.util.ArrayList<>();
        for (int i = 0; i < shares.length; i += 2) {
            cs.add(new ReferenceSpec.ColourShare((String) shares[i], (Integer) shares[i + 1]));
        }
        return new ReferenceSpec(cs, List.of("body"), ReferenceSpec.Size.SMALL, ReferenceSpec.Confidence.HIGH,
                List.of("a page"));
    }

    private static CreatureSculpt.Part box(String name, int colour) {
        return new CreatureSculpt.Part(name, null, new float[3], new float[]{0, 0, 0}, new float[]{4, 4, 4}, colour);
    }

    @Test
    @DisplayName("the mint Puffo of #2526's run: the mint parts turn pink, the rest of the design stays")
    void mintPuffoTurnsPink() {
        var puffo = ref("purple/pink", 78, "red", 12, "blue", 3);
        var parts = List.of(box("body", 0x9FE8C0), box("body2", 0xA6EBC6), box("eyeL", 0x1A1A40),
                box("antenna", 0xF2C94C));
        var shift = StandInColour.plan(SculptCritique.composition(parts), puffo, "make me Puffo").orElseThrow();
        assertEquals("green", shift.from());
        assertEquals("purple/pink", shift.to());
        var out = StandInColour.parts(parts, shift);
        assertEquals("purple/pink", SculptCritique.bucket(out.get(0).colour()));
        assertEquals("purple/pink", SculptCritique.bucket(out.get(1).colour()));
        assertEquals(0x1A1A40, out.get(2).colour(), "the eyes are the agent's, untouched");
        assertEquals(0xF2C94C, out.get(3).colour(), "and the antenna");
        assertEquals("body", out.get(0).name());
        assertTrue(StandInColour.note("Puffo", shift).startsWith("🎨 COLOURS KEPT: the real Puffo is mostly pink or purple"));
    }

    @Test
    @DisplayName("a pastel lands pink, a deep colour purple; every target lands inside its bucket")
    void shiftsLandInTheirBucket() {
        assertTrue(SculptCritique.bucket(StandInColour.shift(0x9FE8C0, "purple/pink")).equals("purple/pink"));
        for (String to : new String[]{"red", "tan/orange", "yellow", "green", "cyan", "blue", "purple/pink"}) {
            for (int c : new int[]{0x9FE8C0, 0x1E4FD8, 0x7A1F2B, 0xF2C94C}) {
                assertEquals(to, SculptCritique.bucket(StandInColour.shift(c, to)), to + " from " + Integer.toHexString(c));
            }
        }
    }

    @Test
    @DisplayName("no shift: the main colour kept, a child's own colours, an unsure reference, a white target")
    void noShift() {
        var sparkit = ref("yellow", 75, "black", 8);
        assertFalse(StandInColour.plan(Map.of("yellow", 0.9), sparkit, "").isPresent());
        assertFalse(StandInColour.plan(Map.of("blue", 0.9), sparkit, "a BLUE sparkit please").isPresent());
        assertFalse(StandInColour.plan(Map.of("blue", 0.9), ref("white", 70), "").isPresent());
        assertTrue(StandInColour.plan(Map.of("blue", 0.9), sparkit, "").isPresent());
    }

    @Test
    @DisplayName("a picture's rectangles in the dominant colour are turned; the others are not")
    void pictureRects() {
        var shift = new StandInColour.Shift("blue", "yellow");
        var out = StandInColour.rects(List.of(new CreatureSculpt.PaintRect(0, 0, 10, 10, 0x2850E0),
                new CreatureSculpt.PaintRect(2, 2, 2, 2, 0x101010)), shift);
        assertEquals("yellow", SculptCritique.bucket(out.get(0).colour()));
        assertEquals(0x101010, out.get(1).colour());
    }

    @Test
    @DisplayName("round 2's Plumo: the orange shirt turns red, his skin, hair and moustache do not")
    void skinAndHairAreNeverTurned() {
        var plumo = ref("red", 45, "blue", 30);
        var parts = List.of(box("body", 0xFFA500), box("armL", 0xFFA500), box("head", 0xF2C49B),
                box("hair", 0x6B3E1E), box("beard", 0x6B3E1E), box("overalls", 0x7A3FB0));
        var shift = StandInColour.plan(SculptCritique.composition(parts), plumo, "make me Plumo").orElseThrow();
        assertEquals("tan/orange", shift.from());
        var out = StandInColour.parts(parts, shift);
        assertEquals("red", SculptCritique.bucket(out.get(0).colour()));
        assertEquals("red", SculptCritique.bucket(out.get(1).colour()));
        assertEquals(0xF2C49B, out.get(2).colour(), "skin");
        assertEquals(0x6B3E1E, out.get(3).colour(), "hair");
        assertEquals(0x6B3E1E, out.get(4).colour(), "moustache");
        assertTrue(StandInColour.turnable(0x9FE8C0), "a pastel body is still turned");
    }

    @Test
    @DisplayName("round 2's Link r2: only skin and browns in the wrong bucket, so nothing is turned")
    void onlySkinMeansNothingTurned() {
        var parts = List.of(box("head", 0xE8B88A), box("legL", 0x6B4226), box("tunic", 0x2A5DB0));
        var out = StandInColour.parts(parts, new StandInColour.Shift("tan/orange", "green"));
        assertEquals(parts, out);
    }
}
