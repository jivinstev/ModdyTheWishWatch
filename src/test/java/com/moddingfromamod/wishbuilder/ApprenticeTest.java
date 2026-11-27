package com.moddingfromamod.wishbuilder;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** The Apprentice matcher on fixed words: each case is a rule of docs/APPRENTICE.md §3 seen to hold. */
class ApprenticeTest {

    private static final BuilderTree TREE = BuilderTree.of(BuilderAllowlist.REVIEWED, LibraryPresets.SHIPPED);
    private static final Apprentice APPRENTICE = new Apprentice(TREE, Apprentice.english());

    private static Apprentice.Pick top(String text) {
        Apprentice.Answer a = APPRENTICE.match(text);
        assertFalse(a.offers().isEmpty(), "nothing offered for: " + text);
        return a.offers().get(0);
    }

    /** The typo suite's repairs (docs/APPRENTICE.md §4⅞), each with the case that taught its limit. */
    @Test
    @DisplayName("a mistyped wish is put back together the way the reference does it, and no further")
    void typosArePutBackTogether() {
        java.util.function.Function<String, List<String>> toks = t -> APPRENTICE.normalize(Apprentice.wordsOf(t));
        assertEquals(List.of("cat", "please", "so"), toks.apply("a caaat pleeease sooo"), "letters held down");
        assertEquals(List.of("soup", "giant", "dragon", "that"), toks.apply("soupgiant dragonthat"), "two words run together");
        assertEquals(List.of("swimmy", "cathes"), toks.apply("swimmy cathes"),
                "never cut into a word and a word ending, or into a merely English word");
        assertEquals(List.of("crystals", "lemonade"), toks.apply("crys tals lemon ade"), "one word broken in two");
        assertEquals(List.of("that", "and", "with"), toks.apply("taht adn wiht"), "a mistyped joining word");
        assertEquals(List.of("lol", "duper"), toks.apply("lol duper"), "never into a filler word the tree gives a meaning to");
        assertEquals(top("a castle"), top("a kastle"), "the first sound spelt another way still finds the thing");
        assertEquals(top("a cat"), top("a kat"));
        assertEquals(top("a cat"), top("a cta"));
        assertEquals(top("a chicken"), top("a shicken"));
        assertEquals(top("a chicken"), top("a cihcken"));
        assertEquals(top("a giant chicken"), top("a jiant chicken"));
    }

    @Test
    @DisplayName("a family word offers each of its entries, and unnamed slots are left to the defaults")
    void aFamilyWordOffersEachEntry() {
        Apprentice.Answer a = APPRENTICE.match("a green dino i can ride");
        Set<String> bodies = new LinkedHashSet<>();
        int plain = 0;
        for (Apprentice.Pick p : a.offers()) {
            assertEquals("ride", p.kind());
            if (p.first("scheme").isEmpty()) {
                plain++;   // the colour leads, and the same make as it comes is a later tile
                assertEquals(a.offers().get(0).first("from"), p.first("from"), "the plain tile is the top make, uncoloured");
            } else {
                assertEquals("green", p.first("scheme"), "#2421: one colour on a creature is that colour, all over: " + p.picks());
            }
            bodies.add(p.first("from"));
        }
        assertEquals(1, plain, "exactly one plain tile, after the coloured ones: " + a.offers());
        assertTrue(a.offers().get(a.offers().size() - 1).first("scheme").isEmpty(), "the plain tile is the last");
        assertTrue(bodies.size() >= 3, "the dinosaurs offered: " + bodies);
        assertEquals("green", a.offers().get(0).first("scheme"), "#2421: the first tile is the colour he named");
        assertEquals(Apprentice.Reach.IN, a.reach());
        BuilderSpec spec = ApprenticeMake.spec(BuilderAllowlist.REVIEWED, LibraryPresets.SHIPPED, TREE, a.offers().get(0));
        assertEquals(BuilderSpec.Kind.RIDE, spec.kind());
        assertEquals("green", spec.scheme());
        assertEquals(2, spec.size(), "the size nobody named is the Builder's default");
        assertEquals("friendly", spec.temper());
    }

