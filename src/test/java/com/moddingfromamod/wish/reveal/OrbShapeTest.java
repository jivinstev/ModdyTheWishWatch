package com.moddingfromamod.wish.reveal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** The wish pillar's rules a child would notice. */
class OrbShapeTest {

    @Test
    void theHelixClimbsWithTheWishAndIsWholeOnlyWhenDone() {
        int n = OrbShape.PER_STRAND;
        assertTrue(OrbShape.visible(n, 0f) >= 2, "a fresh wish should already spiral");
        assertTrue(OrbShape.visible(n, 0.99f) <= n);
        assertEquals(n, OrbShape.visible(n, 1f));
        int last = 0;
        for (int k = 0; k <= 100; k++) {
            int v = OrbShape.visible(n, k / 100f);
            assertTrue(v >= last, "the helix shrank as the wish went on");
            last = v;
        }
    }

    @Test
    void itTurnsFasterAsItNearsDone() {
        assertTrue(OrbShape.spinRate(1f) > OrbShape.spinRate(0.5f));
        assertTrue(OrbShape.spinRate(0.5f) > OrbShape.spinRate(0f));
        assertEquals(OrbShape.spinRate(2f), OrbShape.spinRate(1f));
    }

    @Test
    void theHelixHugsThePillar() {
        // The owner's ask: tighter than the reference, so the pillar reads from far away.
        assertTrue(OrbShape.HELIX_RADIUS <= 2.0);
        assertTrue(OrbShape.HELIX_RADIUS - OrbShape.HELIX_BLOCK > OrbShape.CORE_WIDTH / 2,
                "a helix block sits inside the pillar");
        for (int s = 0; s < OrbShape.STRANDS; s++) {
            for (int i = 0; i < OrbShape.PER_STRAND; i++) {
                double[] p = OrbShape.helix(s, i, 0.7);
                assertEquals(OrbShape.HELIX_RADIUS, Math.hypot(p[0], p[2]), 1e-9);
            }
        }
    }

    @Test
    void theStrandsClimbOppositeEachOther() {
        double prev = -1;
        for (int i = 0; i < OrbShape.PER_STRAND; i++) {
            double[] a = OrbShape.helix(0, i, 0.3), b = OrbShape.helix(1, i, 0.3);
            assertTrue(a[1] > prev, "a strand went down");
            prev = a[1];
            assertEquals(a[1], b[1], 1e-9);
            assertEquals(-a[0], b[0], 1e-9);
            assertEquals(-a[2], b[2], 1e-9);
        }
        assertTrue(prev < OrbShape.PILLAR_HEIGHT, "the helix runs off the top of the pillar");
        assertTrue(OrbShape.helix(0, 0, 0)[1] >= 1, "the helix starts in the ground");
    }

    @Test
    void theRevealStartsOnThePillarAtHeadHeight() {
        assertTrue(OrbShape.ORB_HEIGHT > 1 && OrbShape.ORB_HEIGHT < OrbShape.PILLAR_HEIGHT);
    }

    /** a corrected spot is reached by a visible glide, not a jump. */
    @Test
    void aGlideStartsAndEndsWhereItShouldAndArcsInBetween() {
        double[] a = {0, 64, 0}, b = {20, 64, 0};
        org.junit.jupiter.api.Assertions.assertArrayEquals(a, OrbShape.glide(a, b, 0), 1e-9);
        org.junit.jupiter.api.Assertions.assertArrayEquals(b, OrbShape.glide(a, b, 1), 1e-9);
        double[] mid = OrbShape.glide(a, b, 0.5);
        assertEquals(10, mid[0], 1e-9, "halfway there at half time");
        assertTrue(mid[1] > 64 + 2, "it lifts in an arc so it visibly travels: " + mid[1]);
        double last = -1;
        for (int k = 0; k <= 20; k++) {
            double x = OrbShape.glide(a, b, k / 20.0)[0];
            assertTrue(x >= last, "a glide never goes backwards");
            assertTrue(k == 0 || x - last < 3, "no step is a jump: " + (x - last));
            last = x;
        }
        int t = OrbShape.glideTicks(20);
        assertTrue(t >= 24 && t <= 50, "a glide is 1.2–2.5 s: " + t);
    }
}
