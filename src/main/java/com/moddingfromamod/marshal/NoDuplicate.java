package com.moddingfromamod.marshal;

import java.util.List;
import java.util.Locale;

/**
 * <b>One wish, one thing — never two of it</b>. The two ways a single wish left a child
 * with a duplicate on his shelf, both measured on a real shelf, and the rule that stops each.
 *
 * <ol>
 *   <li><b>A rebuild minted a second building.</b> One wish called {@code build_structure} twice,
 *       eleven seconds apart, under two NEW ids — once after the "check it" nudge, once after the
 *       reveal. Nothing removed the first; a later edit wish then changed both. A second building
 *       from nothing inside one wish is refused, with nothing changed: re-sent under the first
 *       id it REPLACES it where it stands, and {@code as_new: true} says a second, separate
 *       building is what he asked for. Refused rather than silently rerouted, because the other
 *       reading (a house AND a tower) is a real wish, and replacing the house would destroy it.</li>
 *   <li><b>A refused edit became a fresh item.</b> {@code make_item start_from=… shape=thrown}
 *       was refused (the base item cannot be thrown), twice; then a call with no
 *       {@code start_from} made a NEW item beside the one he had. Once an edit is refused in a
 *       wish, a make_item from nothing is refused too until it says {@code as_new: true} — and
 *       the edit's own refusal says what to do instead: tell him what cannot change.</li>
 * </ol>
 *
 * <p>Both are the agent SAYING which it means, never a guess from its wording (ItemLineage's
 * rule). Minecraft-free; {@code NoDuplicateTest}.
 */
public final class NoDuplicate {

    private NoDuplicate() {
    }

    /**
     * The refusal for a {@code build_structure} call, or null when it may go ahead.
     *
     * @param freshThisWish buildings this wish already created from nothing (WishRun.freshBuilds)
     * @param id            the id this call would save under
     * @param inheriting    it passed {@code start_from} — an edit, never a duplicate
     * @param asNew         it said {@code as_new: true} — a second building on purpose
     * @param idExists      a building of this id already exists — this call replaces it
     */
    public static String rebuildRefusal(List<String> freshThisWish, String id, boolean inheriting,
                                        boolean asNew, boolean idExists) {
        if (inheriting || asNew || idExists || freshThisWish == null || freshThisWish.isEmpty()) {
            return null;
        }
        String want = norm(id);
        for (String f : freshThisWish) {
            if (norm(f).equals(want)) {
                return null;
            }
        }
        String first = freshThisWish.get(freshThisWish.size() - 1);
        return "Nothing was built. This wish already built '" + first + "', and a second building "
                + "under a new id would leave him with TWO — the old one is not removed. If this is "
                + "the same building again (a fix, a redo, a better version), send this call again "
                + "with id '" + first + "': it is rebuilt where it stands and he keeps one. Only if "
                + "he asked for a SECOND, separate building as well (a house AND a tower), send "
                + "this call again with as_new: true.";
    }

    /**
     * The refusal for a {@code make_item} from nothing after an edit was refused in this wish, or
     * null when it may go ahead.
     *
     * @param refusedEdit the thing whose edit was refused (WishRun.refusedEdit), or null
     * @param editing     this call passed {@code start_from} — another try at an edit is fine
     * @param asNew       it said {@code as_new: true} — he really asked for a separate new item
     */
    public static String freshAfterRefusedEdit(String refusedEdit, boolean editing, boolean asNew) {
        if (refusedEdit == null || refusedEdit.isBlank() || editing || asNew) {
            return null;
        }
        return "Nothing was made. The change to '" + refusedEdit + "' was refused in this wish, and "
                + "making a NEW item instead would leave him with two — the one he had, unchanged, "
                + "and a second he did not ask for. " + tellHim(refusedEdit)
                + " Only if he really asked for a separate, new item as well, send this call again "
                + "with as_new: true.";
    }

    /** What a refused {@code make_item start_from} adds to its refusal. */
    public static String refusedEditSuffix(String baseId) {
        return "\n\nEDIT REFUSED — '" + baseId + "' is unchanged. Do NOT make a new item instead: "
                + "that leaves him two. Change it in a way that is allowed (another start_from "
                + "call), or " + lower(tellHim(baseId));
    }

    private static String tellHim(String baseId) {
        return "Tell him, in a sentence, what about his '" + baseId + "' cannot change and why, "
                + "and what it can do instead.";
    }

    private static String lower(String s) {
        return s.isEmpty() ? s : Character.toLowerCase(s.charAt(0)) + s.substring(1);
    }

    private static String norm(String s) {
        return s == null ? "" : s.trim().toLowerCase(Locale.ROOT).replace(' ', '_');
    }
}
