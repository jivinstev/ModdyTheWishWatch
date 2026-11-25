package com.moddingfromamod.safety;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import com.moddingfromamod.safety.SafetyVerdict.Band;

/**
 * <b>L1 — what to do about a wish, decided once, with no Minecraft in sight.</b>
 * ({@code docs/CHILD_SAFETY_PLAN.md} §3 L1, and the INPUT column of §2.)
 *
 * <p>The sibling of {@link SafetyPlanGate} and deliberately the same shape: {@link #decide} is pure
 * and is a unit test, and everything that touches the game lives at the call site
 * ({@code AwaitWishTool}). That matters more here than at the plan layer, because the INPUT column
 * has the row this whole design is most likely to get wrong — the redirect a child reads — and it
 * is otherwise reachable only through a real agent, a real model and a real child.
 *
 * <h2>Two graders, one decision</h2>
 *
 * <p>L0 (the lexicon) and L1 (Haiku) both have an opinion about the same string, and the layer is
 * {@code L0 ∨ L1} rather than either alone — CS-S1b's ledger says so explicitly, and measured the
 * case: <i>"make two mobs have sex"</i> reads {@code LIKELY} from Haiku and {@code CLEARLY} from the
 * lexicon. {@link #combine} is the whole of that rule, and it is <b>one</b> implementation for the
 * same reason {@code WishService.inputAction} delegates here rather than keeping its own: a
 * band-to-action map written twice is this
 * repo's most reliable source of confident wrong answers.
 *
 * <h2>LIKELY with nothing to offer is a BLOCK</h2>
 *
 * <p>§2 gives a redirect to {@code LIKELY} alone, and a redirect that does not exist is not an
 * action: a wish "redirected" to nothing would be assigned unchanged, which is the one outcome the
 * band does not mean. So {@code LIKELY} without a usable redirect falls through to {@code BLOCK}.
 * ⚠ This is a <b>correction</b> to the map CS-S1b shipped, which wrote {@code SHADOW_REDIRECT} for
 * every {@code LIKELY} — a shadow prediction of a redirect for a wish the on arm would have
 * blocked is a base rate predicting behaviour enforcement does not have.
 */
public final class SafetyInputGate {

    private SafetyInputGate() {
    }

    /**
     * What the dequeue point should do about this wish.
     *
     * <p>The names are the log's {@code action} vocabulary (§6) verbatim, so the record is the enum
     * and nothing translates between them. The enforcing and shadow vocabularies are the same set,
     * or the base rate stops predicting the arm it was collected for.
     */
    public enum Act {
        /** Assign it, say nothing. */
        PASS,
        /** Assign it, and hand the agent one line of guidance in the wake text. */
        NOTE,
        /** Assign the REDIRECT instead, having told the child what it will be. */
        REDIRECT,
        /** Nothing reaches the agent; the wish ends REJECTED and the child is told. */
        BLOCK,
        /** The grader could not run and this machine passes those (§8). */
        UNAVAILABLE_OPEN,
        /** The grader could not run and this machine refuses those (§8). */
        UNAVAILABLE_CLOSED;

        /** Does the wish stop here? */
        public boolean refuses() {
            return this == BLOCK || this == UNAVAILABLE_CLOSED;
        }
    }

    /**
     * One decision, plus what it would have been.
     *
     * @param act          what will actually happen — always {@link Act#PASS} in shadow mode
     * @param assessed     what the grade earned, whatever the mode. In {@code on} the two are equal;
     *                     in {@code shadow} this is the prediction. Kept apart so a test can assert
     *                     the layer THOUGHT the right thing while doing nothing, which is the only
     *                     claim shadow mode can make about itself
     * @param verdict      the two graders combined, <b>strictness NOT applied</b> — this is what the
     *                     log carries, per CS-S1b: the record already names the dial, and logging
     *                     the adjusted bands instead would make every measured base rate a function
     *                     of one machine's parent setting
     * @param childMessage what the child hears, or empty
     * @param guidance     one line for the agent's wake text, or empty
     * @param runText      the text the agent should work on — the redirect, or the child's own words
     * @param redirectShown whether {@link #childMessage} really is the redirect, for the log field
     *                     that makes §2's "nothing is ever quietly substituted" checkable
     */
    public record Decision(Act act, Act assessed, SafetyVerdict verdict, String childMessage,
                           String guidance, String runText, boolean redirectShown) {

        /** Nothing graded — the OFF arm, or a text nobody looked at. */
        public static Decision pass(String text) {
            return new Decision(Act.PASS, Act.PASS, SafetyVerdict.unavailable(), "", "",
                    text == null ? "" : text, false);
        }

        /** Does the wish stop here? Always false in shadow mode. */
        public boolean refuses() {
            return act.refuses();
        }

        /**
         * The {@code action} string for {@link com.moddingfromamod.agent.ActionLog#safety}.
         *
         * <p>Reads the mode again rather than carrying it, which is safe because the caller logs
         * immediately after deciding — and is worth saying out loud, because a decision and a
         * record that disagreed about which arm they were in would be the one defect this whole
         * instrument cannot survive.
         */
        public String logAction() {
            return (ChildSafetyConfig.enforcing() ? "" : "SHADOW_") + assessed.name();
        }
    }

