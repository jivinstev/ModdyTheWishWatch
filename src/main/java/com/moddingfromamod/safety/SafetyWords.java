package com.moddingfromamod.safety;

/**
 * <b>Every word a child hears from the safety layer, in one place.</b>
 *
 * <p>{@code docs/CHILD_SAFETY_PLAN.md} §5. Pure, so all of it is under Gate A and reviewable at a
 * glance rather than scattered through the call sites that send it — which is the only way the
 * tone can be kept consistent, and the only way a server owner can retune it without reading Java.
 *
 * <h2>The rules these obey, each one already paid for</h2>
 *
 * <ul>
 *   <li><b>Never "I don't know how to do that."</b> {@link com.moddingfromamod.wish.WishFeedback}
 *       carries the note: that line was true when escalation meant <i>maybe someday</i>, and it
 *       read as a refusal — a child asked for a giant airplane, got it, and sat through 88 seconds
 *       of silence while one was actually being built. A safety message is a refusal, so it has to
 *       be the kind that still points forward.</li>
 *   <li><b>Never blame him.</b> "That's not one I can make" is about the wish and about me. "You
 *       shouldn't ask for that" is about him, and a 7-year-old hears the second one all evening.</li>
 *   <li><b>Always invite the next wish.</b> Every string ends pointing at something he can do now.
 *       {@code SafetyWordsTest} asserts that rather than trusting it.</li>
 *   <li><b>Never explain what was wrong with it.</b> Naming the category is both a lesson in
 *       vocabulary he did not have and an invitation to see how close he can get.</li>
 *   <li><b>Never {@code VILLAGER_NO}</b> — literally the "no" noise; the same file records it being
 *       removed for the same reason.</li>
 * </ul>
 *
 * <p>The wording is a maintainer's to tune (§12.5). The shape — short, warm, forward-looking, no
 * explanation — is not.
 */
public final class SafetyWords {

    private SafetyWords() {
    }

    /**
     * CLEARLY at input: nothing reached the agent.
     *
     * <p>"not a Minecraft kind of wish" does the work: it is a statement about the GAME rather than
     * about him, it is true, and it carries no vocabulary he has to ask about.
     */
    public static final String BLOCKED =
            "🪄 That's not one I can make — it's not a Minecraft kind of wish. "
                    + "Wish for something else and I'm on it!";

    /** LIKELY at input: said BEFORE the build starts, so nothing is ever quietly substituted. */
    public static final String REDIRECT_PREFIX = "✨ I'll make it like this instead: ";

    /** …and the tail, so the sentence still ends by pointing forward. */
    public static final String REDIRECT_SUFFIX = " — here goes!";

    /** A plan that went somewhere it cannot go. The wish ends; the world is untouched. */
    public static final String PLAN_STOPPED =
            "🪄 That one went somewhere I can't take it. Try wishing it a different way? 🪄";

    /**
     * The grader could not run and this machine is configured {@code closed}.
     *
     * <p>Deliberately the same "Moddy is resting" register the rate limiter already uses: an outage
     * is a temporary state of the WORLD, not a judgement about the wish, and the difference has to
     * be audible or a child learns the wrong lesson from a flaky CLI. "Moddy", not "the genie":
     * a maintainer's decision (2026-09-11) — Moddy is the name the child knows him by.
     */
    public static final String UNAVAILABLE =
            "😴 Moddy needs a little rest! Try that wish again in a moment.";

    /** @see #BLOCKED */
    public static String sayBlocked() {
        return BLOCKED;
    }

    /**
     * The redirect, shown verbatim before the agent starts.
     *
     * <p>Verbatim is the point: §2 says nothing is ever quietly substituted, so this is the whole
     * transparency mechanism. A blank redirect would make it a lie by omission, so it degrades to
     * the block wording rather than announcing an empty plan.
     */
    public static String sayRedirect(String redirect) {
        String r = redirect == null ? "" : redirect.strip();
        return r.isEmpty() ? sayBlocked() : REDIRECT_PREFIX + r + REDIRECT_SUFFIX;
    }

    /** @see #PLAN_STOPPED */
    public static String sayPlanStopped() {
        return PLAN_STOPPED;
    }

    /** @see #UNAVAILABLE */
    public static String sayUnavailable() {
        return UNAVAILABLE;
    }

    /**
     * Every child-facing string, for the test that checks the whole set at once.
     *
     * <p>Listed rather than reflected so that adding a message and forgetting the rules is a
     * compile-visible omission in one place, not a silently unchecked string.
     */
    public static java.util.List<String> all() {
        return java.util.List.of(BLOCKED, sayRedirect("a zombie that bonks villagers with a rubber "
                + "chicken"), PLAN_STOPPED, UNAVAILABLE);
    }
}
