package com.moddingfromamod.wish;

import net.minecraft.network.chat.Component;
import com.moddingfromamod.ModdingFromAModMod;
import com.moddingfromamod.marshal.WatchIcons;
import com.moddingfromamod.testing.gt.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import com.moddingfromamod.testing.gt.GameTestHolder;

/**
 * <b>A remembered wish shows what its recipe MAKES</b>, driven through real recipes — the
 * shipped starter library's, so nothing is seeded and no shelf is written.
 */
@GameTestHolder(ModdingFromAModMod.MODID)
public final class PresetIconsGameTest {

    @GameTest(template = "empty_test")
    public static void aRecipeShowsWhatItMakes(GameTestHelper helper) {
        // An item recipe: the item design's own sprite, in its own colour.
        String boots = PresetIcons.forRecipe("starter_jump_boots", "boots that let me DOUBLE JUMP!!!");
        helper.assertValueEqual(boots, "wish:helmet#2FC7C7",
                Component.literal("the jump boots' button shows the design it hands out"));
        // A building recipe: the block the building is mostly made of — never bricks-for-all.
        String castle = PresetIcons.forRecipe("starter_lava_castle", "a huge castle with a lava moat");
        helper.assertTrue(castle.startsWith("minecraft:") && !castle.equals(WatchIcons.UNKNOWN),
                Component.literal("the castle's button: " + castle));
        // A creature recipe: the creature's own colour.
        String dragon = PresetIcons.forRecipe("starter_pet_dragon",
                "a pet dragon that follows me and breathes fire");
        helper.assertTrue(dragon.startsWith(WatchIcons.SPRITE_PREFIX + WatchIcons.CREATURE_SPRITE),
                Component.literal("the dragon's button: " + dragon));
        // ⚠ Its SCULPT's colour: a sculpted species' tint is the white default, and a white egg for
        // every creature he makes is one icon again (PresetIcons.creatureIcon).
        helper.assertTrue(!dragon.equals(WatchIcons.forCreature(0xFFFFFF)),
                Component.literal("the sculpted dragon is a WHITE egg — the species tint was asked instead of its parts"));
        // Texture creatures: a vanilla skin is that mob's own egg; a tint is kept. (These were the
        // built-in Guard Bot and Pink Spider Queen retired them; the rule is the same.)
        var skinned = com.moddingfromamod.species.Species.fromJson(com.google.gson.JsonParser.parseString(
                "{\"id\":\"icon_probe_skinned\",\"name\":\"Skinned\",\"body\":\"humanoid\","
                        + "\"texture\":\"skeleton\"}").getAsJsonObject());
        helper.assertValueEqual(PresetIcons.creatureIcon(skinned), "minecraft:skeleton_spawn_egg",
                Component.literal("a creature wearing the skeleton's skin shows the skeleton's egg"));
        var tinted = com.moddingfromamod.species.Species.fromJson(com.google.gson.JsonParser.parseString(
                "{\"id\":\"icon_probe_tinted\",\"name\":\"Tinted\",\"body\":\"spider\","
                        + "\"texture\":\"spider\",\"tint\":\"hot_pink\"}").getAsJsonObject());
        helper.assertValueEqual(PresetIcons.creatureIcon(tinted),
                WatchIcons.forCreature(tinted.tint()), Component.literal("a tinted creature keeps its colour"));
        // No template, or a template nobody knows: nothing, so the words-based answer stands.
        helper.assertValueEqual(PresetIcons.forRecipe("", "x"), "", Component.literal("no template"));
        helper.assertValueEqual(PresetIcons.forRecipe("no_such_template_anywhere", "x"), "",
                Component.literal("an unknown template"));
        helper.succeed();
    }
}