    // ---- the decision ----

    /**
     * Decide, from both graders.
     *
     * @param lexicon  L0's answer for this text, or null when it said nothing
     * @param model    L1's verdict — {@link SafetyVerdict#unavailable()} when the grade never
     *                 arrived, which is a different fact from "it looked and it was fine"
     * @param wishText the child's own words, returned as {@link Decision#runText} unless redirected
     */
    public static Decision decide(SafetyVerdict lexicon, SafetyVerdict model, String wishText) {
        return decide(lexicon, model, wishText, false);
    }

    /**
     * Decide, from both graders — and from whether Moddy is tired (D8).
     *
     * @param tired         the model's grade could not be OBTAINED (no answer, or the budget ran
     *                      out) while Moddy is tired. D8's degraded mode is closed for every grant,
     *                      new wish or re-wish, so such a wish is refused as
     *                      {@link Act#UNAVAILABLE_CLOSED} — the existing word for "the grader could
     *                      not run and this machine refuses those" — with the tired line, whatever
     *                      {@code child_safety_unavailable} says. Two things still win over it:
     *                      a lexicon BLOCK (a refusal is a refusal), and shadow mode, which acts on
     *                      nothing.
     */
    public static Decision decide(SafetyVerdict lexicon, SafetyVerdict model, String wishText,
                                  boolean tired) {
        String text = wishText == null ? "" : wishText;
        if (!ChildSafetyConfig.grading()) {
            return Decision.pass(text);
        }
        SafetyVerdict combined = combine(lexicon, model);
        Act assessed = actionOf(combined);
        // THE TIRED CASE. Not only when the COMBINED verdict is unavailable: a lexicon MAYBE
        // with no model grade combines to the lexicon's verdict, and it is still a grant L1 never
        // looked at — closed means closed for it too.
        boolean closedTired = tired && model != null && model.isUnavailable()
                && assessed != Act.BLOCK;
        if (closedTired) {
            assessed = Act.UNAVAILABLE_CLOSED;
        }
        // ⚠ THE DIAL, APPLIED HERE TOO, AND IT IS NOT REDUNDANT. `actionOf` decides on the
        // ADJUSTED verdict, so under `strict` a promoted MAYBE can come back REDIRECT — and asking
        // the UNADJUSTED one for the redirect then answers EMPTY, because redirectIfUsable() gates
        // on LIKELY and the raw band is still MAYBE. That handed the child "I'll make it like
        // this: ''" and the agent an EMPTY WISH, on a machine whose only crime was setting
        // child_safety_strictness=strict. The shift is idempotent (MAYBE→LIKELY→LIKELY,
        // LIKELY→MAYBE→MAYBE), so applying it in both places is safe.
        //
        // `Decision.verdict` below stays the RAW grade regardless — CS-S1b's rule: logging the
        // adjusted bands would make every measured base rate a function of one machine's dial.
        SafetyVerdict acted = combined.adjusted(ChildSafetyConfig.strictness());

        String childMessage = "";
        String guidance = "";
        String runText = text;
        boolean redirectShown = false;
        switch (assessed) {
            case NOTE -> guidance = guidanceFor(acted);
            case REDIRECT -> {
                // Present by construction: actionOf only says REDIRECT when there is one -- of the
                // ADJUSTED verdict, which is why this reads `acted` and not `combined`.
                String redirect = acted.redirectIfUsable().orElse("");
                childMessage = SafetyWords.sayRedirect(redirect);
                runText = redirect;
                redirectShown = true;
            }
            case BLOCK -> childMessage = SafetyWords.sayBlocked();
            case UNAVAILABLE_CLOSED -> childMessage = tired
                    ? com.moddingfromamod.health.OpsWords.sayTired()
                    : SafetyWords.sayUnavailable();
            default -> { }
        }

        return ChildSafetyConfig.enforcing()
                ? new Decision(assessed, assessed, combined, childMessage, guidance, runText,
                        redirectShown)
                // Shadow acts on NOTHING -- the guidance included, because a line an agent reads
                // and builds differently for is an action. SafetyPlanGate says the same about its
                // own NOTE, for the same reason.
                : new Decision(Act.PASS, assessed, combined, "", "", text, false);
    }

    /**
     * The band-to-action map, and the ONE copy of it.
     *
     * <p>Strictness is applied here rather than by the caller, so a decision and the action written
     * beside it in the log can never have been taken under different dials.
     */
    public static Act actionOf(SafetyVerdict verdict) {
        SafetyVerdict v = verdict == null ? SafetyVerdict.unavailable() : verdict;
        if (v.isUnavailable()) {
            // `closed` exists to fail safe when the grader is down (§8).
            return ChildSafetyConfig.unavailableClosed()
                    ? Act.UNAVAILABLE_CLOSED : Act.UNAVAILABLE_OPEN;
        }
        SafetyVerdict adjusted = v.adjusted(ChildSafetyConfig.strictness());
        return switch (adjusted.overall()) {
            case CLEARLY -> Act.BLOCK;
            // A redirect that does not exist is not an action -- see the class note.
            case LIKELY -> adjusted.redirectIfUsable().isPresent() ? Act.REDIRECT : Act.BLOCK;
            case MAYBE -> Act.NOTE;
            case FINE -> Act.PASS;
        };
    }

