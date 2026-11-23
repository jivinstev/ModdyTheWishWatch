package com.moddingfromamod.wishbuilder;

import java.util.List;
import java.util.Map;

/**
 * <b>Make another one of a kept Builder make</b>: the recipe a Builder or Apprentice make's page and
 * hotbar proxy replay.
 *
 * <p>A Magician's thing can be made again because a taught recipe names it by its replay command
 * ({@code wishmob spawn <id>}, {@code wishbuild <id>}, {@code wishdatapack <id> give}, {@code wishpaint <id>},
 * {@code moddyevent stage <id>}: {@code ReplayContract.ALL}). A Builder make saves the very same things under its own
 * id (the species, the building, the item, the picture, the event design) and keeps a row on his shelf saying so
 * ({@link BuilderSpecStore}). So its recipe is that row: {@code builder:<make id>}, and replaying it runs the replay
 * command of the thing it made, through the one path every recipe takes ({@code WishService.submit} with the recipe,
 * a {@code CommandPlan} on the executor). No model, no second grammar, and no new artifact: another one of THE thing,
 * as it is now, so a make a Magician wish changed since is made again changed, never thrown back to its spec.
 *
 * <p>The id is verified on the server: it is looked up on the wisher's OWN shelf, so a client cannot invent one and
 * another child's id finds nothing; the shelf outlives a restart; a shelf file or a spec a newer Moddy wrote is
 * refused by name, never read as empty-and-fine.
 *
 * <p>Per kind: a creature or ride (another of it, beside where he aims), an item, machine or magic block (another in
 * his hand), a building (another at the spot aimed now), a picture (another board where he aims), an event (it
 * happens again where he is). A RULE is not made again: it is on for the whole world already, and a second copy would
 * change nothing, so it has no recipe. A real mob and supplies keep no row, so they have none either.
 *
 * <p>Pure: no Minecraft imports.
 */
public final class BuilderAgain {

    /** What every Builder-make recipe id starts with; a taught recipe id never carries a colon. */
    public static final String PREFIX = "builder:";

    private BuilderAgain() {
    }

    /** Is this recipe id a kept Builder make's? */
    public static boolean is(String recipe) {
        return recipe != null && recipe.startsWith(PREFIX) && recipe.length() > PREFIX.length();
    }

    /** The recipe id for the kept make {@code makeId}. */
    public static String recipeId(String makeId) {
        return PREFIX + makeId;
    }

    /** The make id a Builder recipe names, or null when it is not one. */
    public static String makeIdOf(String recipe) {
        return is(recipe) ? recipe.substring(PREFIX.length()) : null;
    }

    /**
     * The replay command that makes another of a kept make's thing, or null when this kind is not made again.
     *
     * @param id    the make's id (what the species, building, picture or event design is saved under)
     * @param alias an item's own minted id, when it is not the make's ({@link BuilderSpecStore.Made#alias})
     */
    public static String command(String kind, String id, String alias) {
        BuilderSpec.Kind k = BuilderSpec.Kind.of(kind);
        if (k == null || id == null || id.isBlank()) {
            return null;
        }
        return switch (k) {
            case CREATURE, RIDE -> "wishmob spawn " + id;
            case ITEM, MACHINE, MAGIC_BLOCK -> "wishdatapack " + (alias == null || alias.isBlank() ? id : alias) + " give";
            case BUILDING -> "wishbuild " + id;
            case PICTURE -> "wishpaint " + id;
            case EVENT -> "moddyevent stage " + id;
            case RULE, SUPPLIES -> null;
        };
    }

    /** Can this kept make be made again (it has a replay command)? */
    public static boolean canMakeAgain(BuilderSpecStore.Made m) {
        return m != null && command(m.kind(), m.id(), m.alias()) != null;
    }

    /**
     * The kept make a thing's tile shows, by the tile's id ({@code arg}) or the name it was made under ({@code label}),
     * whether or not a Magician wish changed it since: either way it can be made again as it is. Null when none is his.
     */
    public static BuilderSpecStore.Made keptFor(Map<String, BuilderSpecStore.Made> mine, String arg, String label) {
        if (mine == null || mine.isEmpty()) {
            return null;
        }
        String a = arg == null ? "" : arg.strip();
        if (a.startsWith("id:")) {
            a = a.substring(3);   // an item's tile id is "id:<its own id>"
        }
        if (mine.containsKey(a)) {
            return mine.get(a);
        }
        String l = label == null ? "" : label.strip();
        for (BuilderSpecStore.Made m : mine.values()) {
            if (m.ref().equals(a) || (!m.alias().isEmpty() && m.alias().equals(a))
                    || (!m.ref().equals(m.id()) && !l.isEmpty() && m.ref().equalsIgnoreCase(l))) {
                return m;
            }
        }
        return null;
    }

    /** The recipe a thing's tile carries, or {@code ""} when it is not a kept Builder make that can be made again. */
    public static String recipeFor(Map<String, BuilderSpecStore.Made> mine, String arg, String label) {
        BuilderSpecStore.Made m = keptFor(mine, arg, label);
        return canMakeAgain(m) ? recipeId(m.id()) : "";
    }

    /**
     * What a Builder recipe makes, for one child, or why it cannot.
     *
     * @param words    what the wish says, in the make's own name ("Red dragon"): the wish's text, never the id
     * @param commands the replay command(s), for a {@code CommandPlan}
     * @param ready    what Moddy says when it is made
     * @param refusal  null when it can be made; else what went wrong, for the log
     */
    public record Resolved(String words, List<String> commands, String ready, String refusal) {
        public boolean ok() {
            return refusal == null;
        }

        static Resolved refused(String why) {
            return new Resolved("", List.of(), "", why);
        }
    }

    /**
     * Resolve {@code recipe} against the makes this child keeps ({@code mine}, read from HIS shelf). Refused when it is
     * not a Builder recipe, when he keeps no such make (it is gone, or it is someone else's), when its spec was written
     * by a newer Moddy, or when its kind is not made again.
     */
    public static Resolved resolve(Map<String, BuilderSpecStore.Made> mine, String recipe) {
        String id = makeIdOf(recipe);
        if (id == null) {
            return Resolved.refused("'" + recipe + "' is not a Builder recipe");
        }
        BuilderSpecStore.Made m = mine == null ? null : mine.get(id);
        if (m == null) {
            return Resolved.refused("he keeps no Builder make '" + id + "' (gone, or not his)");
        }
        BuilderSpec spec;
        try {
            spec = m.read();
        } catch (RuntimeException e) {
            return Resolved.refused("the Builder make '" + id + "' cannot be read here: " + e.getMessage());
        }
        String command = command(m.kind(), m.id(), m.alias());
        if (command == null) {
            return Resolved.refused("a " + m.kind() + " is not made again");
        }
        return new Resolved(BuilderLowering.label(spec), List.of(command), BuilderWords.ready(spec), null);
    }

    /** What he is told when a Builder recipe cannot be made: the same words whatever the cause. */
    public static final String GONE = "✨ I can't find how you made that any more, so I can't make another one.";
}
