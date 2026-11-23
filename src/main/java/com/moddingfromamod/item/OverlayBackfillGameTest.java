package com.moddingfromamod.item;

import net.minecraft.network.chat.Component;
import java.util.List;

import com.google.gson.JsonObject;
import com.moddingfromamod.ModdingFromAModMod;
import com.moddingfromamod.agent.tools.MakeItemTool;
import com.moddingfromamod.compat.ModelData;
import com.moddingfromamod.datapack.ItemCatalogue;
import com.moddingfromamod.testing.MockPlayerNetwork;
import net.minecraft.core.component.DataComponents;
import com.moddingfromamod.testing.gt.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import com.moddingfromamod.testing.gt.GameTestHolder;

/**
 * <b>Items made before overlays get theirs on join, and nothing else moves.</b>
 *
 * <p>The stacks are minted exactly as a pre-T6D.3c wish minted them — the production give line with
 * no overlay, and one further back with no {@code item_model} either (a pre-T6D.1 stack, as the data
 * fixer leaves it) — then {@link OverlayBackfill#sweep}, the join hook's own body, runs over a real
 * inventory. Each stack is asked what the composite reads: its overlay, its number, its model.
 */
@GameTestHolder(ModdingFromAModMod.MODID)
public final class OverlayBackfillGameTest {

    /** Its own batch: mock players and the design store are process-wide. */
    private static final String BATCH = "overlaybackfill";

    private static ItemStack mint(GameTestHelper helper, String feature, String name, String icon) {
        String arg = MakeItemTool.itemArg(new MakeItemTool.Variant(name, 0x9966FF),
                ItemCatalogue.icon(icon), "Right-click to use it.", null, null,
                WishItem.WISH_FEATURE + ":\"" + feature + "\"");
        try {
            var parsed = new net.minecraft.commands.arguments.item.ItemParser(
                    helper.getLevel().registryAccess()).parse(new com.mojang.brigadier.StringReader(arg));
            return new ItemStack(parsed.item(), 1, parsed.components());
        } catch (Exception e) {
            throw new IllegalStateException("the old give line did not parse: " + arg, e);
        }
    }

    private static ItemSpecStore.Spec spec(String id, String effect) {
        return new ItemSpecStore.Spec(id, id, "wand", "purple", "held", 1, List.of(effect), new JsonObject());
    }

    @GameTest(template = "empty_test", batch = BATCH)
    public static void anOldWandGetsItsSparksOnJoinAndOnlyOnce(GameTestHelper helper) {
        ItemSpecStore.put(spec("t6d3d_zap", "lightning"));
        ItemSpecStore.put(spec("t6d3d_snack", "hurt"));   // no overlay (food steams on its vanilla base)
        MockPlayerNetwork.clearStaleMocks(helper);
        ServerPlayer p = MockPlayerNetwork.inLevel(helper);
        try {
            int wand = ItemCatalogue.icon("wand");
            ItemStack zap = mint(helper, "t6d3d_zap", "Old Zapper", "wand");
            ItemStack older = ModelData.unnamed(mint(helper, "t6d3d_zap", "Older Zapper", "wand"));
            ItemStack snack = mint(helper, "t6d3d_snack", "Old Snack", "wand");
            ItemStack plain = new ItemStack(net.minecraft.world.item.Items.GOLDEN_AXE);
            p.getInventory().clearContent();
            p.getInventory().setItem(0, zap);
            p.getInventory().setItem(1, older);
            p.getInventory().setItem(2, snack);
            p.getInventory().setItem(3, plain);
            ItemStack snackBefore = snack.copy();
            String keyBefore = WishItemCatalogue.keyOf(zap);

            int changed = OverlayBackfill.sweep(p);
            ItemStack a = p.getInventory().getItem(0);
            ItemStack b = p.getInventory().getItem(1);
            if (!ModelData.COMPOSED_ICONS) {
                helper.assertValueEqual(changed, 0, Component.literal("1.21.1 has nowhere to put an overlay"));
                helper.succeed();
                return;
            }
            helper.assertValueEqual(changed, 2, Component.literal("both old lightning stacks, and nothing else"));
            for (ItemStack s : new ItemStack[]{a, b}) {
                var cmd = s.get(DataComponents.CUSTOM_MODEL_DATA);
                helper.assertValueEqual(ModelData.overlayOf(cmd), "sparks",
                        Component.literal(s.getHoverName().getString() + " wears what it does"));
                helper.assertValueEqual(ModelData.id(cmd), wand, Component.literal("the number is kept"));
                helper.assertValueEqual(ModelData.itemModelOf(s),
                        com.moddingfromamod.pack.NativeItemModels.forNumber(wand),
                        Component.literal("the composite is on the icon's own model — given to the pre-T6D.1 stack too"));
            }
            helper.assertTrue(ItemStack.isSameItemSameComponents(p.getInventory().getItem(2), snackBefore),
                    Component.literal("a plain weapon (hurt) has no overlay, so this stack is untouched"));
            helper.assertTrue(!p.getInventory().getItem(3).has(DataComponents.CUSTOM_MODEL_DATA),
                    Component.literal("a real item he never wished for is never touched"));
            helper.assertValueEqual(WishItemCatalogue.keyOf(a), keyBefore,
                    Component.literal("the backfill must not give him a second row for the same wand"));
            helper.assertValueEqual(OverlayBackfill.sweep(p), 0,
                    Component.literal("a second join changes nothing — the backfill is idempotent"));
            helper.succeed();
        } finally {
            MockPlayerNetwork.remove(p);
            ItemSpecStore.remove("t6d3d_zap");
            ItemSpecStore.remove("t6d3d_snack");
        }
    }
}
