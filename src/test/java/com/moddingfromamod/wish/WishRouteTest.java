package com.moddingfromamod.wish;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Every combination of who, toggle, what was asked and question: the table in {@link WishRoute}. */
class WishRouteTest {

    @Test
    @DisplayName("a Crafter's words always go to the Apprentice: he has no door to the agent")
    void aCrafterIsAlwaysTheApprentices() {
        for (boolean prefers : new boolean[] {false, true}) {
            for (WishRoute.Hint hint : WishRoute.Hint.values()) {
                for (boolean question : new boolean[] {false, true}) {
                    assertEquals(WishRoute.Route.APPRENTICE,
                            WishRoute.choose(Crafters.Role.CRAFTER, prefers, hint, question),
                            "crafter, prefers=" + prefers + ", " + hint + ", question=" + question);
                }
            }
        }
    }

    @Test
    @DisplayName("a Magician's default is the Magician; his toggle or asking by name sends a wish to the Apprentice")
    void aMagicianChooses() {
        Crafters.Role w = Crafters.Role.MAGICIAN;
        assertEquals(WishRoute.Route.MAGICIAN, WishRoute.choose(w, false, WishRoute.Hint.AUTO, false));
        assertEquals(WishRoute.Route.APPRENTICE, WishRoute.choose(w, true, WishRoute.Hint.AUTO, false), "his toggle");
        assertEquals(WishRoute.Route.APPRENTICE, WishRoute.choose(w, false, WishRoute.Hint.APPRENTICE, false), "/moddy apprentice");
        assertEquals(WishRoute.Route.APPRENTICE, WishRoute.choose(w, false, WishRoute.Hint.APPRENTICE, true),
                "asked for by name, even a question");
        assertEquals(WishRoute.Route.MAGICIAN, WishRoute.choose(w, true, WishRoute.Hint.MAGICIAN, false),
                "'Ask Moddy instead' wins over the toggle");
        assertEquals(WishRoute.Route.MAGICIAN, WishRoute.choose(w, true, WishRoute.Hint.AUTO, true),
                "a question is the agent's to answer, toggle or not");
    }

    @Test
    @DisplayName("#2314: with Magician mode off, every typed wish goes to the Apprentice, whatever was asked")
    void magicianOffMeansTheApprentice() {
        for (Crafters.Role role : Crafters.Role.values()) {
            for (WishRoute.Hint hint : WishRoute.Hint.values()) {
                for (boolean q : new boolean[]{false, true}) {
                    assertEquals(WishRoute.Route.APPRENTICE, WishRoute.choose(false, role, false, hint, q),
                            role + " " + hint + " question=" + q);
                }
            }
        }
        assertEquals(WishRoute.Route.MAGICIAN,
                WishRoute.choose(true, Crafters.Role.MAGICIAN, false, WishRoute.Hint.AUTO, false));
    }
}
