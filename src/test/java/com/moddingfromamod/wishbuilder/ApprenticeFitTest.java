package com.moddingfromamod.wishbuilder;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.moddingfromamod.ui.PickPage;
import org.junit.jupiter.api.Test;

/**
 * #68: the Apprentice's ranked makes are fitted AFTER ranking and outside the matcher. Order is kept, a dropped make is
 * gone, a locked one is shown locked, the layer's picks stay with their offers, and with no world nothing changes.
 */
class ApprenticeFitTest {

    private static Apprentice.Pick pick(String from) {
        return new Apprentice.Pick("creature", "ready", Map.of("from", List.of(from)));
    }

    private static Apprentice.Answer answer() {
        List<Apprentice.Pick> offers = List.of(pick("species/cat"), pick("species/wolf"), pick("species/trex"),
                pick("species/frog"));
        return new Apprentice.Answer(offers, Apprentice.Reach.values()[0], List.of("purple"),
                List.of(List.of(), List.of("powers"), List.of(), List.of("temper")));
    }

    @Test
    void withNoWorldTheAnswerIsExactlyTheRankedOne() {
        Apprentice.Answer a = answer();
        ApprenticeFit.Shown s = ApprenticeFit.fit(a, null);
        assertSame(a, s.answer(), "no world: the matcher's own answer, untouched");
        assertTrue(s.locked().isEmpty());
        PickPage page = PickPage.of("t", List.of(new PickPage.Tile("offer0", "minecraft:stone", "Cat", List.of(), false)), false);
        assertSame(page, ApprenticeFit.page(page, s.locked()), "and the page as it is drawn today");
    }

    @Test
    void hiddenAndUnfittableAreDroppedLockedIsShownAndTheOrderIsKept() {
        List<String> asked = new ArrayList<>();
        ApprenticeFit.Shown s = ApprenticeFit.fit(answer(), p -> {
            asked.add(p.first("from"));
            return switch (p.first("from")) {
                case "species/wolf" -> BuilderPageFit.Judged.FIT;
                case "species/trex" -> BuilderPageFit.Judged.DROP;
                case "species/frog" -> new BuilderPageFit.Judged(BuilderPageFit.Seen.LOCK, "🔒 Later!");
                default -> BuilderPageFit.Judged.KEEP;
            };
        });
        assertEquals(List.of("species/cat", "species/wolf", "species/trex", "species/frog"), asked,
                "every ranked make is asked, in the matcher's order");
        List<String> kept = new ArrayList<>();
        s.answer().offers().forEach(o -> kept.add(o.first("from")));
        assertEquals(List.of("species/cat", "species/wolf", "species/frog"), kept, "the T-Rex is dropped, the rest kept in order");
        assertEquals(List.of(List.of(), List.of("powers"), List.of("temper")), s.answer().layer(),
                "the layer's picks stay with their offers");
        assertEquals(List.of("purple"), s.answer().unmatched());
        assertEquals(Map.of(2, "🔒 Later!"), s.locked(), "the frog, now the third tile, is locked");

        List<PickPage.Tile> tiles = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            tiles.add(new PickPage.Tile(ApprenticeWords.OFFER + i, "minecraft:stone", "T" + i, List.of("lore"), false));
        }
        tiles.add(new PickPage.Tile(ApprenticeWords.NONE, "minecraft:barrier", "None", List.of(), false));
        PickPage page = ApprenticeFit.page(PickPage.of("t", tiles, false), s.locked());
        assertEquals("T0", page.tiles().get(0).label());
        assertEquals("T2 🔒", page.tiles().get(2).label());
        assertEquals(List.of("🔒 Later!", "lore"), page.tiles().get(2).lore(), "its line first, under its name");
        assertEquals(PickPage.BLOCKED_ITEM, page.tiles().get(2).item());
        assertEquals("None", page.tiles().get(3).label());
    }
}
