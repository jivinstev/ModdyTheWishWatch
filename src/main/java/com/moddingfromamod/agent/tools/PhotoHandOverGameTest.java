package com.moddingfromamod.agent.tools;

import net.minecraft.network.chat.Component;
import com.moddingfromamod.ModdingFromAModMod;
import com.moddingfromamod.item.WishItem;
import com.moddingfromamod.testing.MockPlayerNetwork;
import net.minecraft.core.component.DataComponents;
import com.moddingfromamod.testing.gt.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.CustomData;
import com.moddingfromamod.testing.gt.GameTestHolder;

/**
 * <b>The reveal photograph does not hand him a second copy.</b>
 *
 * <p>Gate D's itemCheck found two tridents and two fishing rods after every make_item grant: the
 * agent handed the item over as told, then the item photograph gave him another to hold. Now the
 * photograph moves the one he has into his hand, and only gives one when he has none.
 */
@GameTestHolder(ModdingFromAModMod.MODID)
public final class PhotoHandOverGameTest {

    private static ItemStack made(String feature) {
        ItemStack s = new ItemStack(Items.TRIDENT);
        CompoundTag t = new CompoundTag();
        t.putString(WishItem.WISH_FEATURE, feature);
        s.set(DataComponents.CUSTOM_DATA, CustomData.of(t));
        return s;
    }

    @GameTest(template = "empty_test", batch = "photohandover")
    public static void theOneHeHasGoesIntoHisHandAndNoSecondArrives(GameTestHelper helper) {
        MockPlayerNetwork.clearStaleMocks(helper);
        ServerPlayer p = MockPlayerNetwork.inLevel(helper);
        try {
            var inv = p.getInventory();
            inv.clearContent();
            inv.setSelectedSlot(0);
            inv.setItem(0, new ItemStack(Items.DIRT));
            inv.setItem(20, made("boomerang_trident_plain"));
            helper.assertTrue(PhotographTool.intoHand(p, "boomerang_trident_plain"),
                    Component.literal("he carries the trident, so the photograph must not give another"));
            helper.assertValueEqual(WishItem.wishFeature(p.getMainHandItem()), "boomerang_trident_plain",
                    Component.literal("the trident is in his hand for the photograph"));
            helper.assertTrue(inv.getItem(20).is(Items.DIRT), Component.literal("what he was holding moved, not vanished"));
            int tridents = 0;
            for (int i = 0; i < inv.getContainerSize(); i++) {
                tridents += inv.getItem(i).is(Items.TRIDENT) ? 1 : 0;
            }
            helper.assertValueEqual(tridents, 1, Component.literal("still exactly one trident"));
            helper.assertTrue(!PhotographTool.intoHand(p, "some_other_thing_plain"),
                    Component.literal("an item he does not carry is reported missing, so the caller gives him one"));
            helper.succeed();
        } finally {
            MockPlayerNetwork.remove(p);
        }
    }
}
