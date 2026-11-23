package com.moddingfromamod.marshal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Gate A: what {@code ask_which} may offer, decided on the server. The live case is the
 * first test — three T-Rex designs, one out in the world, "kill my t rex".
 */
class WhichPickTest {

    private static final WhichPick.Offer GIANT = new WhichPick.Offer("giant_trex", "Giant T-Rex", InWorld.of(1));
    private static final WhichPick.Offer HOT_WHEELS = new WhichPick.Offer("hot_wheels_trex", "Hot Wheels T-Rex", InWorld.of(0));
    private static final WhichPick.Offer ROBO = new WhichPick.Offer("robo_trex", "Robo T-Rex", InWorld.of(2));

    @Test
    @DisplayName("the live case: one T-Rex out of three is out, so he is not asked — that one is the pick")
    void oneOutIsChosenWithoutAsking() {
        var d = WhichPick.decide(WhichPick.About.INSTANCE, "kill my t rex", List.of(HOT_WHEELS, GIANT));
        assertEquals(WhichPick.Kind.CHOSEN, d.kind());
        assertEquals(List.of("giant_trex"), d.ids());
        assertTrue(d.message().contains("plan_wish about: 'giant_trex'"), d.message());
        assertTrue(d.message().contains("Hot Wheels T-Rex is not out"), d.message());
    }

    @Test
    @DisplayName("not just removal: hit, ride, improve all act on one that is out")
    void everyInstanceVerbFilters() {
        for (String wish : List.of("hit my t rex", "let me ride my t rex", "make my t rex bigger",
                "take me to my t rex", "feed my t rex", "make my t rex better")) {
            var d = WhichPick.decide(WhichPick.About.INSTANCE, wish, List.of(HOT_WHEELS, GIANT, ROBO));
            assertEquals(WhichPick.Kind.ASK, d.kind(), wish);
            assertEquals(List.of("giant_trex", "robo_trex"), d.ids(), wish);
        }
    }

    @Test
    @DisplayName("none out: refused, with words that forbid acting on a design")
    void noneOutIsRefused() {
        var d = WhichPick.decide(WhichPick.About.INSTANCE, "kill my t rex",
                List.of(HOT_WHEELS, new WhichPick.Offer("old_rex", "", InWorld.of(0))));
        assertEquals(WhichPick.Kind.REFUSE, d.kind());
        assertTrue(d.message().contains("Do NOT act on a design"), d.message());
        assertTrue(d.message().contains("'old_rex'"), d.message());
    }

    @Test
    @DisplayName("a design wish offers every design, out or not")
    void designOffersTheWholeLibrary() {
        var d = WhichPick.decide(WhichPick.About.DESIGN, "make me another t rex", List.of(HOT_WHEELS, GIANT));
        assertEquals(WhichPick.Kind.ASK, d.kind());
        assertEquals(List.of("hot_wheels_trex", "giant_trex"), d.ids());
    }

    @Test
    @DisplayName("a destroying wish cannot be about a design — unless it asks for another one")
    void destroyIsNeverADesignPick() {
        assertEquals(WhichPick.Kind.REFUSE, WhichPick.decide(WhichPick.About.DESIGN, "kill my t rex",
                List.of(HOT_WHEELS, GIANT)).kind());
        assertEquals(WhichPick.Kind.ASK, WhichPick.decide(WhichPick.About.DESIGN,
                "get rid of this one and make me another t rex", List.of(HOT_WHEELS, GIANT)).kind());
    }

    @Test
    @DisplayName("things with no instance (items, rules) are never dropped")
    void kindsWithNoInstanceStay() {
        var sword = new WhichPick.Offer("fire_sword", "Fire Sword", InWorld.NOT_A_THING);
        var axe = new WhichPick.Offer("ice_axe", "Ice Axe", InWorld.NOT_A_THING);
        var d = WhichPick.decide(WhichPick.About.INSTANCE, "make my sword stronger", List.of(sword, axe));
        assertEquals(WhichPick.Kind.ASK, d.kind());
        assertEquals(2, d.ids().size());
        assertEquals("", d.message());
    }

    @Test
    @DisplayName("about parses; an unknown word is refused, blank means instance")
    void aboutParses() {
        assertEquals(WhichPick.About.INSTANCE, WhichPick.About.parse(null));
        assertEquals(WhichPick.About.INSTANCE, WhichPick.About.parse("instance"));
        assertEquals(WhichPick.About.DESIGN, WhichPick.About.parse(" Design "));
        assertEquals(null, WhichPick.About.parse("maybe"));
        assertEquals(WhichPick.Kind.REFUSE, WhichPick.decide(null, "x", List.of(GIANT, ROBO)).kind());
    }

    @Test
    @DisplayName("InWorld words: creature counts, buildings stand, nothing for an item")
    void inWorldWords() {
        assertEquals("1 out in the world", InWorld.of(1).describe("species"));
        assertEquals("3 out in the world", InWorld.of(3).describe("species"));
        assertTrue(InWorld.of(0).describe("species").contains("design only"));
        assertEquals("standing", InWorld.standing(true).describe("build"));
        assertTrue(InWorld.standing(false).describe("build").contains("design only"));
        assertEquals("", InWorld.NOT_A_THING.describe("item"));
        assertTrue(InWorld.NOT_A_THING.out());
        assertFalse(InWorld.of(0).out());
    }

    @Test
    @DisplayName("the catalogue marks each creature and building, lists everything, and says what the marks mean")
    void catalogueMarks() {
        var entries = List.of(
                new WorldCatalogue.Entry("giant_trex", "species", "Giant T-Rex", List.of()),
                new WorldCatalogue.Entry("hot_wheels_trex", "species", "Hot Wheels T-Rex", List.of()),
                new WorldCatalogue.Entry("fire_sword", "item", "Fire Sword", List.of()));
        Map<String, InWorld> w = Map.of("giant_trex", InWorld.of(1), "hot_wheels_trex", InWorld.of(0));
        var r = WorldCatalogue.render(entries, e -> w.getOrDefault(e.id(), InWorld.NOT_A_THING));
        assertEquals(3, r.listed());
        assertTrue(r.text().contains("species giant_trex \"Giant T-Rex\" — 1 out in the world"), r.text());
        assertTrue(r.text().contains("hot_wheels_trex \"Hot Wheels T-Rex\" — none out in the world now — design only"), r.text());
        assertTrue(r.text().contains("item fire_sword \"Fire Sword\"\n"), r.text());
        assertTrue(r.text().contains(WorldCatalogue.OUT_RULE), r.text());
        assertFalse(WorldCatalogue.render(entries).text().contains(WorldCatalogue.OUT_RULE),
                "the unmarked render is unchanged");
    }
}