    @Test
    @DisplayName("#2252: a food word asks for the food — never a snack machine or a rule that drops it, unless he says so")
    void aFoodWordIsNotAMachine() {
        for (String w : List.of("a cake", "a banana", "cookies", "a pet banana", "fake diamonds")) {
            for (Apprentice.Pick p : APPRENTICE.match(w).offers()) {
                assertFalse(p.kind().equals("machine") || p.kind().equals("rule"), w + " offered " + Apprentice.key(p));
            }
        }
        for (String w : List.of("a cake machine", "a machine that makes cookies", "a cookie factory", "a cookie maker")) {
            assertEquals("machine", top(w).kind(), w + " names a machine");
        }
        assertEquals("rule", top("a zombie that drops diamonds").kind(), "two words say what the rule does");
        assertEquals("rule", top("when a cow dies it drops a cake").kind(), "a when word says it is a rule");
    }

    @Test
    @DisplayName("#2261: a negated word is not read, unless the Builder's own phrase says the negation")
    void aNegatedWordIsNotRead() {
        for (String w : List.of("a horse that never runs away", "a horse that doesn't run away", "a horse that does not run away")) {
            Apprentice.Answer a = APPRENTICE.match(w);
            assertEquals("bodies/horse", a.offers().get(0).first("from"), w);
            assertFalse(a.offers().get(0).picks().containsKey("temper"), "never runs away is not a shy horse: " + w);
            assertEquals(Apprentice.Reach.PART, a.reach(), w);
        }
        assertTrue(top("a chicken that never leaves").picks().getOrDefault("powers", List.of()).contains("power:persistent"),
                "\"never leaves\" is the Builder's own phrase for stays forever");
        // A phrase that says its negation keeps it: the words after it are read (the refresh's own.changed, 2026-11-15).
        Apprentice.Pick p = top("machine lemonade speed from veggie make it not too strong five at a time");
        assertEquals(List.of("1"), p.picks().get("strength"), "not too strong is still strength 1");
        assertEquals(List.of("5"), p.picks().get("fill"), "...and five at a time, after it, still fills five");
        assertFalse(p.picks().getOrDefault("snack_powers", List.of()).contains("snackpower:treat_strong"),
                "the 'strong' of 'not too strong' is no strong treat");
    }

    @Test
    @DisplayName("#2256: a body made to be ridden is never offered as a creature he cannot ride (the griffin may be)")
    void aVehicleIsAlwaysRidden() {
        for (String w : List.of("a bike", "a car", "a boat", "a rocket")) {
            for (Apprentice.Pick p : APPRENTICE.match(w).offers()) {
                assertFalse(p.kind().equals("creature"), w + " offered " + Apprentice.key(p));
            }
        }
        assertTrue(APPRENTICE.match("a griffin").offers().stream().anyMatch(p -> p.kind().equals("creature")));
    }

    @Test
    @DisplayName("#2226: the bands — sure (every word kept, no stand-in), close, nothing")
    void theBands() {
        java.util.function.Function<String, Apprentice.Confidence> c = w -> APPRENTICE.confidence(w, APPRENTICE.match(w));
        assertEquals(Apprentice.Band.SURE, c.apply("a lion").band());
        assertEquals(Apprentice.Band.SURE, c.apply("a castle").band());
        assertEquals(Apprentice.Band.CLOSE, c.apply("a tiger").band(), "a stand-in is never sure");
        assertEquals(Apprentice.Band.CLOSE, c.apply("a robot dog").band());
        assertEquals(Apprentice.Band.NOTHING, c.apply("do my homework").band());
        assertEquals("auto", c.apply("a dragon that spits flames").stratum());
        // A power the body already has is granted, not left out: the dragon flies on its own.
        assertEquals(Apprentice.Band.SURE, c.apply("a dragon that can fly").band());
        assertEquals(Apprentice.Band.SURE, c.apply("a dragon that breathes fire and can fly").band());
        assertTrue(APPRENTICE.notes("a dragon that can fly", APPRENTICE.match("a dragon that can fly").offers().get(0))
                .cant().isEmpty(), "\"fly\" is not something the dragon leaves out");
    }

