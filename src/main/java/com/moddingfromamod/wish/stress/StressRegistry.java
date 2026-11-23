package com.moddingfromamod.wish.stress;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.moddingfromamod.wish.WishCorpus;

/**
 * <b>Which harness stresses which kind — and the guard that makes the corpus honest.</b>
 *
 * <p>The keystone of the stress standard. Everything else in the standard decays quietly
 * without {@link #unclaimed}: a corpus that skips cases while reporting green is worse than no
 * corpus, because it converts an absence of testing into an appearance of testing. That is the
 * same disease as the Create GameTest that skip-succeeded for four days while a broken oracle
 * shipped behind it — and the first version of this very corpus reintroduced it, ignoring
 * 141 of its own 160 cases.
 *
 * <p>So: a kind is stressed if and only if a harness claims it, and a case whose kind nobody claims
 * <b>fails the build by name</b>.
 */
public final class StressRegistry {

    /**
     * Kinds that legitimately execute nothing.
     *
     * <p>Two, and both are demand evidence rather than tests — carried in the corpus so they cannot
     * be forgotten, and named here rather than left as an implicit skip, so that "we do not test
     * this" is a decision on the record.
     *
     * <ul>
     *   <li>{@code unsupported} — wishes the engine cannot express yet (see
     *       {@link WishCorpus#unsupported}).</li>
     *   <li>{@code demand} — sentences a child actually typed, mined from the action log. They have
     *       no spec and nothing to materialise: their entire job is to be counted when someone asks
     *       what to build next. Running them is not merely unnecessary, it is impossible, and
     *       inventing a spec for one would turn the only direct evidence in this corpus into
     *       somebody's guess.</li>
     * </ul>
     */
    public static final List<String> EXEMPT = List.of("unsupported", "demand");

    private static final Map<String, StressHarness> HARNESSES = new LinkedHashMap<>();

    static {
        register(new BuildingsHarness());
        register(new CreaturesHarness());
        register(new CompositeHarness());
        register(new DatapacksHarness());
        register(new PicturesHarness());
        register(new PlacementHarness());
        register(new GroundHarness());
    }

    private StressRegistry() {
    }

    private static void register(StressHarness h) {
        HARNESSES.put(h.kind(), h);
    }

    public static StressHarness forKind(String kind) {
        return HARNESSES.get(kind);
    }

    public static List<String> claimedKinds() {
        return List.copyOf(HARNESSES.keySet());
    }

    /**
     * Cases nobody will run, as human sentences. Empty means the corpus is honest.
     *
     * <p>Reported per KIND rather than per case, because the fix is per kind — one missing harness
     * silences every case of that kind at once, which is exactly how 22 cases went quiet.
     */
    public static List<String> unclaimed(List<WishCorpus.Case> all) {
        Map<String, Integer> orphans = new LinkedHashMap<>();
        for (WishCorpus.Case c : all) {
            String kind = c.kind();
            if (EXEMPT.contains(kind) || HARNESSES.containsKey(kind)) {
                continue;
            }
            orphans.merge(kind, 1, Integer::sum);
        }
        List<String> out = new ArrayList<>();
        orphans.forEach((kind, n) -> out.add(n + " case(s) of kind '" + kind + "' have no "
                + "StressHarness, so they load and are never run. A corpus that skips cases while "
                + "reporting green is worse than no corpus. Implement a StressHarness for '" + kind
                + "' and register it, or move those cases to kind 'unsupported' if the engine "
                + "genuinely cannot express them."));
        return out;
    }

    /** How many cases each claimed kind will actually run — for the run report. */
    public static Map<String, Integer> coverage(List<WishCorpus.Case> all) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (String kind : HARNESSES.keySet()) {
            counts.put(kind, (int) all.stream().filter(c -> kind.equals(c.kind())).count());
        }
        return counts;
    }
}
