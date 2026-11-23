package com.moddingfromamod.wish;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.moddingfromamod.wishbuilder.ApprenticeWords;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import com.moddingfromamod.world.WorldCrafters;
import org.junit.jupiter.api.Test;

/** step 5: who is a Wish Crafter, his rank, and every word he is told. */
class CraftersTest {

    static final List<WorldCrafters.Rank> RANKS = List.of(
            new WorldCrafters.Rank("Wish Crafter", 1, ""),
            new WorldCrafters.Rank("Skilled Crafter", 2, "a:cobble"),
            new WorldCrafters.Rank("Master Crafter", 3, "a:iron"));

    @Test
    void aCrafterOnlyWhereTheDoorIsOpenOffTheListAndNoOperator() {
        assertEquals(Crafters.Role.CRAFTER, Crafters.role(true, false, false));
        assertEquals(Crafters.Role.MAGICIAN, Crafters.role(true, true, false), "on the list: a Magician");
        assertEquals(Crafters.Role.MAGICIAN, Crafters.role(true, false, true), "an operator: a Magician");
        assertEquals(Crafters.Role.MAGICIAN, Crafters.role(false, false, false), "the door shut: everyone a Magician");
    }

    @Test
    void ranksAreEarnedByPlayingInOrder() {
        assertEquals("Wish Crafter", Crafters.rank(RANKS, id -> false).name());
        assertEquals("Skilled Crafter", Crafters.rank(RANKS, Set.of("a:cobble")::contains).name());
        assertEquals("Master Crafter", Crafters.rank(RANKS, Set.of("a:cobble", "a:iron")::contains).name());
        assertNull(Crafters.rank(List.of(), id -> true));
    }

    @Test
    void titlesSayStarsForACrafterAndAWandForAMagician() {
        assertEquals("✦✦ Skilled Crafter", Crafters.title(Crafters.Role.CRAFTER, RANKS.get(1)));
        assertEquals("✦ Wish Crafter", Crafters.title(Crafters.Role.CRAFTER, null));
        assertEquals("✪ Wish Magician", Crafters.title(Crafters.Role.MAGICIAN, RANKS.get(2)));
    }

    @Test
    void anUnaskedNudgeIsTenMinutesApartAfterThe1stAndEvery5th() {
        assertTrue(Crafters.mayNudge(0, 1000));
        assertFalse(Crafters.mayNudge(1000, 1000 + 9 * 60_000));
        assertTrue(Crafters.mayNudge(1000, 1000 + 10 * 60_000));
        assertTrue(Crafters.nudgeAfterMade(1));
        assertFalse(Crafters.nudgeAfterMade(2));
        assertTrue(Crafters.nudgeAfterMade(5));
        assertTrue(Crafters.nudgeAfterMade(10));
    }

    /** §5 and §11.7: facts for grown-ups; never "ask your parents", never a countdown, never "can't". */
    @Test
    void everyLineIsAFactForGrownUpsAndNeverPushesAChild() {
        List<String> all = List.of(Crafters.welcome("✦ Wish Crafter"), Crafters.QUESTION,
                Crafters.AFTER_MADE, Crafters.rankUp("✦✦ Skilled Crafter", "Master Crafter"), Crafters.PROMOTED,
                String.join(" ", Crafters.COMPOSE_LORE),
                // everything the Apprentice says to a Crafter is held to the same rule.
                ApprenticeWords.TITLE, ApprenticeWords.HELLO, ApprenticeWords.CLOSEST, ApprenticeWords.OUT,
                ApprenticeWords.NONE_LABEL, String.join(" ", ApprenticeWords.NONE_LORE), ApprenticeWords.NONE_SAID,
                ApprenticeWords.UPSELL, ApprenticeWords.BUILDER_OFF, ApprenticeWords.TILE_LORE,
                ApprenticeWords.boxLabel(true, true), ApprenticeWords.boxLabel(true, false),
                // the trained layer's line and the three tile tags.
                ApprenticeWords.GUESSED, ApprenticeWords.TAG_JUST, ApprenticeWords.TAG_CLOSEST, ApprenticeWords.TAG_LEAVES_OUT,
                // A tile makes at once, and the thing can be changed after, free once.
                ApprenticeWords.TAG_CHANGE_AFTER, ApprenticeWords.CHANGE_IT_FREE);
        for (String s : all) {
            String l = s.toLowerCase(Locale.ROOT);
            for (String bad : List.of("parent", "mum", "mom", "dad", "only ", "hurry", "left!", "minutes left",
                    "can't", "cannot", "not allowed", "pay", "buy", "upgrade")) {
                assertFalse(l.contains(bad), "'" + bad + "' in: " + s);
            }
        }
        assertTrue(Crafters.welcome("✦ Wish Crafter").startsWith("✨ You're a Wish Crafter!"));
        for (String l : Crafters.COMPOSE_LORE) {
            assertTrue(l.length() <= Crafters.LORE_MAX, "a tooltip line the client would wrap: " + l);
        }
        for (String l : ApprenticeWords.NONE_LORE) {
            assertTrue(l.length() <= Crafters.LORE_MAX, "a tooltip line the client would wrap: " + l);
        }
        for (String l : List.of(ApprenticeWords.TAG_JUST, ApprenticeWords.TAG_CLOSEST, ApprenticeWords.TAG_LEAVES_OUT,
                ApprenticeWords.TAG_CHANGE_AFTER, ApprenticeWords.TILE_LORE)) {
            assertTrue(l.length() <= Crafters.LORE_MAX, "a tooltip line the client would wrap: " + l);
        }
        assertTrue(ApprenticeWords.UPSELL.contains("18+"), "the Magician line addresses grown-ups");
        assertTrue(ApprenticeWords.TITLE.contains("Apprentice") && ApprenticeWords.HELLO.contains("Apprentice"),
                "Apprentice mode always says it is Apprentice mode");
        // It is tuned, so "(experimental)" is gone; it names itself and Moddy's Parts instead.
        for (String s : List.of(ApprenticeWords.TITLE, ApprenticeWords.HELLO, ApprenticeWords.GUESSED, ApprenticeWords.boxLabel(true, true),
                ApprenticeWords.boxLabel(true, false), String.join(" ", Crafters.COMPOSE_LORE), ApprenticeWords.modeSaid(true))) {
            assertFalse(s.contains("experimental"), "the Apprentice is no longer experimental: " + s);
            assertTrue(s.contains("Moddy's Parts") || s.contains("Moddy's Apprentice"), "one vocabulary (#1921): " + s);
        }
    }
}
