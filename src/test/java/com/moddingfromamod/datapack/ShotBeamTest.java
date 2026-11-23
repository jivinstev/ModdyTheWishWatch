package com.moddingfromamod.datapack;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** the laser's beam lines, read as text — no game needed. */
class ShotBeamTest {

    @Test
    void aBeamForSomethingElseRunsTheWishWhereItStopsAndOnlyOnce() {
        List<String> lines = ShotBeam.lines("ns:effect", true, "minecraft:end_rod");
        long calls = lines.stream().filter(l -> l.contains("run function ns:effect")).count();
        // One conditional call per step, plus the open-air end; the DONE tag makes them exclusive.
        assertEquals(ShotBeam.REACH + 1, calls);
        assertTrue(lines.stream().allMatch(l -> !l.contains("run function") || l.contains("mfam_beam")),
                "every call to the wish must be gated on the beam's own tags");
        assertTrue(lines.get(lines.size() - 1).contains("remove " + ShotBeam.SHOOTER),
                "the shooter's tag must come off at the end, or the next beam cannot find him");
        assertTrue(lines.stream().anyMatch(l -> l.contains("tag=!" + ShotBeam.SHOOTER)),
                "the beam must never stop on the one who fired it");
    }

    @Test
    void aBeamForHimRunsTheWishOnHimAndStillDrawsTheBeam() {
        List<String> lines = ShotBeam.lines("ns:effect", false, "minecraft:heart");
        assertEquals(1, lines.stream().filter(l -> l.contains("function ns:effect")).count());
        assertTrue(lines.contains("/function ns:effect"));
        assertTrue(lines.stream().anyMatch(l -> l.contains("particle minecraft:heart")));
    }

    @Test
    void theBeamStopsAtSolidBlocksAndCreaturesAndNeverAtArrows() {
        String all = String.join("\n", ShotBeam.lines("ns:effect", true, "minecraft:end_rod"));
        assertTrue(all.contains("unless block ~ ~ ~ " + ChangeBlock.SEE_THROUGH));
        assertTrue(all.contains("type=!#minecraft:arrows"), "his own arrow would stop his own beam");
        assertFalse(all.contains("^ ^ ^" + (ShotBeam.REACH + 1)));
    }

    @Test
    void theBeamIsTheItemsOwnColour() {
        assertEquals("minecraft:dust{color:[1.000,0.125,0.125],scale:1.5}", ShotBeam.particle(0xFF2020));
    }

    @Test
    void theGuidanceOffersBothMomentsOnABow() {
        String g = ItemTriggers.guidance(List.of("arrow", "on_shot"));
        assertTrue(g.contains("`arrow` or `on_shot`"), g);
        assertFalse(ItemTriggers.guidance(List.of("arrow")).contains("on_shot"),
                "a ride that is not built must not be offered");
    }
}
