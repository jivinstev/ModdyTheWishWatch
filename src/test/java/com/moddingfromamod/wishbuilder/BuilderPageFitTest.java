package com.moddingfromamod.wishbuilder;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.moddingfromamod.ui.PickPage;
import com.moddingfromamod.ui.TuneDialog;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * <b>The Builder's pages under a world's limits</b> (#14): the facts of every tile are worked out once
 * ({@link BuilderPageFacts}), and a draw is a comparison that derives nothing. A tile that cannot be fitted is dropped,
 * a kind with nothing allowed left is culled, a locked make is grey with its words, a page not worked out yet is drawn
 * "getting ready", never unfitted, the tune dialog stops at the limit, and with no world answering nothing is done at
 * all. The work and its time are measured.
 */
class BuilderPageFitTest {

    private static BuilderPageFacts.Index index;
    private static long buildNanos;

    @BeforeAll
    static void build() {
        long t0 = System.nanoTime();
        index = BuilderPageFacts.buildNow(BuilderAllowlist.REVIEWED, LibraryPresets.SHIPPED, 120);
        buildNanos = System.nanoTime() - t0;
    }

    private static BuilderFlow root() {
        return new BuilderFlow(BuilderAllowlist.REVIEWED, LibraryPresets.SHIPPED);
    }

    /** A world that keeps everything, within {@code limits}. */
    static BuilderPageFit.World limited(Map<String, Double> limits) {
        return f -> new BuilderPageFit.Judged(BuilderPageFit.Seen.KEEP, "", limits);
    }

    // ── every make the menus reach (MakeFitTest's property test walks these) ─────────────────

    private static final int DEPTH = 9;
    private static final int CAP = 4000;

    static List<BuilderSpec> leaves() {
        List<BuilderSpec> out = new ArrayList<>();
        walk(root(), DEPTH, new HashSet<>(), out);
        return out;
    }

    static void walk(BuilderFlow flow, int depth, Set<String> seen, List<BuilderSpec> out) {
        if (out.size() >= CAP) {
            return;
        }
        if (flow.step() == BuilderFlow.Step.TUNE) {
            BuilderSpec d = flow.draft();
            if (d != null) {
                out.add(d);
            }
            return;
        }
        if (depth <= 0 || !seen.add(BuilderPageFacts.stateKey(flow))) {
            return;
        }
        PickPage page;
        try {
            page = flow.page();
        } catch (RuntimeException e) {
            return;
        }
        boolean multi = page.picks() != null;
        if (multi) {
            BuilderFlow on = flow.after(PickPage.NEXT_ID);
            if (on != null) {
                walk(on, depth - 1, seen, out);
            }
        }
        for (PickPage.Tile t : page.tiles()) {
            BuilderFlow next = flow.after(t.id());
            if (next != null && multi) {
                next = next.after(PickPage.NEXT_ID);
            }
            if (next != null) {
                walk(next, depth - 1, seen, out);
            }
        }
    }

    // ── the tests ─────────────────────────────────────────────────────────────────────────────

    @Test
    void theTreeFingerprintIsTheOneTheApprenticeRecords() {
        BuilderTree tree = BuilderTree.of(BuilderAllowlist.REVIEWED, LibraryPresets.SHIPPED);
        assertEquals(ApprenticeTreeFingerprintDiffTest.fingerprint(tree.toJson()).get("hash").getAsString(), tree.fingerprint());
        assertEquals(tree.fingerprint(), index.fingerprint());
    }

    @Test
    void aCopyPagesAndSpecsAsItsOriginalAndAPressNeverMovesIt() {
        BuilderFlow f = root();
        f.press("creature");
        String before = f.page().toString();
        BuilderFlow c = f.after("species/cat");
        assertTrue(c != null && c.step() != f.step(), "the copy moved on");
        assertEquals(before, f.page().toString(), "the original did not");
        BuilderFlow g = root();
        g.press("creature");
        g.press("species/cat");
        assertEquals(g.page().toString(), c.page().toString());
        assertEquals(g.draft(), c.draft());
    }

    @Test
    void withNoWorldAnsweringNothingIsDoneAtAll() {
        BuilderFlow f = root();
        f.press("creature");
        PickPage page = f.page();
        long derived = MakeFacts.derivations();
        assertSame(page, BuilderPageFit.draw(f, page, null, null), "no world: the page as it is");
        assertSame(page, BuilderPageFit.draw(f, page, index, null), "no world: the page as it is, index or not");
        assertEquals(derived, MakeFacts.derivations(), "and no make was read");
        assertFalse(BuilderPageFacts.started(), "no world asked: the background worker was never started");
    }

    @Test
    void aDrawAfterWarmUpDerivesNothingAndIsTimed() {
        BuilderPageFit.World w = limited(Map.of(MakeFacts.COUNT_MADE, 3.0, MakeFacts.BUILDER_SIZE_STEP, 3.0));
        BuilderFlow kinds = root();
        BuilderFlow bodies = root();
        bodies.press("creature");
        PickPage kindPage = kinds.page();
        PickPage bodyPage = bodies.page();
        BuilderPageFit.draw(kinds, kindPage, index, w);   // warm the classes
        BuilderPageFit.draw(bodies, bodyPage, index, w);
        long derived = MakeFacts.derivations();
        long t0 = System.nanoTime();
        PickPage k = BuilderPageFit.draw(kinds, kindPage, index, w);
        long kindNanos = System.nanoTime() - t0;
        t0 = System.nanoTime();
        PickPage b = BuilderPageFit.draw(bodies, bodyPage, index, w);
        long bodyNanos = System.nanoTime() - t0;
        assertEquals(derived, MakeFacts.derivations(), "a draw reads no make: every tile's facts were worked out before");
        assertFalse(BuilderPageFit.gettingReady(k) || BuilderPageFit.gettingReady(b), "both pages are worked out");
        System.out.printf("[BuilderPageFit timing] worked out once: %d pages in %.1f s; a draw: kind page %.2f ms, "
                        + "creature body page (%d tiles) %.2f ms%n", index.size(), buildNanos / 1e9, kindNanos / 1e6,
                bodyPage.tiles().size(), bodyNanos / 1e6);
    }

    @Test
    void aKindWithNothingAllowedLeftIsCulledAndTheRestStay() {
        BuilderFlow f = root();
        PickPage kinds = f.page();
        // nothing may have a size: every creature, ride, building and event has one; supplies and items do not
        PickPage seen = BuilderPageFit.draw(f, kinds, index, limited(Map.of(MakeFacts.BUILDER_SIZE_STEP, 0.0)));
        List<String> ids = seen.tiles().stream().map(PickPage.Tile::id).toList();
        assertFalse(ids.contains("creature"), ids.toString());
        assertTrue(ids.contains("supplies"), ids.toString());
        assertSame(kinds, BuilderPageFit.draw(f, kinds, index, limited(Map.of())), "no limits: the same page");
    }

    @Test
    void aLockedMakeIsGreyWithItsWordsAndAFittableOneIsKept() {
        BuilderFlow f = root();
        f.press("creature");
        PickPage from = f.page();
        BuilderPageFit.World lockBig = facts -> facts.number(MakeFacts.SCALE_TIMES).orElse(0) > 0
                ? new BuilderPageFit.Judged(BuilderPageFit.Seen.LOCK, "Unlocks later") : BuilderPageFit.Judged.KEEP;
        PickPage seen = BuilderPageFit.draw(f, from, index, lockBig);
        PickPage.Tile cat = seen.tiles().stream().filter(t -> t.id().equals("species/cat")).findFirst().orElseThrow();
        assertEquals(PickPage.BLOCKED_ITEM, cat.item());
        assertEquals("Unlocks later", cat.lore().get(0));
        assertEquals(from.tiles().size(), seen.tiles().size(), "a locked tile is shown, not dropped");
        assertEquals(from.tiles().size(), BuilderPageFit.draw(f, from, index,
                limited(Map.of(MakeFacts.BUILDER_SIZE_STEP, 1.0))).tiles().size(), "every body can be made smaller");
    }

    @Test
    void aPageNotWorkedOutYetIsDrawnGettingReadyNeverUnfitted() {
        BuilderFlow f = root();
        f.press("creature");
        f.press("species/cat");
        PickPage page = f.page();
        BuilderPageFacts.Index empty = new BuilderPageFacts.Index("none");
        PickPage seen = BuilderPageFit.draw(f, page, empty, limited(Map.of(MakeFacts.COUNT_MADE, 3.0)));
        assertTrue(BuilderPageFit.gettingReady(seen));
        assertTrue(seen.tiles().stream().allMatch(t -> t.selected() || t.item().equals(PickPage.BLOCKED_ITEM)));
    }

    @Test
    void theTuneDialogStopsAtTheLimit() {
        BuilderFlow f = root();
        f.press("creature");
        f.press("species/cat");
        while (f.step() != BuilderFlow.Step.TUNE) {
            f.press(PickPage.NEXT_ID);
        }
        TuneDialog d = f.tune();
        long derived = MakeFacts.derivations();
        TuneDialog seen = BuilderPageFit.tune(f, d, Map.of(MakeFacts.COUNT_MADE, 3.0));
        assertEquals(derived, MakeFacts.derivations(), "a limit on how many is compared with the slider, no make read");
        TuneDialog.Slider count = (TuneDialog.Slider) seen.inputs().stream().filter(i -> i.key().equals("count"))
                .findFirst().orElseThrow();
        assertEquals(3, count.end());
        TuneDialog.Choice size = (TuneDialog.Choice) BuilderPageFit.tune(f, d, Map.of(MakeFacts.SCALE_TIMES, 1.0))
                .inputs().stream().filter(i -> i.key().equals("mobsize")).findFirst().orElseThrow();
        TuneDialog.Choice all = (TuneDialog.Choice) d.inputs().stream().filter(i -> i.key().equals("mobsize")).findFirst().orElseThrow();
        assertTrue(size.options().size() < all.options().size() && !size.options().isEmpty(), size.options().toString());
    }

    @Test
    void theIndexHoldsTheFirstPagesOfEveryKind() {
        BuilderFlow f = root();
        assertTrue(index.page(BuilderPageFacts.stateKey(f)) != null, "the kind page");
        for (PickPage.Tile t : f.page().tiles()) {
            BuilderFlow k = f.after(t.id());
            if (k != null && k.step() != BuilderFlow.Step.TUNE) {
                assertTrue(index.page(BuilderPageFacts.stateKey(k)) != null, "the first page of " + t.id());
            }
        }
    }
}
