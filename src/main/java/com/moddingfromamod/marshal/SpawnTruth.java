package com.moddingfromamod.marshal;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;

/**
 * <b>Is the creature this wish spawned standing anywhere?</b> — the pure half of
 * {@code finish_wish}'s check, so the rule is a unit test ({@code SpawnTruthTest}).
 *
 * <p>: a wish sculpted a creature, spawned it, was told <i>"Spawned 0×"</i>, and ended DONE
 * with <i>"Your GIANT sea creature is here"</i>. Four minutes later: <i>"I can't see the sea
 * creature you made"</i>. Nothing between the spawn and the verdict asked whether anything was
 * there.
 *
 * <p>Only species this wish tried to SPAWN are asked about. A sculpt with no spawn (a spawn egg,
 * a design for later) records no attempt and is not a missing creature.
 */
public final class SpawnTruth {

    private SpawnTruth() {
    }

    /**
     * @param attempts   this wish's spawn attempts, oldest first
     * @param isStanding whether at least one creature of this species is standing in the world
     * @return the first species this wish spawned of which none is standing, or null
     */
    public static String missing(List<WishRun.CreatureSpawned> attempts,
                                 Predicate<String> isStanding) {
        if (attempts == null || attempts.isEmpty() || isStanding == null) {
            return null;
        }
        Set<String> species = new LinkedHashSet<>();
        for (WishRun.CreatureSpawned a : attempts) {
            if (a != null && a.species() != null && !a.species().isBlank()) {
                species.add(a.species());
            }
        }
        for (String s : species) {
            if (!isStanding.test(s)) {
                return s;
            }
        }
        return null;
    }

    /** What the agent is told when a granted verdict was recorded as {@code failed} for this. */
    public static String note(String species) {
        return "Note: no '" + species + "' is standing anywhere in the world — the creature this "
                + "wish spawned is not there, so the child cannot see it, and this was recorded as "
                + "'failed'. To grant a creature, spawn it and read the count the spawn reports "
                + "(it is a count of what is really standing there).";
    }
}
