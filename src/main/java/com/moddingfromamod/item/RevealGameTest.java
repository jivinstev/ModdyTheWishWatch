package com.moddingfromamod.item;

import net.minecraft.network.chat.Component;
import com.google.gson.JsonObject;
import com.moddingfromamod.ModdingFromAModMod;
import com.moddingfromamod.datapack.ItemProperties;
import com.moddingfromamod.testing.MockPlayerNetwork;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import com.moddingfromamod.testing.gt.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.TagParser;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Display;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;
import com.moddingfromamod.testing.gt.GameTestHolder;

/**
 * <b>: "show me where the diamonds are", on the real game.</b> A diamond ore 6 blocks
 * from him: holding the map, a glowing display of it sits exactly on it; put the map away and it is
 * gone. The beeper beeps higher when he is closer. Red on {@code main}: nothing reveals anything.
 */
@GameTestHolder(ModdingFromAModMod.MODID)
public final class RevealGameTest {

    private static ItemStack with(Item base, String ore, String how) throws Exception {
        JsonObject r = new JsonObject();
        r.addProperty("ore", ore);
        r.addProperty("how", how);
        JsonObject props = new JsonObject();
        props.add(ItemProperties.REVEALS_KEY, r);
        String entries = ItemProperties.dataEntries(props);
        ItemStack s = new ItemStack(base);
        s.set(DataComponents.CUSTOM_DATA, CustomData.of(TagParser.parseCompoundFully("{" + entries.substring(1) + "}")));
        return s;
    }

    private static long glowingAt(GameTestHelper helper, BlockPos at) {
        return helper.getLevel().getEntitiesOfClass(Display.BlockDisplay.class, new AABB(at).inflate(0.1),
                d -> d.getTags().contains(Reveal.TAG) && d.hasGlowingTag()).size();
    }

    @GameTest(template = "empty_test", batch = "reveal", timeoutTicks = 100)
    public static void theDiamondGlowsWhileHeHoldsTheMap(GameTestHelper helper) throws Exception {
        MockPlayerNetwork.clearStaleMocks(helper);
        ServerPlayer p = MockPlayerNetwork.inLevel(helper);
        BlockPos feet = helper.absolutePos(new BlockPos(1, 2, 1));
        p.snapTo(feet.getX() + 0.5, feet.getY(), feet.getZ() + 0.5, 0, 0);
        BlockPos ore = helper.absolutePos(new BlockPos(7, 2, 1));
        helper.getLevel().setBlockAndUpdate(ore, Blocks.DIAMOND_ORE.defaultBlockState());

        Reveal.look(p);
        helper.assertTrue(Reveal.glowing(p) == 0 && glowingAt(helper, ore) == 0, Component.literal("something glows with nothing held"));
        p.setItemInHand(InteractionHand.MAIN_HAND, with(Items.MAP, "diamonds", "glow"));
        Reveal.look(p);
        helper.assertTrue(Reveal.glowing(p) == 1 && glowingAt(helper, ore) == 1,
                Component.literal("the diamond 6 blocks away does not glow: " + Reveal.glowing(p) + " tracked, "
                        + glowingAt(helper, ore) + " at it"));
        Reveal.look(p);
        helper.assertTrue(glowingAt(helper, ore) == 1, Component.literal("a second look doubled the glow"));
        p.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
        Reveal.look(p);
        helper.assertTrue(Reveal.glowing(p) == 0 && glowingAt(helper, ore) == 0, Component.literal("the glow stayed after he put the map away"));
        helper.succeed();
    }

    @GameTest(template = "empty_test", batch = "reveal", timeoutTicks = 100)
    public static void theBeeperIsHigherWhenHeIsCloser(GameTestHelper helper) throws Exception {
        MockPlayerNetwork.clearStaleMocks(helper);
        ServerPlayer p = MockPlayerNetwork.inLevel(helper);
        BlockPos ore = helper.absolutePos(new BlockPos(8, 2, 1));
        helper.getLevel().setBlockAndUpdate(ore, Blocks.GOLD_ORE.defaultBlockState());
        p.setItemInHand(InteractionHand.MAIN_HAND, with(Items.CLOCK, "gold", "beep"));
        BlockPos far = helper.absolutePos(new BlockPos(1, 2, 1));
        p.snapTo(far.getX() + 0.5, far.getY(), far.getZ() + 0.5, 0, 0);
        Reveal.beep(p, 1_000_000L);
        Float farPitch = Reveal.LAST_PITCH.get(p.getUUID());
        BlockPos near = helper.absolutePos(new BlockPos(7, 2, 1));
        p.snapTo(near.getX() + 0.5, near.getY(), near.getZ() + 0.5, 0, 0);
        Reveal.beep(p, 2_000_000L);
        Float nearPitch = Reveal.LAST_PITCH.get(p.getUUID());
        helper.assertTrue(farPitch != null && nearPitch != null && nearPitch > farPitch,
                Component.literal("the beep is not higher closer: far " + farPitch + ", near " + nearPitch));
        helper.succeed();
    }

    /** Where it RUNS: make_item takes it on a real map. */
    @GameTest(template = "empty_test", batch = "reveal", timeoutTicks = 100)
    public static void makeItemTakesADiamondMap(GameTestHelper helper) {
        JsonObject r = new JsonObject();
        r.addProperty("ore", "diamond");
        r.addProperty("how", "glow");
        JsonObject props = new JsonObject();
        props.add(ItemProperties.REVEALS_KEY, r);
        JsonObject o = new JsonObject();
        o.addProperty("name", "Gem Map " + helper.getLevel().getGameTime());
        o.addProperty("icon", "wand");
        o.addProperty("colour", "#40C0E0");
        o.addProperty("shape", "plain");
        o.addProperty("base_item", "minecraft:map");
        o.addProperty("count", 1);
        o.add("properties", props);
        var res = new com.moddingfromamod.agent.tools.MakeItemTool().call(o);
        helper.assertTrue(!res.isError(), Component.literal("make_item refused a diamond map: " + res.text()));
        helper.succeed();
    }
}
