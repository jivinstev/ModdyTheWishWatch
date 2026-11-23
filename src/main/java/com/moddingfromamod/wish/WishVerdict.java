package com.moddingfromamod.wish;

import java.util.Locale;

/**
 * Moddy's honest opinion of what it just made — and the thing that <b>ends</b> a wish.
 *
 * <p><b>Why a verdict replaced the fix loop.</b> The agent used to keep improving its own work until
 * it was satisfied. In a repo that is a virtue; in a live world it is not, because the world does
 * not hold still. On 2026-08-04 a wish ran eleven minutes and the child killed the creature twice
 * while it was still "improving" — the agent was polishing something that no longer existed, and it
 * never told him anything. In a game the player IS the loop: one honest attempt, one honest opinion,
 * hand back.
 *
 * <p>So every wish now ends on one of four notes. Which one is the agent's judgement; what the child
 * hears is not, because "good enough" and "I got this wrong" need very different words for a
 * 7-year-old and neither should ever sound like a stack trace.
 *
 * <p>Pure and Minecraft-free so the mapping — verdict → outcome, does it seed a follow-up, does it
 * undo itself — is unit-tested rather than discovered in front of a child.
 */
public enum WishVerdict {

    /**
     * It works and it's good. Celebrate, and invite the next wish — a child who is told "done!"
     * asks for the next thing; a child who is told nothing assumes it broke.
     */
    DONE,

    /**
     * It works, but Moddy can see how it could be better. Says so, and offers the improvement as a
     * ready-made next wish so the child only has to press a button rather than find the words.
     */
    COULD_BE_BETTER,

    /**
     * It works, sort of. Honest about that, and tells the child how to throw it away, because a
     * child who doesn't know he can say no just lives with a bad thing in his world.
     */
    ROUGH,

    /** Rare. It couldn't be done, so Moddy takes back what it made rather than leaving a mess. */
    FAILED;

    /** Did the child actually get their wish? Drives {@link WishStatus}. */
    public boolean granted() {
        return this != FAILED;
    }

    /** Should Moddy offer a follow-up wish for the child to accept with one press? */
    public boolean seedsFollowUp() {
        return this == COULD_BE_BETTER;
    }

    /** Should Moddy tell the child how to reject it? Only when it already thinks it's poor. */
    public boolean offersReject() {
        return this == ROUGH;
    }

    /** Should Moddy undo what it built? Only on outright failure — never on merely imperfect. */
    public boolean undoesItself() {
        return this == FAILED;
    }

    /**
     * Parse whatever the agent passed. Unknown or missing degrades to {@link #DONE} rather than
     * throwing: a wish that ends imperfectly labelled is fine, a wish that cannot end is not — that
     * is the exact failure this whole enum exists to prevent.
     */
    public static WishVerdict parse(String s) {
        if (s == null) {
            return DONE;
        }
        switch (s.trim().toLowerCase(Locale.ROOT)) {
            case "done", "good", "great", "good_enough": return DONE;
            case "could_be_better", "better", "improvable", "ok": return COULD_BE_BETTER;
            case "rough", "poor", "bad", "workable": return ROUGH;
            case "failed", "fail", "couldnt", "could_not": return FAILED;
            default: return DONE;
        }
    }

    /** The names the tool advertises, for the schema description. */
    public static String choices() {
        return "done | could_be_better | rough | failed";
    }
}
