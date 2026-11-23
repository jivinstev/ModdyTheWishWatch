package com.moddingfromamod.wish;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class ReplayCommandTest {

    @Test
    void everyReplayableClassesOwnCommandIsAReplay() {
        for (ReplayContract.Replayable r : ReplayContract.ALL) {
            assertTrue(ReplayCommand.isReplay(r.sample().replace("demo_id", "pet_fox")), r.sample());
            assertTrue(ReplayCommand.isReplay("/" + r.sample().replace("demo_id", "pet_fox")), "with its slash");
        }
        assertTrue(ReplayCommand.isReplay("wishsummon fire_cow"));
        assertTrue(ReplayCommand.isReplay("moddyevent stage volcano_erupt_big"));
        assertTrue(ReplayCommand.isReplay("wishbuild castle ~ ~ ~"), "trailing arguments");
    }

    @Test
    void anythingThatMakesOrChangesIsNot() {
        assertFalse(ReplayCommand.isReplay("summon minecraft:cow ~ ~ ~"));
        assertFalse(ReplayCommand.isReplay("wishmob edit pet_fox"), "a creature changed, not put back");
        assertFalse(ReplayCommand.isReplay("moddyevent stop volcano"));
        assertFalse(ReplayCommand.isReplay("wishdatapack wand remove"));
        assertFalse(ReplayCommand.isReplay("wishvisit castle"), "a trip, nothing put back");
        assertFalse(ReplayCommand.isReplay("wishbuild"), "no id");
        assertFalse(ReplayCommand.isReplay(null));
        assertFalse(ReplayCommand.isReplay(""));
    }
}
