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
import net.minecraft.world.entity.projectile.arrow.AbstractArrow;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;
import com.moddingfromamod.testing.gt.GameTestHolder;

/**
 * <b>: {@code on_shot} — the wish happens the moment he SHOOTS, and the weapon still
 * shoots.</b> The laser bow: a beam from his eyes to the first thing in its way, and the wish there,
 * on the same tick he lets go; his arrow still flies, the way it always does.
 *
 * <p>Every weapon is used through the REAL path: {@code useItem} starts the draw (asserted — that is
 * the use the real-base rule stopped taking away), then {@code releaseUsing} as if he held it. The wall is 6
 * blocks out, so a wish that is there ONE tick after the shot came from the beam, not from an arrow
 * landing (a full-power arrow flies about 3 blocks a tick).
 *
 * <p>Each test is its own batch: the mock-player helper clears every stale mock, including a
 * concurrent sibling's, and batches are sequential.
 *
 * <p>Also the crossbow's {@code arrow} test, which the arrow shape gave the bow and the trident and not the
 * crossbow.
 */
@GameTestHolder(ModdingFromAModMod.MODID)
public final class OnShotShapeGameTest {

    /**
     * Six blocks: inside the test arena. The first version put it at 16 and the beam stopped at 8 —
     * at the arena's own barrier, which is solid, so the laser was right and the wall was outside.
     */
    private static final int WALL = 6;

    private static ItemStack made(GameTestHelper helper, ServerPlayer p, String name, String shape, String base) {
        JsonObject o = new JsonObject();
        o.addProperty("name", name);
        o.addProperty("icon", "wand");
        o.addProperty("colour", "#FF2020");
        o.addProperty("shape", shape);
        o.addProperty("effect", "place");
        o.addProperty("count", 1);
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
        String line = MakeItemTool.gives(shape, tool.lastMadeId(), ItemCatalogue.icon("wand"), 1,
                "A hint.", java.util.List.of(new MakeItemTool.Variant(name, 0xFF2020)),
                com.moddingfromamod.datapack.ItemRecipient.ME, null, base);
        p.getInventory().clearContent();
        helper.getLevel().getServer().getCommands().performPrefixedCommand(
                p.createCommandSourceStack().withPermission(2).withSuppressedOutput(), line.strip().substring(1));
        for (ItemStack s : p.getInventory().getNonEquipmentItems()) {
            if (!s.isEmpty() && s.getHoverName().getString().equals(name)) {
                return s.copy();
            }
        }
        helper.fail(Component.literal("nothing arrived from: " + line));
        return ItemStack.EMPTY;
    }

    /** A 3x3 stone wall {@link #WALL} blocks south of his eyes; returns the wall's centre. */
    private static BlockPos wall(GameTestHelper helper, BlockPos feet, int distance) {
        BlockPos wall = feet.offset(0, 1, distance);
        for (int dx = -1; dx <= 1; dx++) {
            for (int dy = -1; dy <= 1; dy++) {
                helper.getLevel().setBlockAndUpdate(wall.offset(dx, dy, 0), Blocks.STONE.defaultBlockState());
            }
        }
        return wall;
    }

    private static boolean glowstoneNear(GameTestHelper helper, BlockPos centre, int r) {
        for (BlockPos q : BlockPos.betweenClosed(centre.offset(-r, -r, -r), centre.offset(r, r, r))) {
            if (helper.getLevel().getBlockState(q).is(Blocks.GLOWSTONE)) {
                return true;
            }
        }
        return false;
    }

    private static ServerPlayer shooter(GameTestHelper helper, BlockPos feet) {
        MockPlayerNetwork.clearStaleMocks(helper);
        ServerPlayer p = MockPlayerNetwork.inLevel(helper);
        p.setGameMode(GameType.SURVIVAL);
        p.snapTo(feet.getX() + 0.5, feet.getY(), feet.getZ() + 0.5, 0.0F, 0.0F);
        return p;
    }

    /** Start the real use and say whether it began — the draw, the charge, the wind-up. */
    private static boolean startUse(ServerPlayer p) {
        p.gameMode.useItem(p, p.level(), p.getMainHandItem(), InteractionHand.MAIN_HAND);
        return p.isUsingItem();
    }

    private static int arrowsNear(GameTestHelper helper, BlockPos feet) {
        return helper.getLevel().getEntitiesOfClass(AbstractArrow.class, new AABB(feet).inflate(24)).size();
    }

