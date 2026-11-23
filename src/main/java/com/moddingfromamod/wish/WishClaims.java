package com.moddingfromamod.wish;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * <b>What the child asked for, listed before it is built and answered one by one before it is
 * handed over.</b>
 *
 * <h2>The failure this exists for — measured 2026-08-13, and fixed by this class</h2>
 *
 * <p>On that day an agent finished one grinder wish five times over, and each time told the child it
 * worked: <i>"Drop logs in the TOP and the giant spiky wheels SQUEEZE them"</i>, <i>"It IS working —
 * the magic is just too fast!"</i>, <i>"Trees now BURST apart and the wood FLIES through the
 * air"</i>. The machine ground nothing, and that last sentence was written after reading a chest's
 * contents — which says something was converted and nothing whatever about anything flying.
 *
 * <p>It was not lying. It verified <b>what was easy to verify</b> — a chest count, a shaft rpm — and
 * then described what it had intended. Nowhere in those five finishes was the question "did the
 * thing he asked for happen?" asked in those words.
 *
 * <h2>Why the list is bounded by the WISH and not by the artifact</h2>
 *
 * <p>"What should we verify about a machine?" has no end: rpm, stress, item flow, output acceptor,
 * every link, every port. Any list built that way grows until nobody finishes it.
 *
 * <p>"What did the CHILD ask for?" is a different question with a naturally small answer. His
 * grinder wish contained exactly three things:
 *
 * <ol>
 *   <li>it grinds up the trees</li>
 *   <li>it turns them into butt cheek water</li>
 *   <li>it bottles it so I can drink it later</li>
 * </ol>
 *
 * <p>Each is falsifiable by one observation, and <b>N is bounded by his sentence</b> — which is why
 * {@link #MAX} is 3 and not a matter of judgement. A wish with more than three claims in it is a
 * wish that should have been built as its best chunky first version instead.
 *
 * <p>This also fixes the machine gate's weakness. That gate demanded something make the trip through
 * a machine, and was satisfied by a feed of <i>cobblestone</i> — which a crusher really does
 * process. The gate passed while what the child had asked for did not happen, because the claim came
 * from the DESIGN rather than from him.
 *
 * <p>In memory and per-wish, like {@code MachineHealth}: cleared when a wish starts, so a claim
 * about yesterday's wish can never hold up today's.
 */
public final class WishClaims {

    private WishClaims() {
    }

    /**
     * At most three, and the number is the design rather than a limit.
     *
     * <p>Three is what a child's sentence actually contains. A longer list is the artifact's
     * checklist wearing the wish's clothes, and it is the thing that makes a verification pass
     * open-ended and therefore skipped.
     */
    public static final int MAX = 3;

    /** One thing he asked for, and what we found out about it. */
    public record Claim(String what, Verdict verdict, String evidence) {

        public Claim {
            what = what == null ? "" : what.trim();
            verdict = verdict == null ? Verdict.UNCHECKED : verdict;
            evidence = evidence == null ? "" : evidence.trim();
        }

        public boolean answered() {
            return verdict != Verdict.UNCHECKED;
        }
    }

    public enum Verdict {
        /** Nobody has looked yet. */
        UNCHECKED,
        /** Looked, and it really happens. */
        WORKS,
        /** Looked, and it does not. Saying so is the whole point. */
        BROKEN,
        /**
         * Said to work, but nothing could check it: the child is told "I tried", never a ✔.
         * A tick is something the SERVER saw; the agent's word alone is not one.
         */
        TRIED
    }

    /** One claim list per wish ({@link WishScope#key()}), so one wish's start never wipes another's. */
    private static final Map<String, Map<String, Claim>> BY_WISH = new java.util.HashMap<>();

    private static Map<String, Claim> claims() {
        return BY_WISH.computeIfAbsent(WishScope.key(), k -> new LinkedHashMap<>());
    }

    /** Forget one wish's claims (it settled). */
    public static synchronized void forget(String wishKey) {
        BY_WISH.remove(wishKey == null ? "" : wishKey);
    }

    /** A new wish: whatever was claimed about the last one is gone. */
    public static synchronized void clear() {
        BY_WISH.remove(WishScope.key());
    }

    /**
     * State what the child asked for. Extra claims past {@link #MAX} are <b>dropped</b> and the
     * caller is told how many — never silently truncated, which is the defect this repo has paid
     * for in three other places.
     *
     * @return how many were kept
     */
    public static synchronized int state(List<String> asked) {
        claims().clear();
        if (asked == null) {
            return 0;
        }
        for (String s : asked) {
            if (s == null || s.isBlank() || claims().size() >= MAX) {
                continue;
            }
            String what = s.trim();
            claims().put(key(what), new Claim(what, Verdict.UNCHECKED, ""));
        }
        return claims().size();
    }

    /**
     * Answer one claim.
     *
     * <p>Matched on the claim's own words rather than an index, because an index is a thing the
     * agent has to keep in its head across a whole build and a sentence is not.
     *
     * @return false when nothing was claimed by that name
     */
    public static synchronized boolean answer(String what, Verdict verdict, String evidence) {
        String k = key(what == null ? "" : what);
        Claim existing = claims().get(k);
        if (existing == null) {
            return false;
        }
        claims().put(k, new Claim(existing.what(), verdict, evidence));
        return true;
    }

    public static synchronized List<Claim> all() {
        return new ArrayList<>(claims().values());
    }

    /** The claims nobody has looked at. Empty means every one has an answer, good or bad. */
    public static synchronized List<Claim> unanswered() {
        List<Claim> out = new ArrayList<>();
        for (Claim c : claims().values()) {
            if (!c.answered()) {
                out.add(c);
            }
        }
        return out;
    }

    /**
     * <b>What to put in front of the agent when it tries to finish with claims unchecked.</b>
     *
     * <p>Names them, because "verify your work" is advice and "you said it would bottle the water
     * and nobody has looked" is an instruction.
     */
    public static synchronized String nagAboutUnanswered() {
        List<Claim> open = unanswered();
        if (open.isEmpty()) {
            return null;
        }
        StringBuilder sb = new StringBuilder("You said this wish would do ")
                .append(claims().size()).append(" thing(s), and ").append(open.size())
                .append(" of them nobody has checked:");
        for (Claim c : open) {
            sb.append("\n  - \"").append(c.what()).append('"');
        }
        return sb.append("\nGo and look at each one — make the thing happen and observe it, the way "
                + "the CHILD would, not the way that is easiest to measure. A chest that has "
                + "something in it does not prove anything flew, and a check that came back "
                + "'no entity was found' is not a check that passed. Then call this again with a "
                + "verdict for every claim. If one genuinely does not work, say BROKEN — an honest "
                + "'this bit isn't working yet' is worth far more to him than a confident sentence "
                + "he can disprove in ten seconds.").toString();
    }

    /**
     * <b>What the child hears about his claims: nothing, unless one is broken</b>.
     *
     * <p>This used to be a checklist — "Here's what works:" and a ✔, ✘ or "I tried" per claim. In
     * live play every item was "I tried" (30 h on a dedicated server: 10 lists, 0 ✔, 0 ✘), so the header sat
     * above lists that said nothing had been checked, and it read as noise.
     * The checks themselves are unchanged and still gate the agent; only the telling changed.
     *
     * <p>What survives is the honest half, and the reason it was ever shown: he finds a broken bit
     * anyway, in about ten seconds, by trying it. So a BROKEN claim — the agent said works:false,
     * or the world contradicted it — is told in ONE sentence in Moddy's voice, never as a list and
     * never with a tick. Everything passing, or merely tried, says nothing extra.
     *
     * @return the sentence, or null when nothing is broken
     */
    public static synchronized String brokenSentence() {
        List<String> broken = new ArrayList<>();
        for (Claim c : claims().values()) {
            if (c.verdict() == Verdict.BROKEN && !c.what().isBlank()) {
                broken.add(c.what());
            }
        }
        return brokenSentence(broken);
    }

    /** Pure form of {@link #brokenSentence()}: one natural sentence for these broken bits. */
    static String brokenSentence(List<String> broken) {
        if (broken == null || broken.isEmpty()) {
            return null;
        }
        List<String> bits = new ArrayList<>();
        for (String b : broken) {
            String t = b.strip().replaceAll("[.!?]+$", "");
            if (!t.isEmpty()) {
                bits.add("\"" + t + "\"");
            }
        }
        if (bits.isEmpty()) {
            return null;
        }
        String list = bits.size() == 1 ? bits.get(0)
                : String.join(", ", bits.subList(0, bits.size() - 1)) + " and " + bits.get(bits.size() - 1);
        return bits.size() == 1
                ? "🔧 Hmm — " + list + " isn't quite working yet. Wish for it again and I'll sort it!"
                : "🔧 Hmm — " + list + " aren't quite working yet. Wish for them again and I'll sort them!";
    }

    /** Did every claim come back working? */
    public static synchronized boolean allWork() {
        if (claims().isEmpty()) {
            return true;
        }
        return claims().values().stream().allMatch(c -> c.verdict() == Verdict.WORKS);
    }

    /** Loose matching, so a claim can be answered without quoting it back character-perfect. */
    private static String key(String s) {
        return s.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9 ]", " ")
                .replaceAll("\\s+", " ").trim();
    }
}
