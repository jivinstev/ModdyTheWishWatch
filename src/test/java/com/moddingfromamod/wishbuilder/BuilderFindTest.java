package com.moddingfromamod.wishbuilder;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.moddingfromamod.ui.PickPage;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * <b>The Builder's 🔍 Find</b> (§2): a child's words, misspelt, run together or described, find the installed
 * thing, never a refused one, and a found pick makes exactly what a page's own pick would. Over the committed snapshot of
 * the game's names (content/Found off the game); {@code BuilderFindGameTest} drives the real menu in the game.
 */
class BuilderFindTest {

    static List<String> top(String typed, int n) {
        List<String> ids = BuilderFind.search(typed, r -> true).stream().map(BuilderFind.Result::id).toList();
        return ids.subList(0, Math.min(n, ids.size()));
    }

    @Test
    void eachKindOfTypoFindsTheThingInTheTopThree() {
        Map<String, String> wants = new java.util.LinkedHashMap<>();
        wants.put("honny blok", "minecraft:honey_block");          // spelt as heard
        wants.put("gold blok", "minecraft:gold_block");
        wants.put("glowstone", "minecraft:glowstone");
        wants.put("glostone", "minecraft:glowstone");              // a letter dropped
        wants.put("packedice", "minecraft:packed_ice");            // run together
        wants.put("sandd", "minecraft:sand");                      // a letter held down
        wants.put("honey blocks", "minecraft:honey_block");        // a plural
        wants.forEach((typed, id) -> assertTrue(top(typed, 3).contains(id), typed + " → " + top(typed, 9)));
    }

    @Test
    void aDescriptionStillFindsSomethingAndNothingFoundIsEmpty() {
        assertFalse(top("shiny blue block", 9).isEmpty());
        assertTrue(top("zzqxv", 9).isEmpty());
        assertTrue(top("   ", 9).isEmpty());
    }

    @Test
    void refusedKindsAreNeverOffered() {
        for (String typed : List.of("command block", "structure block", "barrier", "spawn egg", "debug stick", "chest",
                "nether portal", "bedrock", "spawner", "water", "lava")) {
            for (String id : top(typed, 9)) {
                assertTrue(!BuilderFind.refused(id), typed + " offered " + id);
            }
        }
        assertTrue(BuilderFind.refused("minecraft:chest"));
        assertTrue(BuilderFind.refused("othermod:copper_chest"));
        assertTrue(BuilderFind.refused("moddingfromamod:moddy_watch"));
        assertFalse(BuilderFind.refused("minecraft:diamond_chestplate"));
        assertNull(BuilderFind.of("minecraft:command_block"));
        assertNotNull(BuilderFind.of("minecraft:tuff"));
    }

    @Test
    void atMostNineAndTheSameEveryTime() {
        List<String> a = top("wool", 99);
        assertTrue(a.size() <= BuilderFind.MOST);
        assertEquals(a, top("wool", 99));
    }

    @Test
    void theTurnIntoPagesHaveAFindAndAFoundPickMakesWhatAPickOfTheirOwnWould() {
        BuilderFlow f = BuilderTurnIntoTest.toTurnInto("wand");
        assertTrue(BuilderTurnIntoTest.ids(f.page()).contains(BuilderFind.TILE_ID));
        f.press(BuilderFind.TILE_ID);
        assertTrue(f.asking());
        assertTrue(f.findBox().template().contains("builder find " + f.token));
        f.find("tuf");
        assertTrue(f.found().stream().anyMatch(r -> r.id().equals("minecraft:tuff")), f.found().toString());
        f.press(BuilderFind.RESULT_PREFIX + "minecraft:tuff");
        assertEquals(BuilderFlow.Step.TURN_FROM, f.step());
        assertTrue(BuilderTurnIntoTest.ids(f.page()).contains(BuilderTurnInto.FROM_PREFIX + "found:minecraft:tuff"));
        f.press(PickPage.NEXT_ID);
        assertEquals(BuilderFlow.Step.TURN_TO, f.step());
        // A To found by name, then made
        f.press(BuilderFind.TILE_ID);
        f.find("honny blok");
        assertEquals("minecraft:honey_block", f.found().get(0).id());
        f.press(BuilderFind.RESULT_PREFIX + "minecraft:honey_block");
        assertEquals(BuilderFlow.Step.TUNE, f.step());
        BuilderSpec spec = f.spec(f.values(""));
        assertEquals("found:minecraft:tuff", spec.opt(BuilderTurnInto.FROM_SLOT));
        assertEquals("found:minecraft:honey_block", spec.opt(BuilderTurnInto.TO_SLOT));
        Map<String, String> p = BuilderTurnInto.params("held", BuilderTurnInto.parse(spec.opt(BuilderTurnInto.FROM_SLOT)),
                BuilderTurnInto.to(spec.opt(BuilderTurnInto.TO_SLOT)), 2);
        assertEquals("minecraft:tuff", p.get("FROM"));
        assertEquals("minecraft:honey_block", p.get("TO"));
        assertEquals(null, BuilderTurnIntoTest.makeItemSays("held", BuilderTurnInto.parse(spec.opt(BuilderTurnInto.FROM_SLOT)),
                BuilderTurnInto.to(spec.opt(BuilderTurnInto.TO_SLOT))));
    }

    @Test
    void aSearchOffersOnlyWhatTheItemCanDo() {
        // A sword changes blocks: an item with no block form (an apple) is never a From on it
        BuilderFlow sword = BuilderTurnIntoTest.toTurnInto("sword");
        sword.press(BuilderFind.TILE_ID);
        sword.find("apple");
        assertTrue(sword.found().stream().noneMatch(r -> r.id().equals("minecraft:apple")), sword.found().toString());
        assertEquals(BuilderFind.NOTHING_TITLE.equals(sword.page().title()), sword.found().isEmpty());
    }

    @Test
    void nothingFoundIsAPageThatSaysSoAndTryAgainOpensTheBoxWithHisWords() {
        BuilderFlow f = BuilderTurnIntoTest.toTurnInto("wand");
        f.press(BuilderFind.TILE_ID);
        f.find("zzqxv");
        assertEquals(BuilderFind.NOTHING_TITLE, f.page().title());
        assertEquals(List.of(BuilderFind.AGAIN_ID), BuilderTurnIntoTest.ids(f.page()));
        f.press(BuilderFind.AGAIN_ID);
        assertTrue(f.asking());
        assertTrue(f.findBox().json().toString().contains("zzqxv"));
        f.find("");   // a blank send is a cancel
        assertFalse(f.asking());
        assertNull(f.found());
        assertEquals(BuilderFlow.Step.TURN_FROM, f.step());
    }

    @Test
    void suppliesFindAnyItem() {
        BuilderFlow f = new BuilderFlow(BuilderAllowlist.REVIEWED, LibraryPresets.SHIPPED);
        f.press("supplies");
        assertTrue(BuilderTurnIntoTest.ids(f.page()).contains(BuilderFind.TILE_ID));
        f.press(BuilderFind.TILE_ID);
        f.find("honny blok");
        f.press(BuilderFind.RESULT_PREFIX + "minecraft:honey_block");
        assertEquals(BuilderFlow.Step.TUNE, f.step());
        assertEquals("item:minecraft:honey_block", f.spec(f.values("")).from());
    }
}
