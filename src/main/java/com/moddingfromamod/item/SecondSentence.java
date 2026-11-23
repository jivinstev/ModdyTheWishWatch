package com.moddingfromamod.item;

import java.util.List;

/**
 * <b>"and one that…" — refuse to invent what he was pointing at (item 3).</b>
 *
 * <p>an earlier issue's shape is <i>"I need context on item A in order to build item B — and I must NOT modify
 * item A"</i>, and its third requirement is <b>a refusal that asks, when nothing recent resolves —
 * because inventing what A was is worse than asking which one he means.</b>
 *
 * <p>Items 1 and 2 shipped: {@code list_items} answers recency, and {@code as_new} makes
 * copy-then-diverge expressible rather than structurally impossible. Neither stops the failure this
 * one is about. A wish whose whole content is a DELTA — <i>"and one that shoves the noob in
 * LAVA"</i> — carries no shape, no icon, no colour, and not even the fact that it is a thrown pearl.
 * An agent that mints from scratch anyway fills all of that in from nowhere, and the result looks
 * like a successful grant at every tier we run.
 *
 * <h2>What it fires on, and the case it must NOT fire on</h2>
 *
 * <p>All three at once, and the third is what keeps it from crying wolf (P6):
 *
 * <ol>
 *   <li>the wish asks for <b>a sibling</b> — see {@link #looksLikeASibling}, and note that this is
 *       deliberately NOT {@code RefersBack};</li>
 *   <li>the call carries <b>no {@code start_from}</b>, so nothing is being inherited;</li>
 *   <li>the store is <b>not empty</b> — there is genuinely something he could have meant.</li>
 * </ol>
 *
 * <p>An empty store is the case to leave alone, and it is worth being explicit because it reads
 * like the opposite. "Nothing recent resolves" sounds like the moment to ask — but with no items at
 * all there is nothing to ask ABOUT, and minting from scratch is the correct grant for his first
 * ever item even if he happened to start the sentence with "and". Asking there would be a question
 * with no answers in it.
 *
 * <h2>Why the escape is a FIELD and not a give-way</h2>
 *
 * <p>{@code BuildGate} refuses once and then yields, which is right for a guard whose demand the
 * agent can always satisfy. This one cannot use that shape: a second refusal that yields is a
 * refusal the cheapest response walks past, which is exactly the defect found in
 * {@code refuseFinish} — the message advertised its own bypass and the agent took it, six seconds
 * later, without ever looking.
 *
 * <p>So the way out is {@code from_scratch: true} — the agent <b>saying</b> that it checked and he
 * means something genuinely unrelated. Same rule as {@code as_new}: the catalogue's own
 * {@code _no_defaults} forbids inferring an intention from wording, the two outcomes are one word
 * apart in the call and a world apart for the child, and a claim made explicitly is one a transcript
 * can be audited against later.
 *
 * <p>Pure and Minecraft-free: the decision is unit-testable without booting a server, and the
 * wiring is what the GameTest proves.
 */
public final class SecondSentence {

    /**
     * <b>The grammar of "another one", which is not the grammar of "change that one".</b>
     *
     * <p>The obvious move is {@link com.moddingfromamod.marshal.RefersBack} — it exists, it is
     * tested, and the wish plainly refers to something. It does not fire here, and the Gate B run
     * that proved it is the reason this method exists rather than a borrowed call. {@code
     * RefersBack}'s vocabulary is {@code again, too, also, instead, that one, the one, same, still,
     * more, this} plus the pronouns: every one of them is a word for <b>changing the thing you are
     * pointing at</b>, which is an earlier issue's question. an earlier issue's sentence contains none of them.
     *
     * <p>That is the distinction is entirely about, stated in its own words: <i>"#136 is 'a
     * wish that points at something ALREADY BUILT' and its answer is to change that thing. Here
     * changing A is the one outcome that is wrong."</i> Two different grammars, and reusing one for
     * the other silently answers the wrong question — in this case by never firing at all, which
     * looks exactly like a feature that was never wired.
     *
     * <p>Kept deliberately tight. Every phrase here is one whose plain meaning is <i>another of
     * what we were just discussing</i>; the negatives in {@code SecondSentenceTest} matter more
     * than the positives, because a refusal that fires on an ordinary new wish is one the agent
     * learns to route around (P6).
     */
    public static boolean looksLikeASibling(String wishText) {
        if (wishText == null || wishText.isBlank()) {
            return false;
        }
        String t = " " + wishText.toLowerCase(java.util.Locale.ROOT).replaceAll("[^a-z0-9]+", " ")
                + " ";
        for (String phrase : SIBLING) {
            if (t.contains(" " + phrase + " ")) {
                return true;
            }
        }
        return false;
    }

    /**
     * Phrases whose plain meaning is "another of the thing we were just talking about".
     *
     * <p> lists the same sentence across four wish classes — <i>"and one that shoves the noob in
     * LAVA"</i>, <i>"make another one but blue"</i>, <i>"build another tower like that on the other
     * side"</i>, <i>"a second track that goes the other way"</i> — so this is deliberately not
     * item-specific vocabulary.
     */
    private static final List<String> SIBLING = List.of(
            "and one", "and another", "another one", "another", "a second", "one more",
            "a third", "and a second");

    /**
     * The phrases {@link #looksLikeASibling} fires on — exported so the routing harness's stub
     * refuses exactly when {@code make_item} would, rather than keeping a second list that drifts.
     */
    public static List<String> siblingPhrases() {
        return SIBLING;
    }

    /** What to do with this call. */
    public enum Verdict {
        /** Nothing to say — mint it. */
        FINE,
        /** He was pointing at something he owns and nothing is being inherited: ask. */
        ASK
    }

    private SecondSentence() {
    }

    /**
     * @param sibling      the wish asks for ANOTHER of something — see {@link #looksLikeASibling}
     * @param hasStartFrom the call inherits from a stored design
     * @param fromScratch  the agent has explicitly said this really is unrelated
     * @param storeSize    how many designs exist to have meant
     */
    public static Verdict of(boolean sibling, boolean hasStartFrom, boolean fromScratch,
                             int storeSize) {
        if (!sibling || hasStartFrom || fromScratch || storeSize <= 0) {
            return Verdict.FINE;
        }
        return Verdict.ASK;
    }

    /**
     * The refusal, naming both routes and the things he might have meant.
     *
     * <p>Names the candidates rather than only the problem (P2): "which one did he mean" is not a
     * question an agent can answer from a message that lists nothing.
     */
    public static String refusal(String wishText, List<String> recentIds) {
        StringBuilder sb = new StringBuilder(
                "That wish points BACK at something he already has — \"" + wishText + "\" — but "
                + "this call inherits nothing, so every part of the new item that his sentence did "
                + "not mention would be invented here. That is the one outcome worth refusing: he "
                + "described a DIFFERENCE, and a difference from nothing is just a new item he did "
                + "not ask for.\n\nWhat he might have meant, most recent last:\n");
        for (String id : recentIds) {
            sb.append("  - ").append(id).append('\n');
        }
        sb.append("\nPick one and call again:\n")
          .append("  • ANOTHER one like it — start_from:\"<id>\" AND as_new:true, plus only the "
                  + "field that changes. He ends up with both.\n")
          .append("  • A CHANGE to the one he has — start_from:\"<id>\" alone. He ends up with one, "
                  + "different.\n")
          .append("  • Genuinely unrelated — from_scratch:true, and send every field. Only if you "
                  + "have actually checked; this is you saying so, and it is the one route that "
                  + "cannot be undone by reading the list above.");
        return sb.toString();
    }
}
