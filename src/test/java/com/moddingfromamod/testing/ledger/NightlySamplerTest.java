package com.moddingfromamod.testing.ledger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * phase 2, the nightly sampler's acceptance criteria: it keeps to its time budget, writes every combination it
 * tried (with its seed) to the ledger, prefers combinations no earlier night tried, prints a failure's seed, and that
 * seed reproduces the failure exactly.
 */
class NightlySamplerTest {

    static final List<List<String>> FACTORS = List.of(List.of("cottage", "barn", "tower", "hut"), List.of("1", "2", "3"),
            List.of("flat", "pointed", "dome", "sloped"), List.of("front", "back", "left"), List.of("none", "some", "lots"),
            List.of("nothing", "pool", "flag", "pool+flag"));

    final AtomicLong now = new AtomicLong();
    final List<JsonObject> written = new ArrayList<>();

    NightlySampler sampler(String seedSpec, String day, Set<String> tried, double budget) {
        written.clear();
        return new NightlySampler("Fixture#houses", "com.example.FixtureTest", FACTORS, budget, seedSpec, day, tried, written::add, now::get);
    }

    /** Each combination "takes" 10 ms. */
    List<String> check(List<String> row, String bad) {
        now.addAndGet(10_000_000L);
        return String.join("|", row).equals(bad) ? List.of("room 2 cannot be reached") : List.of();
    }

    @Test
    void itKeepsToItsBudgetAndWritesEveryCombinationWithItsSeed() {
        NightlySampler s = sampler(null, "2026-11-15", Set.of(), 3.0);
        NightlySampler.Outcome o = s.run(r -> check(r, ""));
        assertTrue(o.seconds() <= 3.0 + 0.010 + 1e-9, "at most one combination past the budget: " + o.seconds());
        assertEquals(o.tried(), written.size(), "every combination tried is on the ledger");
        assertTrue(o.triplesComplete() && o.random() > 0, o.summary());
        assertTrue(written.stream().allMatch(r -> r.get("seed").getAsLong() == s.seed()));
        assertEquals(o.tried(), written.stream().map(r -> r.get("key").getAsString()).distinct().count(), "no combination twice");
    }

    @Test
    void aNewNightIsANewSeedAndPrefersWhatNoNightTried() {
        long a = sampler(null, "2026-11-15", Set.of(), 1).seed();
        assertNotEquals(a, sampler(null, "2026-11-16", Set.of(), 1).seed());
        NightlySampler.Outcome first = sampler(null, "2026-11-15", Set.of(), 4.0).run(r -> check(r, ""));
        Set<String> ledger = new HashSet<>();
        written.forEach(r -> ledger.add(r.get("key").getAsString()));
        NightlySampler.Outcome second = sampler(null, "2026-11-16", ledger, 4.0).run(r -> check(r, ""));
        long randomAgain = written.stream().filter(r -> r.get("index").getAsInt() >= second.triples())
                .filter(r -> ledger.contains(r.get("key").getAsString())).count();
        assertEquals(0, randomAgain, "the random half tries only what no earlier night did");
        assertTrue(first.tried() > 0 && second.random() > 0, second.summary());
    }

    @Test
    void aFailurePrintsItsSeedAndTheSeedReproducesItExactly() {
        // find tonight's sequence, then plant a failure deep in its RANDOM half
        sampler(null, "2026-11-15", Set.of(), 3.0).run(r -> check(r, ""));
        JsonObject victim = written.get(written.size() - 5);
        String bad = victim.get("key").getAsString();
        NightlySampler.Outcome o = sampler(null, "2026-11-15", Set.of(), 3.0).run(r -> check(r, bad));
        assertEquals(1, o.failures().size(), o.failures().toString());
        String f = o.failures().get(0);
        Matcher m = Pattern.compile("-Pgate\\.seed=(\\d+):(\\d+)").matcher(f);
        assertTrue(m.find() && f.contains("room 2 cannot be reached") && f.contains("seed " + o.seed()), f);
        // the printed seed and index replay exactly that combination, whatever the ledger says
        NightlySampler.Outcome replay = sampler(m.group(1) + ":" + m.group(2), "2027-01-01", Set.of(bad), 3.0).run(r -> check(r, bad));
        assertEquals(1, replay.tried(), replay.summary());
        assertEquals(bad, written.get(0).get("key").getAsString());
        assertEquals(1, replay.failures().size());
        // and the seed alone replays the night's whole sequence, on another day
        NightlySampler.Outcome night = sampler(m.group(1), "2027-01-01", Set.of(), 3.0).run(r -> check(r, bad));
        assertEquals(1, night.failures().size(), night.summary());
    }

    @Test
    void aSmallProductStopsWhenEveryCombinationIsTried() {
        NightlySampler s = new NightlySampler("Fixture#small", "x", List.of(List.of("a", "b"), List.of("1", "2"), List.of("x", "y")), 1000,
                null, "2026-11-15", Set.of(), written::add, now::get);
        NightlySampler.Outcome o = s.run(r -> check(r, ""));
        assertEquals(8, o.tried());
        assertTrue(o.exhausted(), o.summary());
    }
}
