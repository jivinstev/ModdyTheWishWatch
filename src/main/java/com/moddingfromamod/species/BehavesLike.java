package com.moddingfromamod.species;

import java.util.function.Function;

/**
 * <b>{@code behaves_like}</b>: the one word a creature tool takes for HOW a creature lives — the
 * vanilla mob it lives like ({@code "wolf"}, {@code "spider"}, {@code "skeleton"}), or {@code "dragon"}.
 * One field and a mob's name rather than a menu of behaviours, so the prompt does not grow with the
 * archetypes: the agent already knows what a wolf does, and the table ({@link Archetype}) says which
 * life a wolf's is.
 *
 * <p>Resolved in three steps, and only the last refuses:
 * <ol>
 *   <li>a mob or word the table knows → that archetype;</li>
 *   <li>any other mob this game has (a modded one, or a vanilla one with no row yet) → the nearest built
 *       archetype by what it IS — flies, swims, is a monster ({@link Archetype#byTraits});</li>
 *   <li>anything else → refused by name, never dropped in silence (an earlier issue's rule for powers).</li>
 * </ol>
 * Pure: the registry is handed in as {@code traitsOf}, which answers null for a name it does not have.
 */
public final class BehavesLike {

    private BehavesLike() {
    }

    /** What a registered mob IS, as far as choosing its life goes. */
    public record Traits(boolean flies, boolean water, boolean monster) {
    }

    /**
     * {@code archetype} null with {@code error} empty means the word was absent: nothing changes. An
     * {@code error} is the refusal to hand back; {@code note} says how a stand-in was chosen.
     */
    public record Result(Archetype archetype, String note, String error) {
        static final Result NONE = new Result(null, "", "");

        public boolean refused() {
            return !error.isEmpty();
        }
    }

    /** The tool argument's name. */
    public static final String ARG = "behaves_like";

    /** The schema's description, shared by every creature tool. */
    public static final String DESCRIBE =
            "HOW IT LIVES when nobody is riding or fighting it: the vanilla mob it lives like, e.g. "
                    + "'wolf' (trots, keeps with its pack, pounces), 'cow' (grazes, herds), 'horse' (comes over "
                    + "to its rider), 'bee' (flits low), 'spider' (climbs walls), 'skeleton' (keeps its distance "
                    + "and shoots), 'villager' (potters about, looks at you), 'ravager' (heavy, charges), "
                    + "'cod' (swims), or 'dragon' (circles high over its home and perches). Anything he DRIVES or "
                    + "flies in (a car, truck, plane, helicopter, boat, submarine, train, rocket) is 'vehicle': it "
                    + "stays parked until he drives it and its parts never move by themselves. Name the mob whose "
                    + "life is THIS creature's, whatever it looks like: a dragon lives like 'dragon' on any body. "
                    + "Left out it keeps the life its body's carrier gives it.";

    /**
     * The sculpt tool's description: there it is required, and left out one is chosen for it, where on a
     * re-shape ({@code modify_mob}) or a reskin ({@code define_species}) left out still changes nothing.
     */
    public static final String SCULPT_DESCRIBE = DESCRIBE.replace(
            "Left out it keeps the life its body's carrier gives it.",
            "REQUIRED: name one for EVERY creature, the first sculpt and every re-sculpt. A creature that flies "
                    + "lives a flying life ('dragon', 'bee', 'horse' for one he rides, 'vehicle' for one he flies in): "
                    + "a flier named a ground life is given a flying one, and a sea creature (fins, no legs) a land life swims. Left out, one is chosen from its powers "
                    + "and size and the reply says which; with start_from it keeps the base's.");

    public static Result resolve(String word, Function<String, Traits> traitsOf) {
        if (word == null || word.isBlank()) {
            return Result.NONE;
        }
        Archetype a = Archetype.resolve(word);
        if (a != null) {
            return new Result(a, "", "");
        }
        String id = word.trim().toLowerCase(java.util.Locale.ROOT).replace(' ', '_');
        Traits t = traitsOf == null ? null : traitsOf.apply(id.contains(":") ? id : "minecraft:" + id);
        if (t != null) {
            Archetype near = Archetype.byTraits(t.flies(), t.water(), t.monster());
            return new Result(near, "'" + word.trim() + "' has no life of its own here yet, so it lives like a "
                    + near.key() + " — the nearest one built.", "");
        }
        return new Result(null, "", "'" + word.trim() + "' is not a mob this game has. Name the vanilla mob this "
                + "creature lives like — 'wolf', 'cow', 'horse', 'bee', 'spider', 'skeleton', 'villager', "
                + "'ravager', 'cod' — or 'dragon', or 'vehicle' for anything he drives.");
    }
}
