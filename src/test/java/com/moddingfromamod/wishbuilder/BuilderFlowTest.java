package com.moddingfromamod.wishbuilder;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.moddingfromamod.ui.PickPage;
import com.moddingfromamod.ui.TuneDialog;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** every path through the Builder's menus, as a child presses them, ends in a spec the tools take. */
class BuilderFlowTest {

    static BuilderFlow flow(BuilderAllowlist allow) {
        return new BuilderFlow(allow, LibraryPresets.SHIPPED);
    }

    /** past "Turn things into…"'s own two pages, if the flow is on them: its first From, then its first To. */
    static void pastTurnInto(BuilderFlow f) {
        if (f.step() == BuilderFlow.Step.TURN_FROM) {
            f.press(ids(f.page()).get(0));
            f.press(PickPage.NEXT_ID);
            f.press(ids(f.page()).get(0));
        }
    }

    static List<String> ids(PickPage p) {
        return p.tiles().stream().map(PickPage.Tile::id).toList();
    }

    @Test
    void everyKindOnTheReviewedListHasTilesAndEveryPathLowers() throws Exception {
        for (BuilderSpec.Kind k : BuilderSpec.Kind.values()) {
            BuilderFlow f = flow(BuilderAllowlist.REVIEWED);
            assertTrue(ids(f.page()).contains(k.id()), k + " is on the first page");
            f.press(k.id());
            if (k == BuilderSpec.Kind.RULE) {
                f.press(ids(f.page()).get(0));
                f.press(ids(f.page()).get(0));
            } else {
                List<String> from = ids(f.page());
                assertFalse(from.isEmpty(), k + " has something to start from");
                f.press(from.get(0).equals(BuilderFlow.MAKE_UP_ID) ? from.get(1) : from.get(0));
                if (f.step() == BuilderFlow.Step.ITEM_POWER) {
                    f.press(ids(f.page()).get(0));   //: an item is a stock item and a power
                }
                if (f.step() == BuilderFlow.Step.BUILD_ROOF) {   //: a simple building: its roof, then windows
                    f.press(ids(f.page()).get(0));
                    f.press(ids(f.page()).get(0));
                }
                if (f.step() == BuilderFlow.Step.BUILD_OUTSIDE) {   //: what goes outside a building (several), then Done
                    f.press(ids(f.page()).get(0));
                    f.press(com.moddingfromamod.ui.PickPage.NEXT_ID);
                }
                if (f.step() == BuilderFlow.Step.SUPPLY_WHAT) {   //: a category, then the supply
                    f.press(ids(f.page()).get(0));
                }
                if (f.step() == BuilderFlow.Step.EVENT_WHAT) {   //: what an event is made of
                    f.press(ids(f.page()).get(0));
                }
                if (f.step() == BuilderFlow.Step.PICT_BACKGROUND) {   //: what is behind a picture
                    f.press(ids(f.page()).get(0));
                }
                if (f.step() == BuilderFlow.Step.BLOCK_WHEN) {   //: a magic block is a look, a trigger, an effect
                    f.press(ids(f.page()).get(0));
                    f.press(ids(f.page()).get(0));
                }
                if (f.step() == BuilderFlow.Step.SNACK_POWER) {   //: a machine is a look, a power, what goes in
                    f.press(ids(f.page()).get(0));
                    f.press(com.moddingfromamod.ui.PickPage.NEXT_ID);
                    f.press(ids(f.page()).get(0));
                }
                if (f.step() == BuilderFlow.Step.PARTS || f.step() == BuilderFlow.Step.POWERS) {
                    pastPowers(f);
                }
            }
            assertEquals(BuilderFlow.Step.TUNE, f.step(), k.toString());
            TuneDialog d = f.tune();
            assertTrue(d.inputs().size() <= TuneDialog.MAX_INPUTS);
            assertFalse(d.keys().contains("name"), k + ": Moddy names it; the dialog asks no name");
            BuilderSpec spec = f.spec(Map.of());
            assertTrue(BuilderAllowlist.REVIEWED.problems(spec).isEmpty(), k + ": " + BuilderAllowlist.REVIEWED.problems(spec));
            assertFalse(BuilderLowering.lower(spec, "x_1", LibraryPresets.SHIPPED).isEmpty(), k.toString());
        }
    }

