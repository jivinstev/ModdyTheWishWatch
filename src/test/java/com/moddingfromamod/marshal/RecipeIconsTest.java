package com.moddingfromamod.marshal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Reading what a recipe command makes — the pure half of a preset's icon. */
class RecipeIconsTest {

    @Test
    @DisplayName("each recipe verb names the thing it makes")
    void eachVerbNamesItsThing() {
        assertEquals(new WatchIcons.RecipeTarget(MadeKind.ITEM, "tiny_cookie_grow"),
                WatchIcons.recipeTarget("wishdatapack tiny_cookie_grow give"));
        assertEquals(new WatchIcons.RecipeTarget(MadeKind.BUILD, "lava_moat_castle"),
                WatchIcons.recipeTarget("/wishbuild lava_moat_castle"));
        assertEquals(new WatchIcons.RecipeTarget(WatchIcons.SPECIES, "pet_dragon"),
                WatchIcons.recipeTarget("wishmob spawn pet_dragon"));
        assertEquals(new WatchIcons.RecipeTarget(MadeKind.PAINTING, "starter_creeper_face"),
                WatchIcons.recipeTarget("wishpaint starter_creeper_face"));
        assertEquals(new WatchIcons.RecipeTarget(MadeKind.MOB, "minecraft:axolotl"),
                WatchIcons.recipeTarget("summon axolotl ~ ~ ~ {Variant:2}"));
    }

    @Test
    @DisplayName("a command that makes nothing picturable says so, so the next one gets its turn")
    void somethingThatMakesNothing() {
        assertNull(WatchIcons.recipeTarget("tellraw @a \"hi\""));
        assertNull(WatchIcons.recipeTarget("weather rain"));
        assertNull(WatchIcons.recipeTarget("wishmob list"));
        assertNull(WatchIcons.recipeTarget(""));
        assertNull(WatchIcons.recipeTarget(null));
    }

    @Test
    @DisplayName("a vanilla mob is its own spawn egg")
    void aVanillaMobIsItsEgg() {
        assertEquals("minecraft:axolotl_spawn_egg", WatchIcons.forVanillaMob("axolotl"));
        assertEquals("minecraft:cow_spawn_egg", WatchIcons.forVanillaMob("minecraft:cow"));
    }
}
