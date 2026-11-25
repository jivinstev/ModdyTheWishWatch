package com.moddingfromamod.wishbuilder;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.moddingfromamod.ui.PickPage;
import com.moddingfromamod.ui.TuneDialog;
import org.junit.jupiter.api.Test;

/**
 * <b>The Builder's pages under a world's limits</b> (#14): a tile that cannot be fitted is dropped, a kind with nothing
 * allowed left is culled, a locked make is drawn grey with its words, a tune dialog stops at the limit, a page with no
 * world answering is the page as it is. And the walk is timed: one page, and every page of the whole menu tree.
 */
class BuilderPageFitTest {

    private static BuilderFlow root() {
        return new BuilderFlow(BuilderAllowlist.REVIEWED, LibraryPresets.SHIPPED);
    }

    /** A world's judge, made the way the core's is: fitted to {@code limits} through the allowlist's cap. */
    static BuilderPageFit.Judge judge(Map<String, Double> limits) {
        return spec -> {
            MakeFit.Fitted f = BuilderAllowlist.REVIEWED.capped(spec, limits);
            return !f.fits() ? BuilderPageFit.Judged.DROP : f.changed() ? BuilderPageFit.Judged.FIT : BuilderPageFit.Judged.KEEP;
        };
    }

    // ── the whole tree, as the menus reach it ─────────────────────────────────────────────────

    private static final int DEPTH = 9;
    private static final int CAP = 4000;

    /** Every make the menus reach (a page's tiles, a multi-pick page left as it is or with one pick), up to a cap. */
    static List<BuilderSpec> leaves() {
        List<BuilderSpec> out = new ArrayList<>();
        walk(root(), DEPTH, new HashSet<>(), out, null);
        return out;
    }