    @Test
    void anItemIsMadeFromEveryStockItemAndEveryPowerThatFitsIt() {
        // the item kind opens on "What is it?" (the stock items) → what does it do → the tune dialog.
        // on a world that allows lava (the Place wand's lava is the one power behind the world's lava knob).
        BuilderAllowlist lava = BuilderAllowlist.REVIEWED;   // lava is offered everywhere
        for (BuilderItemPowers.Stock st : BuilderItemPowers.STOCK) {
            for (BuilderItemPowers.Power w : BuilderItemPowers.fitting(st)) {
                BuilderFlow f = flow(lava);
                f.press("item");
                assertEquals(BuilderFlow.Step.SHAPE, f.step(), "the item kind starts at its stock items");
                assertFalse(ids(f.page()).contains(BuilderFlow.MAKE_UP_ID), "no separate 'make one up'");
                f.press(BuilderFlow.STOCK_PREFIX + st.id());
                assertEquals(BuilderFlow.Step.ITEM_POWER, f.step());
                assertTrue(ids(f.page()).contains("itempower:" + w.id()), st.id() + " offers " + w.id());
                f.press("itempower:" + w.id());
                pastTurnInto(f);
                assertEquals(BuilderFlow.Step.TUNE, f.step());
                TuneDialog d = f.tune();
                assertTrue(d.keys().contains("strength"), "a made-up item asks how strong");
                assertTrue(d.inputs().size() <= TuneDialog.MAX_INPUTS);
                BuilderSpec spec = f.spec(Map.of("strength", "3"));
                assertTrue(lava.problems(spec).isEmpty(), lava.problems(spec).toString());
                var calls = BuilderLowering.lower(spec, "x_1", LibraryPresets.SHIPPED);
                var make = calls.stream().filter(c -> c.tool().equals("make_item")).findFirst().orElseThrow();
                if (w.effect().isEmpty()) {   //: "Just for fun" is make_item's plain item, no effect
                    assertFalse(make.args().has("effect"));
                    assertEquals(com.moddingfromamod.datapack.ItemCatalogue.PLAIN, make.args().get("shape").getAsString());
                    continue;
                }
                assertEquals(w.effect(), make.args().get("effect").getAsString());
                assertEquals(st.shape(), make.args().get("shape").getAsString());
                assertEquals(w.id().equals(BuilderTurnInto.POWER) ? java.util.Set.of("FROM", "TO", "RADIUS") : w.params(3).keySet(),
                        make.args().getAsJsonObject("params").keySet());
                assertEquals(BuilderItemPowers.name(st.id(), w.id(), spec.opt(BuilderTurnInto.TO_SLOT)), BuilderLowering.label(spec));
            }
        }
    }

    @Test
    void theItemKindHasNoReadyMadePage() {
        BuilderFlow f = flow(BuilderAllowlist.REVIEWED);
        assertTrue(f.fromIds(BuilderSpec.Kind.ITEM).isEmpty(), "ready-made items are compositions now (#1988)");
        f.press("item");
        assertEquals(BuilderFlow.Step.SHAPE, f.step());
        assertTrue(ids(f.page()).size() >= 15, "15–20 stock items: " + ids(f.page()));
        f.press(PickPage.BACK_ID);
        assertEquals(BuilderFlow.Step.KIND, f.step(), "back from 'What is it?' is the kind page");
    }

    @Test
    void aSwordNeverOffersAWandsPowers() {
        BuilderFlow f = flow(BuilderAllowlist.REVIEWED);
        f.press("item");
        f.press("stock:sword");
        assertFalse(ids(f.page()).contains("itempower:teleport"));
        assertFalse(f.press("itempower:teleport").step() == BuilderFlow.Step.TUNE, "a wand's power is refused on a sword");
    }

