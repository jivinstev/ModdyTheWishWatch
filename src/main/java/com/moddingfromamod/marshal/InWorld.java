package com.moddingfromamod.marshal;

/**
 * <b>Is one of his things OUT in the world right now, and how many?</b>
 *
 * <p>The catalogue lists his DESIGNS — every creature he ever wished for, every building he ever
 * had. A wish that acts on one that is out there ("kill my t rex", "ride my dragon", "make my horse
 * faster") can only mean one that is out there, and nothing said which those were: a
 * child with three T-Rex designs and one T-Rex standing in front of him was asked to pick between
 * three pictures, picked one that had not been out for days, and the removal found nothing to
 * remove — one wish spent, the T-Rex he meant still stomping.
 *
 * <p>{@code count} is what the acting tools would find: live creatures of that design in a loaded
 * part of any world ({@code RemoveCreatureTool.carriersOf}), 1 for a building that stands, 1 for a
 * changed mob that is still alive. {@link #NOT_A_THING} is every kind that has no instance to be out
 * (an item, a rule, a datapack, an event design) — those are never filtered.
 *
 * <p>Minecraft-free, so the wording and the "is it out" rule are unit-tested ({@code InWorldTest}).
 */
public record InWorld(int count) {

    /** A kind with no instance to be out in the world: never filtered, never described. */
    public static final InWorld NOT_A_THING = new InWorld(-1);

    public static InWorld of(int count) {
        return new InWorld(Math.max(0, count));
    }

    public static InWorld standing(boolean stands) {
        return new InWorld(stands ? 1 : 0);
    }

    /** True when the question "is it out" means anything for this kind. */
    public boolean applies() {
        return count >= 0;
    }

    /** Out in the world — or a kind for which there is nothing to be out, which is never filtered. */
    public boolean out() {
        return count != 0;
    }

    /**
     * The catalogue's words for it, or "" for {@link #NOT_A_THING}. Written for the agent: "design
     * only" is the phrase that tells it a wish to hit, ride or remove it has nothing to act on, and a
     * wish to make ANOTHER one still can.
     */
    public String describe(String kind) {
        if (!applies()) {
            return "";
        }
        if ("build".equals(kind)) {
            return count > 0 ? "standing" : "NOT standing — design only";
        }
        if ("mob".equals(kind)) {
            return count > 0 ? "alive" : "gone";
        }
        if (count == 0) {
            return "none out in the world now — design only";
        }
        return count == 1 ? "1 out in the world" : count + " out in the world";
    }
}