    /**
     * One tick after the shot: the beam's wish is at the wall, 6 blocks out, and his own
     * projectile left the weapon.
     */
    private static void expectBeam(GameTestHelper helper, ServerPlayer p, BlockPos feet, BlockPos wall,
                                   int arrowsBefore, String what) {
        helper.runAfterDelay(1, () -> {
            try {
                if (!glowstoneNear(helper, wall.north(), 1)) {
                    java.util.List<String> found = new java.util.ArrayList<>();
                    for (BlockPos q : BlockPos.betweenClosed(feet.offset(-3, -2, -3), feet.offset(3, 4, WALL + 2))) {
                        if (helper.getLevel().getBlockState(q).is(Blocks.GLOWSTONE)) {
                            found.add(q.subtract(feet).toShortString());
                        }
                    }
                    throw GameTestCompat.assertion(what + ": no wish at the wall two ticks after the shot — "
                            + "the beam did not run (a full-power arrow flies ~3 blocks a tick, so it has not reached the wall). Glowstone, "
                            + "relative to his feet: " + found + "; facing yaw " + p.getYRot() + " pitch "
                            + p.getXRot() + "; tags " + p.getTags());
                }
                if (arrowsNear(helper, feet) <= arrowsBefore) {
                    throw GameTestCompat.assertion(what + ": the wish ran but the weapon shot NOTHING — "
                            + "on_shot must ride the shot, never replace it");
                }
                helper.succeed();
            } finally {
                MockPlayerNetwork.remove(p);
            }
        });
    }

    @GameTest(template = "empty_test", batch = "onshot_bow", timeoutTicks = 900)
    public static void aBowShootsAndTheBeamMakesTheWish(GameTestHelper helper) {
        if (!CbScriptCompiler.available()) {
            helper.succeed();
            return;
        }
        BlockPos feet = helper.absolutePos(new BlockPos(1, 2, 1));
        ServerPlayer p = shooter(helper, feet);
        ItemStack bow = made(helper, p, "Laser Bow " + helper.getLevel().getGameTime(), "on_shot", "minecraft:bow");
        if (bow.isEmpty()) {
            MockPlayerNetwork.remove(p);
            return;
        }
        BlockPos wall = wall(helper, feet, WALL);
        p.getInventory().clearContent();
        p.setItemInHand(InteractionHand.MAIN_HAND, bow);
        p.getInventory().setItem(1, new ItemStack(Items.ARROW, 4));   // a slot, not add(): LEARNINGS
        p.snapTo(feet.getX() + 0.5, feet.getY(), feet.getZ() + 0.5, 0.0F, 0.0F);
        helper.assertTrue(startUse(p), Component.literal("the laser bow cannot be DRAWN"));
        int before = arrowsNear(helper, feet);
        p.getMainHandItem().releaseUsing(helper.getLevel(), p, p.getMainHandItem().getUseDuration(p) - 30);
        p.stopUsingItem();
        expectBeam(helper, p, feet, wall, before, "bow");
    }

    @GameTest(template = "empty_test", batch = "onshot_crossbow", timeoutTicks = 900)
    public static void aCrossbowShootsAndTheBeamMakesTheWish(GameTestHelper helper) {
        if (!CbScriptCompiler.available()) {
            helper.succeed();
            return;
        }
        BlockPos feet = helper.absolutePos(new BlockPos(1, 2, 1));
        ServerPlayer p = shooter(helper, feet);
        ItemStack xbow = made(helper, p, "Laser Crossbow " + helper.getLevel().getGameTime(), "on_shot",
                "minecraft:crossbow");
        if (xbow.isEmpty()) {
            MockPlayerNetwork.remove(p);
            return;
        }
        BlockPos wall = wall(helper, feet, WALL);
        p.getInventory().clearContent();
        p.setItemInHand(InteractionHand.MAIN_HAND, xbow);
        p.getInventory().setItem(1, new ItemStack(Items.ARROW, 4));
        p.snapTo(feet.getX() + 0.5, feet.getY(), feet.getZ() + 0.5, 0.0F, 0.0F);
        // A crossbow is two uses: wind it (charge), then click again to loose it.
        helper.assertTrue(startUse(p), Component.literal("the laser crossbow cannot be WOUND"));
        // 1.21.5+ loads a crossbow in onUseTick once it is fully drawn (releaseUsing no longer
        // does), so draw it fully first, as holding the button does.
        p.getMainHandItem().getItem().onUseTick(helper.getLevel(), p, p.getMainHandItem(),
                p.getMainHandItem().getUseDuration(p)
                        - net.minecraft.world.item.CrossbowItem.getChargeDuration(p.getMainHandItem(), p));
        p.getMainHandItem().releaseUsing(helper.getLevel(), p, 0);
        p.stopUsingItem();
        helper.assertTrue(net.minecraft.world.item.CrossbowItem.isCharged(p.getMainHandItem()),
                Component.literal("the crossbow did not charge"));
        int before = arrowsNear(helper, feet);
        p.gameMode.useItem(p, p.level(), p.getMainHandItem(), InteractionHand.MAIN_HAND);
        expectBeam(helper, p, feet, wall, before, "crossbow");
    }

