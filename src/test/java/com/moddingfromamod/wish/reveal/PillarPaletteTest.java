package com.moddingfromamod.wish.reveal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Test;

class PillarPaletteTest {

    @Test
    void slotZeroIsThePillarAsItAlwaysWas() {
        // WishOrbs' old constants, so a single player's wishes look exactly as before (#178)
        PillarPalette p = PillarPalette.PLAYERS.get(0);
        assertEquals(0xFFF4FDFF, p.core());
        assertEquals(0x9A4FE6FF, p.halo());
        assertEquals(0x3A4FE6FF, p.haze());
        assertEquals(0x804FE6FF, p.star());
        assertEquals(0xF2DDFAFF, p.arc());
    }

    @Test
    void theWorldIsNeverAPlayersColourAndEveryPlayerColourIsDistinct() {
        assertFalse(PillarPalette.isPlayers(PillarPalette.WORLD.halo()));
        Set<Integer> halos = new HashSet<>();
        for (PillarPalette p : PillarPalette.PLAYERS) {
            halos.add(p.halo() & 0xFFFFFF);
        }
        assertEquals(PillarPalette.PLAYERS.size(), halos.size());
    }

    @Test
    void playersTakeTheLowestFreeSlotAndGiveItBack() {
        PillarPalette.Slots s = new PillarPalette.Slots();
        UUID a = UUID.randomUUID(), b = UUID.randomUUID(), c = UUID.randomUUID();
        assertEquals(0, s.slotOf(a));
        assertEquals(1, s.slotOf(b));
        assertEquals(0, s.slotOf(a), "a player keeps their slot");
        s.release(a);
        assertEquals(0, s.slotOf(c), "the freed slot goes to the next to join");
        assertNotEquals(s.paletteOf(b), s.paletteOf(c));
        assertEquals(0, s.slotOf(null));
    }

    @Test
    void aNinthPlayerSharesRatherThanFails() {
        PillarPalette.Slots s = new PillarPalette.Slots();
        for (int i = 0; i < PillarPalette.PLAYERS.size(); i++) {
            s.slotOf(UUID.randomUUID());
        }
        int ninth = s.slotOf(UUID.randomUUID());
        assertEquals(true, ninth >= 0 && ninth < PillarPalette.PLAYERS.size());
    }

    @Test
    void aPillarForSomebodyNotHereTakesNoSlotAndAWorldsIsCrimson() {
        PillarPalette.Slots s = new PillarPalette.Slots();
        UUID stranger = UUID.randomUUID();
        assertEquals(PillarPalette.PLAYERS.get(0), s.paletteOf(stranger), "not joined: slot 0");
        UUID joined = UUID.randomUUID();
        assertEquals(0, s.slotOf(joined), "drawing a stranger's pillar kept no slot from the next to join");
        UUID world = com.moddingfromamod.world.WorldOwners.of("arena");
        assertEquals(PillarPalette.WORLD, s.paletteOf(world));
        assertEquals(PillarPalette.PLAYERS.get(0), s.paletteOf(joined));
        s.clear();
        assertEquals(0, s.slotOf(UUID.randomUUID()), "a server stopping lets every slot go");
    }

    @Test
    void aWorldOwnerIsToldFromAPlayerWithNothingRemembered() {
        UUID w = com.moddingfromamod.world.WorldOwners.of("arena");
        assertEquals(w, com.moddingfromamod.world.WorldOwners.of("arena"), "one fixed id per module");
        assertNotEquals(w, com.moddingfromamod.world.WorldOwners.of("isle"));
        assertEquals(true, com.moddingfromamod.world.WorldOwners.is(w));
        assertFalse(com.moddingfromamod.world.WorldOwners.is(UUID.randomUUID()), "an online player's id (v4)");
        assertFalse(com.moddingfromamod.world.WorldOwners.is(UUID.nameUUIDFromBytes("OfflinePlayer:Steve".getBytes(
                java.nio.charset.StandardCharsets.UTF_8))), "an offline player's id (v3)");
        assertFalse(com.moddingfromamod.world.WorldOwners.is(null));
    }
}