    @Test
    @DisplayName("the longer phrase wins: a lion tail is a tail, not a lion")
    void theLongerPhraseWins() {
        Apprentice.Pick p = top("an eagle with a lion tail");
        assertEquals("bodies/eagle", p.first("from"));
        assertEquals(List.of("kit:lion_tail"), p.picks().get("parts"));
    }

    @Test
    @DisplayName("a child's spelling finds the thing; a real English word is never respelt into one")
    void spelling() {
        assertEquals("species/pet_dragon", top("a drajon").first("from"));
        assertEquals("species/pet_dragon", top("a dragn with wings").first("from"));
        assertEquals(Apprentice.Reach.OUT, APPRENTICE.match("the front pond wall").reach(),
                "front, pond and wall are words, not a misspelt frost, pony and well");
    }

    @Test
    @DisplayName("reach: sure, the closest we have, or nothing at all")
    void reach() {
        assertEquals(Apprentice.Reach.OUT, APPRENTICE.match("do my homework").reach());
        assertTrue(APPRENTICE.match("do my homework").offers().isEmpty());
        Apprentice.Answer part = APPRENTICE.match("a cat that hides my diamonds");
        assertEquals(Apprentice.Reach.PART, part.reach());
        assertEquals(List.of("hides", "diamonds"), part.unmatched());
        assertEquals(Apprentice.Reach.OUT, APPRENTICE.match("").reach());
        assertEquals(Apprentice.Reach.OUT, APPRENTICE.match("!!! ???").reach());
    }

    @Test
    @DisplayName("a word that also picks elsewhere does: a unicorn is a horse with a unicorn horn")
    void aWordAlsoPicks() {
        Apprentice.Pick p = top("a unicorn");
        assertEquals("bodies/horse", p.first("from"));
        assertEquals(List.of("kit:unicorn_horn"), p.picks().get("parts"));
    }

    @Test
    @DisplayName("a made-up item needs its shape; a rule needs its when and its then")
    void formsNeedTheirAnchors() {
        Apprentice.Pick sword = top("a sword that shoots lightning");
        assertEquals("item", sword.kind());
        assertEquals("stock:sword", sword.first("shape"));
        assertEquals("itempower:lightning", sword.first("power"));
        Apprentice.Pick rule = top("zombies drop diamonds when they die");
        assertEquals("rule", rule.kind());
        assertEquals("when:mob_dies", rule.first("when"));
        assertEquals("mob:minecraft:zombie", rule.first("who"));
        assertEquals("rule:diamond", rule.first("does"));
        for (Apprentice.Pick p : APPRENTICE.match("boots that make me jump high").offers()) {
            assertFalse(p.kind().equals("rule"), "jumping in boots is not a rule about jumping");
        }
    }

    @Test
    @DisplayName("what a body already does takes no power slot")
    void aBodyKeepsItsOwnPowers() {
        Apprentice.Pick p = top("a rocket ship that flies and glows");
        assertEquals("bodies/rocket", p.first("from"));
        assertEquals(List.of("power:glowing"), p.picks().get("powers"), "a rocket already flies");
    }

