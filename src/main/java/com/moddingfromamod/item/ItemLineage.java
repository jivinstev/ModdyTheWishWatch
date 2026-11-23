package com.moddingfromamod.item;

import java.util.Locale;

/**
 * <b>"and one that…" — inherit a design without overwriting it.</b>
 *
 * <h2>The wish, and why it is the hardest two words in the corpus</h2>
 * From {@code uns_items_3}: <i>"…<b>and one</b> that shoves the noob in LAVA"</i>. The sentence
 * carries only the delta. To grant it you must know what A was — its shape, its icon, its colour,
 * the fact that it is a thrown pearl at all — because none of that is in the words. And the one
 * outcome that is <b>wrong</b> is changing A: he asked for a second pearl and would be furious to
 * find his first one turned into a lava pearl.
 *
 * <p>The same sentence appears in every class: <i>"make another one but blue"</i> (creatures),
 * <i>"build another tower like that on the other side"</i> (buildings), <i>"a second track that
 * goes the other way"</i> (trains).
 *
 * <h2>⚠ It was not expressible. That is measured, not assumed.</h2>
 * {@code MakeItemTool} chose the design id like this:
 *
 * <pre>{@code String featureId = base != null ? base.id() : featureId(name, …);}</pre>
 *
 * <p>When {@code start_from} resolved, the id was <b>unconditionally</b> A's id — so every possible
 * use of {@code start_from} overwrote A, and there was no argument, in any combination, that meant
 * "inherit this design and mint a new one". an earlier issue's stated expectation was that the agent would
 * reach {@code start_from} and modify A; it is stronger than an expectation, because nothing else
 * could have happened.
 *
 * <p>Worse, {@code start_from}'s own schema text taught the failure: <i>"Without this you make a
 * SECOND item and he ends up with two — which is what used to happen, every time."</i> True of a
 * refinement, and exactly backwards for the one wish where a second item is what he asked for. Same
 * shape as the copycat schema that documented a broken form — an agent following its instructions
 * produced the wrong thing and no check it could run would say why.
 *
 * <h2>EXPLICIT, never inferred from the wording</h2>
 * The tempting fix is to read "another"/"and one" out of the sentence and switch behaviour. That is
 * forbidden by the item catalogue's own {@code _no_defaults} rule and for the reason records: a
 * guess cannot tell an ambiguous wish from an explicit one. Getting it wrong in either direction is
 * expensive — an unwanted second item, or a destroyed first one — so the agent states which it
 * means and the two intentions become impossible to confuse.
 */
public final class ItemLineage {

    /** What a {@code make_item} call is doing to the design it names. */
    public enum Route {
        /** No {@code start_from}: a thing from scratch, with its own new id. */
        FRESH,
        /** {@code start_from} alone: change THAT design, keeping its id. A refinement. */
        EDIT_IN_PLACE,
        /** {@code start_from} + {@code as_new}: inherit the design, mint a SECOND under a new id. */
        COPY_THEN_DIVERGE
    }

    private ItemLineage() {
    }

    /**
     * Which of the three this call is.
     *
     * @param startFromResolved whether {@code start_from} named a design that actually exists — a
     *     miss is not a copy request, it is the foreign-item path ({@link ForeignItemEdit})
     * @param asNew the agent's explicit statement that it wants a second thing
     */
    public static Route of(boolean startFromResolved, boolean asNew) {
        if (!startFromResolved) {
            return Route.FRESH;
        }
        return asNew ? Route.COPY_THEN_DIVERGE : Route.EDIT_IN_PLACE;
    }

    /**
     * <b>The id the design is saved under</b> — the single line that was the whole bug.
     *
     * @param baseId the id {@code start_from} resolved to, or null
     * @param freshId the id {@code make_item} would mint for a thing from scratch
     */
    public static String idFor(Route route, String baseId, String freshId) {
        return route == Route.EDIT_IN_PLACE ? baseId : freshId;
    }

    /**
     * Refuse a copy that would land on top of the thing it copied.
     *
     * <p>{@code as_new} with a name that mints the same id is a copy in name only: it inherits A,
     * writes under A's id, and the child still ends with one item. That is the original defect
     * wearing the new flag, and it is worth refusing rather than silently doing the old thing —
     * this is a "say something" refusal, which 's rule is always possible and so may be
     * repeated safely.
     *
     * @return the sentence to send back, or null when the call is fine
     */
    public static String refusal(Route route, String baseId, String freshId) {
        if (route != Route.COPY_THEN_DIVERGE) {
            return null;
        }
        if (baseId != null && freshId != null && norm(baseId).equals(norm(freshId))) {
            return "You asked for a SECOND item (as_new) but the name gives it the same id as '"
                    + baseId + "', so it would land on top of the one you copied and he would "
                    + "still end up with one. Give the new one its own name — he asked for another "
                    + "thing, so it needs to be tellable apart in his menu. If you actually meant "
                    + "to CHANGE '" + baseId + "', drop as_new.";
        }
        return null;
    }

    /**
     * What to tell the agent it just did, so a wrong route is visible in the transcript.
     *
     * <p>The two outcomes are one word apart in the call and a world apart for the child, and
     * names the number that separates them: <b>items in his inventory afterwards.</b> Granted
     * correctly, "and one that…" leaves him with two; granted as an edit, one — and it is the wrong
     * one. So the result says which happened rather than leaving it to be inferred from the id.
     */
    public static String note(Route route, String baseId, String newId) {
        return switch (route) {
            case COPY_THEN_DIVERGE -> "Made a SECOND item '" + newId + "' from '" + baseId
                    + "'. '" + baseId + "' is untouched — he now has BOTH. Check he wanted another "
                    + "one rather than a change; if he wanted a change, undo this and call again "
                    + "with start_from alone.";
            case EDIT_IN_PLACE -> "CHANGED '" + baseId + "' in place. He still has one of these, "
                    + "and it is the new version. If he asked for ANOTHER one as well, that is not "
                    + "what happened — pass as_new next time.";
            case FRESH -> "";
        };
    }

    private static String norm(String s) {
        return s == null ? "" : s.trim().toLowerCase(Locale.ROOT);
    }

    /**
     * <b>A child's own copy of a shared item, named so a datapack can carry it</b>.
     *
     * <p>Every other kind of fork is {@code <id>~<child>} ({@link
     * com.moddingfromamod.marshal.ArtifactOwner#forkKey}). An item's id is also its datapack's
     * namespace, which allows only {@code [a-z][a-z0-9_]{0,47}}, so a {@code ~} key compiled for
     * nobody: every child's edit of a canned or another child's item answered with an error, while
     * the design itself was stored. The owner is stamped on the design, so the separator carries no
     * meaning here; the base is shortened, never the child's part, so two children never collide.
     * Returns "" when there is nobody to make a copy for.
     */
    public static String forkId(String baseId, String askerId) {
        String key = com.moddingfromamod.marshal.ArtifactOwner.forkKey(baseId, askerId);
        if (key.isEmpty()) {
            return "";
        }
        int at = key.indexOf(com.moddingfromamod.marshal.ArtifactOwner.FORK);
        String base = key.substring(0, at).replaceAll("[^a-z0-9_]", "_");
        String who = key.substring(at + 1);
        int room = MAX_ID - who.length() - 1;
        if (base.length() > room) {
            base = base.substring(0, room);
        }
        if (base.isEmpty() || !Character.isLetter(base.charAt(0))) {
            base = "i" + base;
        }
        return base + "_" + who;
    }

    /** The longest id a datapack namespace takes here (CbScriptCompiler.VALID_ID). */
    private static final int MAX_ID = 48;
}
