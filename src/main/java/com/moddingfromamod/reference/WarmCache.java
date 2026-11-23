package com.moddingfromamod.reference;

import java.nio.file.Path;

/**
 * Resolve one character's reference into the cache, from the command line.
 *
 * <pre>./gradlew warmReference -Pcharacter="Spikor"</pre>
 *
 * <p>Exists so {@code tools/warm-reference-cache.sh} can pre-resolve every named creature through
 * <b>the mod's own researcher</b> rather than a second implementation. A separate lookup script
 * would drift from the one the game actually uses — different prompt, different trust ranking,
 * different cache format — and the eval would then be warmed with references the run never sees.
 *
 * <p>Exits non-zero when nothing usable was found, so the caller can report it rather than assume
 * a silent success. "No canonical design" is a legitimate answer and is cached as one; it is
 * distinct from a failed lookup.
 *
 * <h2>Why this WAITS, when nothing else does</h2>
 *
 * <p>{@link ReferenceResearcher#MAX_WAIT_SECONDS} is <b>zero</b>: a child must never sit through a
 * 90-second fetch, so {@code lookUp} returns immediately and lets the fetch finish into the cache
 * for next time. That is right in the game and <b>fatal here</b> — this process exits the moment
 * main returns, killing the very fetch it was started to perform.
 *
 * <p>It was: warming seven named creatures reported <b>"unresolved: 6"</b> and wrote nothing, while
 * looking for all the world like it had run. The tool whose entire job is to guarantee a warm cache
 * before an eval guaranteed nothing, and an eval run on that cache measures creatures built with no
 * reference — which looks exactly like creatures built with a bad one.
 *
 * <p>So this one blocks on the file landing. The zero-wait rule protects a seven-year-old watching a
 * ticker; it has no business governing a batch script nobody is waiting on.
 */
public final class WarmCache {

    /**
     * How long to wait for one lookup. Five live fetches measured 75, 90, 97, 109 and 109 seconds —
     * that is the whole distribution, not a tail — so a cap much under three minutes would fail on
     * the ordinary case and be indistinguishable from a character with no canonical design.
     */
    private static final int WAIT_SECONDS = 180;

    private WarmCache() {
    }

    public static void main(String[] args) {
        if (args.length == 0 || args[0].isBlank()) {
            System.err.println("usage: WarmCache <character name>");
            System.exit(2);
        }
        String dir = System.getenv("MFAM_REFERENCE_CACHE");
        if (dir != null && !dir.isBlank()) {
            ReferenceResearcher.cacheDir(Path.of(dir));
        }
        String character = String.join(" ", args);
        // Start it, then WAIT — see the class note. lookUp alone returns in milliseconds and the
        // JVM then exits out from under the fetch.
        ReferenceResearcher.prefetch(character);
        ReferenceSpec spec = ReferenceResearcher.awaitCached(character, WAIT_SECONDS);

        System.out.println(character + " -> " + spec.colours().size() + " colour(s), confidence "
                + spec.confidence() + ", " + spec.sources().size() + " source(s)");
        spec.sources().forEach(s -> System.out.println("    " + s));

        // An empty spec with NONE confidence is a real answer for a meme with no canonical design.
        // An empty spec with no sources at all is a failed lookup. Only the second is an error.
        if (spec.isEmpty() && spec.sources().isEmpty()
                && spec.confidence() == ReferenceSpec.Confidence.NONE) {
            System.exit(1);
        }
    }
}
