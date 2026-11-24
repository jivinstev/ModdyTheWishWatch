package com.moddingfromamod.item;

import com.moddingfromamod.testing.gt.GameTestCompat;
import net.minecraft.network.chat.Component;
import com.google.gson.JsonObject;
import com.moddingfromamod.ModdingFromAModMod;
import com.moddingfromamod.agent.ToolResult;
import com.moddingfromamod.agent.tools.MakeItemTool;
import com.moddingfromamod.datapack.CbScriptCompiler;
import com.moddingfromamod.datapack.ItemCatalogue;
import com.moddingfromamod.testing.MockPlayerNetwork;
import net.minecraft.core.BlockPos;
import com.moddingfromamod.testing.gt.GameTest;
import net.minecraft.gametest.framework.GameTestAssertException;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import com.moddingfromamod.testing.gt.GameTestHolder;

/**
 * <b>: {@code on_consume} — a magic cookie is EATEN, and the wish happens after.</b>
 *
 * <p>Food and potions are group A's largest share (11 of 22): a {@code held} wish on a real cookie
 * cancelled the eating. This shape waits for vanilla to FINISH — the eating, the drinking — and the
 * wish rides on that. Each case uses the real path: {@code useItem} starts it (asserted: that is the
 * use), the wish must NOT have happened yet, and vanilla's own {@code completeUsingItem} finishes it,
 * which is where NeoForge fires {@code LivingEntityUseItemEvent.Finish}. A GameTest's mock player is
 * never ticked, so the eating cannot run down by itself; calling the method vanilla calls at the end
 * of it is the closest thing to letting it.
 *
 * <p>Each test is its own batch: the mock-player helper clears every stale mock.
 */
@GameTestHolder(ModdingFromAModMod.MODID)
public final class OnConsumeShapeGameTest {

    private static ItemStack made(GameTestHelper helper, ServerPlayer p, String name, String shape, String base,
                                  int count) {
        JsonObject o = new JsonObject();
        o.addProperty("name", name);
        o.addProperty("icon", "wand");
        o.addProperty("colour", "#FFAA00");
        o.addProperty("shape", shape);
        o.addProperty("effect", "place");
        o.addProperty("count", count);
        o.addProperty("base_item", base);
        JsonObject params = new JsonObject();
        params.addProperty("BLOCK", "minecraft:glowstone");
        o.add("params", params);
        MakeItemTool tool = new MakeItemTool();
        ToolResult r = tool.call(o);
        if (r.isError()) {
            helper.fail(Component.literal("make_item refused " + shape + " on " + base + ": " + r.text()));
            return ItemStack.EMPTY;
        }
        String line = MakeItemTool.gives(shape, tool.lastMadeId(), ItemCatalogue.icon("wand"), count,
                "A hint.", java.util.List.of(new MakeItemTool.Variant(name, 0xFFAA00)),
                com.moddingfromamod.datapack.ItemRecipient.ME, null, base);
        p.getInventory().clearContent();
        helper.getLevel().getServer().getCommands().performPrefixedCommand(
                p.createCommandSourceStack().withPermission(net.minecraft.server.permissions.LevelBasedPermissionSet.GAMEMASTER).withSuppressedOutput(), line.strip().substring(1));
        for (ItemStack s : p.getInventory().getNonEquipmentItems()) {
            if (!s.isEmpty() && s.getHoverName().getString().equals(name)) {
                return s.copy();
            }
        }
        helper.fail(Component.literal("nothing arrived from: " + line));
        return ItemStack.EMPTY;
    }

    private static boolean glowstoneNear(GameTestHelper helper, BlockPos centre, int r) {
        for (BlockPos q : BlockPos.betweenClosed(centre.offset(-r, -r, -r), centre.offset(r, r, r))) {
            if (helper.getLevel().getBlockState(q).is(Blocks.GLOWSTONE)) {
                return true;
            }
        }
        return false;
    }

