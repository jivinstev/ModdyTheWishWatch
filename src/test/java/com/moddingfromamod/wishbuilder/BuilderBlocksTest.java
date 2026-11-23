package com.moddingfromamod.wishbuilder;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import com.moddingfromamod.datapack.ItemCatalogue;
import com.moddingfromamod.ui.PickPage;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** the magic-block composer's tables and its walk through the menus. */
class BuilderBlocksTest {

    @Test
    void fifteenToTwentyLooksEachARealBlockAndAColour() {
        assertTrue(BuilderBlocks.LOOKS.size() >= 15 && BuilderBlocks.LOOKS.size() <= 20, "" + BuilderBlocks.LOOKS.size());
        Set<String> ids = new HashSet<>();
        for (BuilderBlocks.Look l : BuilderBlocks.LOOKS) {
            assertTrue(ids.add(l.id()), "two looks are " + l.id());
            assertTrue(l.block().matches("minecraft:[a-z_]+"), l.id());
            assertTrue(l.tint().matches("#[0-9A-F]{6}"), l.id());
            assertTrue(l.words().size() >= BuilderTree.MIN_WORDS, l.id());
        }
    }

    @Test
    void everyTriggerIsOneMakeItemRuns() {
        for (BuilderBlocks.When w : BuilderBlocks.WHENS) {
            assertTrue(List.of("every", "near", "stepped_on", "broken").contains(w.id()), w.id());
            assertFalse(BuilderBlocks.fitting(w.id()).isEmpty(), w.id() + " has nothing to do");
        }
    }

    @Test
    void everyEffectIsInTheCatalogueAndFitsAtLeastOneTrigger() {
        for (BuilderBlocks.Does d : BuilderBlocks.DOES) {
            assertTrue(ItemCatalogue.load().get(d.effect()) != null, d.effect());
            assertFalse(d.whens().isEmpty(), d.id());
            assertEquals(3, d.byStrength().size(), d.id());
            assertTrue(d.words().size() >= BuilderTree.MIN_WORDS, d.id());
        }
    }

    /** WISH_BUILDER_PLAN §6.2: what hurts reaches monsters only, and is kept off players. */
    @Test
    void nothingThatHurtsCanReachAPlayer() {
        for (String hurts : List.of("freeze", "zap")) {
            BuilderBlocks.Does d = BuilderBlocks.does(hurts);
            assertEquals("hostile", d.dials().get("who"), hurts);
            assertTrue(d.sparePlayers(), hurts);
        }
        for (BuilderBlocks.Does d : BuilderBlocks.DOES) {
            assertFalse(List.of("launch", "fire", "damage", "explode").contains(d.effect()), d.id());
        }
    }

    @Test
    void aMismatchedPickIsRefused() {
        BuilderSpec spec = new BuilderSpec(BuilderSpec.CURRENT, BuilderSpec.Kind.MAGIC_BLOCK, "", "", 2, List.of(), "", 1, "", "",
                BuilderBlocks.opts("stone", "stepped_on", "lucky", 2));
        assertThrows(IllegalArgumentException.class, () -> BuilderBlocks.args(spec));
    }

    @Test
    void theArgsCarryTheBlockDials() {
        BuilderSpec spec = new BuilderSpec(BuilderSpec.CURRENT, BuilderSpec.Kind.MAGIC_BLOCK, "", "", 2, List.of(), "", 1, "", "",
                BuilderBlocks.opts("ice", "near", "zap", 3));
        JsonObject a = BuilderBlocks.args(spec);
        assertEquals("lightning", a.get("effect").getAsString());
        assertEquals("magic_block", a.get("shape").getAsString());
        JsonObject b = a.getAsJsonObject("params").getAsJsonObject("_block");
        assertEquals("near", b.get("when").getAsString());
        assertEquals("minecraft:packed_ice", b.get("look").getAsString());
        assertEquals("hostile", b.get("who").getAsString());
        assertEquals("on_each", b.get("where").getAsString());
        assertEquals("3", a.getAsJsonObject("params").get("STRIKES").getAsString());
        assertTrue(a.get("spare_players").getAsBoolean());
        assertEquals("#90CAF9", a.get("colour").getAsString());
    }

    @Test
    void theMenusOfferOnlyWhatFitsTheTrigger() {
        for (BuilderBlocks.When w : BuilderBlocks.WHENS) {
            BuilderFlow f = new BuilderFlow(BuilderAllowlist.REVIEWED, LibraryPresets.SHIPPED);
            f.press("magic_block");
            assertEquals(BuilderBlocks.LOOKS.size(), f.page().tiles().size());
            f.press("blocklook:stone");
            f.press("blockwhen:" + w.id());
            List<String> offered = f.page().tiles().stream().map(PickPage.Tile::id).toList();
            assertEquals(BuilderBlocks.fitting(w.id()).stream().map(d -> "blockdoes:" + d.id()).toList(), offered, w.id());
            assertFalse(f.press("blockdoes:" + (w.id().equals("broken") ? "jump" : "lucky")).say().isEmpty(),
                    "a pick that does not fit " + w.id() + " is refused");
        }
    }

    @Test
    void aComposedBlockIsNamedForWhatItDoesAndAsksHowStrong() {
        BuilderFlow f = new BuilderFlow(BuilderAllowlist.REVIEWED, LibraryPresets.SHIPPED);
        f.press("magic_block");
        f.press("blocklook:slime");
        f.press("blockwhen:stepped_on");
        f.press("blockdoes:jump");
        assertEquals("Bouncy Slime Block", f.pickedLabel());
        assertTrue(f.tune().keys().contains("strength"));
        Map<String, String> v = new LinkedHashMap<>();
        v.put("strength", "3");
        BuilderSpec spec = f.spec(v);
        assertTrue(BuilderBlocks.composed(spec));
        assertTrue(BuilderAllowlist.REVIEWED.problems(spec).isEmpty(), BuilderAllowlist.REVIEWED.problems(spec).toString());
        var calls = BuilderLowering.lower(spec, "mine_1", LibraryPresets.SHIPPED);
        JsonObject a = calls.get(1).args();
        assertEquals("jump", a.get("effect").getAsString());
        assertEquals("3", a.getAsJsonObject("params").get("LEVEL").getAsString());
        assertTrue(a.get("only_looks_like").getAsBoolean());
        assertEquals("Bouncy Slime Block", a.get("name").getAsString());
    }

    /** Every composed name fits the tune dialog's name picker, so the first name offered is his block's own. */
    @Test
    void everyNameFitsTheNamePicker() {
        for (BuilderBlocks.Look l : BuilderBlocks.LOOKS) {
            for (BuilderBlocks.Does d : BuilderBlocks.DOES) {
                String n = BuilderBlocks.name(l, d);
                assertTrue(n.length() <= 20, n);
            }
        }
        assertEquals("Glowing Diamond", BuilderBlocks.name(BuilderBlocks.look("diamond"), BuilderBlocks.does("glow")));
    }
}
