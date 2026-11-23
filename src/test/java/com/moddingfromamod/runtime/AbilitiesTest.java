package com.moddingfromamod.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.EnumSet;
import java.util.Set;

import org.junit.jupiter.api.Test;

/**
 * Gate A for the ability timing brain. These rules decide whether a fight feels fair, and they're
 * the sort of thing that's miserable to verify by standing in front of a monster — so they're pure.
 */
class AbilitiesTest {

    private static final int LATER = Abilities.SPAWN_GRACE_TICKS + 1000;

    @Test
    void nothingFiresDuringTheSpawnGrace() {
        Abilities.Cooldowns cd = new Abilities.Cooldowns();
        // A slam is in range at point-blank, but the creature has only just appeared.
        assertNull(Abilities.choose(EnumSet.of(Abilities.Kind.SLAM), 0, 1.0, cd));
        assertNull(Abilities.choose(EnumSet.of(Abilities.Kind.SLAM),
                Abilities.SPAWN_GRACE_TICKS - 1, 1.0, cd));
        assertNotNull(Abilities.choose(EnumSet.of(Abilities.Kind.SLAM),
                Abilities.SPAWN_GRACE_TICKS, 1.0, cd));
    }

    @Test
    void rangeBandsPickTheRightMove() {
        Set<Abilities.Kind> both = EnumSet.of(Abilities.Kind.RANGED, Abilities.Kind.SLAM);
        Abilities.Cooldowns cd = new Abilities.Cooldowns();
        // Point-blank: the stomp, not the fireball.
        assertEquals(Abilities.Kind.SLAM, Abilities.choose(both, LATER, 2.0, cd));
        // Across the room: the fireball, not the stomp.
        assertEquals(Abilities.Kind.RANGED, Abilities.choose(both, LATER, 15.0, new Abilities.Cooldowns()));
        // Miles away: neither is in range.
        assertNull(Abilities.choose(both, LATER, 100.0, new Abilities.Cooldowns()));
    }

    @Test
    void cooldownsStopAMoveFiringEveryTick() {
        Abilities.Cooldowns cd = new Abilities.Cooldowns();
        Set<Abilities.Kind> slam = EnumSet.of(Abilities.Kind.SLAM);

        assertEquals(Abilities.Kind.SLAM, Abilities.choose(slam, LATER, 1.0, cd));
        cd.use(Abilities.Kind.SLAM, LATER);
        // Immediately after: not ready.
        assertNull(Abilities.choose(slam, LATER + 1, 1.0, cd));
        assertNull(Abilities.choose(slam, LATER + Abilities.Kind.SLAM.cooldown - 1, 1.0, cd));
        // Once the cooldown elapses: ready again.
        assertEquals(Abilities.Kind.SLAM,
                Abilities.choose(slam, LATER + Abilities.Kind.SLAM.cooldown, 1.0, cd));
    }

    @Test
    void withNoTargetOnlyHealMakesSense() {
        Abilities.Cooldowns cd = new Abilities.Cooldowns();
        Set<Abilities.Kind> all = EnumSet.allOf(Abilities.Kind.class);
        // Negative distance = "nothing to fight".
        assertEquals(Abilities.Kind.HEAL, Abilities.choose(all, LATER, -1, cd));
        // A creature with no heal just carries on normally.
        assertNull(Abilities.choose(EnumSet.of(Abilities.Kind.SLAM, Abilities.Kind.RANGED),
                LATER, -1, cd));
    }

    @Test
    void aCreatureWithNoAbilitiesNeverFires() {
        assertNull(Abilities.choose(Set.of(), LATER, 3.0, new Abilities.Cooldowns()));
        assertNull(Abilities.choose(null, LATER, 3.0, new Abilities.Cooldowns()));
    }

