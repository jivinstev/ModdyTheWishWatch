package com.moddingfromamod.wish;

import java.util.List;
import java.util.Locale;

/**
 * <b>What a claim becomes once the world has been asked</b> — the decisions, with no
 * Minecraft in them, so every one is a unit test ({@code ClaimJudgeTest}). {@code ClaimWorld}
 * takes the measurements; this says what they mean.
 */
public final class ClaimJudge {

    private ClaimJudge() {
    }

    /**
     * How long a watched claim is watched, in server ticks (3 s at 20 per second). Long enough for
     * a companion that walks to the nearest player to leave a court — which is the claim that was
     * wrong live — and short enough to sit inside a reveal the child is already waiting for.
     */
    public static final int WINDOW_TICKS = 60;

    /** "With him", for a follower: inside this many blocks of the child. */
    public static final double FOLLOW_NEAR = 8.0;

    /** "Coming to him": this much closer at the end of the window than at the start. */
    public static final double FOLLOW_CLOSING = 1.0;

    /** Lower case, every run of anything but a letter or digit one underscore. */
    public static String words(String s) {
        return s == null ? "" : s.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "_")
                .replaceAll("^_+|_+$", "");
    }

    /**
     * {@code name} is {@code w}, or begins with it as whole words — so a check for
     * {@code pickleball_player} finds species {@code pickleball_player_a} and "Pickleball Player
     * Red" (measured: an exact match counted 0 of them), and {@code bow} does not find "spikor".
     */
    public static boolean leading(String name, String w) {
        return !w.isEmpty() && (name.equals(w) || name.startsWith(w + "_"));
    }

    /** What the world said about one check. */
    public record Observed(boolean holds, String saw) {
        public Observed {
            saw = saw == null ? "" : saw;
        }
    }

    /** What the child is told about one claim. */
    public enum Outcome {
        /** Claimed, checked, true: ✔. */
        WORKS,
        /** The agent said it does not work: ✘. */
        BROKEN,
        /** Claimed to work, but nothing could check it: "I tried", never a ✔. */
        TRIED,
        /** Claimed to work and the world says otherwise: never told as working. */
        CONTRADICTED
    }

    public static Outcome judge(boolean claimedWorks, ClaimCheck check, Observed seen) {
        if (!claimedWorks) {
            return Outcome.BROKEN;
        }
        if (check == null || seen == null) {
            return Outcome.TRIED;
        }
        return seen.holds() ? Outcome.WORKS : Outcome.CONTRADICTED;
    }

    /** A count at the start and at the end of the window; only the end counts. */
    public static Observed count(ClaimCheck c, int atStart, int atEnd) {
        return count(c, atStart, atEnd, 0);
    }

    /**
     * A count by IDENTITY first: {@code ownAtEnd} of the total are this wish's own creatures,
     * counted wherever they are — a follower walks away from the wish's spot with the child, and
     * "Spikor appears" is a fact about Spikor, not about a radius. The rest are OTHER matches inside
     * the radius, which is the only thing a radius is still for.
     */
    public static Observed count(ClaimCheck c, int atStart, int atEnd, int ownAtEnd) {
        String where = c.near() == ClaimCheck.Near.WISH ? "the wish's spot" : "the child";
        int others = atEnd - ownAtEnd;
        String saw = ownAtEnd > 0
                ? atEnd + "× " + c.what() + " (" + ownAtEnd + " this wish's own, wherever "
                        + (ownAtEnd == 1 ? "it is" : "they are")
                        + (others > 0 ? "; " + others + " more within " + c.radius() + " blocks of " + where : "")
                        + ")"
                : atEnd + "× " + c.what() + " within " + c.radius() + " blocks of " + where;
        saw += atStart != atEnd ? " (" + atStart + " when I started watching, " + atEnd
                + " three seconds later)" : "";
        return new Observed(atEnd >= c.min(), saw + (atEnd >= c.min() ? "" : "; the claim needs " + c.min()));
    }

    /** The prefix our own made items' ids carry; a wish key may be named with or without it. */
    public static final String OUR_NAMESPACE = "moddingfromamod:";

    /**
     * A made item's WISH KEY ({@code custom_data} {@code WishItem}, e.g. {@code boomerang_plain})
     * is {@code what}, EXACTLY. The agent names a made item by the key {@code make_item}
     * printed, which is neither its registry id nor its display name, so the judge counted 0 of a
     * boomerang the child was holding — 20 of the 24 archived item contradictions. Exact, never a
     * prefix: {@code bow_plain} is not {@code spikor_plain}.
     */
    public static boolean keyMatches(String key, String what) {
        if (key == null || key.isBlank() || what == null) {
            return false;
        }
        String w = what.trim().toLowerCase(Locale.ROOT);
        if (w.startsWith(OUR_NAMESPACE)) {
            w = w.substring(OUR_NAMESPACE.length());
        }
        return w.equals(key.trim().toLowerCase(Locale.ROOT));
    }

    /**
     * {@code item_in_inventory}, by identity: of what THIS wish handed over that is
     * {@code what}, how much he still has — hotbar, inventory or Wish Bag.
     *
     * @param handed how many matching things this wish handed over (0: it never handed one over)
     * @param withHim of those, how many are still with him, capped at {@code handed}
     */
    public static Observed items(ClaimCheck c, int handed, int withHim) {
        if (handed <= 0) {
            return new Observed(false, "this wish never handed him a " + c.what()
                    + " — item_in_inventory counts what THIS wish gave him (give_items, the "
                    + "make_item give command, a kit), wherever he keeps it");
        }
        int n = Math.min(withHim, handed);
        String saw = n + "× " + c.what() + " with him (hotbar, inventory or Wish Bag), of the "
                + handed + " this wish handed over";
        return new Observed(n >= c.min(), saw + (n >= c.min() ? ""
                : n < handed ? "; the rest are gone — dropped, used up or thrown away; the claim needs "
                        + c.min() : "; the claim needs " + c.min()));
    }

    /**
     * A follower: set to follow, and with him or coming to him. Being near him is not enough on its
     * own — a statue next to the child is not following him — and being set to follow is not enough
     * either: Spikor was "set" by addressing a llama.
     *
     * @param found          a creature by that name exists near him at all
     * @param setToFollow    one of them has its follow behaviour on
     * @param distStart      the nearest one's distance to him when the window opened
     * @param distEnd        …and when it closed
     */
    public static Observed follows(String what, boolean found, boolean setToFollow,
            double distStart, double distEnd) {
        if (!found) {
            return new Observed(false, "no " + what + " anywhere near him");
        }
        if (!setToFollow) {
            return new Observed(false, "the " + what + " near him is not set to follow him "
                    + "(modify_mob follow:true on THAT creature)");
        }
        boolean with = distEnd <= FOLLOW_NEAR;
        boolean closing = distEnd < distStart - FOLLOW_CLOSING;
        String saw = String.format(Locale.ROOT, "the %s was %.0f blocks from him, then %.0f", what,
                distStart, distEnd);
        return new Observed(with || closing, saw + (with || closing ? "" : " — it is not coming to him"));
    }

    /** Alive, and no health lost while watched. */
    public static Observed staysHealthy(String what, boolean found, boolean aliveAtEnd,
            float healthStart, float healthEnd) {
        if (!found) {
            return new Observed(false, "no " + what + " to watch — is it in the world?");
        }
        if (!aliveAtEnd) {
            return new Observed(false, "the " + what + " died while I watched");
        }
        boolean held = healthEnd >= healthStart - 0.01f;
        return new Observed(held, String.format(Locale.ROOT, "the %s's health went %.1f → %.1f",
                what, healthStart, healthEnd) + (held ? "" : " — something is hurting it"));
    }

    /** One contradicted claim, for the refusal. */
    public record Contradiction(String claim, ClaimCheck check, Observed seen) {
    }

    /**
     * What the agent is told when the world contradicts a claim it marked as working. Names each
     * claim and what was actually seen, because "verify your work" is advice and "you said 2 mobs are
     * on the court and I counted 0 there" is an instruction.
     */
    public static String refusal(List<Contradiction> wrong) {
        StringBuilder sb = new StringBuilder("Not yet — you said these work, and the world says "
                + "otherwise, so the child has NOT been told anything:");
        for (Contradiction c : wrong) {
            sb.append("\n  - \"").append(c.claim()).append("\": checked ").append(c.check().describe())
              .append(" → ").append(c.seen().saw());
        }
        return sb.append("\nFix it so it is true (then call finish_building again), or say "
                + "works:false for it — he is told either way, and an honest 'this bit isn't "
                + "working yet' beats a tick he disproves in ten seconds. If you call finish_building "
                + "again with it still untrue, it is shown to him as NOT working.").toString();
    }
}
