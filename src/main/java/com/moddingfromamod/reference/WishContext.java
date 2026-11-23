package com.moddingfromamod.reference;

import java.util.Optional;

/**
 * <b>What the agent should be told with the wish, rather than have to go and ask for.</b>
 *
 * <p>Built because asking has failed repeatedly. A capability offered as a tool is used sometimes:
 * the reference lookup was called for 3 of 7 named creatures in one run, 0 of 7 in another, and on
 * a follow-up that literally said "make it look more like the real one" it was not called at all.
 * Six separate features in this project have been offered and quietly not taken.
 *
 * <p>A cached reference is a <b>fact about the wish</b>, like the wish text. Delivering facts with
 * the request is ordinary; requiring a tool call to discover something already in hand is the odd
 * part. So when the cache has it, it travels with the wish.
 *
 * <h2>The follow-up must not become a trap</h2>
 *
 * <p>Telling the agent "this continues your Omega Lad" is useful right up until the child asks for
 * something else entirely. Two wishes in a row are frequently unrelated — Zippy then Spikor — and
 * carried context that cannot be overridden turns a helpful hint into a wrong answer that the agent
 * has been told to trust.
 *
 * <p>So the previous creature is offered as <b>context, explicitly labelled as droppable</b>, and
 * the new wish's own character always wins. {@link CharacterLineage#resolve} already encodes that
 * precedence; this states it in words the agent reads.
 */
public final class WishContext {

    private WishContext() {
    }

    /**
     * The block of context to prepend to a wish, or empty when there is nothing useful to say.
     *
     * @param wishText     the child's new wish
     * @param lastSpecies  the creature they were just working on, or null
     */
    public static String forWish(String wishText, String lastSpecies) {
        StringBuilder sb = new StringBuilder();

        Optional<String> fromWish = CharacterResolver.resolve(wishText);
        Optional<String> fromLineage = lastSpecies == null ? Optional.empty()
                : CharacterLineage.forSpecies(lastSpecies);

        // A NEW SUBJECT ALWAYS WINS. This is the case that makes carried context safe: Zippy then
        // Spikor must build Spikor, and must not be nudged toward the thing that came before.
        boolean changedSubject = fromWish.isPresent() && fromLineage.isPresent()
                && !normalise(fromWish.get()).equals(normalise(fromLineage.get()));

        if (lastSpecies != null && !changedSubject) {
            sb.append("You were last working on '").append(lastSpecies).append("'. If this wish is "
                    + "a change to it, edit that creature rather than building a second one. If it "
                    + "is about something else, ignore this line completely.\n");
        } else if (changedSubject) {
            sb.append("This wish is about ").append(fromWish.get())
              .append(", which is NOT the creature you were last working on. Build the new one.\n");
        }

        Optional<String> character = fromWish.isPresent() ? fromWish : fromLineage;
        character.ifPresent(c -> {
            ReferenceSpec spec = ReferenceResearcher.cachedOnly(c);
            if (spec != null && !spec.isEmpty()) {
                sb.append("\nWhat ").append(c).append(" ACTUALLY looks like, already looked up "
                        + "from real sources — use it, do not go from memory:\n");
                sb.append(spec.describe());
            }
        });
        return sb.toString();
    }

    private static String normalise(String s) {
        return CharacterResolver.normalise(s);
    }
}
