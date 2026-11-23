package com.moddingfromamod.paint;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.moddingfromamod.runtime.CreatureSculpt;
import org.junit.jupiter.api.Test;

/** whose picture a paint_surface call may change, and how a refinement builds on it. */
class PictureEditTest {

    private static final String ALICE = "aaaaaaaa-0000-4000-8000-000000000001";
    private static final String BOB = "bbbbbbbb-0000-4000-8000-000000000002";

    private static PaintRecipe rects(int n) {
        List<CreatureSculpt.PaintRect> out = new java.util.ArrayList<>();
        for (int i = 0; i < n; i++) {
            out.add(new CreatureSculpt.PaintRect(i, i, 8, 8, 0xFF00FF00));
        }
        return new PaintRecipe(64, out);
    }

    private static final class FakeStore implements PictureEdit.Lookup {
        final Map<String, PictureStore.Picture> m = new HashMap<>();
        final Set<String> library = new java.util.HashSet<>();

        FakeStore with(String id, String owner, int n) {
            m.put(id, new PictureStore.Picture(id, owner, rects(n), 3, 2));
            return this;
        }

        @Override
        public PictureStore.Picture get(String id) {
            return m.get(id);
        }

        @Override
        public boolean inLibrary(String id) {
            return library.contains(id);
        }
    }

    @Test
    void aNewPictureIsHisAndHangsWhereHeSaid() {
        var p = PictureEdit.plan(ALICE, "sun", "", false, rects(2), 0, 0, new FakeStore());
        assertFalse(p.refused());
        assertEquals("sun", p.targetId());
        assertEquals(ALICE, p.ownerId());
        assertFalse(p.freshBoard());
    }

    @Test
    void startFromKeepsEveryRectAndLaysTheChangeOnTop() {
        var store = new FakeStore().with("sun", ALICE, 5);
        var p = PictureEdit.plan(ALICE, "sun", "sun", false, rects(1), 0, 0, store);
        assertEquals(6, p.recipe().rects().size(), "five kept, one added — not a redraw");
        assertEquals("sun", p.targetId(), "his own picture is edited in place");
        assertEquals(3, p.wide(), "the span is inherited");
        assertFalse(p.freshBoard());
    }

    @Test
    void hisBrothersPictureIsForkedOntoItsOwnBoardNeverPaintedOver() {
        var store = new FakeStore().with("sun", ALICE, 5);
        var p = PictureEdit.plan(BOB, "sun", "sun", false, rects(1), 0, 0, store);
        assertEquals("sun~bbbbbbbb", p.targetId(), "Bob gets his own copy");
        assertEquals(BOB, p.ownerId());
        assertTrue(p.freshBoard(), "on a NEW board — Alice's picture is never repainted");
        assertEquals(6, p.recipe().rects().size());
    }

    @Test
    void reusingHisBrothersIdIsAnEditTooAndForks() {
        var store = new FakeStore().with("sun", ALICE, 5);
        var p = PictureEdit.plan(BOB, "sun", "", false, rects(2), 0, 0, store);
        assertEquals("sun~bbbbbbbb", p.targetId(),
                "the path the brother's repaint used to take must fork as well");
        assertTrue(p.freshBoard());
    }

    @Test
    void hisSecondRefinementEditsHisForkWhereItHangs() {
        var store = new FakeStore().with("sun", ALICE, 5).with("sun~bbbbbbbb", BOB, 6);
        var p = PictureEdit.plan(BOB, "sun", "sun", false, rects(1), 0, 0, store);
        assertEquals("sun~bbbbbbbb", p.targetId());
        assertFalse(p.freshBoard(), "no third board");
        assertEquals(7, p.recipe().rects().size(), "built on HIS copy, not on Alice's");
    }

    @Test
    void aLibraryPictureIsABaseAndForks() {
        var store = new FakeStore().with("starter_creeper_face", "", 64);
        store.library.add("starter_creeper_face");
        var p = PictureEdit.plan(ALICE, "starter_creeper_face", "starter_creeper_face", false,
                rects(1), 0, 0, store);
        assertEquals("starter_creeper_face~aaaaaaaa", p.targetId());
        assertTrue(p.freshBoard());
        assertEquals(65, p.recipe().rects().size());
    }

    @Test
    void aLibraryPictureWithNobodyToForkForIsRefused() {
        var store = new FakeStore().with("starter_creeper_face", "", 64);
        store.library.add("starter_creeper_face");
        assertTrue(PictureEdit.plan("", "starter_creeper_face", "starter_creeper_face", false,
                rects(1), 0, 0, store).refused());
    }

    @Test
    void asNewMakesASecondPictureOnItsOwnBoard() {
        var store = new FakeStore().with("sun", ALICE, 5);
        var p = PictureEdit.plan(ALICE, "sun_at_night", "sun", true, rects(1), 0, 0, store);
        assertEquals("sun_at_night", p.targetId());
        assertTrue(p.freshBoard(), "never on top of the first");
        assertEquals(6, p.recipe().rects().size());
        assertTrue(PictureEdit.plan(ALICE, "sun", "sun", true, rects(1), 0, 0, store).refused(),
                "as_new under the same id is not a second picture");
        assertTrue(PictureEdit.plan(ALICE, "x", "", true, rects(1), 0, 0, store).refused(),
                "as_new with nothing to copy");
    }

    @Test
    void anUnknownStartFromIsRefusedNeverRedrawn() {
        assertTrue(PictureEdit.plan(ALICE, "sun", "moon", false, rects(1), 0, 0, new FakeStore())
                .refused());
    }

    @Test
    void aChangeDrawnOnAnotherCanvasSizeLandsInTheSamePlace() {
        PaintRecipe base = new PaintRecipe(64, List.of());
        PaintRecipe change = new PaintRecipe(256, List.of(
                new CreatureSculpt.PaintRect(128, 128, 64, 64, 0xFFFFFF00)));
        var r = PictureEdit.layer(base, change).rects().get(0);
        assertEquals(32, r.x());
        assertEquals(16, r.width());
    }

    @Test
    void anEditAtTheCapKeepsTheEditAndDropsTheOldestShapes() {
        PaintRecipe base = rects(PaintRecipe.MAX_RECTS);
        PaintRecipe change = new PaintRecipe(64, List.of(
                new CreatureSculpt.PaintRect(1, 2, 3, 4, 0xFFFF0000)));
        List<CreatureSculpt.PaintRect> got = PictureEdit.layer(base, change).rects();
        assertEquals(PaintRecipe.MAX_RECTS, got.size());
        assertEquals(0xFFFF0000, got.get(got.size() - 1).colour(),
                "the shape he just asked for is the one kept on top");
        assertEquals(1, got.get(0).x(), "the oldest (x=0) went, the next oldest stays");
    }
}
