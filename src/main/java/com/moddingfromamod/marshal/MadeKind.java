package com.moddingfromamod.marshal;

/**
 * The kinds of thing a wish can make, as plain strings.
 *
 * <p>One constant per kind that has a <b>real inverse the mod can perform itself</b> — which is the
 * whole basis of an honest undo. Each corresponds to a {@code remove_*} tool whose body is a
 * registry lookup and a clear, so an undo replays that same code rather than asking the agent (which
 * has just been refused every tool, and rightly).
 *
 * <p>Adding a kind means adding its reversal to {@code WishUndo} <b>and</b> its tool to
 * {@link UndoContract}; a kind recorded but never reversed makes the {@code [undo]} button lie,
 * which is the one thing the design says must never happen.
 */
public final class MadeKind {

    /** A structure — reversed by clearing its plan at its remembered anchor. */
    public static final String BUILD = "build";

    /** A creature species — reversed by discarding the mobs of that species. */
    public static final String CREATURE = "creature";

    // "contraption" and "train" were kinds until Create went. An undo ledger, a watch or
    // an artifact index written before then may still hold one: every reader treats a kind it
    // does not know as nothing to do (MadeKindTest pins that), never as an error.

    /** A datapack — reversed by turning it off (kept on disk, so nothing is lost). */
    public static final String DATAPACK = "datapack";

    /**
     * An item — reversed by {@code remove_item}, which forgets the design, forgets the catalogue
     * entry and clears the stacks he is carrying.
     *
     * <p>Added only once that inverse existed. Until then items deliberately recorded
     * NOTHING here: a kind with no reversal makes the {@code [undo]} button lie, which is the one
     * thing the design says must never happen.
     */
    public static final String ITEM = "item";

    /**
     * <b>A mob a wish has changed</b> — reversed by putting its numbers back.
     *
     * <p>The odd one out, and worth saying why it belongs here anyway. Every other kind names
     * something the wish BROUGHT INTO EXISTENCE, so its inverse is a deletion. A mob was already
     * standing there — most of them were never made by a wish at all — so deleting it would be
     * vandalism rather than an undo. What the wish created is the CHANGE, and the inverse is the
     * state recorded immediately before it (see {@code EntityHandles.Touch} and {@code MobState}).
     *
     * <p>Registering it is what makes a mob ladder measurable at all. Without it the seam
     * records nothing, {@code ArtifactAffinity} finds no owner, every rung routes {@code anyone},
     * and a three-wish sequence about one animal is indistinguishable in the log from three wishes
     * about nothing in particular.
     */
    public static final String MOB = "mob";

    /**
     * A picture painted on a surface.
     *
     * <p>Its own kind rather than folded into {@link #BUILD}, because its inverse is not a
     * build's. A build undoes by removing the blocks it placed; a painting may have to
     * <b>restore the picture that was there before</b>, which is a different act and needs
     * different information — see {@code PaintRecord}.
     */
    public static final String PAINTING = "painting";

    /**
     * A staged world event: a wave, a flood, a meteor. The id is the running event's id
     * (its journal is the undo record), or the design's name when it was only kept, not staged.
     */
    public static final String EVENT = "event";

    private MadeKind() {
    }

    /**
     * Which kind of thing this tool makes, or null if it makes nothing undoable.
     *
     * <p>Recorded centrally at the dispatch seam rather than inside each tool, for the same reason
     * the narrator and the action log live there: one hook covers every tool now and in future, and
     * a tool added later that forgets to opt in simply gets no undo — the safe direction.
     */
    public static String forTool(String tool) {
        if (tool == null) {
            return null;
        }
        return switch (tool) {
            case "build_structure" -> BUILD;
            case "sculpt_creature", "define_creature" -> CREATURE;
            case "author_datapack" -> DATAPACK;
            case "make_item" -> ITEM;
            // No id argument to read — `target` may be a type word, or absent entirely — so the
            // tool reports the handle it stamped instead, via McpTool.lastMadeId().
            case "modify_mob" -> MOB;
            case "paint_surface" -> PAINTING;
            // No id argument: stage_event reports the event it staged (or the design it kept).
            case "stage_event" -> EVENT;
            default -> null;
        };
    }

    /**
     * The kind a {@code remove_*} tool takes away, or null (a thing a wish made and then
     * removed again is a superseded version, not one of its parts).
     */
    public static String forRemoveTool(String tool) {
        if (tool == null) {
            return null;
        }
        return switch (tool) {
            case "remove_build" -> BUILD;
            case "remove_creature" -> CREATURE;
            case "remove_datapack" -> DATAPACK;
            case "remove_item" -> ITEM;
            case "remove_painting" -> PAINTING;
            case "remove_event" -> EVENT;
            default -> null;
        };
    }

    /**
     * Which argument carries the id.
     *
     * <p>{@code make_item} has NO id argument at all — an item's id is derived once and kept for
     * life, precisely so that a refinement which renames it does not produce a different item. So
     * the seam cannot read it from the arguments and the tool reports it instead; see
     * {@link com.moddingfromamod.agent.McpTool#lastMadeId()}.
     */
    public static String idField(String tool) {
        return "author_datapack".equals(tool) || "remove_datapack".equals(tool) ? "feature_id" : "id";
    }
}
