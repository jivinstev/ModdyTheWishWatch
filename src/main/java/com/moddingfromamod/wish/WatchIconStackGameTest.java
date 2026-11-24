package com.moddingfromamod.wish;

import net.minecraft.network.chat.Component;
import com.moddingfromamod.ModdingFromAModMod;
import com.moddingfromamod.compat.ModelData;
import com.moddingfromamod.datapack.VanillaCarrier;
import com.moddingfromamod.item.IconSprites;
import com.moddingfromamod.marshal.MadeKind;
import com.moddingfromamod.marshal.WatchIcons;
import net.minecraft.core.component.DataComponents;
import com.moddingfromamod.testing.gt.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import com.moddingfromamod.testing.gt.GameTestHolder;

/**
 * <b>Every icon spelling the panel can be handed becomes the right stack, and none an
 * empty slot.</b> {@code WatchMenu.iconStack} is the one place a {@code WatchIcons} string turns
 * into an item; this drives each spelling through it on a real server.
 */
@GameTestHolder(ModdingFromAModMod.MODID)
public final class WatchIconStackGameTest {

    @GameTest(template = "empty_test")
    public static void everyIconSpellingBecomesTheRightStack(GameTestHelper helper) {
        // A creature: the icon library's egg on the carrier, in the creature's own colour.
        ItemStack egg = WatchMenu.iconStack(WatchIcons.forCreature(0x2E8B57));
        if (VanillaCarrier.enabled()) {
            helper.assertTrue(egg.is(Items.LEATHER_HORSE_ARMOR), Component.literal("the egg rides the carrier: " + egg));
            var cmd = egg.get(DataComponents.CUSTOM_MODEL_DATA);
            helper.assertTrue(cmd != null && ModelData.id(cmd) == IconSprites.numberFor("egg"),
                    Component.literal("the creature's cell does not draw the egg sprite: " + cmd));
            var dyed = egg.get(DataComponents.DYED_COLOR);
            helper.assertTrue(dyed != null && dyed.rgb() == 0x2E8B57,
                    Component.literal("the creature's colour did not reach its egg: " + dyed));
            double[] armour = {0};
            egg.forEachModifier(net.minecraft.world.entity.EquipmentSlot.BODY, (a, m) -> {
                if (a.is(net.minecraft.world.entity.ai.attributes.Attributes.ARMOR)) {
                    armour[0] += m.amount();
                }
            });
            helper.assertValueEqual(armour[0], 0.0,
                    Component.literal("a panel icon claims horse armour in its tooltip (the EMPTY-component trap)"));
        }
        // A building's block, a fluid's bucket, and a vanilla id.
        helper.assertTrue(WatchMenu.iconStack("minecraft:stone_bricks").is(Items.STONE_BRICKS), Component.literal("block"));
        helper.assertTrue(WatchMenu.iconStack(WatchIcons.forBuild(java.util.Map.of("minecraft:lava", 9)))
                .is(Items.LAVA_BUCKET), Component.literal("a lava moat shows as lava"));
        // An item he no longer has in the catalogue: the kind's icon, never an empty slot.
        ItemStack gone = WatchMenu.iconStack(WatchIcons.forItem("nobody_made_this|1"));
        helper.assertTrue(!gone.isEmpty() && gone.is(net.minecraft.core.registries.BuiltInRegistries.ITEM
                        .getValue(net.minecraft.resources.Identifier.parse(
                                WatchIcons.forKind(MadeKind.ITEM)))),
                Component.literal("a missing catalogue item must fall back to the item kind: " + gone));
        // Garbage never empties a slot.
        helper.assertTrue(!WatchMenu.iconStack("minecraft:not_a_real_item").isEmpty(), Component.literal("unknown id"));
        helper.assertTrue(!WatchMenu.iconStack("wish:not_a_sprite#123456").isEmpty(), Component.literal("unknown sprite"));
        helper.succeed();
    }
}
