package com.moddingfromamod.marshal;

/**
 * <b>What is "the top"? The top of the thing you just built.</b>
 *
 * <h2>The measurement</h2>
 * Every refinement rung in every fixture, against the rule that decides whether a wish is a
 * refinement at all: creature 16/16, item 8/8, effect 12/12, datapack 4/4, <b>building 11/16</b>.
 * Live on a 12-cell matrix: <b>30/48</b>, with the SAME rungs failing in every cell of a fixture —
 * specific sentences, not noise:
 *
 * <pre>
 * put a giant diamond spire on the very top
 * put a flag on the very top
 * put a big glass floor at the top so you can look down
 * make the lighthouse light actually glow
 * </pre>
 *
 * <p>Every miss is one shape: <b>the child names a PART or a POSITION rather than the thing.</b> A
 * creature is person-like so he says "him"; an item is one object so "it"; a building has parts you
 * can point at, so he names the part. <b>Machines have exactly that shape</b> — the grinder, the
 * belt, the tank — so this lands on that class the moment it is unblocked.
 *
 * <h2>Why the grammar gate cannot be widened</h2>
 * {@code RefersBack} is a gate on grammar, and "put a flag on the very top" has no referring word,
 * so it is classified a creation and the router never asks what he was working on. Widening the word
 * list is exhausted: {@code "this"} was added and fixed one fixture, but {@code "that"} would bind
 * <i>"make a castle THAT floats on the water"</i> — a brand-new wish, and rung 1 of that very
 * fixture — and {@code "there"} would bind <i>"put a castle over there"</i>. Binding a CREATION rung
 * is worse than leaving a refinement unbound: it claims an experiment ran on a wish that had nothing
 * to refer to.
 *
 * <h2>Recency instead, with the guards the issue names</h2>
 * If he finished something ninety seconds ago and this wish names nothing else, it is about that
 * thing. Four guards, all of them here rather than at the call site:
 *
 * <ol>
 *   <li>only when the wish names <b>no</b> artifact at all — a named one always wins;</li>
 *   <li>only inside a short window — {@link WishLadder#STICKY_MS}, reused rather than a second
 *       number, and the issue's own example is "ninety seconds ago";</li>
 *   <li>never when the wish names a <b>different</b> artifact — subsumed by guard 1;</li>
 *   <li>never when there is nothing behind it, which is what rung 1 of a ladder is.</li>
 * </ol>
 *
 * <h2>⚠ RECORD BOTH, NEVER SUBSTITUTE — the rule that makes this safe to ship</h2>
 * Dad, twice: <i>"I genuinely am still worried about forking execution into the prod representative
 * vs non prod representative paths, because I'm worried we would stop running prod
 * representative."</i>
 *
 * <p>That is why {@link How} exists and why it is returned rather than logged. The failure mode of
 * recency is mis-binding a genuine new wish that happens to follow a finished one. In production
 * that only chooses which worker takes the wish, which is low harm. <b>In a MEASUREMENT it would
 * inflate the binding rate</b> — exactly the flattering error this rollout keeps finding. So a
 * caller can always tell "bound because he referred to it" from "bound because it was recent", and
 * the two are reported apart instead of one number that quietly improved.
 */
public final class SubjectBinding {

    /** How the subject was arrived at — the thing that must never be averaged away. */
    public enum How {
        /** The wish named it. Certain, and the only one that was ever reported before. */
        NAMED,
        /** A referring word ("this castle", "make it taller") plus something to refer to. */
        REFERRED,
        /** No referring word, but he finished this seconds ago and named nothing else. */
        RECENT,
        /** Nothing to bind — a creation, or nothing recent enough. */
        NONE
    }

    /**
     * @param subjectId the artifact this wish is about, or {@code ""}
     * @param how       which rule got there — never collapse this into the id
     */
    public record Binding(String subjectId, How how) {

        public Binding {
            subjectId = subjectId == null ? "" : subjectId;
        }

        /** True when a subject was found at all, however. */
        public boolean bound() {
            return !subjectId.isEmpty();
        }

        /**
         * True when the wish itself points at the subject.
         *
         * <p>The honest denominator for "did production route this correctly": a RECENT binding is
         * a convenience, and counting it as evidence that the grammar worked is the inflation this
         * class is built to prevent.
         */
        public boolean fromTheWords() {
            return how == How.NAMED || how == How.REFERRED;
        }
    }

    private SubjectBinding() {
    }

    /**
     * @param named        what the wish's own words name, or {@code ""}
     * @param refersBack   whether the wish contains a referring word
     * @param recentId     the last thing he finished, or {@code ""}
     * @param recentAgeMs  how long ago that was; negative or huge simply fails the window
     */
    public static Binding decide(String named, boolean refersBack,
                                 String recentId, long recentAgeMs) {
        if (named != null && !named.isBlank()) {
            return new Binding(named, How.NAMED);
        }
        // GUARD 4. Nothing behind it — rung 1 of a ladder, or a genuinely first wish. Both rules
        // below need something to bind to, so this is checked once.
        if (recentId == null || recentId.isBlank()) {
            return new Binding("", How.NONE);
        }
        if (refersBack) {
            // Unchanged behaviour, deliberately: this is what already worked for creatures (16/16),
            // items (8/8), effects (12/12) and datapacks (4/4), and it is not window-limited —
            // "make it taller" an hour later still means the thing he was talking about.
            return new Binding(recentId, How.REFERRED);
        }
        // GUARD 2. The window, and it is the ONLY thing standing between this and binding every
        // new wish that happens to follow a finished one.
        if (recentAgeMs < 0 || recentAgeMs > WishLadder.STICKY_MS) {
            return new Binding("", How.NONE);
        }
        return new Binding(recentId, How.RECENT);
    }
}
