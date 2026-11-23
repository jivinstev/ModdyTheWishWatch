package com.moddingfromamod.wishbuilder;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.moddingfromamod.ui.PickPage;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * <b>Turn-into's suggestions</b> (§1): what he looks at, what he carries, what he picked last — ranked the same way
 * every time, above the pages' own picks, and each made exactly as the matching searched pick is.
 * {@code BuilderSuggestGameTest} reads a real view and a real inventory.
 */
class BuilderSuggestTest {

    static final BuilderSuggest.Seen WALL = new BuilderSuggest.Seen("minecraft:stone",
            Map.of("minecraft:grass_block", 12, "minecraft:stone", 30, "minecraft:dirt", 3),
            Map.of("minecraft:raw_iron", 5, "minecraft:sand", 20));

    @Test
    void theCrosshairFirstThenTheMostRaysThenTheId() {
        assertEquals(List.of("minecraft:stone", "minecraft:grass_block", "minecraft:dirt"), BuilderSuggest.lookingAt(WALL, id -> true));
        BuilderSuggest.Seen dirtAtTheCrosshair = new BuilderSuggest.Seen("minecraft:dirt", WALL.inView(), Map.of());
        assertEquals(List.of("minecraft:dirt", "minecraft:stone", "minecraft:grass_block"),
                BuilderSuggest.lookingAt(dirtAtTheCrosshair, id -> true));
        // ties by id, at most six
        Map<String, Integer> many = new java.util.HashMap<>();
        for (String b : List.of("b", "a", "d", "c", "f", "e", "g", "h")) {
            many.put("minecraft:" + b, 1);
        }
        assertEquals(List.of("minecraft:a", "minecraft:b", "minecraft:c", "minecraft:d", "minecraft:e", "minecraft:f"),
                BuilderSuggest.lookingAt(new BuilderSuggest.Seen(null, many, Map.of()), id -> true));
        assertEquals(List.of(), BuilderSuggest.lookingAt(BuilderSuggest.Seen.NOTHING, id -> true), "only sky: no section");
        assertEquals(List.of("minecraft:sand", "minecraft:raw_iron"), BuilderSuggest.carrying(WALL, id -> true));
        assertEquals(List.of("glass", "gold"), BuilderSuggest.recent(List.of("glass", "gold", "glass", "ice", "wool", "sand"), id -> true)
                .subList(0, 2));
        assertEquals(4, BuilderSuggest.recent(List.of("glass", "gold", "ice", "wool", "sand"), id -> true).size());
        assertEquals(List.of("gold"), BuilderSuggest.recent(List.of("glass", "gold"), id -> !id.equals("glass")),
                "a To the world forbids now is left out");
    }

    @Test
    void theFromPageOpensWithWhatHeSeesAndCarriesAboveItsOwnPicks() {
        BuilderFlow f = BuilderTurnIntoTest.toTurnInto("wand");
        assertTrue(f.wantsView());
        f.seeing(WALL);
        assertFalse(f.wantsView(), "read once");
        List<PickPage.Tile> tiles = f.page().tiles();
        List<String> ids = tiles.stream().map(PickPage.Tile::id).toList();
        String p = BuilderTurnInto.FROM_PREFIX + BuilderTurnInto.FOUND;
        assertEquals(List.of(p + "minecraft:stone", p + "minecraft:grass_block", p + "minecraft:dirt",
                p + "minecraft:sand", p + "minecraft:raw_iron"), ids.subList(0, 5), ids.toString());
        assertEquals(List.of(BuilderSuggest.LOOKING_LORE), tiles.get(0).lore());
        assertEquals(List.of(BuilderSuggest.CARRYING_LORE), tiles.get(3).lore());
        assertEquals(BuilderTurnInto.FROM_PREFIX + "any_block", ids.get(5), "then the page's own picks");
        // A suggested tile is the same pick as the searched one: one tap, the same make
        f.press(p + "minecraft:stone");
        f.press(PickPage.NEXT_ID);
        f.recentTo(List.of());
        f.press(BuilderTurnInto.TO_PREFIX + "gold");
        BuilderSpec spec = f.spec(f.values(""));
        assertEquals("found:minecraft:stone", spec.opt(BuilderTurnInto.FROM_SLOT));
        assertEquals("minecraft:stone", BuilderTurnInto.params("held", BuilderTurnInto.parse(spec.opt(BuilderTurnInto.FROM_SLOT)),
                BuilderTurnInto.to(spec.opt(BuilderTurnInto.TO_SLOT)), 2).get("FROM"));
    }

    @Test
    void aSwordIsOfferedOnlyTheBlocks() {
        BuilderFlow sword = BuilderTurnIntoTest.toTurnInto("sword");
        sword.seeing(WALL);
        List<String> ids = sword.page().tiles().stream().map(PickPage.Tile::id).toList();
        assertFalse(ids.contains(BuilderTurnInto.FROM_PREFIX + BuilderTurnInto.FOUND + "minecraft:raw_iron"),
                "a sword changes blocks: nothing from his inventory");
        assertTrue(ids.contains(BuilderTurnInto.FROM_PREFIX + BuilderTurnInto.FOUND + "minecraft:stone"));
    }

    @Test
    void theToPageOpensWithWhatHePickedLast() {
        BuilderFlow f = BuilderTurnIntoTest.toTurnInto("wand");
        f.seeing(BuilderSuggest.Seen.NOTHING);
        f.press(BuilderTurnInto.FROM_PREFIX + "sand");
        f.press(PickPage.NEXT_ID);
        assertTrue(f.wantsRecent());
        f.recentTo(List.of("glass", "gold", "nosuch"));
        List<String> ids = f.page().tiles().stream().map(PickPage.Tile::id).toList();
        assertEquals(List.of(BuilderTurnInto.TO_PREFIX + "glass", BuilderTurnInto.TO_PREFIX + "gold"), ids.subList(0, 2));
        assertEquals(1, ids.stream().filter(id -> id.equals(BuilderTurnInto.TO_PREFIX + "gold")).count(), "shown once");
    }
}
