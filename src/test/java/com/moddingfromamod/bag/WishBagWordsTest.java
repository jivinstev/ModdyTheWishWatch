package com.moddingfromamod.bag;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import com.moddingfromamod.safety.SafetyLexicon;
import org.junit.jupiter.api.Test;

/** every word the Wish Bag says, and the rule said in each place he looks. */
class WishBagWordsTest {

    @Test
    void theTitleNamesTheBagAndItsRule() {
        assertEquals("✦Wish Bag · wished things only✦", WishBagWords.TITLE,
                "the intended words less the spaces beside the stars: with them it is 167 px of 160");
        assertEquals(160, WishBagWords.TITLE_ROOM, "a 176-px chest less the 8-px label inset each side");
    }

    @Test
    void theTooltipKeepsItsLinesAndAddsTheRule() {
        assertEquals(List.of("Use it to open!", "Things you wished for wait in here.",
                "Only wished things can go in."), WishBagWords.LORE);
    }

    @Test
    void theStarCountsPagesAndThings() {
        assertEquals("Page 1 of 1 · 1 thing", WishBagWords.counter(0, 1));
        assertEquals("Page 2 of 3 · 100 things", WishBagWords.counter(1, 100));
        assertEquals("Page 1 of 1 · 0 things", WishBagWords.counter(0, 0));
    }

    @Test
    void theRefusalNamesABlockOrAnItem() {
        assertEquals("Only things you wished for go in your Wish Bag — that's a regular Minecraft block.",
                WishBagWords.refusal(true));
        assertTrue(WishBagWords.refusal(false).endsWith("regular Minecraft item."));
    }

    @Test
    void everyLineAChildReadsPassesTheLexicon() {
        SafetyLexicon lex = SafetyLexicon.shared();
        List<String> lines = new ArrayList<>();
        lines.add(WishBagWords.TITLE);
        lines.add(WishBagWords.NAME);
        lines.addAll(WishBagWords.LORE);
        lines.add(WishBagWords.OLDER);
        lines.add(WishBagWords.NEWER);
        lines.add(WishBagWords.refusal(true));
        lines.add(WishBagWords.refusal(false));
        for (int size : new int[] {0, 1, 45, 46, 300}) {
            lines.add(WishBagWords.counter(0, size));
        }
        for (String l : lines) {
            assertFalse(l.isBlank());
            assertTrue(lex.check(l).isEmpty(), "the lexicon flags a bag line: " + l);
        }
    }
}
