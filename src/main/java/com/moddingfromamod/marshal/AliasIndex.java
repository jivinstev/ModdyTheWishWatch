package com.moddingfromamod.marshal;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Rung 0 of the resolution ladder — <b>the text names it</b>.
 *
 * <p>Every rung of the ladder is pure Java: no model call, no round trip, no latency on the
 * submission path. This is the first and cheapest, and the one that has to be right, because
 * everything above it costs either a question or a model.
 *
 * <h2>What an artifact answers to</h2>
 * Not just its id. The strong model, at the moment it creates Spikor, knows perfectly well that he
 * is King Kroak — so the artifact records the names it answers to, authored at creation as one more
 * field on a tool call already being made. Then <i>"give King Kroak fire breath"</i> resolves here,
 * at zero cost, at wish time.
 *
 * <p>That is the ladder principle applied exactly: push the check from a post-hoc classification
 * down to an <b>input at generation</b>, where the defect never happens instead of being detected
 * later. It also steps around the trap {@code CharacterResolver} fell into twice — <i>never let a
 * model's own knowledge gate access to knowledge it lacks</i>. A small model asked at wish time may
 * not know King Kroak is Spikor, and certainly will not know that "the wobbly one" is Steve's Zizzle.
 * The strong model at creation time knows both, because it just made the thing.
 *
 * <h2>The rule that matters more than the matching</h2>
 * <b>A near miss must not silently pick one.</b> With both "Spikor" and "Spikor Jr" present, a fuzzy
 * match that quietly chooses is a coin flip in front of a child. The margin rule applies to string
 * distance exactly as it does to proximity: an exact hit always wins, and an inexact one only wins
 * when it is <i>strictly closer</i> than every other candidate.
 *
 * <p>Pure — no Minecraft — so every misspelling is a table-driven unit test.
 */
public final class AliasIndex {

    /** One thing the child owns, and everything he might call it. */
    public record Artifact(String id, String kind, String name, List<String> aliases) {

        public Artifact {
            aliases = aliases == null ? List.of() : List.copyOf(aliases);
        }

        /** Every string this thing answers to, normalised, longest first. */
        public List<String> allNames() {
            Set<String> out = new LinkedHashSet<>();
            for (String s : new String[]{name, id}) {
                String n = KidTyping.normalise(s);
                if (!n.isEmpty()) {
                    out.add(n);
                }
            }
            for (String a : aliases) {
                String n = KidTyping.normalise(a);
                if (!n.isEmpty()) {
                    out.add(n);
                }
            }
            List<String> list = new ArrayList<>(out);
            // Longest first, so "spikor jr" is tried before "spikor" and a specific name is never
            // swallowed by a shorter one it happens to contain.
            list.sort((x, y) -> Integer.compare(y.length(), x.length()));
            return list;
        }
    }

    /** What rung 0 concluded. */
    public enum Verdict {
        /** The text named exactly one thing. */
        RESOLVED,
        /** It named several equally well — do not guess; this goes up the ladder or becomes a question. */
        AMBIGUOUS,
        /** Nothing here is what he meant. */
        NONE
    }

    /** @param how "exact", "alias" or "fuzzy" — so the pick can be narrated and the log audited */
    public record Result(Verdict verdict, Artifact artifact, String matched, String how,
                         List<Artifact> candidates) {

        public static Result none() {
            return new Result(Verdict.NONE, null, "", "", List.of());
        }

        public boolean resolved() {
            return verdict == Verdict.RESOLVED;
        }
    }

    private final List<Artifact> artifacts = new ArrayList<>();

    public AliasIndex add(Artifact artifact) {
        if (artifact != null) {
            artifacts.add(artifact);
        }
        return this;
    }

    public List<Artifact> artifacts() {
        return List.copyOf(artifacts);
    }