    @Test
    void aPickaxeOffersOnlyWhatMiningCanDo() {
        BuilderFlow f = flow(BuilderAllowlist.REVIEWED);
        f.press("item");
        f.press("stock:pickaxe");
        List<String> got = ids(f.page());
        assertTrue(got.contains("itempower:haste") && got.contains("itempower:glow"), got.toString());
        assertFalse(got.contains("itempower:lightning"), "a bolt where he mines would strike HIM");
        assertFalse(got.contains("itempower:freeze"), got.toString());
    }

    /** Colours is offered where a scheme shows (a pickaxe is tinted, boots dyed), and not on the trident. */
    @Test
    void colourIsOfferedOnlyWhereItShows() {
        for (String[] c : new String[][] {{"pickaxe", "haste", "yes"}, {"bow", "freeze", "yes"}, {"boots", "jump", "yes"},
                {"wand", "teleport", "yes"}, {"drink", "speed", "yes"}, {"trident", "lightning", "no"}}) {
            BuilderFlow f = flow(BuilderAllowlist.REVIEWED);
            f.press("item");
            f.press("stock:" + c[0]);
            f.press("itempower:" + c[1]);
            assertEquals(BuilderFlow.Step.TUNE, f.step(), c[0]);
            boolean colours = f.tune().inputs().stream().anyMatch(i -> i.key().equals("scheme"));
            assertEquals(c[2].equals("yes"), colours, c[0] + ": Colours offered? " + colours);
        }
    }

    @Test
    void theMagicBlockAndTheMachineKindsAreComposed() {
        BuilderFlow f = flow(BuilderAllowlist.REVIEWED);
        assertTrue(f.fromIds(BuilderSpec.Kind.MACHINE).isEmpty(), "#1993: every machine is composed");
        assertFalse(f.fromIds(BuilderSpec.Kind.ITEM).stream().anyMatch(i -> i.startsWith("outputs/")),
                "a machine's food is never a tile of its own");
        assertTrue(f.fromIds(BuilderSpec.Kind.MAGIC_BLOCK).isEmpty(), "#1994: every magic block is composed");
        assertFalse(f.fromIds(BuilderSpec.Kind.EVENT).contains("events/meteor_crater"), "a follow-on design is not an event");
    }

    @Test
    void powersAreTilesAsManyAsHeLikesAndATemperamentIsOne() {
        // a power is a flag on the creature — no tool limits them, so neither does the page.
        BuilderFlow f = flow(BuilderAllowlist.REVIEWED);
        f.press("creature");
        f.press("species/cat");
        toPowers(f);
        List<String> all = f.page().tiles().stream().map(PickPage.Tile::id).filter(id -> id.startsWith("power:")).toList();
        assertTrue(all.size() > 2, "more than two powers are offered: " + all);
        for (String p : all) {
            assertTrue(f.press(p).say().isEmpty(), p + " is taken, whatever came before");
        }
        assertTrue(f.page().tiles().stream().filter(PickPage.Tile::selected).map(PickPage.Tile::id).toList()
                .containsAll(all), "every power is on");
        assertTrue(f.page().picks().blocked().isEmpty(), "and nothing is greyed");
        for (String p : all) {
            if (!p.equals("power:glowing")) {
                f.press(p);   // tap again: off
            }
        }
        f.press("temper:shy");
        pastPowers(f);
        BuilderSpec s = f.spec(Map.of("size", "3", "count", "2.0", "scheme", "ocean", "name", "Blaze Jr"));
        assertEquals(List.of("power:glowing"), s.powers());
        assertEquals("shy", s.temper());
        assertEquals(3, s.size());
        assertEquals(2, s.count(), "a Bedrock slider may send 2.0");
        assertEquals(BuilderNames.name(s, LibraryPresets.SHIPPED, List.of()), s.name(), "a sent name is not used");
    }

