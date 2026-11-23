package com.moddingfromamod.datapack;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** The parts of change_block (turn_into's block side) that are true without a game. */
class ChangeBlockTest {

    @Test
    @DisplayName("'any' is our curated tag, a tag passes through, a block id is left to BlockRef")
    void fromReadsAnyATagOrABlock() {
        assertEquals(ChangeBlock.ANY, ChangeBlock.fromTag("any"));
        assertEquals(ChangeBlock.ANY, ChangeBlock.fromTag(" Anything "));
        assertEquals("#minecraft:logs", ChangeBlock.fromTag("#minecraft:logs"));
        assertNull(ChangeBlock.fromTag("minecraft:dirt"));
        assertNull(ChangeBlock.fromTag("#logs] run kill @a"), "a 'tag' with a payload is not a tag");
    }

    @Test
    @DisplayName("the radius is 0 to 3, and 0 only on a thing that aims exactly (held, on_use_done)")
    void radiusIsBoundedAndZeroOnlyWhenHeld() {
        assertEquals(0, ChangeBlock.radius("0"));
        assertEquals(3, ChangeBlock.radius("3"));
        assertNull(ChangeBlock.radius("4"));
        assertNull(ChangeBlock.radius("-1"));
        assertNull(ChangeBlock.radius("big"));
        assertNull(ChangeBlock.radiusRefusal("held", 0));
        // on_use_done walks the same line of sight, and was refused 0 although it aims exactly.
        assertNull(ChangeBlock.radiusRefusal("on_use_done", 0));
        assertNotNull(ChangeBlock.radiusRefusal("on_block", 0));
        assertNotNull(ChangeBlock.radiusRefusal("thrown", 0));
        assertNull(ChangeBlock.radiusRefusal("thrown", 1));
    }

    @Test
    @DisplayName("the aim walks six blocks in quarter steps and fires at most once")
    void theAimWalksAndFiresOnce() {
        List<String> lines = ChangeBlock.aimLines("x:effect");
        assertEquals(2 + 4 * ChangeBlock.STEPS, lines.size());
        assertTrue(lines.get(1).contains("^ ^ ^0.25 ") && lines.get(1).contains("tag=!mfam_aimed"),
                lines.get(1));
        assertTrue(lines.stream().anyMatch(l -> l.contains("^ ^ ^6 ")), "the last step is 6 blocks");
        assertTrue(lines.stream().filter(l -> l.contains("run function x:effect"))
                .allMatch(l -> l.contains("tag=mfam_aim_now") && l.contains("align xyz")),
                "the effect runs only on the step that hit, centred in that block");
        assertEquals("/tag @s remove mfam_aimed", lines.get(lines.size() - 1),
                "the flag is cleared, so the next click aims afresh");
    }
}
