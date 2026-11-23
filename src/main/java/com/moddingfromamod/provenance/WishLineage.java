package com.moddingfromamod.provenance;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Reconstruct the TRUE chain of wishes behind an artifact, when the recorded one forked.
 *
 * <p><b>The bug this exists for.</b> {@link ProvenancePublisher} keys a chain on {@code (kind, id)}
 * and the id is the <i>agent's</i> choice. When a retry renames the artifact the chain forks, and
 * every attempt records as a first-time grant with zero refinements:
 *
 * <pre>
 *   chucko_mcburger      0 refinements  "make me a chucko mcburger mob!"   (paraphrased)
 *   clown_friend         0 refinements  "You just made chucko mcburger but he looks scary!..."
 *   chucko_clown         0 refinements  (the same complaint again)
 *   chucko_mcburger_wish 0 refinements  "that chucko mcburger is terrible, kill it"
 * </pre>
 *
 * <p><b>Chucko took four attempts and records as four one-shot successes.</b> Every quality number
 * computed over this data is optimistic, in the direction that flatters the agent. It is the same
 * root cause as the creature species→corpusId mismatch: anything keyed on an id the agent chose
 * fragments the moment it renames.
 *
 * <p><b>This reports, it does not rewrite.</b> Each link carries the rule that produced it, so a
 * reconstruction can be read and disagreed with. Silently repairing history would replace an
 * optimistic number with a confident one, which is worse — the repo's standing rule is provenance
 * over heuristics, and this is openly a heuristic over data whose provenance was lost.
 *
 * <p>The proper fix is upstream (carry a {@code refines} pointer at grant time, so a retry never
 * forks in the first place). This recovers what already happened.
 *
 * <p>Pure and Minecraft-free: unit-testable with no game.
 */
public final class WishLineage {

    /** Words that carry no identity — they match everything and would chain unrelated wishes. */
    private static final Set<String> STOP = Set.of(
            "make", "made", "create", "new", "mob", "the", "and", "you", "just", "that", "this",
            "with", "for", "but", "also", "has", "have", "his", "her", "him", "she", "they", "them",
            "was", "were", "are", "get", "rid", "put", "add", "one", "two", "more", "very", "into",
            "please", "want", "wanted", "like", "looks", "look", "give", "does", "did", "can");

    /**
     * Phrasing that refers back to something that already exists. STRUCTURE, not knowledge — the
     * same rule that took the character resolver from 6/7 to 7/7: a small signal can judge how a
     * child refers to a thing without knowing anything about the thing.
     */
    private static final List<String> BACKREF = List.of(
            "you just made", "you made", "that ", "it has", "it is", "he looks", "she looks",
            "he has", "she has", "get rid of", "instead", "kill it", "remove it", "again",
            "you spawned", "i wanted", "isnt", "isn't", "doesn't", "does not", "too ");

    /** Why two wishes were linked. Reported, so a reconstruction can be argued with. */
    public enum Evidence {
        /** The later wish names the earlier artifact or its distinctive words. */
        NAMES_PRIOR,
        /** The later wish is phrased as a correction and lands close behind the earlier one. */
        BACKREFERENCE,
        /** Both: the strongest available signal short of a recorded pointer. */
        NAMES_PRIOR_AND_BACKREFERENCE
    }

    /** One reconstructed step: {@code later} is a refinement of {@code earlier}. */
    public record Link(String earlierId, String laterId, Evidence evidence, String because) {
    }

    /** The minimum a reconstruction needs to know about a recorded wish. */
    public record Entry(String id, String kind, String intent, long createdMs) {
    }

    private WishLineage() {
    }

    static Set<String> significantWords(String text) {
        Set<String> out = new LinkedHashSet<>();
        if (text == null) {
            return out;
        }
        for (String raw : text.toLowerCase(Locale.ROOT).split("[^a-z0-9]+")) {
            // 4+ characters, same threshold the corpus matcher uses: "big", "boy" and "the"
            // collide across wishes that have nothing to do with each other.
            if (raw.length() >= 4 && !STOP.contains(raw)) {
                out.add(raw);
            }
        }
        return out;
    }

    /** Does {@code later} refer to the artifact {@code earlier} produced, by name? */
    static boolean namesPrior(Entry earlier, Entry later) {
        Set<String> theirs = significantWords(later.intent());
        if (theirs.isEmpty()) {
            return false;
        }
        Set<String> mine = significantWords(earlier.id().replace('_', ' '));
        mine.addAll(significantWords(earlier.intent()));
        mine.retainAll(theirs);
        // TWO shared significant words, not one. One is a coincidence between any two creature
        // wishes ("dragon"); two is a reference.
        return mine.size() >= 2;
    }

    /** Is {@code later} phrased as a complaint about something that already exists? */
    static boolean backReferences(Entry later) {
        String t = later.intent() == null ? "" : later.intent().toLowerCase(Locale.ROOT);
        return BACKREF.stream().anyMatch(t::contains);
    }

    /**
     * Reconstruct links across recorded wishes, newest-first within {@code windowMs}.
     *
     * <p>A link needs the later wish to NAME the earlier one, or to back-reference it while landing
     * inside the window. Back-reference alone across an arbitrary gap would chain a complaint to
     * whatever happened to precede it days earlier.
     */
    public static List<Link> reconstruct(List<Entry> entries, long windowMs) {
        List<Entry> sorted = new ArrayList<>(entries);
        sorted.sort((a, b) -> Long.compare(a.createdMs(), b.createdMs()));
        List<Link> links = new ArrayList<>();

        for (int i = 1; i < sorted.size(); i++) {
            Entry later = sorted.get(i);
            for (int j = i - 1; j >= 0; j--) {
                Entry earlier = sorted.get(j);
                if (earlier.id().equals(later.id())) {
                    break;   // already one chain; the publisher handled it
                }
                boolean named = namesPrior(earlier, later);
                boolean back = backReferences(later)
                        && later.createdMs() - earlier.createdMs() <= windowMs;
                if (!named && !back) {
                    continue;
                }
                Evidence ev = named && back ? Evidence.NAMES_PRIOR_AND_BACKREFERENCE
                        : named ? Evidence.NAMES_PRIOR : Evidence.BACKREFERENCE;
                links.add(new Link(earlier.id(), later.id(), ev,
                        named ? "names the earlier artifact" : "phrased as a correction, "
                                + ((later.createdMs() - earlier.createdMs()) / 1000) + "s later"));
                break;   // nearest plausible parent only
            }
        }
        return links;
    }

    /**
     * How many attempts each artifact really took, following reconstructed links back to a root.
     * An artifact nobody refined stays at 1 — a first-time success is a real and common outcome,
     * and inflating it would swap an optimistic bias for a pessimistic one.
     */
    public static int attemptsFor(String id, List<Link> links) {
        // COUNT DISTINCT ARTIFACTS, not steps. Reconstruction is heuristic, so it can produce a
        // cycle (a -> b -> a); counting steps reported THREE attempts for two artifacts, which is
        // the pessimistic mirror of the very bug this class exists to fix.
        Set<String> chain = new LinkedHashSet<>();
        String cur = id;
        while (cur != null && chain.add(cur)) {
            cur = parentOf(cur, links);
        }
        return chain.size();
    }

    private static String parentOf(String id, List<Link> links) {
        for (Link l : links) {
            if (l.laterId().equals(id)) {
                return l.earlierId();
            }
        }
        return null;
    }
}