    /**
     * The two graders, merged into the verdict the decision is taken on.
     *
     * <p>Per axis, the higher band wins — so the lexicon's {@code CLEARLY} beats the model's
     * {@code FINE} and the model's {@code LIKELY} beats the lexicon's silence.
     *
     * <p>{@code provenance} answers <b>who decided</b>: {@code LEXICON} when the floor raised a band
     * above what the model said, otherwise the model's own. A log that guessed at this could not
     * report an outage, and one that always said {@code haiku} would credit the model for the
     * lexicon's catches.
     */
    public static SafetyVerdict combine(SafetyVerdict lexicon, SafetyVerdict model) {
        SafetyVerdict m = model == null ? SafetyVerdict.unavailable() : model;
        if (lexicon == null) {
            return m;
        }
        Band violence = merge(lexicon.violence(), m.violence());
        Band sexual = merge(lexicon.sexual(), m.sexual());
        Band hate = merge(lexicon.hate(), m.hate());
        Band danger = merge(lexicon.danger(), m.danger());

        // DID THE LEXICON CHANGE THE ANSWER. Raising a band (a slur the model missed) is the floor
        // deciding, and a verdict the floor decided is a lexicon verdict even when the model also
        // ran, because "who decided" is the question this field answers. When the model was unavailable and the
        // lexicon said something, that is doubly true: reporting UNAVAILABLE over a floor that
        // fired would say no measurement existed when one did.
        boolean changed = violence != m.violence() || sexual != m.sexual()
                || hate != m.hate() || danger != m.danger();
        SafetyVerdict.Provenance provenance =
                changed ? SafetyVerdict.Provenance.LEXICON : m.provenance();

        List<SafetyVerdict.Reason> reasons = new ArrayList<>(lexicon.reasons());
        for (SafetyVerdict.Reason r : m.reasons()) {
            if (!reasons.contains(r)) {
                reasons.add(r);
            }
        }
        return new SafetyVerdict(violence, sexual, hate, danger, reasons,
                // The lexicon never proposes one, so this is the model's or nothing.
                m.redirect(), provenance, SafetyVerdict.NO_STAGE);
    }

    private static Band merge(Band lex, Band model) {
        return lex.compareTo(model) > 0 ? lex : model;
    }

    // ---- the words the AGENT reads ----

    /**
     * One line for a {@code MAYBE} wish, carried into the wake text.
     *
     * <p>§2: "the grader's one-line guidance rides into the agent's wake text". Deferred from CS-S4,
     * which owns the standing prompt section and does not own this file.
     *
     * <p>Per AXIS, because the axis is what the advice differs on; the reasons ride along as words
     * rather than as a band, because "keep it cartoon" is actionable and "your violence axis is
     * MAYBE" is not. It also tells the agent not to mention any of it — §5's rule is that a child is
     * never told what was wrong with his wish, and an agent left to itself narrates its own
     * guidance cheerfully.
     *
     * <p>Deliberately NOT shared with {@link SafetyPlanGate#guidanceFor}: that one is advice about a
     * PLAN handed back in a tool result, this one is advice about a WISH handed over on the wake,
     * and folding them into one string would make both worse in order to save four lines.
     */
    public static String guidanceFor(SafetyVerdict verdict) {
        Band worst = verdict.overall();
        String advice;
        if (verdict.violence() == worst) {
            advice = "keep it cartoon — big, silly and bloodless, and nothing that would frighten a "
                    + "7-year-old who did not ask to be frightened";
        } else if (verdict.sexual() == worst) {
            advice = "keep it silly rather than grown-up — gross-out and bathroom humour are exactly "
                    + "right, romance is not";
        } else if (verdict.hate() == worst) {
            advice = "keep it kind — goofy rather than mean, and never pointed at a real person";
        } else {
            advice = "keep it Minecraft — in-game potions, TNT and cartoon weapons are fine, "
                    + "real-world ones are not";
        }
        String why = why(verdict);
        return "SAFETY NOTE — grant this wish exactly as asked; this is NOT a refusal. The game's "
                + "safety grader marked it MAYBE" + (why.isEmpty() ? "" : " for " + why) + ": "
                + advice + ". Do not mention any of this to the child.";
    }

    /** The closed reason vocabulary, rendered for a reader. */
    private static String why(SafetyVerdict verdict) {
        List<String> out = new ArrayList<>();
        for (SafetyVerdict.Reason r : verdict.reasons()) {
            out.add(r.name().toLowerCase(Locale.ROOT).replace('_', ' '));
        }
        return String.join(", ", out);
    }
}