    @GameTest(template = "empty_test", batch = "onshot_trident", timeoutTicks = 900)
    public static void aTridentIsThrownAndTheBeamMakesTheWish(GameTestHelper helper) {
        if (!CbScriptCompiler.available()) {
            helper.succeed();
            return;
        }
        BlockPos feet = helper.absolutePos(new BlockPos(1, 2, 1));
        ServerPlayer p = shooter(helper, feet);
        ItemStack trident = made(helper, p, "Laser Trident " + helper.getLevel().getGameTime(), "on_shot",
                "minecraft:trident");
        if (trident.isEmpty()) {
            MockPlayerNetwork.remove(p);
            return;
        }
        BlockPos wall = wall(helper, feet, WALL);
        p.getInventory().clearContent();
        p.setItemInHand(InteractionHand.MAIN_HAND, trident);
        p.snapTo(feet.getX() + 0.5, feet.getY(), feet.getZ() + 0.5, 0.0F, 0.0F);
        helper.assertTrue(startUse(p), Component.literal("the laser trident cannot be WOUND UP"));
        int before = arrowsNear(helper, feet);
        p.getMainHandItem().releaseUsing(helper.getLevel(), p, p.getMainHandItem().getUseDuration(p) - 30);
        p.stopUsingItem();
        expectBeam(helper, p, feet, wall, before, "trident");
    }

    /** The crossbow's `arrow` test, as the bow and the trident have (ArrowAndMineShapeGameTest). */
    @GameTest(template = "empty_test", batch = "arrow_crossbow", timeoutTicks = 900)
    public static void aCrossbowBoltMakesTheWishWhereItLands(GameTestHelper helper) {
        if (!CbScriptCompiler.available()) {
            helper.succeed();
            return;
        }
        BlockPos feet = helper.absolutePos(new BlockPos(1, 2, 1));
        ServerPlayer p = shooter(helper, feet);
        ItemStack xbow = made(helper, p, "Glow Crossbow " + helper.getLevel().getGameTime(), "arrow",
                "minecraft:crossbow");
        if (xbow.isEmpty()) {
            MockPlayerNetwork.remove(p);
            return;
        }
        BlockPos wall = wall(helper, feet, 4);
        p.getInventory().clearContent();
        p.setItemInHand(InteractionHand.MAIN_HAND, xbow);
        p.getInventory().setItem(1, new ItemStack(Items.ARROW, 4));
        p.snapTo(feet.getX() + 0.5, feet.getY(), feet.getZ() + 0.5, 0.0F, 0.0F);
        helper.assertTrue(startUse(p), Component.literal("the crossbow cannot be WOUND"));
        // 1.21.5+ loads a crossbow in onUseTick once it is fully drawn (releaseUsing no longer
        // does), so draw it fully first, as holding the button does.
        p.getMainHandItem().getItem().onUseTick(helper.getLevel(), p, p.getMainHandItem(),
                p.getMainHandItem().getUseDuration(p)
                        - net.minecraft.world.item.CrossbowItem.getChargeDuration(p.getMainHandItem(), p));
        p.getMainHandItem().releaseUsing(helper.getLevel(), p, 0);
        p.stopUsingItem();
        p.gameMode.useItem(p, p.level(), p.getMainHandItem(), InteractionHand.MAIN_HAND);
        helper.succeedWhen(() -> {
            if (!glowstoneNear(helper, wall.north(), 1)) {
                throw GameTestCompat.assertion("no glowstone where the crossbow bolt hit the wall");
            }
            MockPlayerNetwork.remove(p);
        });
    }
}
