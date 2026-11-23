package com.moddingfromamod.marshal;

import java.util.List;

/**
 * <b>One thing the child owns, as the world index sees it</b> — and,, nothing else.
 *
 * <p>This class was the resolution LADDER: seven rungs (named, aimed, in flight, sticky, property,
 * nearest, only one) that decided what "it" meant before the agent had read a word, announced the
 * pick to the child and told the agent not to argue. Live it was 4 picks and 4 wrong — every one a
 * wish for a NEW item bound to a creature he owned — and {@code docs/RESOLVER_PLAN.md}'s own gate
 * (disagreement over 35%) said to drop the inferred rungs. The owner dropped all of them
 * (2026-10-04): the agent names the referent in {@code plan_wish}'s {@code about}, from his words
 * plus the facts {@link ReferentFacts} renders, and asks with {@code ask_which} when a wrong guess
 * would be irreversible.
 *
 * <p>The name is kept because {@link Candidate} is what {@code ArtifactIndex} has always built and
 * a dozen callers read; renaming it would be churn with no reader.
 */
public final class WishLadder {

    /** How long the thing he last talked about counts as "recent" for worker affinity. */
    public static final long STICKY_MS = 90_000L;

    /** One thing the child owns, where it is, and whether it is in his view. */
    public record Candidate(String id, String kind, String name, List<String> aliases,
                            double distance, boolean inFlight, long lastTouchedMs,
                            boolean inView, String ownerId) {

        public Candidate {
            aliases = aliases == null ? List.of() : List.copyOf(aliases);
            ownerId = ArtifactOwner.normalise(ownerId);
        }

        /** A candidate nobody owns — the pre-HD-7 shape, and every shared artifact. */
        public Candidate(String id, String kind, String name, List<String> aliases,
                         double distance, boolean inFlight, long lastTouchedMs, boolean inView) {
            this(id, kind, name, aliases, distance, inFlight, lastTouchedMs, inView, "");
        }
    }

    private WishLadder() {
    }

    /**
     * <b>How a thing of his is said out loud</b> — {@code your ice dragon}.
     *
     * <p>Used by the plan line and the progress bar once the AGENT has named what a wish is about
     * — never before. Never empty for a real thing.
     */
    public static String narrate(Candidate pick) {
        if (pick == null) {
            return "";
        }
        String name = pick.name() == null || pick.name().isBlank()
                ? pick.id().replace('_', ' ') : pick.name();
        return "your " + name;
    }
}
