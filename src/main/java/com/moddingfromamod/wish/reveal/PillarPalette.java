package com.moddingfromamod.wish.reveal;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * <b>Whose wish is this pillar?</b> (#178) One colour per wisher: every player has their own, and a world (the arena
 * wishing its monsters) is always {@link #WORLD}, crimson, which no player is ever given. Pure: no Minecraft.
 *
 * <p>Each palette is the five ARGB colours a pillar is drawn in (beam core, halo, haze, the star on the ground, the
 * arcs), all text-display backgrounds, so a stock client draws them. Slot 0 is the pillar as it always was, so a
 * single player's wishes look exactly as before.
 *
 * <p>{@link Slots} hands out slots: a player takes the lowest free one when they join and gives it back when they
 * leave; every slot is let go when the server stops, so a colour never follows anyone into another world. Session-scoped by design: on a busy server the same child can be blue today and lime tomorrow; with one or two
 * players it is stable. A ninth player shares a slot (the lowest, by join order modulo the slots).
 */
public record PillarPalette(String name, int core, int halo, int haze, int star, int arc) {

    /** A palette from three RGB colours, with the alphas the pillar has always used per layer. */
    static PillarPalette of(String name, int haloRgb, int coreRgb, int arcRgb) {
        return new PillarPalette(name, 0xFF000000 | coreRgb, 0x9A000000 | haloRgb, 0x3A000000 | haloRgb,
                0x80000000 | haloRgb, 0xF2000000 | arcRgb);
    }

    /** The pillar as it always was (cyan halo, white core): slot 0. */
    public static final PillarPalette CYAN = new PillarPalette("cyan", 0xFFF4FDFF, 0x9A4FE6FF, 0x3A4FE6FF, 0x804FE6FF,
            0xF2DDFAFF);

    /** The players' palettes, in the order they are handed out. */
    public static final List<PillarPalette> PLAYERS = List.of(
            CYAN,
            of("violet", 0xB04FFF, 0xFAF0FF, 0xEEDDFF),
            of("lime", 0x6CFF4F, 0xF6FFF2, 0xE4FFDD),
            of("gold", 0xFFC94F, 0xFFFBEF, 0xFFF1D6),
            of("pink", 0xFF4FC8, 0xFFF2FB, 0xFFDDF4),
            of("orange", 0xFF8A3D, 0xFFF5EE, 0xFFE6D5),
            of("blue", 0x4F7CFF, 0xF0F4FF, 0xDDE6FF),
            of("white", 0xE8E8E8, 0xFFFFFF, 0xFFFFFF));

    /** A world's own wishes: crimson, never a player's. */
    public static final PillarPalette WORLD = of("crimson", 0xFF2B2B, 0xFFE9E9, 0xFFD0D0);

    /** Is {@code rgb} (no alpha) one of a player's halo colours? For the test that the world's is never one. */
    static boolean isPlayers(int haloArgb) {
        for (PillarPalette p : PLAYERS) {
            if ((p.halo & 0xFFFFFF) == (haloArgb & 0xFFFFFF)) {
                return true;
            }
        }
        return false;
    }

    /** Who holds which player slot. Not thread-safe by itself: callers hold the server thread. */
    public static final class Slots {
        private final Map<UUID, Integer> held = new HashMap<>();
        private int joins;

        /** {@code player}'s slot: the one they hold, else the lowest free one (taken now). */
        public synchronized int slotOf(UUID player) {
            if (player == null) {
                return 0;
            }
            Integer have = held.get(player);
            if (have != null) {
                return have;
            }
            int slot = -1;
            for (int i = 0; i < PLAYERS.size(); i++) {
                if (!held.containsValue(i)) {
                    slot = i;
                    break;
                }
            }
            if (slot < 0) {
                slot = joins % PLAYERS.size();   // all taken: share, spread by join order
            }
            joins++;
            held.put(player, slot);
            return slot;
        }

        /**
         * {@code player}'s palette: a world's own ({@code WorldOwners}) is {@link #WORLD}; a player holding a slot,
         * that slot's; anyone else (not joined, a stand-in) slot 0. Takes no slot: only joining does
         * ({@link #slotOf}), so a pillar drawn for somebody who is not here never keeps a colour from a player.
         */
        public synchronized PillarPalette paletteOf(UUID player) {
            if (com.moddingfromamod.world.WorldOwners.is(player)) {
                return WORLD;
            }
            Integer have = player == null ? null : held.get(player);
            return PLAYERS.get(have == null ? 0 : have);
        }

        /** They left: the slot is free for the next to join. */
        public synchronized void release(UUID player) {
            if (player != null) {
                held.remove(player);
            }
        }

        public synchronized void clear() {
            held.clear();
            joins = 0;
        }
    }
}