    @Test
    @DisplayName("a pick the menus would refuse is an error that names the press, never a make with a piece missing")
    void aRefusedPressIsNamed() {
        // two breaths are one apply_effect pack (the second would replace the first) — the one real clash
        // among moves; three powers were refused here only by a typed "two at most", and are now made.
        Apprentice.Pick breaths = new Apprentice.Pick("creature", "ready", Map.of("from", List.of("species/cat"),
                "abilities", List.of("ability:fire_breath", "ability:frost_breath")));
        assertFalse(Apprentice.problems(TREE, breaths).isEmpty());
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> ApprenticeMake.pressed(BuilderAllowlist.REVIEWED, LibraryPresets.SHIPPED, breaths));
        assertTrue(e.getMessage().contains("ability:frost_breath"), e.getMessage());
        Apprentice.Pick three = new Apprentice.Pick("creature", "ready", Map.of("from", List.of("species/cat"),
                "powers", List.of("power:flies", "power:glowing", "power:hops")));
        assertTrue(Apprentice.problems(TREE, three).isEmpty(), Apprentice.problems(TREE, three).toString());
        assertEquals(3, ApprenticeMake.pressed(BuilderAllowlist.REVIEWED, LibraryPresets.SHIPPED, three).spec(Map.of()).powers().size());
        Apprentice.Pick wrong = new Apprentice.Pick("rule", "rule", Map.of("when", List.of("when:jumps"), "does", List.of("rule:diamond")));
        assertFalse(Apprentice.problems(TREE, wrong).isEmpty(), "a drop cannot follow a jump");
    }

    @Test
    @DisplayName("#2271: a misspelt name finds the installed thing, named as a guess, and a name as typed is never respelt")
    void aMisspeltNameFindsTheThing() {
        Apprentice.Answer parrot = APPRENTICE.match("a prarot");
        assertEquals("mob:minecraft:parrot", parrot.offers().get(0).first("from"));
        assertEquals(List.of(List.of("prarot", "Parrot")), APPRENTICE.notes("a prarot", parrot.offers().get(0)).near(),
                "the page says it is the closest it has, so it is never sure");
        assertEquals(Apprentice.Band.CLOSE, APPRENTICE.confidence("a prarot", parrot).band());
        assertEquals("item:minecraft:diamond", APPRENTICE.match("give me 64 diamodns").offers().get(0).first("from"));
        assertEquals("supply:carrot", APPRENTICE.match("a carrot").offers().get(0).first("from"), "a carrot is never a parrot");
    }

    @Test
    @DisplayName("#2273: any number counts the thing in front of it, fitted to the slider, and a moved one is named")
    void anyNumberIsRead() {
        Apprentice.Answer bees = APPRENTICE.match("7 bees");
        // the counts said by name are 1–6, 8, 10…: seven is not one, so the nearest below it, named
        assertEquals("6", bees.offers().get(0).first("count"), "the nearest step");
        assertEquals(List.of(List.of("7", "6")), APPRENTICE.notes("7 bees", bees.offers().get(0)).near());
        assertEquals(Apprentice.Band.CLOSE, APPRENTICE.confidence("7 bees", bees).band());
        assertEquals("24", APPRENTICE.match("twenty four torches").offers().get(0).first("amount"));
        assertEquals("3", APPRENTICE.match("three cows").offers().get(0).first("count"));
        assertTrue(APPRENTICE.notes("three cows", APPRENTICE.match("three cows").offers().get(0)).empty());
        assertTrue(APPRENTICE.match("a sword that does 10 damage").offers().get(0).first("count").isEmpty(),
                "10 counts the damage, not the sword");
    }

    @Test
    @DisplayName("#103: a Builder creature's dialog asks How strong, not How many; typed words still make several, and say its strength")
    void typedWordsStillMakeSeveralCreatures() {
        Apprentice.Answer cats = APPRENTICE.match("five giant cats");
        Apprentice.Pick pick = cats.offers().get(0);
        assertEquals("species/cat", pick.first("from"));
        assertEquals("5", pick.first("count"), "the count is read from his words, with no control on the dialog");
        BuilderSpec spec = ApprenticeMake.spec(BuilderAllowlist.REVIEWED, LibraryPresets.SHIPPED, TREE, pick);
        assertEquals(5, spec.count(), "and five are made");
        MakeFit.Fitted three = MakeFit.fit(spec, java.util.Map.of(MakeFacts.COUNT_MADE, 3.0));
        assertTrue(three.fits() && three.spec().count() == 3, "a world's countMade fits it as before: " + three);
        // and his words say how strong
        Apprentice.Pick strong = APPRENTICE.match("a super strong cat").offers().get(0);
        assertEquals("3", strong.first("strength"));
        assertEquals("3", ApprenticeMake.spec(BuilderAllowlist.REVIEWED, LibraryPresets.SHIPPED, TREE, strong).opt("strength"));
    }

    @Test
    @DisplayName("#2272: a plural reads as its thing")
    void pluralsRead() {
        assertEquals("mob:minecraft:zombie", APPRENTICE.match("two zombies").offers().get(0).first("from"));
        assertEquals("item:minecraft:cookie", APPRENTICE.match("some cookies").offers().get(0).first("from"));
        assertEquals("item:minecraft:cactus", APPRENTICE.match("3 cacti").offers().get(0).first("from"));
    }

    @Test
    @DisplayName("a world that holds something back is never offered it")
    void theAllowlistIsTheWholeSurface() {
        BuilderAllowlist small = new BuilderAllowlist(Set.of(BuilderSpec.Kind.CREATURE), Set.of("preset:species/cat"),
                Set.of("friendly"), 1);
        Apprentice a = new Apprentice(BuilderTree.of(small, LibraryPresets.SHIPPED), Apprentice.english());
        assertEquals(Apprentice.Reach.OUT, a.match("a dragon that flies").reach(), "no dragon and no powers on this world");
        Apprentice.Answer cat = a.match("three cats that fly");
        assertEquals(1, cat.offers().size());
        assertEquals(Map.of("from", List.of("species/cat")), cat.offers().get(0).picks());
    }

    // ── the trained layer: what the matcher does with a reader's answer ──────────────────────────────

    private static ClauseReader says(Object... keyThenProb) {
        List<ClauseReader.Guess> out = new java.util.ArrayList<>();
        for (int i = 0; i < keyThenProb.length; i += 2) {
            out.add(new ClauseReader.Guess((String) keyThenProb[i], (Double) keyThenProb[i + 1]));
        }
        return clause -> out;
    }

    @Test
    @DisplayName("a clause the keywords cannot read: the layer's pick is added to the top tile, said to be a guess, and the keyword make is the next tile")
    void theLayerAddsItsPick() {
        String wish = "a wolf that zorbles";
        Apprentice.Answer plain = APPRENTICE.match(wish);
        assertEquals(Apprentice.Reach.PART, plain.reach());
        assertTrue(APPRENTICE.wantsReader(wish));
        List<String> asked = new java.util.ArrayList<>();
        ClauseReader reader = clause -> {
            asked.add(clause);
            return List.of(new ClauseReader.Guess("abilities=ability:fire_breath", 0.9), new ClauseReader.Guess(ClauseReader.NONE, 0.05));
        };
        Apprentice.Answer a = APPRENTICE.match(wish, reader, 0.5);
        assertEquals(List.of("that zorbles"), asked, "the clause, cut at the connecting word, and nothing of the base");
        assertEquals(List.of("ability:fire_breath"), a.offers().get(0).picks().get("abilities"));
        assertEquals("species/wolf", a.offers().get(0).first("from"));
        assertTrue(a.guessed(0));
        assertEquals(List.of("abilities=ability:fire_breath"), a.layer().get(0));
        assertEquals(Apprentice.Reach.PART, a.reach(), "a page the layer added to is never 'just what you said'");
        assertEquals(List.of(), a.unmatched());
        assertEquals(plain.offers().get(0), a.offers().get(1), "the make without the guess is the next tile");
        assertFalse(a.guessed(1));
        assertEquals(a.offers().size(), a.layer().size());
        ApprenticeMake.spec(BuilderAllowlist.REVIEWED, LibraryPresets.SHIPPED, TREE, a.offers().get(0));
    }

    @Test
    @DisplayName("'none', a first guess under the threshold, or a pick the make cannot take: the keyword answer, unchanged")
    void theLayerAddsNothingWhenItIsNotSure() {
        String wish = "a wolf that zorbles";
        Apprentice.Answer plain = APPRENTICE.match(wish);
        assertEquals(plain, APPRENTICE.match(wish, says(ClauseReader.NONE, 0.8, "abilities=ability:fire_breath", 0.15), 0.5));
        assertEquals(plain, APPRENTICE.match(wish, says("abilities=ability:fire_breath", 0.49), 0.5));
        assertEquals(plain, APPRENTICE.match(wish, says("abilities=ability:no_such_pick", 0.9, "nonsense", 0.9, "from=species/wolf", 0.9), 0.5));
        assertEquals(plain, APPRENTICE.match(wish, says(), 0.5));
        assertEquals(plain, APPRENTICE.match(wish, null, 0.5));
        assertEquals(List.of("zorbles"), plain.unmatched());
    }

    @Test
    @DisplayName("the layer's second and third choices are further tiles, each a third of the threshold or better")
    void theLayersOtherChoicesAreTiles() {
        Apprentice.Answer a = APPRENTICE.match("a wolf that zorbles", says("abilities=ability:fire_breath", 0.6,
                "abilities=ability:frost_breath", 0.2, "powers=power:glowing", 0.17, "powers=power:flies", 0.01), 0.5);
        assertEquals(List.of("ability:fire_breath"), a.offers().get(0).picks().get("abilities"));
        assertEquals(List.of("ability:frost_breath"), a.offers().get(1).picks().get("abilities"));
        assertEquals(List.of("power:glowing"), a.offers().get(2).picks().get("powers"));
        assertTrue(a.guessed(0) && a.guessed(1) && a.guessed(2));
        assertFalse(a.guessed(3), "then the keyword makes: " + a.offers());
        for (Apprentice.Pick p : a.offers()) {
            assertFalse(p.picks().getOrDefault("powers", List.of()).contains("power:flies"), "under a third of the threshold is not shown");
        }
    }

    @Test
    @DisplayName("words the keywords read are never handed to the layer")
    void aReadClauseIsLeftAlone() {
        ClauseReader never = clause -> {
            throw new AssertionError("asked about: " + clause);
        };
        for (String wish : List.of("a wolf with wings", "a green dino i can ride", "zorble", "")) {
            assertFalse(APPRENTICE.wantsReader(wish), wish);
            assertEquals(APPRENTICE.match(wish), APPRENTICE.match(wish, never, 0.5), wish);
        }
        // one clause read, one not: only the unread one is asked about
        List<String> asked = new java.util.ArrayList<>();
        Apprentice.Answer a = APPRENTICE.match("a wolf that zorbles and has wings", clause -> {
            asked.add(clause);
            return List.of(new ClauseReader.Guess("abilities=ability:fire_breath", 0.9));
        }, 0.5);
        assertEquals(List.of("that zorbles"), asked);
        assertEquals(List.of("kit:wing"), a.offers().get(0).picks().get("parts"), "what the keywords read is kept");
        assertEquals(List.of("ability:fire_breath"), a.offers().get(0).picks().get("abilities"));
    }

    @Test
    @DisplayName("a pick is only added where the Builder would let a player press it")
    void aPickIsOnlyAddedWhereTheBuilderAllows() {
        Apprentice.Pick wolf = APPRENTICE.match("a wolf").offers().get(0);
        Apprentice.Pick fiery = Apprentice.canAdd(TREE, wolf, "abilities", "ability:fire_breath");
        assertEquals(List.of("ability:fire_breath"), fiery.picks().get("abilities"));
        assertEquals(null, Apprentice.canAdd(TREE, fiery, "abilities", "ability:fire_breath"), "already there");
        assertEquals(null, Apprentice.canAdd(TREE, wolf, "abilities", "ability:nope"));
        assertEquals(null, Apprentice.canAdd(TREE, wolf, "nope", "ability:fire_breath"));
        Apprentice.Pick small = Apprentice.canAdd(TREE, wolf, "mobsize", "3");
        assertEquals(null, Apprentice.canAdd(TREE, small, "mobsize", "5"), "a pick-one slot that is already picked has no room");
    }
}
