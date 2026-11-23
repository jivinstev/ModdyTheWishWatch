package com.moddingfromamod.marshal;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * <b>The facts the agent decides a wish's referent from — worded as facts, never as an answer</b>
 *.
 *
 * <p>The local resolver used to DECIDE: it climbed a ladder of rungs (named, aimed, sticky,
 * nearest…), told the child "Working on your Pegasus!" before anyone had read his words, and told
 * the agent "He SAID SO … do not ask him which he meant". Live, that was 4 picks and 4 wrong — every
 * one a wish for a NEW item (a fork, two swords, a bar) bound to a creature he owned because his
 * crosshair was near it. The rungs were deleted; what they knew survives here as two lines of
 * context, and the agent — the one component that reads the words — names the referent itself in
 * {@code plan_wish}'s {@code about}.
 *
 * <p>The wording is the contract, so it is pure and unit-tested ({@code ReferentFactsTest}): it
 * says where things ARE and what he DID, and never "he means", "this is the one" or "do".
 */
public final class ReferentFacts {

    /** The block's first words — how a reader of the wish note (an earlier issue's nudge) finds it. */
    public static final String HEADING = "FACTS ABOUT HIM RIGHT NOW";

    /** How many things in his view are worth naming. More reads as a list to choose from. */
    public static final int MAX_IN_VIEW = 3;

    /** One of his things, as the facts name it. */
    public record Thing(String id, String kind, String name, double distance) {

        public Thing {
            id = id == null ? "" : id;
            kind = kind == null ? "" : kind;
            name = name == null || name.isBlank() ? id.replace('_', ' ') : name;
        }
    }

    private ReferentFacts() {
    }

    /**
     * The facts block, or {@code ""} when there are no facts.
     *
     * @param inView    his things inside his view, in any order (nearest first is applied here)
     * @param last      what a wish of his last made or changed, or null
     * @param lastAgeMs how long ago, or negative when unknown
     */
    public static String render(List<Thing> inView, Thing last, long lastAgeMs) {
        return render(inView, last, lastAgeMs, "", "", -1);
    }

    /**
     * The facts block with HIS LAST WISH said too: the words of the wish that made or
     * changed {@code last}, and — when a later wish made nothing of his — that later wish's words.
     * A follow-up ("give it wings too") names nothing; what it most likely points at is what his
     * last wish made, and the agent could not tell that from an id alone after a restart.
     *
     * @param lastWords     the words of the wish that made or changed {@code last}, or ""
     * @param previousWords the words of his previous wish, or ""
     * @param previousAgeMs how long ago he wished it, or negative when unknown
     */
    public static String render(List<Thing> inView, Thing last, long lastAgeMs, String lastWords,
                                String previousWords, long previousAgeMs) {
        List<Thing> seen = new ArrayList<>(inView == null ? List.of() : inView);
        seen.removeIf(t -> t == null || t.id().isBlank());
        seen.sort((a, b) -> Double.compare(a.distance(), b.distance()));
        boolean hasLast = last != null && !last.id().isBlank();
        String madeBy = lastWords == null ? "" : lastWords.strip();
        String prev = previousWords == null ? "" : previousWords.strip();
        // Said on its own line only when it is NOT the wish that made `last` (it made nothing of his).
        boolean prevAlone = !prev.isEmpty() && !(hasLast && prev.equalsIgnoreCase(madeBy));
        if (seen.isEmpty() && !hasLast && !prevAlone) {
            return "";
        }
        StringBuilder sb = new StringBuilder(HEADING + " — what is in his view and "
                + "what he did last. They are NOT what this wish is about: you decide that from his "
                + "words, and say it in plan_wish's `about`.");
        if (!seen.isEmpty()) {
            sb.append("\n- In his view (roughly in front of him): ");
            int n = Math.min(MAX_IN_VIEW, seen.size());
            for (int i = 0; i < n; i++) {
                Thing t = seen.get(i);
                if (i > 0) {
                    sb.append("; ");
                }
                sb.append(describe(t)).append(", ").append(blocks(t.distance())).append(" away");
            }
            if (seen.size() > n) {
                sb.append(" (and ").append(seen.size() - n).append(" more further off)");
            }
            sb.append('.');
        }
        if (prevAlone) {
            sb.append("\n- His previous wish").append(previousAgeMs >= 0 ? ", " + ago(previousAgeMs) : "")
              .append(": \"").append(prev).append("\" — it made or changed none of his things.");
        }
        if (hasLast) {
            sb.append("\n- The last thing a wish of his made or changed: ").append(describe(last));
            if (lastAgeMs >= 0) {
                sb.append(", ").append(ago(lastAgeMs));
            }
            if (!madeBy.isEmpty()) {
                sb.append(", by his wish \"").append(madeBy).append('"');
            }
            sb.append('.');
            if (!madeBy.isEmpty()) {
                sb.append(" A follow-up that says \"it\", \"him\", \"that\" or \"the …\" without "
                        + "naming another of his things is most likely about this one.");
            }
        }
        return sb.toString();
    }

    /**
     * Said when his words point back at something and there are NO facts at all — nothing of his in
     * view, nothing a wish of his made or changed, no earlier wish. Live, that is where the
     * agent silently changed the CHILD ("make it sparkle" → he sparkled) or built a new thing and
     * called it his. The wording is a request for honesty, never a pick.
     */
    public static final String NO_ANTECEDENT = "NOTHING SAYS WHAT \"IT\" IS: nothing of his is in "
            + "his view and no wish of his has made or changed anything yet. If his words point at "
            + "something he already has, do not guess and do not change HIM instead: if two or three "
            + "of his things fit, ask_which; if none does, tell him kindly you could not find it "
            + "(\"I couldn't find your cloud — want me to make you one?\") and finish_wish without "
            + "making anything. Never make a new one and call it his.";

    /** {@code his Pegasus ('pegasus', creature)}. */
    static String describe(Thing t) {
        String kind = kindWord(t.kind());
        return "his " + t.name() + " ('" + t.id() + "'" + (kind.isEmpty() ? "" : ", " + kind) + ")";
    }

    /** The store's kind as a word the agent's tools use. */
    static String kindWord(String kind) {
        return switch (kind == null ? "" : kind.toLowerCase(Locale.ROOT)) {
            case "species" -> "creature";
            case "build" -> "building";
            case "rule" -> "rule";
            case "datapack" -> "rule (datapack)";
            default -> kind == null ? "" : kind;
        };
    }

    static String blocks(double d) {
        long n = Math.max(1, Math.round(d));
        return n + (n == 1 ? " block" : " blocks");
    }

    static String ago(long ms) {
        long s = Math.max(0, ms / 1000);
        if (s < 90) {
            return s + " s ago";
        }
        long m = Math.round(s / 60.0);
        if (m < 90) {
            return m + " minutes ago";
        }
        long h = Math.round(m / 60.0);
        if (h < 36) {
            return h + (h == 1 ? " hour ago" : " hours ago");
        }
        long d = Math.round(h / 24.0);
        return d + (d == 1 ? " day ago" : " days ago");
    }
}
