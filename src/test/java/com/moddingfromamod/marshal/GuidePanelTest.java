package com.moddingfromamod.marshal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import com.moddingfromamod.safety.SafetyLexicon;
import org.junit.jupiter.api.Test;

/** The "📖 What do I do now?" button and the guide page it opens. */
class GuidePanelTest {

    private static final GuidePanel.Goal COBBLE = new GuidePanel.Goal("skyvoid_advancements:overworld/cobble_gen",
            "The Heart of the Grind", "Water and lava with two empty blocks between them.",
            "help me build a cobblestone generator");

    private static List<WatchPanel.PresetRow> starters(int n) {
        List<WatchPanel.PresetRow> out = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            out.add(new WatchPanel.PresetRow("Thing " + i, "make me thing " + i, "recipe_" + i));
        }
        return out;
    }

    private static void wellPlaced(WatchPanel.Layout l) {
        Set<Integer> seen = new HashSet<>();
        for (WatchPanel.Cell c : l.cells()) {
            assertTrue(c.slot() >= 0 && c.slot() < WatchPanel.SIZE, "off the chest: " + c);
            assertTrue(seen.add(c.slot()), "two cells in slot " + c.slot());
            assertFalse(c.label().isBlank(), "a cell with no label: " + c);
        }
    }

    // ── the button on the watch ─────────────────────────────────────────────────────────────

    @Test
    void theButtonIsOnEveryPageBetweenComposeAndMore_neverBesideAnExit() {
        List<WatchPanel.PresetRow> many = new ArrayList<>();
        for (int i = 0; i < 60; i++) {
            many.add(new WatchPanel.PresetRow("P" + i, "make p" + i, "r" + i));
        }
        for (int page = 0; page < 3; page++) {
            WatchPanel.Layout l = WatchPanel.lay(many, List.of(), List.of(), page);
            wellPlaced(l);
            WatchPanel.Cell g = l.at(WatchPanel.GUIDE_SLOT);
            assertNotNull(g, "no guide button on page " + page);
            assertEquals(WatchPanel.Action.GUIDE, g.action());
            assertEquals("📖 What do I do now?", g.label());
            assertEquals(List.of("Your next goal, and ideas to try"), g.lore());
            assertEquals(WatchIcons.GUIDE, g.icon());
        }
        assertEquals(WatchPanel.CONTROL_ROW_START + 5, WatchPanel.GUIDE_SLOT);
        // The control row's rule: a destructive press is never next to another one.
        assertTrue(Math.abs(WatchPanel.GUIDE_SLOT - WatchPanel.CONTROL_ROW_START) >= 2, "beside 👎");
        assertTrue(Math.abs(WatchPanel.GUIDE_SLOT - (WatchPanel.SIZE - 1)) >= 2, "beside Close");
        assertTrue(WatchIcons.everyIcon().containsAll(List.of(WatchIcons.GUIDE, WatchIcons.GOAL,
                WatchIcons.ALL_DONE, WatchIcons.HOW_TO, WatchIcons.GUIDE_BOOK, WatchIcons.CREATIVE)));
    }

    @Test
    void anEmptyWorldStillHasTheButton() {
        assertNotNull(WatchPanel.lay(List.of(), List.of(), List.of()).at(WatchPanel.GUIDE_SLOT));
    }

    // ── the guide page, on a world with a goal tree ──────────────────────────────

    @Test
    void aGoalTreeWorldShowsTheNextGoalAndHelpWithIt_pricedAsAWish() {
        WatchPanel.Layout l = GuidePanel.lay(new GuidePanel.View(true, COBBLE, 1, true, "World Guide", List.of()));
        wellPlaced(l);
        WatchPanel.Cell goal = l.at(GuidePanel.HEADLINE_SLOT);
        assertEquals("🎯 The Heart of the Grind", goal.label());
        assertEquals(WatchPanel.Action.NONE, goal.action(), "reading the goal is free and does nothing");
        assertEquals("Your next goal:", goal.lore().get(0));
        assertTrue(String.join(" ", goal.lore()).contains("two empty blocks"), goal.lore().toString());
        assertEquals("Press " + GuidePanel.GOALS_BUTTON + " to see them all", goal.lore().get(goal.lore().size() - 1));
        WatchPanel.Cell help = l.at(GuidePanel.HELP_SLOT);
        assertEquals(WatchPanel.Action.HELP_WISH, help.action());
        assertEquals("✨ Help me with this (1 wish)", help.label());
        assertEquals("help me build a cobblestone generator", help.arg(), "the anvil is seeded with this");
        assertTrue(help.lore().contains("You can change the words first"));
        assertEquals(WatchPanel.Action.HOW_TO, l.at(GuidePanel.HOW_TO_SLOT).action());
        assertEquals("📜 How to play", l.at(GuidePanel.HOW_TO_SLOT).label());
        assertEquals(WatchPanel.Action.GUIDE_BOOK, l.at(GuidePanel.BOOK_SLOT).action());
        assertEquals("📖 Get the World Guide book", l.at(GuidePanel.BOOK_SLOT).label());
        WatchPanel.Cell back = l.at(GuidePanel.BACK_SLOT);
        assertEquals(WatchPanel.Action.PAGE, back.action());
        assertEquals("0", back.arg(), "back is an ordinary page turn to page one");
        assertEquals(WatchPanel.Action.CLOSE, l.at(WatchPanel.SIZE - 1).action());
    }

    @Test
    void everyGoalDoneSaysSo_andOffersNoHelpWish() {
        WatchPanel.Layout l = GuidePanel.lay(new GuidePanel.View(true, null, 1, true, "World Guide", List.of()));
        wellPlaced(l);
        assertEquals("🏆 You have done every goal!", l.at(GuidePanel.HEADLINE_SLOT).label());
        assertNull(l.at(GuidePanel.HELP_SLOT));
        assertTrue(l.cells().stream().noneMatch(c -> c.action() == WatchPanel.Action.HELP_WISH));
    }

    @Test
    void theLabelSaysThePriceOnlyWhereWishesCost() {
        assertEquals("✨ Help me with this", GuidePanel.helpLabel(0));
        assertEquals("✨ Help me with this (1 wish)", GuidePanel.helpLabel(1));
        assertEquals("✨ Help me with this (2 wishes)", GuidePanel.helpLabel(2));
    }

    // ── on a world with no goal tree ─────────────────────────────────────────

    @Test
    void aWorldWithNoGoalTreeSaysBeCreative_withFourStarterWishes() {
        WatchPanel.Layout l = GuidePanel.lay(new GuidePanel.View(false, null, 0, false, "", starters(7)));
        wellPlaced(l);
        assertEquals("🎨 Be creative — wish for anything!", l.at(GuidePanel.HEADLINE_SLOT).label());
        List<WatchPanel.Cell> wishes = l.cells().stream().filter(c -> c.action() == WatchPanel.Action.WISH).toList();
        assertEquals(4, wishes.size(), "three or four ideas, not a wall");
        for (int i = 0; i < wishes.size(); i++) {
            assertEquals(GuidePanel.STARTER_SLOTS[i], wishes.get(i).slot());
            assertEquals("recipe_" + i, wishes.get(i).recipeId(), "a starter keeps its recipe");
            assertEquals(WatchPanel.Family.PRESET, wishes.get(i).family());
        }
        assertEquals("📜 How to use Moddy", l.at(GuidePanel.HOW_TO_SLOT_CREATIVE).label());
        assertTrue(l.cells().stream().noneMatch(c -> c.action() == WatchPanel.Action.GUIDE_BOOK
                || c.action() == WatchPanel.Action.HELP_WISH), "no book and no goal on a creative world");
        assertEquals(WatchPanel.Action.CLOSE, l.at(WatchPanel.SIZE - 1).action());
    }

    @Test
    void everyWordOnThePagePassesTheLexicon() {
        SafetyLexicon lex = SafetyLexicon.shared();
        List<String> words = new ArrayList<>(List.of(GuidePanel.BUTTON, GuidePanel.BUTTON_LORE));
        for (WatchPanel.Layout l : List.of(
                GuidePanel.lay(new GuidePanel.View(true, COBBLE, 1, true, "World Guide", List.of())),
                GuidePanel.lay(new GuidePanel.View(true, null, 1, true, "World Guide", List.of())),
                GuidePanel.lay(new GuidePanel.View(false, null, 0, false, "", List.of())))) {
            for (WatchPanel.Cell c : l.cells()) {
                words.add(c.label());
                words.addAll(c.lore());
            }
        }
        for (String w : words) {
            assertTrue(lex.check(w).isEmpty(), "the lexicon flags: " + w);
        }
    }

    @Test
    void loreWrapsLongHints() {
        List<String> w = GuidePanel.wrap("word ".repeat(30));
        assertTrue(w.size() > 1);
        w.forEach(line -> assertTrue(line.length() <= GuidePanel.LORE_WIDTH, line));
    }
}
