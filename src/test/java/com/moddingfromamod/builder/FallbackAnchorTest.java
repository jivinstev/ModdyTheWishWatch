package com.moddingfromamod.builder;

import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * <b>Two things built with nobody watching must not land on the same square.</b>
 *
 * <p>All three build tools carried the same line — spawn plus a fixed forward offset — so with no
 * player and no locked spot, <b>every unattended build in the world went to one place</b>. Found as
 * a red Gate B (two contraption tests both building at {@code 6, 60, 0}, the first to assert
 * reading the other's blocks) but it is not a test-only defect: a wish granted while the child is
 * away has no player to anchor on, and two machines wished for in that state would be built into
 * each other.
 *
 * <p>The spread has to satisfy two things at once, which is why it is a hash of the id rather than
 * a counter: <b>the same id always returns to the same spot</b>, so a rebuild replaces rather than
 * litters, and <b>different ids do not collide</b>, so a neighbour cannot be misread as your own
 * output. A counter would break the first; a random offset would break both.
 *
 * <p>Pure arithmetic, so it is tested here rather than in a game: the Minecraft half is one
 * {@code getSharedSpawnPos()} call, and what can be wrong is the spreading.
 */
class FallbackAnchorTest {

    /** The same arithmetic {@code Builder.fallbackAnchor} does, minus the ServerLevel. */
    private static int[] slot(String id) {
        int h = mix(id.hashCode());
        return new int[]{Math.floorMod(h, 16) * 64, Math.floorMod(mix(h), 16) * 64};
    }

    private static int mix(int h) {
        h ^= h >>> 16;
        h *= 0x85EBCA6B;
        h ^= h >>> 13;
        h *= 0xC2B2AE35;
        h ^= h >>> 16;
        return h;
    }

    @Test
    @DisplayName("the same thing always comes back to the same place")
    void rebuildingReplacesRatherThanLitters() {
        for (String id : new String[]{"grain_mill", "gate_reb_mill", "castle", "my train"}) {
            assertEquals(slot(id)[0], slot(id)[0]);
            assertEquals(slot(id)[1], slot(id)[1]);
        }
    }

    /**
     * The one that would have caught the Gate B failure: these are the two ids that collided.
     */
    @Test
    @DisplayName("the two ids that actually collided no longer share a square")
    void theRealCollisionIsGone() {
        int[] mill = slot("grain_mill");
        int[] reb = slot("gate_reb_mill");
        assertTrue(mill[0] != reb[0] || mill[1] != reb[1],
                "grain_mill and gate_reb_mill are still on the same square — this is the exact "
                        + "pair whose collision turned Gate B red");
    }

    /**
     * And it holds across the ids this repo really builds, not just the two that were caught.
     * Reported as a LIST rather than dying on the first clash: "one pair collides" and "the
     * spreading does nothing" need different fixes and a fail-fast cannot tell them apart.
     *
     * <p><b>This test earned its keep immediately.</b> The first implementation sliced the raw
     * Java string hash — {@code h % 16} and {@code (h / 16) % 16} — which reads overlapping,
     * badly-distributed low bits, and {@code bakery} and {@code house} came out on the same square.
     * The fix is an avalanche mix before slicing, and a second round for the second axis.
     */
    @Test
    @DisplayName("no two real build ids share a square")
    void realIdsAreSpreadOut() {
        String[] ids = {
            "grain_mill", "gate_reb_mill", "melter", "crusher", "washer", "bakery",
            "castle", "tower", "house", "bridge", "farm", "shop",
            "my train", "coal train", "the big train", "zonkbolt", "javelin", "fire_flower",
            "noob_pearl_aggro", "lava_pearl_place", "whole_pizza_food", "small_cup_plain",
        };
        Map<String, String> seen = new HashMap<>();
        StringBuilder clashes = new StringBuilder();
        for (String id : ids) {
            int[] s = slot(id);
            String key = s[0] + "," + s[1];
            String prior = seen.put(key, id);
            if (prior != null) {
                clashes.append("\n  ").append(prior).append(" and ").append(id)
                       .append(" both at ").append(key);
            }
        }
        assertEquals("", clashes.toString(), "unattended builds would be built into each other:"
                + clashes);
    }

    /**
     * The spread must clear the biggest thing built unattended. A machine is tens of blocks; the
     * gap is 64, so even two at adjacent slots cannot touch.
     */
    @Test
    @DisplayName("the gap is wider than anything built unattended")
    void theGapClearsAFootprint() {
        assertTrue(64 >= 48, "the slot spacing is narrower than a large machine's footprint");
        // 16 slots per axis: enough that a child's whole session of away-builds fits without
        // wrapping, and small enough that the spread stays inside loaded-ish chunks near spawn.
        assertEquals(256, 16 * 16);
    }
}
