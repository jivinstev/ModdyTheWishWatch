package com.moddingfromamod.reference;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * <b>Guess, from a child's wish, whether a real character is being asked for — and start looking it
 * up before anyone asks.</b>
 *
 * <p>Purely an optimisation, and deliberately allowed to be wrong in both directions. A false
 * positive costs one background lookup nobody reads. A false negative costs nothing at all: the
 * agent's own {@code look_up_character} call still works, it just waits.
 *
 * <p>So this is not a classifier and must never gate anything. It exists because a live lookup takes
 * 75-109 seconds and the only way to hide that is to start it early.
 */
public final class ReferenceHint {

    private ReferenceHint() {
    }

    /** Words that begin a sentence or are ordinary nouns, not character names. */
    private static final Set<String> NOISE = Set.of(
            "make", "create", "build", "give", "can", "you", "please", "the", "a", "an", "me",
            "my", "and", "with", "that", "this", "who", "which", "he", "she", "it", "they",
            "now", "then", "also", "but", "for", "his", "her", "its", "big", "huge", "tiny",
            "new", "cool", "some", "invent", "summon", "spawn", "add", "put", "i", "want",
            "wish", "like", "looks", "look", "called", "named", "super", "really");

    /**
     * Candidate character names in a wish, best first.
     *
     * <p>Two signals, both cheap: a run of Capitalised Words that is not sentence-initial noise,
     * and a name introduced by "called" or "named". Anything shorter than three characters is
     * dropped, as is anything entirely in the noise list.
     */
    public static List<String> candidates(String wish) {
        List<String> out = new ArrayList<>();
        if (wish == null || wish.isBlank()) {
            return out;
        }
        // "called Grumbnar" / "named Sparky"
        var m = java.util.regex.Pattern
                .compile("\\b(?:called|named)\\s+([A-Za-z][\\w'-]*(?:\\s+[A-Z][\\w'-]*)*)")
                .matcher(wish);
        while (m.find()) {
            add(out, m.group(1));
        }
        // Runs of Capitalised Words, or SHOUTED words — children shout the important one.
        //
        // Split on sentence and clause boundaries FIRST. Without that the matcher ran straight
        // through "...Fun Meals! make Tralalelo Tralalala" and produced the candidate
        // "Fun Mealsmake Tralalelo Tralalala", and "Make SPIKOR" kept its leading verb. Both were
        // then fetched, cached under nonsense keys, and never matched anything the agent asked for.
        for (String clause : wish.split("[.!?,;:\n]+")) {
            var caps = java.util.regex.Pattern
                    .compile("\\b([A-Z][\\w'-]+(?:\\s+[A-Z][\\w'-]+)*)").matcher(clause);
            while (caps.find()) {
                add(out, stripLeadingNoise(caps.group(1)));
            }
        }
        return out;
    }

    /** Drop leading verbs a child starts with: "Make SPIKOR" is a wish for Spikor. */
    static String stripNoise(String s) {
        return stripLeadingNoise(s);
    }

    private static String stripLeadingNoise(String s) {
        String[] w = s.split("\\s+");
        int i = 0;
        while (i < w.length - 1 && NOISE.contains(w[i].toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z']", ""))) {
            i++;
        }
        return String.join(" ", java.util.Arrays.copyOfRange(w, i, w.length));
    }

    private static void add(List<String> out, String raw) {
        String s = raw.trim();
        if (s.length() < 3) {
            return;
        }
        // Drop it if every word is noise ("Make", "Can You").
        boolean allNoise = true;
        for (String w : s.toLowerCase(Locale.ROOT).split("\\s+")) {
            if (!NOISE.contains(w.replaceAll("[^a-z']", ""))) {
                allNoise = false;
                break;
            }
        }
        if (!allNoise && !out.contains(s)) {
            out.add(s);
        }
    }

    /**
     * Start the background lookup for this wish, if it names a real character.
     *
     * <p>Goes through {@link CharacterResolver} rather than the pattern matching above, so the key
     * written here is <b>the same key</b> {@code look_up_character} will read. Two independent
     * guesses at one string agree only by luck, and a live run proved it: prefetched
     * {@code Spikormake} and {@code Kroaks}, neither of which anything would ever ask for.
     */
    /**
     * @return the background work, so a test can wait for it to finish rather than leave it
     *         writing into a temp directory JUnit is deleting (that race failed a Gate A).
     *         The submission path ignores it, which is the point.
     */
    public static java.util.concurrent.CompletableFuture<Void> prefetchFrom(String wish) {
        // FULLY ASYNCHRONOUS, INCLUDING THE RESOLVE.
        //
        // This is called from WishService.submit, on the server thread, the instant a child presses
        // enter. CharacterResolver spawns a model process and waits up to 15 seconds -- doing that
        // here would freeze the game for up to fifteen seconds on every wish, before the wish was
        // even acknowledged. The whole point of this path is that nothing waits, and it was the one
        // place still waiting.
        return java.util.concurrent.CompletableFuture.runAsync(() -> {
            try {
                CharacterResolver.resolveSubject(wish)
                        // A PERSON is painted from his Minecraft skin, never researched: the
                        // researcher's pictures of a real person are photographs.
                        .filter(r -> r.kind() != SubjectKind.PERSON)
                        .ifPresent(r -> ReferenceResearcher.prefetch(r.name(), r.kind()));
            } catch (RuntimeException e) {
                // A failed prefetch costs a slower first lookup, never a broken wish.
            }
        });
    }
}
