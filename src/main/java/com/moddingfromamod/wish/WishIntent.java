package com.moddingfromamod.wish;

import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Is the child asking Moddy to <b>do</b> something, or to <b>tell</b> him something?
 *
 * <p><b>Why this is not just "does it end in a question mark".</b> A seven-year-old phrases almost
 * every request as a question, so punctuation and question-words are close to useless on their own:
 *
 * <pre>
 *   "Can you build me a rock grinder?"        → a question, but he wants a BUILD
 *   "How do I use it?"                        → a question, and he wants an ANSWER
 *   "Can you give me the items to use it?"    → a question, but he wants ITEMS
 * </pre>
 *
 * <p>The rule that works is <b>an action verb beats a question word</b>. "Can you <i>build</i>…" and
 * "can you <i>give</i>…" name something to do; "how do I use it" names nothing at all, and answering
 * it by building is how the watch ends up putting a second rock grinder next to the first one when
 * he only wanted to know which end to put the rocks in.
 *
 * <p>Getting this wrong is worse than it sounds. A question routed as an action is not a slow answer
 * — it is the mod confidently doing something unasked-for and never answering, which to a child
 * reads as being ignored.
 *
 * <p>Pure — no Minecraft — so the whole policy is unit-tested against the phrasings he really uses.
 */
public final class WishIntent {

    /** What the child wants to happen. */
    public enum Intent {
        /** Make something, give something, change something. */
        ACTION,
        /** Explain something. Nothing in the world should change. */
        QUESTION
    }

    /**
     * Verbs that name something to do. These <b>win</b> over any question word.
     *
     * <p>Deliberately includes the polite-request forms a child wraps around them, because "can you
     * build" and "could you make" are the normal way he asks.
     */
    private static final Set<String> ACTION_VERBS = Set.copyOf(List.of(
            "build", "builds", "building", "built",
            "make", "makes", "making", "made",
            "create", "creates", "creating", "created",
            "give", "gives", "giving", "gave", "gimme",
            "spawn", "spawns", "summon", "summons",
            "put", "puts", "place", "places", "add", "adds",
            "get", "gets", "bring", "brings", "hand",
            "wish", "want", "wants", "need", "needs",
            "show", "shows",           // "show me a dragon" — he means summon one
            "fix", "fixes", "change", "changes", "move", "moves",
            "remove", "removes", "delete", "deletes", "undo",
            "start", "starts", "stop", "stops", "turn"));

    /**
     * Openers that mean "explain", when no action verb is present.
     *
     * <p>Multi-word because the single words are far too broad: "what" appears in "what a cool
     * castle, build me another", and "how" appears in "how about a dragon".
     */
    private static final String[] QUESTION_OPENERS = {
            "how do", "how does", "how did", "how can i", "how can you", "how do i",
            "what does", "what do", "what is", "what are", "what's", "whats",
            "why does", "why do", "why is", "why did", "why can",
            "where is", "where are", "where do", "where did", "where's",
            "which one", "which is", "who is", "when does", "when do",
            "tell me", "explain", "what happens", "how many", "how much",
            "is it", "does it", "can i use", "am i", "are you", "can you tell"};

    private WishIntent() {
    }

    /**
     * Classify a wish.
     *
     * <p>Biased towards {@link Intent#ACTION}, deliberately. Treating a genuine request as a
     * question wastes a turn and the child asks again; treating a genuine question as a request
     * builds something he did not ask for and still leaves him without an answer. The second
     * mistake is much worse, but it is also the rarer shape, so the tie-break stays on action.
     */
    public static Intent of(String wish) {
        if (wish == null || wish.isBlank()) {
            return Intent.ACTION;
        }
        String text = " " + wish.toLowerCase(Locale.ROOT).trim() + " ";

        // Unambiguous question openers come FIRST, because several of them legitimately contain an
        // action verb: "how many rocks do I NEED", "does it NEED water". Checking verbs first
        // classified both of those as things to build, which is the exact failure this class is for.
        for (String opener : QUESTION_OPENERS) {
            if (text.contains(" " + opener + " ")) {
                return Intent.QUESTION;
            }
        }
        // Otherwise an action verb settles it, whatever the sentence looks like — this is what
        // makes "can you BUILD me a grinder?" a build rather than a question.
        for (String word : text.split("[^a-z0-9']+")) {
            if (!word.isEmpty() && ACTION_VERBS.contains(word)) {
                return Intent.ACTION;
            }
        }
        // A bare question mark, with no verb naming anything to do, is a question.
        return wish.trim().endsWith("?") ? Intent.QUESTION : Intent.ACTION;
    }

    public static boolean isQuestion(String wish) {
        return of(wish) == Intent.QUESTION;
    }

    /**
     * What to tell the agent when the child asked something rather than requested something.
     *
     * <p>Explicit, because the agent's whole standing brief is about granting wishes, and without
     * this it will reach for a tool and build something. The one thing it must not do is change the
     * world.
     */
    public static String noteForAgent(String wish) {
        return "This is a QUESTION, not a request to build (\"" + wish + "\"). ANSWER IT with "
                + "notify_player, in simple words a 7-year-old can read. Do NOT build anything, do "
                + "NOT change the world, and do NOT teach a template for it. LOOK FIRST — he was "
                + "told \"let me have a look\", and your answer is refused until you have: "
                + "list_creatures / inspect_creature for a creature (is it there, how many, is it "
                + "set to follow him?), look_around for what is near him, list_items and "
                + "list_builds for a machine or a build. Then explain THAT specific thing from what "
                + "you SAW — never a guess about what probably happened (#942: \"he wandered off\" "
                + "was invented; there were two of him and one had died). If they then ask for the "
                + "items to try it, that IS a request: give them a generous handful with give_items.";
    }
}
