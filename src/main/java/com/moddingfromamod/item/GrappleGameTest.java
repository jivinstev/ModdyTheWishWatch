package com.moddingfromamod.item;

import com.moddingfromamod.testing.gt.GameTestCompat;
import net.minecraft.network.chat.Component;
import com.google.gson.JsonObject;
import com.moddingfromamod.ModdingFromAModMod;
import com.moddingfromamod.agent.ToolResult;
import com.moddingfromamod.agent.tools.MakeItemTool;
import com.moddingfromamod.datapack.CbScriptCompiler;
import com.moddingfromamod.datapack.ItemCatalogue;
import com.moddingfromamod.datapack.ItemProperties;
import com.moddingfromamod.testing.MockPlayerNetwork;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import com.moddingfromamod.testing.gt.GameTest;
import net.minecraft.gametest.framework.GameTestAssertException;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.projectile.FishingHook;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.living.LivingFallEvent;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import com.moddingfromamod.testing.gt.GameTestHolder;

/**
 * <b>: a hook that pulls him there, on the real game.</b> A player's movement is his
 * CLIENT's, so the server can only be asked what it SENT: his motion points at where the hook landed,
 * rising, and is marked to go to his client; the next fall does no damage. End to end with a real
 * thrown snowball, and on a real rod reeled in while its bobber rests on a block. How it FEELS is the
 * film (the one Gate C film). Red on {@code main}: there is no grapple, so nothing moves him.
 */
@GameTestHolder(ModdingFromAModMod.MODID)
public final class GrappleGameTest {

    private static ServerPlayer him(GameTestHelper helper, BlockPos feet) {
        MockPlayerNetwork.clearStaleMocks(helper);
        ServerPlayer p = MockPlayerNetwork.inLevel(helper);
        p.setGameMode(GameType.SURVIVAL);
        p.snapTo(feet.getX() + 0.5, feet.getY(), feet.getZ() + 0.5, 0, 0);
        p.setDeltaMovement(Vec3.ZERO);
        p.hurtMarked = false;
        return p;
    }

    @GameTest(template = "empty_test", batch = "grapple", timeoutTicks = 100)
    public static void heIsFlungAtTheMarkAndLandsSoft(GameTestHelper helper) {
        BlockPos feet = helper.absolutePos(new BlockPos(1, 2, 1));
        ServerPlayer p = him(helper, feet);
        WishThrowImpact.recordLanding(p.getUUID(), new Vec3(feet.getX() + 10.5, feet.getY() + 4, feet.getZ() + 0.5),
                helper.getLevel().getGameTime());
        Vec3 v = Grapple.pull(p);
        helper.assertTrue(v != null && v.x > 0.2 && v.y > 0 && Math.abs(v.z) < 0.05,
                Component.literal("he was not sent toward the mark 10 east and 4 up: " + v));
        helper.assertTrue(p.hurtMarked, Component.literal("the motion was not marked to go to his client"));
        LivingFallEvent fall = new LivingFallEvent(p, 8, 1);
        NeoForge.EVENT_BUS.post(fall);
        helper.assertTrue(fall.getDamageMultiplier() == 0, Component.literal("his landing after the swing hurt him"));
        helper.succeed();
    }

    /** End to end: a made grapple snowball, thrown for real at a wall, sends him toward it. */
    @GameTest(template = "empty_test", batch = "grapple", timeoutTicks = 900)
    public static void aThrownHookPullsHimToWhereItLands(GameTestHelper helper) {
        if (!CbScriptCompiler.available()) {
            helper.succeed();
            return;
        }
        BlockPos feet = helper.absolutePos(new BlockPos(1, 2, 1));
        ServerPlayer p = him(helper, feet);
        String name = "Grapple Ball " + helper.getLevel().getGameTime();
        JsonObject o = new JsonObject();
        o.addProperty("name", name);
        o.addProperty("icon", "orb");
        o.addProperty("colour", "#806040");
        o.addProperty("shape", "on_throw_land");
        o.addProperty("effect", "grapple");
        o.addProperty("base_item", "minecraft:snowball");
        o.addProperty("count", 4);
        MakeItemTool tool = new MakeItemTool();
        ToolResult r = tool.call(o);
        helper.assertTrue(!r.isError(), Component.literal("make_item refused a grapple snowball: " + r.text()));
        String line = MakeItemTool.gives("on_throw_land", tool.lastMadeId(), ItemCatalogue.icon("orb"), 4,
                "A hint.", java.util.List.of(new MakeItemTool.Variant(name, 0x806040)),
                com.moddingfromamod.datapack.ItemRecipient.ME, null, "minecraft:snowball");
        p.getInventory().clearContent();
        helper.getLevel().getServer().getCommands().performPrefixedCommand(
                p.createCommandSourceStack().withPermission(net.minecraft.server.permissions.LevelBasedPermissionSet.GAMEMASTER).withSuppressedOutput(), line.strip().substring(1));
        ItemStack ball = ItemStack.EMPTY;
        for (ItemStack s : p.getInventory().getNonEquipmentItems()) {
            if (!s.isEmpty() && s.getHoverName().getString().equals(name)) {
                ball = s.copy();
            }
        }
        helper.assertTrue(!ball.isEmpty(), Component.literal("no grapple snowball arrived from: " + line));
        for (int dx = -2; dx <= 2; dx++) {     // a wall 8 blocks in front of him (yaw 0 faces +z)
            for (int dy = -1; dy <= 3; dy++) {
                helper.getLevel().setBlockAndUpdate(feet.offset(dx, dy, 8), Blocks.STONE.defaultBlockState());
            }
        }
        p.getInventory().clearContent();
        p.setItemInHand(InteractionHand.MAIN_HAND, ball);
        p.setDeltaMovement(Vec3.ZERO);
        p.gameMode.useItem(p, p.level(), p.getMainHandItem(), InteractionHand.MAIN_HAND);
        helper.succeedWhen(() -> {
            if (!(p.getDeltaMovement().z > 0.2)) {
                throw GameTestCompat.assertion("the snowball landed and he was not pulled toward it: "
                        + p.getDeltaMovement());
            }
            MockPlayerNetwork.remove(p);
        });
    }