    @Test
    void aBuilderCreatureIsSizedByNameAndScaledByIt() {
        // a body's size is one of the named sizes, each its own scale.
        BuilderFlow f = flow(BuilderAllowlist.REVIEWED);
        f.press("creature");
        f.press("species/cat");
        toPowers(f);
        pastPowers(f);
        assertTrue(f.tune().keys().contains("mobsize") && !f.tune().keys().contains("size"), "sized by name: " + f.tune().keys());
        BuilderSpec giant = f.spec(Map.of("mobsize", "7"));
        BuilderSpec normal = f.spec(Map.of("mobsize", BuilderMobSizes.NORMAL));
        assertEquals("7", giant.opt("mobsize"));
        assertEquals(4, giant.size(), "its old step follows, for its name and its behaviour");
        assertEquals("", normal.opt("mobsize"), "unnamed is as it comes");
        assertEquals(2, normal.size());
        assertEquals(4.0, scale(giant) / scale(normal), 1e-9, "Giant is four times the cat");
        BuilderSpec old = f.spec(Map.of("size", "3"));   // a spec from before named sizes still lowers its old way
        assertEquals("", old.opt("mobsize"));
        assertEquals(1.6, scale(old) / scale(normal), 1e-9);
    }

    private static double scale(BuilderSpec spec) {
        for (BuilderLowering.Call c : BuilderLowering.lower(spec, "x_1", LibraryPresets.SHIPPED)) {
            if (c.args().has("scale")) {
                return c.args().get("scale").getAsDouble();
            }
        }
        throw new AssertionError("no scale in " + BuilderLowering.lower(spec, "x_1", LibraryPresets.SHIPPED));
    }

    @Test
    void aRideHasNoRideableTileItIsOneAlready() {
        BuilderFlow f = flow(BuilderAllowlist.REVIEWED);
        f.press("ride");
        f.press("species/pet_dragon");
        toPowers(f);
        assertFalse(ids(f.page()).contains("power:rideable"));
    }

    @Test
    void moddyNamesItAndNoNameCanBeSent() {
        BuilderFlow f = flow(BuilderAllowlist.REVIEWED);
        f.press("creature");
        f.press("species/frog");
        toPowers(f);
        pastPowers(f);
        TuneDialog d = f.tune();
        assertFalse(d.keys().contains("name"), "no name control");
        BuilderSpec s = f.spec(Map.of("name", "something rude typed by hand"));
        assertTrue(BuilderNames.valid(s.name()) && !s.name().contains("rude"), "a sent name is ignored: " + s.name());
        assertEquals(BuilderNames.name(s, LibraryPresets.SHIPPED, List.of()), s.name());
    }

    @Test
    void theMakeCommandsTailIsReadInTheDialogsOrderWithTheNameLast() {
        BuilderFlow f = flow(BuilderAllowlist.REVIEWED);
        f.press("creature");
        f.press("species/cat");
        toPowers(f);
        pastPowers(f);
        // #103: size, colours, how fast, how strong (How strong took How many's place; a creature's count is typed)
        assertEquals(Map.of("mobsize", "4", "scheme", "candy", "speed", "2", "strength", "3"), f.values("4 candy 2 3"));
    }

    @Test
    void aPressOffTheWorldsListIsRefused() {
        var noRides = new BuilderAllowlist(java.util.Set.of(BuilderSpec.Kind.CREATURE),
                BuilderAllowlist.REVIEWED.ids(), java.util.Set.of("friendly"), 1);
        BuilderFlow f = flow(noRides);
        assertEquals(List.of("creature"), ids(f.page()));
        assertFalse(f.press("ride").say().isEmpty());
        f.press("creature");
        f.press("species/cat");
        toPowers(f);
        assertFalse(f.press("temper:boss").say().isEmpty());
        pastPowers(f);
        assertTrue(f.tune().inputs().stream().noneMatch(i -> i.key().equals("count")), "maxCount 1: no count slider");
    }

    @Test
    void backWalksBackAndCloseCloses() {
        BuilderFlow f = flow(BuilderAllowlist.REVIEWED);
        f.press("building");
        assertEquals(BuilderFlow.Step.BUILD_TYPE, f.step(), "#1995: a building opens on its types");
        f.press(PickPage.BACK_ID);
        assertEquals(BuilderFlow.Step.KIND, f.step());
        assertTrue(f.press(PickPage.CLOSE_ID).close());
    }

