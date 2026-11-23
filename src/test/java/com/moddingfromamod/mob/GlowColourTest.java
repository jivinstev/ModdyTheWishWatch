package com.moddingfromamod.mob;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** Gate A for snapping any colour a child names onto one of the 16 vanilla team colours. */
class GlowColourTest {

    @Test
    void exactTeamColourNamesArePassedStraightThrough() {
        // If the agent already wrote a real team colour, round-tripping it through RGB could snap
        // it somewhere else — so an exact name must short-circuit.
        for (String name : GlowColour.names()) {
            assertEquals(name, GlowColour.resolve(name));
        }
    }

    @Test
    void childWordsResolveToASensibleColour() {
        assertEquals("light_purple", GlowColour.resolve("pink"));
        assertTrue(GlowColour.resolve("#FF0000").contains("red"));
        assertTrue(GlowColour.resolve("#0000FF").contains("blue"));
        assertTrue(GlowColour.resolve("#00FF00").contains("green"));
        assertEquals("white", GlowColour.resolve("#FFFFFF"));
        assertEquals("black", GlowColour.resolve("#000000"));
    }

    @Test
    void spacesAndCaseAreForgiven() {
        assertEquals(GlowColour.resolve("light_purple"), GlowColour.resolve("Light Purple"));
        assertEquals(GlowColour.resolve("dark_aqua"), GlowColour.resolve("DARK AQUA"));
    }

    @Test
    void saturatedColoursNeverCollapseToGrey() {
        // The whole reason this uses redmean rather than plain Euclidean distance: grey sits in the
        // middle of the RGB cube and is "close" to everything, so naive matching paints a hot pink
        // mob grey and the child sees their wish quietly ignored.
        for (String vivid : new String[]{"#FF69B4", "#FF7FBF", "#00FFAA", "#FF8800", "#8800FF"}) {
            String got = GlowColour.resolve(vivid);
            assertFalse(got.equals("gray") || got.equals("dark_gray"),
                    vivid + " should keep its hue, but snapped to " + got);
        }
    }

    @Test
    void noneMeansStopGlowing() {
        assertEquals(GlowColour.NONE, GlowColour.resolve("none"));
        assertEquals(GlowColour.NONE, GlowColour.resolve("off"));
        assertEquals(GlowColour.NONE, GlowColour.resolve(null));
        assertEquals(GlowColour.NONE, GlowColour.resolve("   "));
    }

    @Test
    void aColourTheAgentInventedStillYieldsARealTeamColour() {
        // An agent writing "sparkly" must not break the wish — it falls back to a valid colour.
        String got = GlowColour.resolve("sparkly");
        assertNotNull(got);
        assertTrue(GlowColour.names().contains(got), "must be a real team colour, got: " + got);
    }

    @Test
    void everyColourNameIsDistinctAndComplete() {
        assertEquals(16, GlowColour.names().size(), "Minecraft has exactly 16 team colours");
        assertEquals(16, GlowColour.names().stream().distinct().count());
    }
}
