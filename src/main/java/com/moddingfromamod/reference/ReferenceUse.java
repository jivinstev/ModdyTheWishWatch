package com.moddingfromamod.reference;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * <b>Did this creature actually get a reference?</b>
 *
 * <p>. When the walled researcher is switched on for an eval arm, some lookups will fail —
 * a timeout, a rate limit, a character with no canonical design. Those creatures get built from the
 * agent's own recall, exactly as in the control arm.
 *
 * <p>That turns one experiment into two mixed together, and it does it <b>silently</b>: a creature
 * built without a reference is indistinguishable, from the artifact alone, from one built with a
 * bad reference. Averaging across the two produces a number that describes neither.
 *
 * <p>So every lookup records what actually happened, and the comparison can split on it. A run
 * where only four of seven named creatures got a reference is still useful — as long as nobody
 * averages it.
 *
 * <p>In-memory and per-session on purpose: this is eval bookkeeping, not game state, and the
 * harness copies what it needs into the run's index at capture time.
 */
public final class ReferenceUse {

    private static final Map<String, Record> BY_CHARACTER = new ConcurrentHashMap<>();

    private ReferenceUse() {
    }

    /** What happened for one character. */
    public record Record(boolean lookedUp, boolean usable, boolean cacheHit,
                         List<String> sources, long waitedMs) {

        public Record(boolean lookedUp, boolean usable, boolean cacheHit, List<String> sources) {
            this(lookedUp, usable, cacheHit, sources, 0L);
        }

        public Record {
            sources = List.copyOf(sources == null ? List.of() : sources);
        }
    }

    /** Nothing was attempted — the control arm, or a tier that has nothing to look up. */
    public static final Record NONE = new Record(false, false, false, List.of(), 0L);

    /** The key, exposed so a test can prove two spellings of one character agree. */
    static String keyForTest(String character) {
        return key(character);
    }

    private static String key(String character) {
        return character == null ? "" : character.toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]+", "_").replaceAll("^_|_$", "");
    }

    /** Called by the researcher after every lookup, successful or not. */
    public static void record(String character, ReferenceSpec spec, boolean cacheHit) {
        record(character, spec, cacheHit, 0L);
    }

    /**
     * @param waitedMs how long the CHILD waited. The number that decides whether the whole
     *                 reference feature is shippable: a live fetch is 75-109 seconds, and the only
     *                 reason it is tolerable is that prefetching turns this into ~0.
     */
    public static void record(String character, ReferenceSpec spec, boolean cacheHit,
                              long waitedMs) {
        boolean usable = spec != null && !spec.isEmpty();
        BY_CHARACTER.put(key(character),
                new Record(true, usable, cacheHit,
                        spec == null ? List.of() : spec.sources(), waitedMs));
    }

    /** The worst wait any child endured this session, in milliseconds. */
    public static long worstWaitMs() {
        return BY_CHARACTER.values().stream().mapToLong(Record::waitedMs).max().orElse(0L);
    }

    /**
     * What happened for this character. Returns {@link #NONE} when no lookup was attempted, which
     * is a meaningful answer and not a missing value — it is what the control arm looks like.
     */
    public static Record forCharacter(String character) {
        return BY_CHARACTER.getOrDefault(key(character), NONE);
    }

    /** Between runs in the same JVM. */
    public static void clear() {
        BY_CHARACTER.clear();
    }

    /** How many lookups were attempted, and how many produced something usable. */
    public static int attempted() {
        return BY_CHARACTER.size();
    }

    public static int usable() {
        return (int) BY_CHARACTER.values().stream().filter(Record::usable).count();
    }
}