    @Test
    void ownIslandTakesRulesOffEvenWhenAWorldListsThem() {
        BuilderSettings s = BuilderSettings.parse("{\"crafters\":{\"builder\":{\"enabled\":true,\"ruleScope\":\"own_island\","
                + "\"kinds\":[\"rule\",\"creature\"]}}}");
        assertEquals(java.util.Set.of(BuilderSpec.Kind.CREATURE), s.allow().kinds());
        assertNull(null);
        assertTrue(BuilderSettings.parse("{}").enabled(), "no block = on (the Builder is always on)");
        assertFalse(BuilderSettings.parse("{\"crafters\":{\"builder\":{\"enabled\":false}}}").enabled(),
                "only enabled:false turns it off");
    }

    // ------------------------------------------------------------------: "change one thing"

    @Test
    void anEditedCreatureReopensAtItsPowersWithEveryPickAlreadyMade() {
        BuilderSpec was = BuilderSpec.read(BuilderSpecTest.j(BuilderExamples.ONE_PER_KIND.get(0)));
        BuilderFlow f = BuilderFlow.editing(BuilderAllowlist.REVIEWED, LibraryPresets.SHIPPED, "blueberry_1", was);
        assertEquals(BuilderFlow.Step.POWERS, f.step());
        assertEquals("blueberry_1", f.editOf());
        assertTrue(f.page().tiles().stream().anyMatch(t -> t.id().equals("power:glowing") && t.selected()), "his power is ticked");
        assertTrue(f.page().tiles().stream().anyMatch(t -> t.id().equals("temper:shy") && t.selected()), "his temperament");
        pastPowers(f);
        TuneDialog d = f.tune();
        assertFalse(d.keys().contains("count"), "an edit adds none, so it asks no 'how many'");
        assertTrue(d.title().startsWith("Change your"), d.title());
        // Change one thing: the size. Everything else comes back as it was.
        BuilderSpec now = f.spec(Map.of("size", "4", "scheme", "ocean", "name", "Blueberry"));
        assertEquals(4, now.size());
        assertEquals(was.from(), now.from());
        assertEquals(was.powers(), now.powers());
        assertEquals(was.temper(), now.temper());
        assertEquals(was.scheme(), now.scheme());
        assertEquals(was.name(), now.name());
    }

    @Test
    void theDialogStartsAtHisSizeColoursAndName() {
        BuilderSpec was = BuilderSpec.read(BuilderSpecTest.j(BuilderExamples.ONE_PER_KIND.get(6)));
        BuilderFlow f = BuilderFlow.editing(BuilderAllowlist.REVIEWED, LibraryPresets.SHIPPED, "fort_1", was);
        assertEquals(BuilderFlow.Step.BUILD_TYPE, f.step(), "a building's edit opens at its type (#2281)");
        EditWalk.toTune(f);
        String dialog = f.tune().toString();
        // his treehouse was size 4, which no longer works (BuilderSizes): it opens at the biggest that does.
        int his = BuilderSizes.clamp("secret_treehouse", 4);
        assertTrue(dialog.contains("initial=" + his), "his size: " + dialog);
        assertTrue(dialog.contains("initial=royal"), "his colours: " + dialog);
        assertEquals(his, f.spec(Map.of()).size(), "a value the dialog did not send is his, not the default");
    }

    @Test
    void anEditedRuleReopensAtWhenAndCannotBecomeAnotherKind() {
        BuilderSpec was = BuilderSpec.read(BuilderSpecTest.j(BuilderExamples.ONE_PER_KIND.get(9)));
        BuilderFlow f = BuilderFlow.editing(BuilderAllowlist.REVIEWED, LibraryPresets.SHIPPED, "rule_1", was);
        assertEquals(BuilderFlow.Step.WHEN, f.step());
        f.press(PickPage.BACK_ID);
        assertEquals(BuilderFlow.Step.WHEN, f.step(), "back does not leave the kind he is changing");
    }

