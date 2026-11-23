package com.moddingfromamod.wishbuilder;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BiFunction;

/**
 * <b>The Builder's REAL limits, and the one place they live</b> (v1-blocker). The menus used to say "two
 * powers", "three parts", "two special moves", "two things a snack does": numbers somebody typed, which no tool
 * needed. A kid wanted a creature that flies, glows, breathes fire and leaves flowers and was told no for nothing.
 *
 * <p>What is left is what a TOOL refuses or silently loses, and nothing else:
 * <ul>
 *   <li><b>a creature's parts</b> fill the body's ROOM: sculpt_creature keeps kit boxes to a share of the creature
 *       ({@link BuilderParts#budget}) and drops the rest without a word;</li>
 *   <li><b>a creature's powers</b> are flags on its species: any number, no limit;</li>
 *   <li><b>a creature's special moves</b>: any number, but ONE of each effect. apply_effect names a move's datapack
 *       {@code <effect>_<creature>} ({@code ApplyEffectTool.featureId}), so a second {@code breath_cone} replaces
 *       the first: frosty breath after fire breath leaves a creature that only breathes frost;</li>
 *   <li><b>a snack</b> is one make_item: at most {@code ItemCatalogue.MAX_EFFECTS} effects, never one effect twice
 *       (giant and tiny are both {@code grow}), and "just yummy" is the plain food, with nothing else.</li>
 * </ul>
 *
 * <p>The pairwise rules are the {@link #CONFLICTS} table. {@link BuilderLowering#lower} refuses a spec this names;
 * a multi-pick page greys a tile this names, in these words; the Apprentice steers by it
 * ({@link Apprentice#problems}). {@code BuilderLimitsTest} holds every row to the tool it stands for, both ways.
 * Pure.
 */
public final class BuilderLimits {

    private BuilderLimits() {
    }

    /**
     * One conflict rule: two picks of one {@code page} that cannot both be made. {@code clash} answers for a pair of
     * bare ids; {@code why} is the tool behaviour the rule stands for (not shown to him); the child sees
     * "Can't with &lt;the other&gt;".
     */
    public record Conflict(String page, String why, BiFunction<String, String, Boolean> clash) {
    }

    public static final List<Conflict> CONFLICTS = List.of(
            new Conflict("abilities", "apply_effect names a move's pack <effect>_<creature>: a second move of the same "
                    + "effect REPLACES the first (ApplyEffectTool.featureId)",
                    (a, b) -> !a.equals(b) && moveEffect(a).equals(moveEffect(b))),
            new Conflict("snack_powers", "make_item refuses one effect twice (\"the same effect twice does nothing "
                    + "twice\"): giant and tiny are both grow",
                    (a, b) -> !a.equals(b) && !snackEffect(a).isEmpty() && snackEffect(a).equals(snackEffect(b))),
            new Conflict("snack_powers", "\"just yummy\" is the look's plain food (BuilderSnacks.calls): it is no "
                    + "effect, so it cannot ride with one",
                    (a, b) -> !a.equals(b) && (BuilderSnacks.YUMMY.id().equals(a) || BuilderSnacks.YUMMY.id().equals(b))));

    /** The most effects one snack carries: make_item's own limit. */
    public static final int SNACK_EFFECTS = com.moddingfromamod.datapack.ItemCatalogue.MAX_EFFECTS;

    // ------------------------------------------------------------------ the checks

    /** Why {@code add} cannot join {@code chosen} on {@code page}, in his words, or null when it can. Bare ids. */
    public static String whyNot(String page, List<String> chosen, String add) {
        if (chosen.contains(add)) {
            return null;
        }
        for (Conflict c : CONFLICTS) {
            if (!c.page().equals(page)) {
                continue;
            }
            for (String have : chosen) {
                if (c.clash().apply(have, add)) {
                    return "Can't with " + label(page, have);
                }
            }
        }
        if (page.equals("snack_powers") && chosen.size() >= SNACK_EFFECTS) {
            return "One snack does " + SNACK_EFFECTS + " things at most";
        }
        return null;
    }

    /** Why these picks of one page cannot all be made, or null. */
    public static String problem(String page, List<String> chosen) {
        List<String> so = new ArrayList<>();
        for (String c : chosen) {
            String why = whyNot(page, so, c);
            if (why != null) {
                return label(page, c) + ": " + why;
            }
            so.add(c);
        }
        return null;
    }

    /** Why parts of {@code boxes} boxes do not fit a body with room for {@code room}, or null. */
    public static String parts(int boxes, int room, String body) {
        return boxes > room ? "Too big for this " + (body == null || body.isBlank() ? "creature" : body.toLowerCase(java.util.Locale.ROOT)) : null;
    }

    /** The picks row's room line: "Body room 7/12 boxes". */
    public static String room(int boxes, int room) {
        return "Body room " + boxes + "/" + room + " boxes";
    }

    /**
     * Why a spec's multi-picks cannot all be made, or null: its special moves and its snack powers. Parts need the
     * body ({@link BuilderLowering} checks them where it reads it).
     */
    public static String problem(BuilderSpec spec) {
        List<String> moves = new ArrayList<>();
        for (String p : spec.powers()) {
            if (Catalogue.prefix(p).equals("ability")) {
                moves.add(Catalogue.bare(p));
            }
        }
        String why = problem("abilities", moves);
        if (why == null && BuilderSnacks.composed(spec)) {
            why = problem("snack_powers", BuilderSnacks.chosenPowers(spec));
        }
        return why;
    }

    // ------------------------------------------------------------------ what each id is

    /** The catalogue effect a special move runs (a bare catalogue effect is its own). */
    static String moveEffect(String move) {
        BuilderAbilities.Ability a = BuilderAbilities.byId(move);
        return a == null ? move : a.effect();
    }

    static String snackEffect(String power) {
        BuilderItemPowers.Power w = BuilderSnacks.power(power);
        return w == null ? "" : w.effect();
    }

    /** A pick's words: "Breathes fire". */
    static String label(String page, String id) {
        if (page.equals("abilities")) {
            BuilderAbilities.Ability a = BuilderAbilities.byId(id);
            return a == null ? id : a.label();
        }
        if (page.equals("snack_powers")) {
            BuilderItemPowers.Power w = BuilderSnacks.power(id);
            return w == null ? id : w.label();
        }
        return id;
    }
}