    @Test
    void onlyAbilitiesTheCreatureHasAreChosen() {
        // It has SLAM in range, but only RANGED is on its sheet.
        assertNull(Abilities.choose(EnumSet.of(Abilities.Kind.RANGED), LATER, 1.0,
                new Abilities.Cooldowns()));
    }

    @Test
    void namesParseBothWaysAndIgnoreNonsense() {
        assertEquals(Abilities.Kind.TELEPORT, Abilities.parse("teleport"));
        assertEquals(Abilities.Kind.TELEPORT, Abilities.parse("  TELEPORT "));
        assertNull(Abilities.parse("sparkly"));
        assertNull(Abilities.parse(null));
        assertTrue(Abilities.names().contains("ranged"));
        assertEquals(Abilities.Kind.values().length, Abilities.names().size());
    }

    @Test
    void everyAbilityHasASaneRangeAndCooldown() {
        for (Abilities.Kind k : Abilities.Kind.values()) {
            assertTrue(k.minRange <= k.maxRange, k + " range band is inverted");
            assertTrue(k.cooldown > 0, k + " would fire every tick");
        }
    }

    /**
     * #2026-10-31: RigAbilities skips its monster scan when {@code anyReady} is false, which is only
     * behaviour-identical if choose() then picks nothing at ANY distance — checked over sets of moves and cooldown
     * states (an all-pairs sample of the on/off states of every move, plus none, each one alone and all of them,
     * not every subset, which doubles with each new move), and a sweep of ticks and ranges.
     */
    @org.junit.jupiter.api.Test
    void anyReadyIsFalseExactlyWhenChooseCanPickNothing() {
        Abilities.Kind[] all = Abilities.Kind.values();
        double[] distances = {-1, 0, 1.5, 3, 5, 8, 12, 16, 24, 40};
        java.util.List<java.util.Set<Abilities.Kind>> sets = subsets(all);
        for (java.util.Set<Abilities.Kind> set : sets) {
            if (set.isEmpty()) {
                continue;
            }
            for (java.util.Set<Abilities.Kind> usedSet : sets) {
                for (int tick : new int[]{0, Abilities.SPAWN_GRACE_TICKS - 1, Abilities.SPAWN_GRACE_TICKS,
                        Abilities.SPAWN_GRACE_TICKS + 5, Abilities.SPAWN_GRACE_TICKS + 400}) {
                    Abilities.Cooldowns cd = new Abilities.Cooldowns();
                    for (Abilities.Kind k : usedSet) {
                        cd.use(k, Math.max(0, tick - 1));
                    }
                    boolean any = Abilities.anyReady(set, tick, cd);
                    boolean someChoice = false;
                    for (double d : distances) {
                        someChoice |= Abilities.choose(set, tick, d, cd) != null;
                    }
                    if (!any) {
                        org.junit.jupiter.api.Assertions.assertFalse(someChoice,
                                "anyReady said no but choose picked something: " + set + " tick " + tick);
                    }
                }
            }
        }
    }

    /** None, each move alone, all of them, and an all-pairs sample of every move's on/off. */
    static java.util.List<java.util.Set<Abilities.Kind>> subsets(Abilities.Kind[] all) {
        java.util.List<java.util.Set<Abilities.Kind>> out = new java.util.ArrayList<>();
        out.add(java.util.EnumSet.noneOf(Abilities.Kind.class));
        for (Abilities.Kind k : all) {
            out.add(java.util.EnumSet.of(k));
        }
        out.add(java.util.EnumSet.allOf(Abilities.Kind.class));
        int[] onOff = new int[all.length];
        java.util.Arrays.fill(onOff, 2);
        for (int[] row : com.moddingfromamod.testing.AllPairs.rows(onOff, 2026)) {
            java.util.Set<Abilities.Kind> set = java.util.EnumSet.noneOf(Abilities.Kind.class);
            for (int b = 0; b < all.length; b++) {
                if (row[b] == 1) {
                    set.add(all[b]);
                }
            }
            out.add(set);
        }
        return out;
    }
}