    @Test
    void anEditKeepsTheNameItWasGiven() {
        BuilderSpec was = BuilderSpec.read(BuilderSpecTest.j(
                "{\"builder\":1,\"kind\":\"building\",\"from\":\"preset:buildings/secret_treehouse\",\"name\":\"Comet\"}"));
        BuilderFlow f = BuilderFlow.editing(BuilderAllowlist.REVIEWED, LibraryPresets.SHIPPED, "fort_1", was);
        assertEquals("Comet", f.spec(Map.of()).name(), "a name that is not Moddy's (an old pick) is kept");
    }

    // ------------------------------------------------------------------: rules about a mob

    @Test
    void aMobRuleAsksWhichMobAndOffersOnlyWhatFitsIt() {
        BuilderFlow f = flow(BuilderAllowlist.REVIEWED);
        f.press("rule");
        assertEquals(BuilderFlow.Step.WHEN, f.step());
        f.press("when:mob_spawns");
        assertEquals(BuilderFlow.Step.WHO, f.step(), "a mob trigger asks which mob");
        assertTrue(ids(f.page()).contains("mob:minecraft:cow"));
        f.press("mob:minecraft:cow");
        assertEquals(BuilderFlow.Step.DOES, f.step());
        List<String> does = ids(f.page());
        assertTrue(does.contains("rule:huge") && does.contains("rule:tiny"), does.toString());
        assertFalse(does.contains("rule:hat"), "a cow draws no helmet");
        assertFalse(does.contains("rule:diamond"), "a drop follows a defeat, not an arrival");
        f.press(PickPage.BACK_ID);
        assertEquals(BuilderFlow.Step.WHO, f.step(), "back from what happens goes to which mob");
        f.press("mob:minecraft:cow");
        f.press("rule:huge");
        var spec = f.spec(Map.of());
        assertEquals("mob:minecraft:cow", spec.opt("who"));
        var calls = BuilderLowering.lower(spec, "r1", LibraryPresets.SHIPPED);
        var a = calls.stream().filter(c -> c.tool().equals("set_rule")).findFirst().orElseThrow().args();
        assertEquals("minecraft:cow", a.get("who").getAsString());
        assertEquals("mob_spawns", a.get("when").getAsString());
    }

    @Test
    void hisOwnTriggersAskNoMobAndOfferNoMobEffects() {
        BuilderFlow f = flow(BuilderAllowlist.REVIEWED);
        f.press("rule");
        f.press("when:jumps");
        assertEquals(BuilderFlow.Step.DOES, f.step());
        List<String> does = ids(f.page());
        assertTrue(does.contains("rule:bounce"));
        assertFalse(does.contains("rule:huge"), "a boy who jumps is never resized (#1813)");
        assertFalse(does.contains("rule:cake"));
    }

    // ------------------------------------------------------------------: more ready-mades than a chest holds

    @Test
    void theReadyMadesTurnPagesWithMoreAndBackTurnsThemBack() {
        BuilderFlow f = flow(BuilderAllowlist.REVIEWED);
        f.press("creature");
        PickPage first = f.page();
        assertTrue(first.next(), "more creatures than one chest: the page has More");
        assertEquals("More", first.nextLabel());
        assertTrue(first.tiles().size() <= PickPage.MAX_TILES);
        f.press(PickPage.NEXT_ID);
        PickPage second = f.page();
        assertFalse(ids(first).stream().anyMatch(ids(second)::contains), "the second page shows the rest");
        assertTrue(second.title().contains("2/"), second.title());
        f.press(PickPage.BACK_ID);
        assertEquals(BuilderFlow.Step.FROM, f.step(), "back from page 2 is page 1, not the kinds");
        assertEquals(ids(first), ids(f.page()));
        f.press(ids(second).get(0));   // a tile from another page is still his to pick
        // (its parts page, or its powers when it is a vehicle, which takes no creature parts:)
        assertEquals(f.vehicle() ? BuilderFlow.Step.POWERS : BuilderFlow.Step.PARTS, f.step());
    }

