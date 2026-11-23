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
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import com.moddingfromamod.testing.gt.GameTestHolder;

/**
 * <b>T6F follow-up: "a pearl that teleports me home" — he throws a real pearl, and where it
 * lands he goes HOME, not to the pearl.</b>
 *
 * <p>Red on {@code main}: there was no {@code teleport_home} effect, so {@code make_item} refused it
 * and the agent hand-wrote a click-item, never a pearl he throws.
 *
 * <p>Home is his respawn point (set here with the forced flag, which is what {@code /spawnpoint}
 * does; a bed is the same field). He faces a wall four blocks south with home five blocks EAST, so
 * where the pearl lands and where home is cannot be confused. The pearl's own teleport would also
 * cost him hearts; going home must not.
 */
@GameTestHolder(ModdingFromAModMod.MODID)
public final class GoHomeGameTest {

    private static ItemStack made(GameTestHelper helper, ServerPlayer p, String name, String shape, String base) {
        JsonObject o = new JsonObject();
        o.addProperty("name", name);
        o.addProperty("icon", "wand");
        o.addProperty("colour", "#8040FF");
        o.addProperty("shape", shape);
        o.addProperty("effect", "teleport_home");
        o.addProperty("count", 1);
        if (base != null) {
            o.addProperty("base_item", base);
        }
        MakeItemTool tool = new MakeItemTool();
        ToolResult r = tool.call(o);
        if (r.isError()) {
            throw GameTestCompat.assertion("make_item refused teleport_home on " + shape + ": " + r.text());
        }
        String line = MakeItemTool.gives(shape, tool.lastMadeId(), ItemCatalogue.icon("wand"), 1,
                "A hint.", java.util.List.of(new MakeItemTool.Variant(name, 0x8040FF)),
                com.moddingfromamod.datapack.ItemRecipient.ME, null, base);
        p.getInventory().clearContent();
        helper.getLevel().getServer().getCommands().performPrefixedCommand(
                p.createCommandSourceStack().withPermission(2).withSuppressedOutput(), line.strip().substring(1));
        for (ItemStack s : p.getInventory().getNonEquipmentItems()) {
            if (!s.isEmpty() && s.getHoverName().getString().equals(name)) {
                return s.copy();
            }
        }
        throw GameTestCompat.assertion("nothing arrived from: " + line);
    }

    /** He stands at (1,2,1) facing south at a wall 4 blocks out; home is 5 blocks east. */
    private static ServerPlayer atStart(GameTestHelper helper, BlockPos feet, BlockPos home) {
        MockPlayerNetwork.clearStaleMocks(helper);
        ServerPlayer p = MockPlayerNetwork.inLevel(helper);
        p.setGameMode(GameType.SURVIVAL);
        for (int dx = -1; dx <= 1; dx++) {
            for (int dy = 0; dy <= 2; dy++) {
                helper.getLevel().setBlockAndUpdate(feet.offset(dx, dy, 4), Blocks.STONE.defaultBlockState());
            }
        }
        if (home != null) {
            com.moddingfromamod.compat.Respawn.set(p, helper.getLevel().dimension(), home, 0.0F, true, false);
        }
        p.snapTo(feet.getX() + 0.5, feet.getY(), feet.getZ() + 0.5, 0.0F, 0.0F);
        return p;
    }

    private static void expectAt(GameTestHelper helper, ServerPlayer p, Vec3 where, float healthBefore,
                                 String what, int ticks) {
        helper.runAfterDelay(ticks, () -> {
            try {
                double d = p.position().distanceTo(where);
                if (d > 1.2) {
                    throw GameTestCompat.assertion(what + ": he is at " + p.position() + ", " + d
                            + " blocks from " + where);
                }
                if (p.getHealth() < healthBefore) {
                    throw GameTestCompat.assertion(what + ": it cost him " + (healthBefore - p.getHealth())
                            + " health — the pearl's own teleport happened");
                }
                helper.succeed();
            } finally {
                MockPlayerNetwork.remove(p);
            }
        });
    }

