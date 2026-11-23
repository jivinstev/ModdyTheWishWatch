package com.moddingfromamod.testing.ledger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import com.moddingfromamod.testing.ledger.fixture.FixCheckA;
import com.moddingfromamod.testing.ledger.fixture.FixCheckB;
import com.moddingfromamod.testing.ledger.fixture.FixWalk;
import com.moddingfromamod.testing.ledger.fixture.FixWalkEdited;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * <b>The fingerprint ledger's acceptance criteria</b> (phase 2), on fixtures: two checks (one runs
 * {@link FixWalk}, one does not), variants with files of their own, a "commit" that edits a preset, a commit that edits
 * {@code FixWalk} (its bytes swapped for {@link FixWalkEdited}'s), and a stale pass.
 */
class GateLedgerTest {

    /** The working tree, in memory: preset files and one the checks do not declare. */
    final Map<String, byte[]> tree = new TreeMap<>();
    /** Class files served under another class's name: an edit, without recompiling. */
    final Map<String, String> edits = new HashMap<>();

    GateLedgerTest() {
        tree.put("presets/a/build.json", bytes("{\"a\":1}"));
        tree.put("presets/b/build.json", bytes("{\"b\":1}"));
        tree.put("presets/c/build.json", bytes("{\"c\":1}"));
        tree.put("shared/sizes.json", bytes("{}"));
        tree.put("undeclared/data.txt", bytes("1"));
    }

    static byte[] bytes(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    final GateLedger.Tree files = new GateLedger.Tree() {
        @Override
        public List<String> list(String path) {
            return tree.keySet().stream().filter(f -> GateLedger.under(f, path)).toList();
        }

        @Override
        public byte[] read(String file) {
            return tree.get(file);
        }
    };

    CodeReach reach() {
        Function<String, byte[]> classes = name -> {
            if (!name.startsWith(CodeReach.OURS)) {
                return null;
            }
            String served = edits.getOrDefault(name, name);
            try (InputStream in = GateLedgerTest.class.getClassLoader().getResourceAsStream(served + ".class")) {
                return in == null ? null : in.readAllBytes();
            } catch (IOException e) {
                throw new IllegalStateException(e);
            }
        };
        return new CodeReach(classes);
    }

    static String internal(Class<?> c) {
        return c.getName().replace('.', '/');
    }

    Map<String, GateLedger.Pass> ledger = new HashMap<>();
    final List<JsonObject> pending = new ArrayList<>();
    final AtomicLong now = new AtomicLong();

    GateLedger open(boolean on, GateLedger.Reverify reverify, long budgetNanos) {
        pending.clear();
        return GateLedger.forTest(ledger, reach(), files, on, reverify, budgetNanos, "2026-11-15", pending,
                commit -> List.of("presets/a/build.json", "undeclared/data.txt", "src/test/java/" + internal(FixWalk.class) + ".java"), now::get);
    }

    /** One "Gate A run" of both fixture checks over presets a, b, c; returns the variants that RAN, as check/variant. */
    List<String> run(GateLedger g, Function<String, List<String>> problems, List<String> stale) {
        List<String> ran = new ArrayList<>();
        for (Class<?> c : List.of(FixCheckA.class, FixCheckB.class)) {
            GateLedger.Check check = g.check(c.getSimpleName() + "#check", internal(c), Set.of("check"), List.of("shared"), List.of("presets"));
            for (String p : List.of("a", "b", "c")) {
                GateLedger.Variant v = check.variant(p, "presets/" + p);
                if (v.skip()) {
                    continue;
                }
                now.addAndGet(1_000_000_000L);   // each variant "takes" a second
                ran.add(c.getSimpleName() + "/" + p);
                v.done(problems.apply(c.getSimpleName() + "/" + p));
            }
            stale.addAll(check.finish());
        }
        return ran;
    }

    List<String> runAll(GateLedger g) {
        List<String> stale = new ArrayList<>();
        List<String> ran = run(g, k -> List.of(), stale);
        assertEquals(List.of(), stale);
        return ran;
    }

    void fold() {
        ledger = GateLedger.folded(ledger, pending);
    }

    static final List<String> ALL = List.of("FixCheckA/a", "FixCheckA/b", "FixCheckA/c", "FixCheckB/a", "FixCheckB/b", "FixCheckB/c");

    @Test
    void anEmptyLedgerRunsEverythingAndAWarmOneSkipsEverything() {
        assertEquals(ALL, runAll(open(true, GateLedger.Reverify.NONE, 0)));
        assertEquals(6, pending.size(), "every pass is recorded");
        fold();
        assertEquals(List.of(), runAll(open(true, GateLedger.Reverify.NONE, 0)), "an untouched variant is skipped");
    }

    @Test
    void editingOnePresetReTestsOnlyIt() {
        runAll(open(true, GateLedger.Reverify.NONE, 0));
        fold();
        tree.put("presets/b/build.json", bytes("{\"b\":2}"));
        assertEquals(List.of("FixCheckA/b", "FixCheckB/b"), runAll(open(true, GateLedger.Reverify.NONE, 0)));
    }

    @Test
    void editingASharedInputReTestsEveryVariant() {
        runAll(open(true, GateLedger.Reverify.NONE, 0));
        fold();
        tree.put("shared/sizes.json", bytes("{\"x\":1}"));
        assertEquals(ALL, runAll(open(true, GateLedger.Reverify.NONE, 0)));
    }

    @Test
    void editingADependedOnClassReTestsEveryVariantThatRunsItAndNothingElse() {
        runAll(open(true, GateLedger.Reverify.NONE, 0));
        fold();
        edits.put(internal(FixWalk.class), internal(FixWalkEdited.class));
        assertEquals(List.of("FixCheckA/a", "FixCheckA/b", "FixCheckA/c"), runAll(open(true, GateLedger.Reverify.NONE, 0)),
                "FixCheckA runs FixWalk; FixCheckB does not, so it stays skipped");
    }

    @Test
    void theReachFollowsCallsNotNames() {
        CodeReach r = reach();
        assertTrue(r.from(internal(FixCheckA.class), Set.of("check")).classes().contains(internal(FixWalk.class)));
        assertFalse(r.from(internal(FixCheckB.class), Set.of("check")).classes().contains(internal(FixWalk.class)));
        // a comment or a blank line is not what a method does: only the instructions (resolved) are hashed
        String before = r.from(internal(FixCheckA.class), Set.of("check")).digest(r);
        edits.put(internal(FixWalk.class), internal(FixWalkEdited.class));
        CodeReach after = reach();
        assertNotEquals(before, after.from(internal(FixCheckA.class), Set.of("check")).digest(after));
    }

    @Test
    void theLedgerOffOrUnreadableRunsEverything(@TempDir Path dir) throws IOException {
        runAll(open(true, GateLedger.Reverify.NONE, 0));
        fold();
        assertEquals(ALL, runAll(open(false, GateLedger.Reverify.NONE, 0)), "-Pgate.ledger=off");
        Path bad = dir.resolve("gate-a-ledger.jsonl");
        Files.writeString(bad, "{ not json\n[1, 2]\n");
        GateLedger.Loaded l = GateLedger.load(bad);
        assertTrue(l.passes().isEmpty());
        assertNotNull(l.unreadable());
        assertNotNull(GateLedger.load(dir.resolve("absent.json")).unreadable(), "a missing ledger is empty, and says so");
        ledger = l.passes();
        assertEquals(ALL, runAll(open(true, GateLedger.Reverify.NONE, 0)));
    }

    @Test
    void aStalePassCaughtOnReVerificationFailsLoudlyAndNamesWhatTheFingerprintMissed() {
        runAll(open(true, GateLedger.Reverify.NONE, 0));
        fold();
        // the checks' verdict now depends on a file they never declared: the fingerprints are unchanged, so a normal run skips
        tree.put("undeclared/data.txt", bytes("2"));
        Function<String, List<String>> broken = k -> k.equals("FixCheckA/a") ? List.of("room 3 cannot be reached") : List.of();
        List<String> stale = new ArrayList<>();
        assertEquals(List.of(), run(open(true, GateLedger.Reverify.NONE, 0), broken, stale), "an incomplete fingerprint hides it...");
        assertEquals(List.of(), stale);
        // ...until a release re-verifies every cached pass
        assertEquals(ALL, run(open(true, GateLedger.Reverify.ALL, 0), broken, stale));
        assertEquals(1, stale.size(), stale.toString());
        String s = stale.get(0);
        assertTrue(s.startsWith("STALE PASS"), s);
        assertTrue(s.contains("FixCheckA#check / a") && s.contains("room 3 cannot be reached"), s);
        assertTrue(s.contains("undeclared/data.txt"), "names the changed file the fingerprint did not hash: " + s);
        assertFalse(s.contains("presets/a/build.json, "), "and not the ones it did: " + s);
        assertFalse(s.contains("FixWalk.java"), "a reached class's source is hashed: " + s);
    }

    @Test
    void factsThatMoveOnReVerificationAreAStalePassToo() {
        GateLedger g = open(true, GateLedger.Reverify.NONE, 0);
        GateLedger.Check c = g.check("FixCheckA#check", internal(FixCheckA.class), Set.of("check"), List.of(), List.of());
        GateLedger.Variant v = c.variant("a", "presets/a");
        assertFalse(v.skip());
        v.done(List.of(), List.of("lamps+flag meet"));
        fold();
        GateLedger.Check again = open(true, GateLedger.Reverify.NONE, 0).check("FixCheckA#check", internal(FixCheckA.class), Set.of("check"),
                List.of(), List.of());
        GateLedger.Variant cached = again.variant("a", "presets/a");
        assertTrue(cached.skip());
        assertEquals(List.of("lamps+flag meet"), cached.facts(), "a skipped variant replays what it measured");
        GateLedger.Check re = open(true, GateLedger.Reverify.ALL, 0).check("FixCheckA#check", internal(FixCheckA.class), Set.of("check"),
                List.of(), List.of());
        GateLedger.Variant rv = re.variant("a", "presets/a");
        assertFalse(rv.skip());
        rv.done(List.of(), List.of());
        assertEquals(1, re.finish().size());
    }

    @Test
    void theNightlySliceRotatesAndKeepsToItsBudget() {
        // many variants, so the rotation has something to choose from
        for (int i = 0; i < 70; i++) {
            tree.put("presets/p" + i + "/build.json", bytes("{\"i\":" + i + "}"));
        }
        GateLedger g = open(true, GateLedger.Reverify.NONE, 0);
        GateLedger.Check c = g.check("FixCheckA#check", internal(FixCheckA.class), Set.of("check"), List.of(), List.of());
        for (int i = 0; i < 70; i++) {
            GateLedger.Variant v = c.variant("p" + i, "presets/p" + i);
            v.skip();
            v.done(List.of());
        }
        fold();
        GateLedger.Check slice = open(true, GateLedger.Reverify.SLICE, 1_000_000_000_000L).check("FixCheckA#check", internal(FixCheckA.class),
                Set.of("check"), List.of(), List.of());
        int due = 0;
        for (int i = 0; i < 70; i++) {
            GateLedger.Variant v = slice.variant("p" + i, "presets/p" + i);
            if (!v.skip()) {
                due++;
                now.addAndGet(1_000_000_000L);
                v.done(List.of());
            }
        }
        assertTrue(due > 0 && due < 30, "about one in " + GateLedger.ROTATION + " re-verified tonight: " + due);
        // with a budget of 2.5 s and a second a variant, it stops after three
        GateLedger.Check tight = open(true, GateLedger.Reverify.SLICE, 2_500_000_000L).check("FixCheckA#check", internal(FixCheckA.class),
                Set.of("check"), List.of(), List.of());
        int ranTight = 0;
        for (int i = 0; i < 70; i++) {
            GateLedger.Variant v = tight.variant("p" + i, "presets/p" + i);
            if (!v.skip()) {
                ranTight++;
                now.addAndGet(1_000_000_000L);
                v.done(List.of());
            }
        }
        assertEquals(Math.min(3, due), ranTight, "the slice keeps to its budget");
    }
}
