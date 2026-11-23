package com.moddingfromamod.marshal;

import java.util.ArrayList;
import java.util.List;

/**
 * <b>What a waking agent is actually handed</b> — the budget, and the only place there is one.
 *
 * <p>{@link ArtifactMemory} keeps everything a wish ever learned. This decides how much of it fits,
 * and it is deliberately the <i>only</i> component allowed to drop anything. That split is the whole
 * correction to the old design: budgeting at write time deletes evidence permanently, and a renderer
 * can always be made cleverer later — but only if the data is still there.
 *
 * <h2>What is kept when it does not all fit, in priority order</h2>
 * Not "the last N turns", which is the easy answer and the wrong one. Ranked by what a refinement
 * actually cannot proceed without:
 *
 * <ol>
 *   <li><b>What is true now.</b> The state. Without it the agent inspects the world to find out —
 *       which is the re-derivation this whole exercise is trying to remove.</li>
 *   <li><b>Everything ever rejected.</b> Never dropped before anything else, because re-walking a
 *       dead end is the most expensive mistake available and the only one that gets <i>worse</i>
 *       the more history there is. Under the old design this was one 600-character field for the
 *       whole life of an artifact.</li>
 *   <li><b>Turn 1, verbatim.</b> The original intent. Everything since is a modifier on it, and it
 *       is the one turn whose meaning does not decay.</li>
 *   <li><b>The most recent turns, in full.</b> "Thinner than that" is meaningless without the
 *       previous attempt, so recency is where trajectory lives.</li>
 *   <li><b>The middle, as one line each: his words and the verdict.</b> His words are the smallest
 *       and highest-value part of a turn, so the first thing compressed is the agent's own
 *       reasoning — which is also the most reconstructible.</li>
 * </ol>
 *
 * <p>Degradation is <b>announced</b>. Every drop prints what went and how much, because a brief that
 * silently omits half its history teaches the agent that it has been told everything — the same
 * defect as a 598-of-600-character field with no marker on it.
 */
public final class MemoryBrief {

    /**
     * The read budget, in characters.
     *
     * <p>Criterion 6 of the wish-context plan caps the injected block at <b>1500 tokens at N=5</b>.
     * ~4 characters a token puts that at 6000, and this is expressed in characters because that is
     * the thing a renderer can actually count. Real token cost is measured separately, off the
     * agent's own transcripts, precisely so this approximation never becomes the measurement — the
     * plan lists characters × 4 as a threat when used for <i>costing</i>, and it is only sound here
     * because a budget may be approximate while a measurement may not.
     *
     * <p>For scale: the whole of {@code crushing_plant}'s record was 1,749 characters, and one agent
     * turn re-reads ~85,000 tokens. This budget is ~1.7% of a turn.
     */
    public static final int DEFAULT_BUDGET_CHARS = 6_000;

    /** {@code -Dmoddingfromamod.contextbudget=N} — so an arm can move the budget and measure it. */
    public static final String BUDGET_PROPERTY = "moddingfromamod.contextbudget";

    /** Recent turns rendered in full, before the budget is even consulted. */
    public static final int FULL_TURNS = 3;

    /**
     * Held back from the budget for the "here is what I left out" footer.
     *
     * <p>Reserved UP FRONT rather than appended afterwards. Appending it is how a budget quietly
     * becomes advisory: the block comes in at 2,595 characters against a 3,000 cap, everything looks
     * fine, and the one case where the footer exists is precisely the case where the block was
     * already at its limit. A cap that is exceeded exactly when it binds is not a cap.
     */
    static final int FOOTER_RESERVE = 140;

    private MemoryBrief() {
    }

    /** The budget in force right now. Read per call, like every other control here. */
    public static int budgetChars() {
        String raw = System.getProperty(BUDGET_PROPERTY);
        if (raw == null || raw.isBlank()) {
            return DEFAULT_BUDGET_CHARS;
        }
        try {
            return Math.max(500, Math.min(60_000, Integer.parseInt(raw.trim())));
        } catch (NumberFormatException e) {
            return DEFAULT_BUDGET_CHARS;
        }
    }

    /** What this brief cost and what it had to leave out — logged, never guessed at. */
    public record Rendered(String text, int chars, int turnsShown, int turnsSummarised,
                           int turnsDropped, int rejectionsDropped) {

        public boolean isEmpty() {
            return text.isEmpty();
        }
    }

    public static Rendered render(ArtifactMemory m) {
        return render(m, budgetChars());
    }