    @GameTest(template = "empty_test", batch = "gohome_pearl", timeoutTicks = 600)
    public static void aThrownPearlTakesHimHomeNotWhereItLands(GameTestHelper helper) {
        if (!CbScriptCompiler.available()) {
            helper.succeed();
            return;
        }
        BlockPos feet = helper.absolutePos(new BlockPos(1, 2, 1));
        BlockPos home = feet.offset(5, 0, 0);
        ServerPlayer p = atStart(helper, feet, home);
        try {
            ItemStack pearl = made(helper, p, "Home Pearl " + helper.getLevel().getGameTime(), "on_throw_land",
                    "minecraft:ender_pearl");
            p.getInventory().clearContent();
            p.setItemInHand(InteractionHand.MAIN_HAND, pearl);
            p.snapTo(feet.getX() + 0.5, feet.getY(), feet.getZ() + 0.5, 0.0F, 0.0F);
            float hp = p.getHealth();
            p.gameMode.useItem(p, p.level(), p.getMainHandItem(), InteractionHand.MAIN_HAND);
            expectAt(helper, p, Vec3.atBottomCenterOf(home), hp, "thrown pearl", 12);
        } catch (RuntimeException e) {
            MockPlayerNetwork.remove(p);
            throw e;
        }
    }

    @GameTest(template = "empty_test", batch = "gohome_click", timeoutTicks = 600)
    public static void aClickedHomeButtonTakesHimHome(GameTestHelper helper) {
        if (!CbScriptCompiler.available()) {
            helper.succeed();
            return;
        }
        BlockPos feet = helper.absolutePos(new BlockPos(1, 2, 1));
        BlockPos home = feet.offset(5, 0, 0);
        ServerPlayer p = atStart(helper, feet, home);
        try {
            ItemStack button = made(helper, p, "Home Button " + helper.getLevel().getGameTime(), "held", null);
            p.getInventory().clearContent();
            p.setItemInHand(InteractionHand.MAIN_HAND, button);
            p.gameMode.useItem(p, p.level(), p.getMainHandItem(), InteractionHand.MAIN_HAND);
            expectAt(helper, p, Vec3.atBottomCenterOf(home), p.getHealth(), "home button", 3);
        } catch (RuntimeException e) {
            MockPlayerNetwork.remove(p);
            throw e;
        }
    }

    /** The control: a plain pearl still takes him where it lands (the pearl's own action is untouched). */
    @GameTest(template = "empty_test", batch = "gohome_plain", timeoutTicks = 600)
    public static void aPlainPearlStillTeleportsWhereItLands(GameTestHelper helper) {
        BlockPos feet = helper.absolutePos(new BlockPos(1, 2, 1));
        BlockPos home = feet.offset(5, 0, 0);
        ServerPlayer p = atStart(helper, feet, home);
        p.getInventory().clearContent();
        p.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.ENDER_PEARL));
        p.gameMode.useItem(p, p.level(), p.getMainHandItem(), InteractionHand.MAIN_HAND);
        helper.runAfterDelay(12, () -> {
            try {
                Vec3 start = Vec3.atBottomCenterOf(feet);
                if (p.position().distanceTo(start) < 1.5 || p.position().distanceTo(Vec3.atBottomCenterOf(home)) < 1.5) {
                    throw GameTestCompat.assertion("a plain pearl did not take him to where it landed: he is at "
                            + p.position());
                }
                helper.succeed();
            } finally {
                MockPlayerNetwork.remove(p);
            }
        });
    }

    /** With no bed and no spawnpoint, home is the world's spawn — not nowhere, and not where he stands. */
    @GameTest(template = "empty_test", batch = "gohome_nohome", timeoutTicks = 100)
    public static void withNoBedHomeIsTheWorldSpawn(GameTestHelper helper) {
        BlockPos feet = helper.absolutePos(new BlockPos(1, 2, 1));
        ServerPlayer p = atStart(helper, feet, null);
        try {
            com.moddingfromamod.compat.Respawn.set(p, helper.getLevel().dimension(), null, 0.0F, false, false);
            GoHome.Home h = GoHome.homeOf(p);
            helper.assertTrue(!h.isHisOwn(), Component.literal("no bed, yet home was 'his own': " + h));
            BlockPos spawn = h.level().getSharedSpawnPos();
            helper.assertTrue(Math.abs(h.at().x - (spawn.getX() + 0.5)) < 0.01
                    && Math.abs(h.at().z - (spawn.getZ() + 0.5)) < 0.01, Component.literal("home " + h.at() + " is not the world spawn " + spawn));
            helper.succeed();
        } finally {
            MockPlayerNetwork.remove(p);
        }
    }
}