    /** The rod: reeled in while its bobber rests on a block, he is pulled to it. */
    @GameTest(template = "empty_test", batch = "grapple", timeoutTicks = 100)
    public static void aRodReeledInOffABlockPullsHim(GameTestHelper helper) {
        BlockPos feet = helper.absolutePos(new BlockPos(1, 2, 1));
        ServerPlayer p = him(helper, feet);
        ItemStack rod = new ItemStack(Items.FISHING_ROD);
        CompoundTag t = new CompoundTag();
        t.putBoolean(ItemProperties.GRAPPLE_DATA, true);
        rod.set(DataComponents.CUSTOM_DATA, CustomData.of(t));
        p.setItemInHand(InteractionHand.MAIN_HAND, rod);
        BlockPos post = feet.offset(0, 2, 9);
        helper.getLevel().setBlockAndUpdate(post, Blocks.STONE.defaultBlockState());
        FishingHook hook = new FishingHook(p, helper.getLevel(), 0, 0);
        hook.snapTo(post.getX() + 0.5, post.getY() + 1, post.getZ() + 0.5, 0, 0);
        hook.setOnGround(true);
        helper.getLevel().addFreshEntity(hook);
        p.fishing = hook;
        NeoForge.EVENT_BUS.post(new PlayerInteractEvent.RightClickItem(p, InteractionHand.MAIN_HAND));
        helper.assertTrue(p.getDeltaMovement().z > 0.2 && p.getDeltaMovement().y > 0,
                Component.literal("reeling in off a block did not pull him to it: " + p.getDeltaMovement()));
        hook.discard();
        helper.succeed();
    }

    /** A hook that stops against a wall pulls him onto the TOP of the wall, not into its side. */
    @GameTest(template = "empty_test", batch = "grapple", timeoutTicks = 100)
    public static void aHookAgainstAWallAimsForItsTop(GameTestHelper helper) {
        var level = helper.getLevel();
        BlockPos base = helper.absolutePos(new BlockPos(4, 2, 4));
        for (int dy = 0; dy < 4; dy++) {
            level.setBlockAndUpdate(base.offset(0, dy, 1), Blocks.STONE.defaultBlockState());
        }
        Vec3 hit = new Vec3(base.getX() + 0.5, base.getY() + 1.2, base.getZ() + 0.9);   // at the face
        Vec3 land = Grapple.landing(level, hit);
        helper.assertTrue(land.y == base.getY() + 4 && land.z == base.getZ() + 1.5,   Component.literal(// one deep: stays at the edge
                "a hook against a 4-high wall should land him on top (y " + (base.getY() + 4) + "), not at " + land));
        Vec3 open = new Vec3(base.getX() + 3.5, base.getY(), base.getZ() - 2.5);
        helper.assertTrue(Grapple.landing(level, open).equals(open), Component.literal("a hook on open ground moved"));
        helper.succeed();
    }

    /**
     * Two children, one hook: only the one who threw it is pulled. The first design left a shared
     * marker and a concurrent test took the other's (1 run in 20 on 1.21.1).
     */
    @GameTest(template = "empty_test", batch = "grapple", timeoutTicks = 100)
    public static void onlyTheThrowerIsPulled(GameTestHelper helper) {
        BlockPos feet = helper.absolutePos(new BlockPos(1, 2, 1));
        MockPlayerNetwork.clearStaleMocks(helper);
        ServerPlayer thrower = MockPlayerNetwork.inLevel(helper);
        ServerPlayer friend = MockPlayerNetwork.inLevel(helper);
        thrower.snapTo(feet.getX() + 0.5, feet.getY(), feet.getZ() + 0.5, 0, 0);
        friend.snapTo(feet.getX() + 2.5, feet.getY(), feet.getZ() + 0.5, 0, 0);
        WishThrowImpact.recordLanding(thrower.getUUID(), new Vec3(feet.getX() + 0.5, feet.getY() + 3, feet.getZ() + 10.5),
                helper.getLevel().getGameTime());
        helper.assertTrue(Grapple.pull(friend) == null, Component.literal("the friend was pulled by the thrower's hook"));
        helper.assertTrue(Grapple.pull(thrower) != null, Component.literal("the thrower was not pulled by his own hook"));
        helper.succeed();
    }
}