    public static Rendered render(ArtifactMemory m, int hardBudget) {
        int budget = Math.max(200, hardBudget - FOOTER_RESERVE);
        if (m == null || (m.turns().isEmpty() && m.state().isEmpty() && m.whatItIs().isEmpty())) {
            return new Rendered("", 0, 0, 0, 0, 0);
        }
        List<ArtifactMemory.Turn> turns = m.turns();
        int n = turns.size();

        // Everything that is never negotiable, built first so the budget is spent on history rather
        // than discovered to have been spent on it.
        StringBuilder head = new StringBuilder();
        head.append("── YOUR NOTES ON '").append(m.artifactId()).append("'");
        if (!m.kind().isEmpty()) {
            head.append(" (a ").append(m.kind()).append(")");
        }
        head.append(" — this is edit #").append(m.revision() + 1).append(" ──\n");
        if (!m.whatItIs().isEmpty()) {
            head.append("it is: ").append(m.whatItIs()).append('\n');
        }
        // CONFIRMED aliases only. A name he used once was probably emphasis, and a false alias
        // both mis-briefs and (since rung 0 now routes too) mis-ROUTES.
        List<String> names = m.confirmedAliases();
        if (!names.isEmpty()) {
            head.append("he calls it: ").append(String.join(", ", names)).append('\n');
        }
        if (!m.where().isEmpty()) {
            head.append("where: ").append(m.where()).append('\n');
        }
        if (!m.state().isEmpty()) {
            head.append("\nWHAT IS TRUE NOW:\n");
            for (String s : m.state()) {
                head.append("  · ").append(s).append('\n');
            }
        }

        List<String> rejected = m.allRejected();
        int rejectionsDropped = 0;
        StringBuilder never = new StringBuilder();
        if (!rejected.isEmpty()) {
            never.append("\nALREADY TRIED AND REJECTED — do not do these again:\n");
            for (String r : rejected) {
                never.append("  · ").append(r).append('\n');
            }
        }

        StringBuilder origin = new StringBuilder();
        if (n > 0 && !turns.get(0).wish().isEmpty()) {
            origin.append("\nWHAT HE ORIGINALLY ASKED FOR (turn 1):\n  \"")
                  .append(turns.get(0).wish()).append("\"\n");
        }

        // Recent turns in full, oldest of them first so it reads as a story.
        int fullFrom = Math.max(n > 1 ? 1 : 0, n - FULL_TURNS);
        StringBuilder recent = new StringBuilder();
        if (fullFrom < n) {
            recent.append("\nWHAT HAS HAPPENED SINCE (newest last):\n");
            for (int i = fullFrom; i < n; i++) {
                appendFull(recent, i + 1, turns.get(i));
            }
        }

        // The middle, one line each. His words plus the verdict — the smallest, highest-value part.
        List<String> middle = new ArrayList<>();
        for (int i = 1; i < fullFrom; i++) {
            ArtifactMemory.Turn t = turns.get(i);
            middle.add("  turn " + (i + 1) + "  \"" + t.wish() + "\""
                    + (t.verdict().isEmpty() ? "" : "  [" + t.verdict() + "]"));
        }

        // Now spend the budget. Order of sacrifice is the priority list in the class note.
        int fixed = head.length() + never.length() + origin.length();
        int turnsDropped = 0;
        while (fixed + recent.length() + lines(middle) > budget && !middle.isEmpty()) {
            middle.remove(0);                       // oldest middle turn goes first
            turnsDropped++;
        }
        while (fixed + recent.length() > budget && recent.length() > 0 && fullFrom < n - 1) {
            fullFrom++;                             // then the oldest FULL turn is demoted away
            turnsDropped++;
            recent.setLength(0);
            recent.append("\nWHAT HAS HAPPENED SINCE (newest last):\n");
            for (int i = fullFrom; i < n; i++) {
                appendFull(recent, i + 1, turns.get(i));
            }
        }
        // Only now, and only if it still will not fit, are rejections trimmed — oldest first,
        // because the most recent dead ends are the ones a refinement is closest to repeating.
        while (head.length() + never.length() + origin.length() + recent.length() > budget
                && !rejected.isEmpty()) {
            rejected.remove(0);
            rejectionsDropped++;
            never.setLength(0);
            if (!rejected.isEmpty()) {
                never.append("\nALREADY TRIED AND REJECTED — do not do these again:\n");
                for (String r : rejected) {
                    never.append("  · ").append(r).append('\n');
                }
            }
        }

        StringBuilder out = new StringBuilder(head).append(never).append(origin);
        if (!middle.isEmpty()) {
            out.append("\nEARLIER TURNS (one line each):\n");
            middle.forEach(l -> out.append(l).append('\n'));
        }
        out.append(recent);
        // SAY WHAT WAS LEFT OUT. A brief that quietly omits history teaches the agent it has been
        // told everything, which is worse than telling it less and saying so.
        if (turnsDropped > 0 || rejectionsDropped > 0) {
            out.append("\n(⚠ ").append(turnsDropped).append(" older turn(s) and ")
               .append(rejectionsDropped).append(" older rejection(s) left out to fit — ask ")
               .append("list_wishes if an early decision matters.)\n");
        }
        return new Rendered(out.toString(), out.length(), n - fullFrom, middle.size(),
                turnsDropped, rejectionsDropped);
    }

    private static void appendFull(StringBuilder sb, int number, ArtifactMemory.Turn t) {
        sb.append("  turn ").append(number);
        if (!t.verdict().isEmpty()) {
            sb.append("  [").append(t.verdict()).append(']');
        }
        sb.append('\n');
        if (!t.wish().isEmpty()) {
            sb.append("    he said: \"").append(t.wish()).append("\"\n");
        }
        if (!t.did().isEmpty()) {
            sb.append("    you did: ").append(t.did()).append('\n');
        }
        if (!t.why().isEmpty()) {
            sb.append("    why: ").append(t.why()).append('\n');
        }
        if (!t.saidToChild().isEmpty()) {
            // So the next agent does not re-explain what he has already been told, or re-ask what
            // he has already answered.
            sb.append("    you told him: \"").append(t.saidToChild()).append("\"\n");
        }
        if (!t.careful().isEmpty()) {
            sb.append("    careful: ").append(t.careful()).append('\n');
        }
    }

    private static int lines(List<String> xs) {
        int n = xs.isEmpty() ? 0 : "\nEARLIER TURNS (one line each):\n".length();
        for (String x : xs) {
            n += x.length() + 1;
        }
        return n;
    }
}
