package com.moddingfromamod.place;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import com.moddingfromamod.wish.CommandGuard;
import org.junit.jupiter.api.Test;

/** Gate A 's run_command {@code at}: each command on the spot, once, and still guarded. */
class AtCommandsTest {

    @Test
    void eachCommandRunsOnTheSpot() {
        assertEquals(List.of("wishspot garage:inside run summon minecraft:minecart ~ ~ ~",
                        "wishspot garage:inside run summon minecraft:minecart ~1 ~ ~"),
                AtCommands.wrap(List.of("summon minecraft:minecart ~ ~ ~", "/summon minecraft:minecart ~1 ~ ~"),
                        "Garage:Inside"));
    }

    @Test
    void noAtLeavesThemAloneAndABadAtIsRefused() {
        List<String> c = List.of("say hi");
        assertSame(c, AtCommands.wrap(c, ""));
        assertSame(c, AtCommands.wrap(c, null));
        assertNull(AtCommands.wrap(c, ":roof"));
    }

    @Test
    void aCommandAlreadyOnASpotIsNotWrappedTwice() {
        assertEquals(List.of("wishspot castle:top run say hi"),
                AtCommands.wrap(List.of("wishspot castle:top run say hi"), "garage:inside"));
        assertTrue(AtCommands.isOnASpot("/wishspot me:front run say hi"));
    }

    @Test
    void theWrappedCommandIsGuardedLikeTheBareOne() {
        assertTrue(CommandGuard.isAllowed(AtCommands.wrap(List.of("summon minecraft:minecart ~ ~ ~"), "garage:inside").get(0)));
        assertTrue(!CommandGuard.isAllowed(AtCommands.wrap(List.of("stop"), "garage:inside").get(0)),
                "an at never lets through what the guard refuses");
        assertTrue(CommandGuard.isAllowed("wishspot kitchen:inside run wishput slushie_machine"),
                "make_item's at is a wishspot + wishput");
    }
}