    /** The walk; with {@code onPage}, each page reached is handed to it (the timed walk under limits). */
    static void walk(BuilderFlow flow, int depth, Set<String> seen, List<BuilderSpec> out,
                     java.util.function.BiConsumer<BuilderFlow, PickPage> onPage) {
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
        BuilderSpec d = flow.draft();
        if (depth <= 0 || !seen.add(flow.step() + " " + (d == null ? "" : d.toJson().toString()))) {
            return;
        }
        PickPage page;
        try {
            page = flow.page();
        } catch (RuntimeException e) {
            return;
        }
        if (onPage != null) {
            onPage.accept(flow, page);
        }
        boolean multi = page.picks() != null;
        if (multi) {
            BuilderFlow on = flow.after(PickPage.NEXT_ID);
            if (on != null) {
                walk(on, depth - 1, seen, out, onPage);
            }
        }
        for (PickPage.Tile t : page.tiles()) {
            BuilderFlow next = flow.after(t.id());
            if (next == null) {
                continue;
            }
            if (multi) {
                next = next.after(PickPage.NEXT_ID);
                if (next == null) {
                    continue;
                }
            }
            walk(next, depth - 1, seen, out, onPage);
        }
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
    void noLimitsIsThePageAsItIs() {
        BuilderFlow f = root();
        PickPage kinds = f.page();
        assertTrue(BuilderPageFit.page(f, kinds, judge(Map.of())) == kinds, "the same page, untouched");
    }

    @Test
    void aKindWithNothingAllowedLeftIsCulledAndTheRestStay() {
        BuilderFlow f = root();
        PickPage kinds = f.page();
        // nothing may have a size: every creature, ride, building and event has one, supplies and items do not
        PickPage seen = BuilderPageFit.page(f, kinds, judge(Map.of(MakeFacts.BUILDER_SIZE_STEP, 0.0)));
        List<String> ids = seen.tiles().stream().map(PickPage.Tile::id).toList();
        assertFalse(ids.contains("creature"), ids.toString());
        assertTrue(ids.contains("supplies"), ids.toString());
        assertTrue(ids.size() < kinds.tiles().size());
    }

    @Test
    void aLockedMakeIsGreyWithItsWordsAndAFittedOneIsKept() {
        BuilderFlow f = root();
        f.press("creature");
        PickPage from = f.page();
        BuilderPageFit.Judge lockCats = spec -> spec.from().equals("preset:species/cat")
                ? new BuilderPageFit.Judged(BuilderPageFit.Seen.LOCK, "Unlocks later") : BuilderPageFit.Judged.KEEP;
        PickPage seen = BuilderPageFit.page(f, from, lockCats);
        PickPage.Tile cat = seen.tiles().stream().filter(t -> t.id().equals("species/cat")).findFirst().orElseThrow();
        assertEquals(PickPage.BLOCKED_ITEM, cat.item());
        assertEquals("Unlocks later", cat.lore().get(0));
        assertEquals(from.tiles().size(), seen.tiles().size(), "a locked tile is shown, not dropped");
        // fitting to a smaller size keeps every body: they can all be made smaller
        assertEquals(from.tiles().size(), BuilderPageFit.page(f, from, judge(Map.of(MakeFacts.BUILDER_SIZE_STEP, 1.0))).tiles().size());
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
        TuneDialog seen = BuilderPageFit.tune(f, d, judge(Map.of(MakeFacts.COUNT_MADE, 3.0)));
        TuneDialog.Slider count = (TuneDialog.Slider) seen.inputs().stream().filter(i -> i.key().equals("count"))
                .findFirst().orElseThrow();
        assertEquals(3, count.end());
        TuneDialog.Choice size = (TuneDialog.Choice) BuilderPageFit.tune(f, d, judge(Map.of(MakeFacts.SCALE_TIMES, 1.0)))
                .inputs().stream().filter(i -> i.key().equals("mobsize")).findFirst().orElseThrow();
        TuneDialog.Choice all = (TuneDialog.Choice) d.inputs().stream().filter(i -> i.key().equals("mobsize")).findFirst().orElseThrow();
        assertTrue(size.options().size() < all.options().size() && !size.options().isEmpty(), size.options().toString());
    }

    @Test
    void theWalkUnderLimitsIsTimed() {
        BuilderPageFit.Judge j = judge(Map.of(MakeFacts.COUNT_MADE, 3.0, MakeFacts.BUILDER_SIZE_STEP, 3.0));
        BuilderFlow f = root();
        PickPage kinds = f.page();
        BuilderPageFit.page(f, kinds, j);   // warm the catalogues
        long t0 = System.nanoTime();
        BuilderPageFit.page(f, kinds, j);
        long kindPage = System.nanoTime() - t0;
        BuilderFlow c = root();
        c.press("creature");
        PickPage bodies = c.page();
        t0 = System.nanoTime();
        BuilderPageFit.page(c, bodies, j);
        long bodyPage = System.nanoTime() - t0;
        int[] pages = {0};
        List<BuilderSpec> reached = new ArrayList<>();
        t0 = System.nanoTime();
        walk(root(), DEPTH, new HashSet<>(), reached, (flow, page) -> {
            pages[0]++;
            BuilderPageFit.page(flow, page, j);
        });
        long tree = System.nanoTime() - t0;
        // the same pages again, with what lies ahead remembered (the menus' bounded cache)
        String ahead = BuilderPageFit.aheadKey(BuilderAllowlist.REVIEWED, "timing-" + System.nanoTime());
        BuilderPageFit.page(c, bodies, j, ahead);
        t0 = System.nanoTime();
        BuilderPageFit.page(c, bodies, j, ahead);
        long bodyAgain = System.nanoTime() - t0;
        System.out.printf("[BuilderPageFit timing] kind page %.1f ms, creature body page (%d tiles) %.1f ms (redrawn with "
                        + "the cache %.1f ms), whole tree %d pages / %d makes %.1f s%n", kindPage / 1e6, bodies.tiles().size(),
                bodyPage / 1e6, bodyAgain / 1e6, pages[0], reached.size(), tree / 1e9);
        assertTrue(bodyAgain < bodyPage, "the cache did not help: " + bodyAgain / 1e6 + " ms");
        assertTrue(kindPage < 2_000_000_000L, "the kind page took " + kindPage / 1e6 + " ms");
    }
}
