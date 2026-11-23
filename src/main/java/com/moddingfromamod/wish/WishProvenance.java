package com.moddingfromamod.wish;

import java.util.Locale;

/**
 * <b>Where a corpus case came from — and therefore what it may be used to argue.</b>
 *
 * <p>Every case in this corpus is a sentence a child might type, and they are not equally good
 * evidence. Six were typed by an actual seven-year-old. A hundred and sixty-one were read off Battle
 * of Lord's commit history, which is months of features he asked for but is still an adult's
 * reconstruction. The rest — the majority — were written by whoever was building a subsystem that
 * week, to exercise it.
 *
 * <p>Conflating them produces a specific and seductive mistake: <b>ranking what to build next by
 * counting corpus cases.</b> Do that and the class with the most fixtures wins, which means whoever
 * generated the most cases last week decides what matters this week. The machines corpus alone
 * holds 117 generated cases against 4 inferred wishes for machines — counted naively it out-votes
 * every real demand signal in the file, and it would be arguing for the subsystem that produced it.
 *
 * <p>So provenance is recorded per case, and {@code tools/rank-wishes.py} will not print a ranking
 * without saying which categories it counted.
 */
public enum WishProvenance {

    /**
     * A child typed this, verbatim, misspellings included.
     *
     * <p>The only category that is evidence of demand on its own, and by far the smallest. Treat
     * every one as precious: it is the only thing here not decided by an adult.
     */
    WISHED,

    /**
     * Read off a mod repo: features a child asked for over months, recovered as wishes.
     *
     * <p>Good evidence, not direct evidence. A mod repo built for a child is hundreds of commits of
     * things he wanted, so a class that is large here very likely matters — but it was reconstructed by an
     * adult reading a git log, and the phrasing is ours, not his.
     */
    INFERRED,

    /**
     * Written to exercise a subsystem — axis sweeps, cap probes, deliberately broken designs.
     *
     * <p>Essential for coverage and <b>worthless as demand</b>. These exist because someone was
     * building that subsystem, which is a fact about our attention rather than about his.
     */
    GENERATED,

    /**
     * Not a wish at all: something the agent really built, kept for calibrating a gate.
     *
     * <p>Recorded separately because it is evidence pointing the other way — output rather than
     * request — and counting it as demand would be a category error.
     */
    OBSERVED_OUTPUT;

    /** Whether this case may be counted when asking "what should we build next?". */
    public boolean isDemandEvidence() {
        return this == WISHED || this == INFERRED;
    }

    public static WishProvenance parse(String s) {
        if (s != null) {
            for (WishProvenance p : values()) {
                if (p.name().equalsIgnoreCase(s.trim().replace('-', '_'))) {
                    return p;
                }
            }
        }
        return null;
    }

    /**
     * Work provenance out of a case's free-text {@code source}.
     *
     * <p>The fallback for cases written before the field existed. Deliberately conservative: an
     * unrecognised source becomes {@link #GENERATED}, because mistaking a fixture for demand skews
     * a roadmap while the opposite merely under-counts.
     */
    public static WishProvenance fromSource(String source) {
        String s = source == null ? "" : source.toLowerCase(Locale.ROOT);
        if (s.contains("real play") || s.contains("verbatim child wish")) {
            return WISHED;
        }
        if (s.contains("real agent output")) {
            return OBSERVED_OUTPUT;
        }
        return GENERATED;
    }
}
