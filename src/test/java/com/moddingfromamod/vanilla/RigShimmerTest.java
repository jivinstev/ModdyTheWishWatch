package com.moddingfromamod.vanilla;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** The in-progress shimmer's colours: pastel, moving, and travelling up the body. */
class RigShimmerTest {

    @Test
    @DisplayName("no channel ever drops below the floor, so a gold creature never goes black")
    void staysPastel() {
        for (long t = 0; t < RigShimmer.PERIOD_TICKS * 2; t++) {
            for (float h = 0f; h <= 1f; h += 0.1f) {
                int c = RigShimmer.tintFor(t, h);
                for (int shift : new int[] {16, 8, 0}) {
                    int ch = (c >> shift) & 0xFF;
                    assertTrue(ch >= RigShimmer.FLOOR && ch <= 255,
                            "channel " + ch + " at t=" + t + " h=" + h);
                }
            }
        }
        // the case that motivated the floor: blue on gold
        int goldUnderBlue = RigTint.multiply(0xE0B341, RigShimmer.pastel(0x0000FF));
        assertTrue(((goldUnderBlue >> 16) & 0xFF) > 0x70, "gold stays readable under the blue frame");
    }

    @Test
    @DisplayName("the wave really cycles red, green and blue over one period")
    void visitsAllThreeHues() {
        Set<Character> dominant = new HashSet<>();
        for (long t = 0; t < RigShimmer.PERIOD_TICKS; t++) {
            int c = RigShimmer.tintFor(t, 0f);
            int r = (c >> 16) & 0xFF, g = (c >> 8) & 0xFF, b = c & 0xFF;
            if (r > g && r > b) {
                dominant.add('r');
            } else if (g > r && g > b) {
                dominant.add('g');
            } else if (b > r && b > g) {
                dominant.add('b');
            }
        }
        assertEquals(Set.of('r', 'g', 'b'), dominant);
        assertEquals(RigShimmer.tintFor(3, 0.2f), RigShimmer.tintFor(3 + RigShimmer.PERIOD_TICKS, 0.2f),
                "one period later it is the same frame");
    }

    @Test
    @DisplayName("the feet and the head are at different points of the wave")
    void travelsUpTheBody() {
        assertNotEquals(RigShimmer.tintFor(10, 0f), RigShimmer.tintFor(10, 1f));
    }

    @Test
    @DisplayName("it steps five times a second, anchored on game time")
    void writeCadence() {
        int writes = 0;
        for (long t = 0; t < 20; t++) {
            if (RigShimmer.writesOn(t)) {
                writes++;
            }
        }
        assertEquals(5, writes);
    }
}
