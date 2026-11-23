package com.moddingfromamod.wish;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The spellings the agent really sent in the 2026-10-10 chained follow-ups. */
class AboutIdsTest {

    @Test
    void theBareWordNamesTheItemAndTheMob() {
        assertTrue(AboutIds.same("id:black_hole_block_pull", "black_hole_block_pull"));
        assertTrue(AboutIds.same("id:bouncy_cloud_block_fly", "Bouncy_Cloud_Block_Fly"));
        assertTrue(AboutIds.same("@bee", "bee"));
        assertTrue(AboutIds.same("@bee", "@bee"));
        assertTrue(AboutIds.same("pet_dragon", "pet_dragon"));
    }

    @Test
    void differentThingsStayDifferent() {
        assertFalse(AboutIds.same("@bee", "bee2"));
        assertFalse(AboutIds.same("id:black_hole_block_pull", "black_hole"));
        assertFalse(AboutIds.same("", ""));
        assertFalse(AboutIds.same("@skeleton_horse2", "skeleton_horse"));
    }
}