    /**
     * Does this wish name one of his things?
     *
     * <p>Three passes, and the order is the whole design:
     * <ol>
     *   <li><b>exact</b> — a name or alias appears in the sentence as whole words. An exact hit ends
     *       it; no amount of fuzzy cleverness may override something he actually typed.</li>
     *   <li><b>squashed</b> — the same, ignoring spaces, so "bigbun" finds "big bun".</li>
     *   <li><b>fuzzy</b> — a single word in the sentence is a near miss of a name, and is
     *       <i>strictly closer</i> to it than to anything else.</li>
     * </ol>
     */
    public Result resolve(String wishText) {
        String text = KidTyping.normalise(wishText);
        if (text.isEmpty() || artifacts.isEmpty()) {
            return Result.none();
        }

        Map<Artifact, String> exact = new LinkedHashMap<>();
        for (Artifact a : artifacts) {
            for (String name : a.allNames()) {
                if (containsWholePhrase(text, name)) {
                    exact.putIfAbsent(a, name);
                    break;
                }
            }
        }
        if (exact.size() == 1) {
            var only = exact.entrySet().iterator().next();
            return new Result(Verdict.RESOLVED, only.getKey(), only.getValue(), "exact",
                    List.of(only.getKey()));
        }
        if (exact.size() > 1) {
            // "make spikor jr bigger" matches BOTH "spikor jr" and "spikor", because one name
            // contains the other. That is not ambiguity — he typed the longer one, and it is the
            // more specific thing. Without this, owning a Spikor Jr makes every Spikor Jr wish
            // unanswerable, which gets worse the more things he names after each other.
            Artifact longest = null;
            String longestName = "";
            for (var e : exact.entrySet()) {
                if (e.getValue().length() > longestName.length()) {
                    longestName = e.getValue();
                    longest = e.getKey();
                }
            }
            boolean allContained = true;
            for (String other : exact.values()) {
                if (!other.equals(longestName) && !longestName.contains(other)) {
                    allContained = false;
                    break;
                }
            }
            if (allContained) {
                return new Result(Verdict.RESOLVED, longest, longestName, "exact", List.of(longest));
            }
            // Genuinely two different things named: he may mean both ("make Spikor fight Dinko"),
            // which is a two-artifact wish rather than a failure. Rung 0 says so; it does not pick.
            return new Result(Verdict.AMBIGUOUS, null, "", "exact", new ArrayList<>(exact.keySet()));
        }

        String squashed = KidTyping.squash(wishText);
        Map<Artifact, String> squashedHits = new LinkedHashMap<>();
        for (Artifact a : artifacts) {
            for (String name : a.allNames()) {
                String s = KidTyping.squash(name);
                if (s.length() >= 4 && squashed.contains(s)) {
                    squashedHits.putIfAbsent(a, name);
                    break;
                }
            }
        }
        if (squashedHits.size() == 1) {
            var only = squashedHits.entrySet().iterator().next();
            return new Result(Verdict.RESOLVED, only.getKey(), only.getValue(), "alias",
                    List.of(only.getKey()));
        }
        if (squashedHits.size() > 1) {
            return new Result(Verdict.AMBIGUOUS, null, "", "alias",
                    new ArrayList<>(squashedHits.keySet()));
        }

        return fuzzy(text);
    }

    /**
     * A misspelling — but only when it is unambiguously a misspelling of ONE thing.
     *
     * <p>A word closer to the <i>wrong</i> artifact than the right one must ask rather than guess,
     * and a word equally close to two must never quietly choose. Both fall out of requiring a
     * strictly smaller distance than every rival.
     */
    private Result fuzzy(String text) {
        Artifact best = null;
        String bestName = "";
        int bestDistance = Integer.MAX_VALUE;
        boolean tied = false;
        List<Artifact> near = new ArrayList<>();

        for (String word : text.split(" ")) {
            if (word.length() < 4) {
                continue;   // too short to misspell distinguishably; "cat" and "bat" differ by one
            }
            for (Artifact a : artifacts) {
                for (String name : a.allNames()) {
                    String candidate = KidTyping.squash(name);
                    int budget = KidTyping.budgetFor(candidate);
                    if (budget == 0) {
                        continue;
                    }
                    int d = KidTyping.distance(word, candidate, budget);
                    if (d > budget) {
                        continue;
                    }
                    if (!near.contains(a)) {
                        near.add(a);
                    }
                    if (d < bestDistance) {
                        bestDistance = d;
                        best = a;
                        bestName = name;
                        tied = false;
                    } else if (d == bestDistance && best != null && !best.equals(a)) {
                        tied = true;
                    }
                }
            }
        }
        if (best == null) {
            return Result.none();
        }
        if (tied) {
            // "Spikor" and "Spikor Jr" both one edit away. Guessing here is a coin flip in front of
            // a child, which is exactly what the margin rule exists to forbid.
            return new Result(Verdict.AMBIGUOUS, null, "", "fuzzy", near);
        }
        return new Result(Verdict.RESOLVED, best, bestName, "fuzzy", List.of(best));
    }

    /** Whole-word phrase containment, so "cod" never matches inside "cocoa". */
    static boolean containsWholePhrase(String haystack, String needle) {
        if (needle.isEmpty()) {
            return false;
        }
        int from = 0;
        while (true) {
            int at = haystack.indexOf(needle, from);
            if (at < 0) {
                return false;
            }
            boolean leftOk = at == 0 || haystack.charAt(at - 1) == ' ';
            int end = at + needle.length();
            boolean rightOk = end == haystack.length() || haystack.charAt(end) == ' ';
            if (leftOk && rightOk) {
                return true;
            }
            from = at + 1;
        }
    }
}
