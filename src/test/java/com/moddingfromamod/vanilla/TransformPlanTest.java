package com.moddingfromamod.vanilla;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** the transformation's timeline and the white it fades through. */
class TransformPlanTest {

    @Test
    @DisplayName("the phases run whiten → swap → colour → done, in that order, with no gap")
    void phasesInOrder() {
        TransformPlan.Phase last = null;
        for (int t = 0; t <= TransformPlan.total(); t++) {
            TransformPlan.Phase p = TransformPlan.phaseAt(t);
            if (last != null) {
                assertTrue(p.ordinal() == last.ordinal() || p.ordinal() == last.ordinal() + 1,
                        "phase jumped from " + last + " to " + p + " at " + t);
            }
            last = p;
        }
        assertEquals(TransformPlan.Phase.WHITEN, TransformPlan.phaseAt(0));
        assertEquals(TransformPlan.Phase.SWAP, TransformPlan.phaseAt(TransformPlan.WHITEN));
        assertEquals(TransformPlan.Phase.COLOUR, TransformPlan.phaseAt(TransformPlan.WHITEN + TransformPlan.SWAP));
        assertEquals(TransformPlan.Phase.DONE, TransformPlan.phaseAt(TransformPlan.total()));
    }

    @Test
    @DisplayName("the whole transformation is about two seconds — long enough to read, short enough not to wait on")
    void aboutTwoSeconds() {
        assertTrue(TransformPlan.total() >= 30 && TransformPlan.total() <= 50, "total " + TransformPlan.total());
    }

    @Test
    @DisplayName("the leaving body whitens monotonically and is fully white by the swap")
    void leavingWhitens() {
        float prev = 0f;
        for (int t = 0; t < TransformPlan.WHITEN; t++) {
            float w = TransformPlan.leavingWhiteness(t);
            assertTrue(w >= prev, "whiteness fell at " + t);
            assertTrue(w > 0f && w <= 1f, "out of range at " + t + ": " + w);
            prev = w;
        }
        int lastStep = (TransformPlan.WHITEN - 1) / TransformPlan.STEP * TransformPlan.STEP;
        assertEquals(1f, TransformPlan.leavingWhiteness(lastStep), 1e-6, "not white on the last whiten step");
        assertEquals(1f, TransformPlan.leavingWhiteness(TransformPlan.WHITEN + 3), 1e-6);
    }

    @Test
    @DisplayName("the arriving body is white through the swap and back to its own colours exactly at the end")
    void arrivingColoursIn() {
        for (int t = 0; t < TransformPlan.WHITEN + TransformPlan.SWAP; t++) {
            assertEquals(1f, TransformPlan.arrivingWhiteness(t), 1e-6, "not white during the swap at " + t);
        }
        float prev = 1f;
        for (int t = TransformPlan.WHITEN + TransformPlan.SWAP; t < TransformPlan.total(); t++) {
            float w = TransformPlan.arrivingWhiteness(t);
            assertTrue(w <= prev, "whiteness rose at " + t);
            prev = w;
        }
        int lastStep = TransformPlan.WHITEN + TransformPlan.SWAP
                + (TransformPlan.COLOUR - 1) / TransformPlan.STEP * TransformPlan.STEP;
        assertEquals(0f, TransformPlan.arrivingWhiteness(lastStep), 1e-6, "not its own colours on the last step");
        assertEquals(0f, TransformPlan.arrivingWhiteness(TransformPlan.total()), 1e-6);
    }

    @Test
    @DisplayName("a colour is written every STEP ticks, never every tick")
    void stepped() {
        int writes = 0;
        for (int t = 0; t < TransformPlan.total(); t++) {
            if (TransformPlan.colourStep(t)) {
                writes++;
            }
        }
        assertEquals((TransformPlan.total() + TransformPlan.STEP - 1) / TransformPlan.STEP, writes);
    }

    @Test
    @DisplayName("a body scales about its own centre: the centre stays put, a part moves toward it")
    void scalesAboutTheCentre() {
        float[] c = TransformPlan.centre(List.of(new float[] {0, 0, 0}, new float[] {2, 4, 0}));
        assertArrayEquals(new float[] {1, 2, 0}, c, 1e-6f);
        assertArrayEquals(c, TransformPlan.about(c, c, TransformPlan.TINY), 1e-6f);
        float[] p = TransformPlan.about(new float[] {2, 4, 0}, c, 0.5f);
        assertArrayEquals(new float[] {1.5f, 3f, 0}, p, 1e-6f);
        assertArrayEquals(new float[] {0, 0, 0}, TransformPlan.centre(List.of()), 0f);
    }

    @Test
    @DisplayName("towardWhite: 0 is the colour, 1 is white, and it never overshoots")
    void towardWhite() {
        for (int rgb : new int[] {0x000000, 0x3F7FBF, 0xA06540, 0xFFFFFF}) {
            assertEquals(rgb, RigTint.towardWhite(rgb, 0f));
            assertEquals(0xFFFFFF, RigTint.towardWhite(rgb, 1f));
            assertEquals(0xFFFFFF, RigTint.towardWhite(rgb, 7f));
            assertEquals(rgb, RigTint.towardWhite(rgb, -1f));
        }
        assertEquals(0x808080, RigTint.towardWhite(0x000000, 128f / 255f));
    }
}
