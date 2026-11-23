package com.moddingfromamod.testing;

import java.util.List;

import com.moddingfromamod.species.Species;
import com.moddingfromamod.species.SpeciesRegistry;
import net.minecraft.gametest.framework.GameTestHelper;

/**
 * <b>A creature a GameTest makes is gone when that test ends</b> — passed, failed or timed out.
 *
 * <p>The species registry is one process-wide map with a 500-creature cap ({@code SpeciesRegistry.put} refuses a NEW
 * creature past it, and says so: "species registry full"). A full Gate B runs ~1,400 tests in one server, and every
 * creature a test {@code put} and never took back stayed for the rest of the run — so the tests late in the run met a
 * full registry and every creature they made was refused, which reads downstream as a creature that "has no species"
 * (2026-11-15,). {@code SourceRuleSweepTest} fails a GameTest that calls {@code SpeciesRegistry.put(}
 * itself, so a new test starts here.
 *
 * <ul>
 *   <li>{@link #put}: in memory only, never saved, outside the cap ({@link SpeciesRegistry#putForTest}) — for every
 *       test that needs a creature to EXIST.</li>
 *   <li>{@link #putSaved}: the real {@code put} — the save, the shelf, the owner stamp — for a test that is ABOUT them;
 *       the creature is taken back out (and saved again) when the test ends.</li>
 * </ul>
 */
public final class SpeciesForTest {
    /** The ids each running test has put, so its cleanup restores what was there BEFORE the test. */
    private static final java.util.Map<Object, java.util.Set<String>> SEEN =
            java.util.Collections.synchronizedMap(new java.util.IdentityHashMap<>());

    private SpeciesForTest() {
    }

    /** {@code s} exists, in memory only, until this test ends. Returns what is registered (self-harm guarded). */
    public static Species put(GameTestHelper helper, Species s) {
        if (s == null) {
            return null;
        }
        Runnable undo = SpeciesRegistry.putForTest(List.of(s));
        if (first(helper, s.id())) {
            MockPlayerNetwork.afterTest(helper, undo);   // a later put of the same id keeps the FIRST undo: the original
        }
        return SpeciesRegistry.get(s.id());
    }

    /**
     * {@code s} through the REAL {@code SpeciesRegistry.put} — saved, stamped, shelved — and taken back out when this
     * test ends. Returns what {@code put} returned ({@code null} if it refused).
     */
    public static Species putSaved(GameTestHelper helper, Species s) {
        if (s == null) {
            return null;
        }
        var server = helper.getLevel().getServer();
        // What was there when the TEST began, once per id: a test that puts one creature twice (an edit) gets the
        // original back, not its own first version.
        if (first(helper, s.id())) {
            Species before = SpeciesRegistry.get(s.id());
            // Registered BEFORE the put, so a put that throws is still undone.
            MockPlayerNetwork.afterTest(helper, () -> SpeciesRegistry.restoreForTest(server, s.id(), before));
        }
        return SpeciesRegistry.put(server, s);
    }

    /** Is this the first time THIS test has put {@code id}? The first registers the cleanup; later ones ride on it. */
    private static boolean first(GameTestHelper helper, String id) {
        java.util.Set<String> seen = SEEN.computeIfAbsent(helper.testInfo, k -> {
            MockPlayerNetwork.afterTest(helper, () -> SEEN.remove(k));
            return java.util.concurrent.ConcurrentHashMap.newKeySet();
        });
        return seen.add(id);
    }
}
