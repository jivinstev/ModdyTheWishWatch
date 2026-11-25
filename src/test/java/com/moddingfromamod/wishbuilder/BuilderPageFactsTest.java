package com.moddingfromamod.wishbuilder;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayDeque;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

import com.moddingfromamod.ui.PickPage;
import org.junit.jupiter.api.Test;

/**
 * <b>The page facts are worked out in the order a player meets the pages</b> (#14): breadth first from the first page,
 * so the first page and every kind's first page are ready before anything deeper; and a page a player opens that is not
 * ready yet is served next, ahead of the rest of the walk, so "getting ready" lasts a moment.
 */
class BuilderPageFactsTest {

    private static BuilderFlow root() {
        return new BuilderFlow(BuilderAllowlist.REVIEWED, LibraryPresets.SHIPPED);
    }

    private static Set<String> firstLevel() {
        Set<String> out = new LinkedHashSet<>();
        BuilderFlow r = root();
        for (PickPage.Tile t : r.page().tiles()) {
            BuilderFlow k = r.after(t.id());
            if (k != null && k.step() != BuilderFlow.Step.TUNE) {
                out.add(BuilderPageFacts.stateKey(k));
            }
        }
        return out;
    }

    @Test
    void theFirstPageAndEveryKindsFirstPageAreWorkedOutFirst() {
        BuilderPageFacts.Walk w = new BuilderPageFacts.Walk(new BuilderPageFacts.Index("order"));
        Set<String> first = firstLevel();
        BuilderPageFacts.walk(w, BuilderAllowlist.REVIEWED, LibraryPresets.SHIPPED, null, first.size() + 3);
        List<String> order = w.index.order();
        assertEquals(BuilderPageFacts.stateKey(root()), order.get(0), "the first page is the first worked out");
        assertTrue(first.size() >= 5, "only " + first.size() + " kinds");
        assertEquals(first, new LinkedHashSet<>(order.subList(1, 1 + first.size())),
                "every kind's first page comes before any page deeper in one");
    }

    @Test
    void aPageAPlayerOpensIsServedNextAheadOfTheWalk() {
        BuilderPageFacts.Walk w = new BuilderPageFacts.Walk(new BuilderPageFacts.Index("asked"));
        // a page the walk would reach late, if ever: a cat with a part picked, still on its parts page
        BuilderFlow deep = root();
        deep.press("creature");
        deep.press("species/cat");
        String partTile = deep.page().tiles().get(0).id();
        deep.press(partTile);
        String asked = BuilderPageFacts.stateKey(deep);
        AtomicBoolean ready = new AtomicBoolean();
        ArrayDeque<BuilderPageFacts.Need> needs = new ArrayDeque<>();
        needs.add(new BuilderPageFacts.Need(BuilderAllowlist.REVIEWED, deep.snapshot(), () -> ready.set(true)));
        BuilderPageFacts.walk(w, BuilderAllowlist.REVIEWED, LibraryPresets.SHIPPED,
                () -> BuilderPageFacts.drain(w, needs), 6);
        List<String> order = w.index.order();
        assertEquals(asked, order.get(1), "served right after the page being worked out when it was asked for: " + order);
        assertTrue(ready.get(), "and its page was told it is ready");
        assertTrue(w.index.page(asked) != null);
    }
}