    @Test
    void aRideListsTheRideableOnesFirst() {
        BuilderFlow f = flow(BuilderAllowlist.REVIEWED);
        f.press("ride");
        List<String> first = ids(f.page());
        assertTrue(first.indexOf("bodies/truck") >= 0 && first.indexOf("bodies/truck") < first.indexOf("species/cat"),
                "a truck before a cat: " + first);
    }

    /**
     * a VEHICLE has no parts step. Vehicle parts were dropped — they did not look
     * right and wings did not earn their complexity — so a ride whose body is a vehicle goes body → powers → … → tune,
     * never through a parts page, forward or back, and no page on the way offers a kit. An animal-bodied ride keeps its
     * creature parts page (those are approved).
     */
    @Test
    void aVehicleBodyHasNoPartsStep() {
        BuilderFlow f = flow(BuilderAllowlist.REVIEWED);
        f.press("ride");
        List<String> bodies = new ArrayList<>();
        for (int page = 0; page < 20; page++) {   // More on the last page turns back to the first: stop at a repeat
            List<String> here = ids(f.page());
            if (here.isEmpty() || bodies.contains(here.get(0))) {
                break;
            }
            bodies.addAll(here);
            if (!f.page().next()) {
                break;
            }
            f.press(PickPage.NEXT_ID);
        }
        bodies.removeIf(id -> id.startsWith("@"));   // the page's own buttons (More, Back), not bodies
        int vehicles = 0;
        int animals = 0;
        for (String body : bodies) {
            BuilderFlow g = flow(BuilderAllowlist.REVIEWED);
            g.press("ride");
            g.press(body);
            if (!g.vehicle()) {
                animals += g.step() == BuilderFlow.Step.PARTS ? 1 : 0;
                continue;
            }
            vehicles++;
            assertEquals(BuilderFlow.Step.POWERS, g.step(), body + ": a vehicle goes from its body straight to its powers");
            assertTrue(g.partTiles().isEmpty(), body + ": a vehicle is offered no parts");
            int pages = 0;
            for (int guard = 0; g.step() != BuilderFlow.Step.TUNE; guard++) {
                assertTrue(guard < 10, body + ": Next never reached the dialog, stuck on " + g.step());
                assertNotEquals(BuilderFlow.Step.PARTS, g.step(), body + ": no parts page on the way to the dialog");
                assertFalse(ids(g.page()).stream().anyMatch(id -> id.startsWith("kit:")), body + ": a kit on " + g.step());
                g.press(PickPage.NEXT_ID);
                pages++;
            }
            // Back, from the last page before the dialog (the dialog's own Back is the client's, not a press here)
            BuilderFlow h = flow(BuilderAllowlist.REVIEWED);
            h.press("ride");
            h.press(body);
            for (int k = 1; k < pages; k++) {
                h.press(PickPage.NEXT_ID);
            }
            for (int guard = 0; h.step() != BuilderFlow.Step.FROM; guard++) {
                assertTrue(guard < 10, body + ": Back never reached the body page, stuck on " + h.step());
                assertNotEquals(BuilderFlow.Step.PARTS, h.step(), body + ": Back never lands on a parts page");
                h.press(PickPage.BACK_ID);
            }
        }
        assertTrue(vehicles >= 3, "the ride page offers vehicles to check: " + bodies);
        assertTrue(animals >= 1, "an animal-bodied ride still has its creature parts page: " + bodies);
    }

    /** Next on the parts, powers and special moves pages, to the tune dialog. */
    static void pastPowers(BuilderFlow f) {
        while (f.step() == BuilderFlow.Step.PARTS || f.step() == BuilderFlow.Step.POWERS
                || f.step() == BuilderFlow.Step.ABILITIES) {
            f.press(PickPage.NEXT_ID);
        }
    }

    /** Past the parts page a ready-made creature opens on, to its powers. */
    static void toPowers(BuilderFlow f) {
        if (f.step() == BuilderFlow.Step.PARTS) {
            f.press(PickPage.NEXT_ID);
        }
    }
}