    /** Vanilla's own end of eating/drinking — the method whose tail fires the Finish event. */
    private static void finishUsing(ServerPlayer p) {
        try {
            java.lang.reflect.Method m = LivingEntity.class.getDeclaredMethod("completeUsingItem");
            m.setAccessible(true);
            m.invoke(p);
        } catch (ReflectiveOperationException e) {
            throw GameTestCompat.assertion("cannot finish the use: " + e);
        }
    }

    private static void eatOrDrink(GameTestHelper helper, String name, String base, net.minecraft.world.item.Item after,
                                   int count, String verb) {
        if (!CbScriptCompiler.available()) {
            helper.succeed();
            return;
        }
        MockPlayerNetwork.clearStaleMocks(helper);
        ServerPlayer p = MockPlayerNetwork.inLevel(helper);
        p.setGameMode(GameType.SURVIVAL);
        ItemStack stack = made(helper, p, name + " " + helper.getLevel().getGameTime(), "on_consume", base, count);
        if (stack.isEmpty()) {
            MockPlayerNetwork.remove(p);
            return;
        }
        BlockPos feet = helper.absolutePos(new BlockPos(1, 2, 1));
        p.snapTo(feet.getX() + 0.5, feet.getY(), feet.getZ() + 0.5, 0.0F, 0.0F);
        p.getFoodData().setFoodLevel(4);
        p.getInventory().clearContent();
        p.setItemInHand(InteractionHand.MAIN_HAND, stack);
        p.gameMode.useItem(p, p.level(), p.getMainHandItem(), InteractionHand.MAIN_HAND);
        helper.assertTrue(p.isUsingItem(), Component.literal("the wished " + base + " cannot be " + verb + " at all"));
        BlockPos ahead = feet.offset(0, 1, 5);
        helper.assertFalse(glowstoneNear(helper, ahead, 2),
                Component.literal("the wish happened on the CLICK, before the " + base + " was " + verb));
        finishUsing(p);
        ItemStack left = p.getMainHandItem();
        helper.assertTrue(after == null ? left.getCount() == count - 1 : left.is(after),
                Component.literal("the " + base + " was not " + verb + ": in hand " + left));
        helper.succeedWhen(() -> {
            if (!glowstoneNear(helper, ahead, 2)) {
                throw GameTestCompat.assertion("the " + base + " was " + verb + " and the wish never came");
            }
            MockPlayerNetwork.remove(p);
        });
    }

    @GameTest(template = "empty_test", batch = "onconsume_food", timeoutTicks = 900)
    public static void aWishedCookieIsEatenAndThenTheWishHappens(GameTestHelper helper) {
        eatOrDrink(helper, "Glow Cookie", "minecraft:cookie", null, 4, "eaten");
    }

    @GameTest(template = "empty_test", batch = "onconsume_drink", timeoutTicks = 900)
    public static void aWishedPotionIsDrunkAndThenTheWishHappens(GameTestHelper helper) {
        eatOrDrink(helper, "Glow Potion", "minecraft:potion", Items.GLASS_BOTTLE, 1, "drunk");
    }

    /** make_item moves a click-taking shape on food onto the eating, and says so. */
    @GameTest(template = "empty_test", batch = "onconsume_redirect", timeoutTicks = 900)
    public static void aHeldCookieIsBuiltToBeEaten(GameTestHelper helper) {
        if (!CbScriptCompiler.available()) {
            helper.succeed();
            return;
        }
        JsonObject o = new JsonObject();
        o.addProperty("name", "Ride Cookie " + helper.getLevel().getGameTime());
        o.addProperty("icon", "wand");
        o.addProperty("colour", "#FFAA00");
        o.addProperty("shape", "held");
        o.addProperty("effect", "light");
        o.addProperty("count", 1);
        o.addProperty("base_item", "minecraft:cookie");
        JsonObject params = new JsonObject();
        params.addProperty("GLOW_SECONDS", "0");
        o.add("params", params);
        ToolResult r = new MakeItemTool().call(o);
        helper.assertTrue(!r.isError() && r.text().contains("Built as `on_consume`, not `held`"),
                Component.literal("a held cookie must be built to be EATEN, and said so: " + r.text()));
        helper.succeed();
    }
}
