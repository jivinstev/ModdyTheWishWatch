package com.moddingfromamod.wishbuilder;

import java.util.List;

/**
 * <b>What reads a clause the keywords could not</b>: the trained layer over a frozen sentence model, or, in
 * a gate, its recorded answers. Given one clause of a wish ("that spits flames") it answers with the Builder picks
 * the clause most likely means, best first. {@link Apprentice#match(String, ClauseReader, double)} decides what to
 * do with the answer; a reader only reads.
 *
 * <p>A reader must be safe to call from any thread and must never keep, log or send the clause anywhere
 * (docs/CHILD_SAFETY.md: a Crafter's words are matched and dropped).
 */
@FunctionalInterface
public interface ClauseReader {

    /** {@code "<slot>=<entry id>"} for a pick, or this for "nothing the Builder offers". */
    String NONE = "none";

    /** One answer: a pick's key (or {@link #NONE}) and how sure the layer is, 0 to 1. */
    record Guess(String key, double prob) {
    }

    /** Best first; at most {@link Apprentice#LAYER_READS} are looked at. Empty reads as {@link #NONE}. */
    List<Guess> read(String clause);
}
