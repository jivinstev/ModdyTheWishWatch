package com.moddingfromamod.species;

import java.util.ArrayList;
import java.util.List;

/**
 * <b>What a creature will DO, said back to the agent the moment it is made</b>.
 * Pure. Ladder level 2: a check inside the authoring tool, before the child ever sees it.
 *
 * <p>Production, 2026-09-25: two pickleball players were given {@code base: companion} "so they
 * don't attack", and walked off their court to follow the child. The schema now says what each
 * instinct does. This is the second line of defence: {@code sculpt_creature} and
 * {@code define_species} echo the consequence of the choice they were just given. It is advice,
 * never a refusal: a flock of pet chicks that follow him is a real wish.
 */
public final class InstinctNotes {

    private InstinctNotes() {
    }

    /**
     * The lines to add to the tool's result for this species; empty when there is nothing to say.
     *
     * @param spawned how many were put in the world by this call (a crowd of followers is louder)
     */
    public static List<String> forSpecies(Species sp, int spawned) {
        List<String> out = new ArrayList<>();
        if (sp == null) {
            return out;
        }
        String who = "'" + sp.name() + "'";
        if (sp.base() == Species.Base.COMPANION) {
            out.add("👣 " + who + " is a COMPANION: it will FOLLOW him everywhere. If he did not ask "
                    + "for it to follow him, re-sculpt it with base 'wild' and the 'friendly' power.");
            if (spawned > 1) {
                out.add("👣 That is " + spawned + " creatures that will ALL follow him around. Fine "
                        + "for a flock of pets he asked for; wrong for anything that belongs to a "
                        + "place.");
            }
        }
        if (sp.archetype() != null) {
            // what its life will look like, said while the agent can still change it.
            out.add("🐾 " + who + " lives like a " + sp.archetype().key().replace('_', ' ') + ": it "
                    + sp.archetype().says() + ".");
        }
        if (attacksHim(sp)) {
            out.add("⚔ " + who + " has no 'friendly' power, so it will ATTACK players"
                    + (sp.base() == Species.Base.HUNTER ? " (it is a hunter)." : ". If it should "
                    + "be harmless, add 'friendly'."));
        }
        return out;
    }

    /** Will it go for a player? The same rule {@code RigInstinct} runs on the carrier. */
    static boolean attacksHim(Species sp) {
        if (sp.archetype() == Archetype.VEHICLE) {
            return false;   // a vehicle has no instincts (RigInstinct.of)
        }
        return switch (sp.base()) {
            case HUNTER -> !sp.has(Species.Power.FRIENDLY);
            case WILD -> sp.isHostile();
            case GUARDIAN, COWARD, COMPANION -> false;
        };
    }
}
