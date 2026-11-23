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
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import com.moddingfromamod.testing.gt.GameTestHolder;

/**
 * <b>The two shapes vanilla fires: an arrow that lands, a block that breaks.</b>
 *
 * <p>Each wish is made by the real {@code make_item} and given with the production give line, then
 * vanilla does the rest — the bow is drawn and released through {@code releaseUsing}, the block is
 * broken through {@code gameMode.destroyBlock} — and the world is asked what happened. The effect
 * is {@code place} glowstone, because it leaves something a test can find. Red on {@code main},
 * where neither shape existed.
 */
@GameTestHolder(ModdingFromAModMod.MODID)
public final class ArrowAndMineShapeGameTest {

    private static final String BATCH = "arrowmine";

    private static ItemStack made(GameTestHelper helper, ServerPlayer p, String name, String shape, String base) {
        JsonObject o = new JsonObject();
        o.addProperty("name", name);
        o.addProperty("icon", "wand");
        o.addProperty("colour", "#FFAA00");
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
            helper.fail(Component.literal("make_item refused " + shape + ": " + r.text()));
            return ItemStack.EMPTY;
        }
        String line = MakeItemTool.gives(shape, tool.lastMadeId(), ItemCatalogue.icon("wand"), 1,
                "A hint.", java.util.List.of(new MakeItemTool.Variant(name, 0xFFAA00)),
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

    private static boolean glowstoneNear(GameTestHelper helper, BlockPos centre, int r) {
        for (BlockPos q : BlockPos.betweenClosed(centre.offset(-r, -r, -r), centre.offset(r, r, r))) {
            if (helper.getLevel().getBlockState(q).is(Blocks.GLOWSTONE)) {
                return true;
            }
        }
        return false;
    }

    /** A wall three blocks south; he draws the bow fully and lets go. */
    @GameTest(template = "empty_test", batch = BATCH, timeoutTicks = 900)
    public static void anArrowFromHisBowMakesTheWishWhereItLands(GameTestHelper helper) {
        if (!CbScriptCompiler.available()) {
            helper.succeed();
            return;
        }
        MockPlayerNetwork.clearStaleMocks(helper);
        ServerPlayer p = MockPlayerNetwork.inLevel(helper);
        ItemStack bow = made(helper, p, "Glow Bow " + helper.getLevel().getGameTime(), "arrow", "minecraft:bow");
        if (bow.isEmpty()) {
            MockPlayerNetwork.remove(p);
            return;
        }
        BlockPos feet = helper.absolutePos(new BlockPos(1, 2, 1));
        BlockPos wall = feet.offset(0, 1, 4);
        for (int dx = -1; dx <= 1; dx++) {
            for (int dy = -1; dy <= 1; dy++) {
                helper.getLevel().setBlockAndUpdate(wall.offset(dx, dy, 0), Blocks.STONE.defaultBlockState());
            }
        }
        p.getInventory().clearContent();
        p.setItemInHand(InteractionHand.MAIN_HAND, bow);
        p.getInventory().setItem(1, new ItemStack(Items.ARROW, 4));   // a slot, not add(): LEARNINGS
        p.snapTo(feet.getX() + 0.5, feet.getY(), feet.getZ() + 0.5, 0.0F, 0.0F);
        p.getMainHandItem().releaseUsing(helper.getLevel(), p, p.getMainHandItem().getUseDuration(p) - 30);
        helper.succeedWhen(() -> {
            if (!glowstoneNear(helper, wall.north(), 1)) {
                throw GameTestCompat.assertion("no glowstone where the arrow hit the wall; arrows="
                                + helper.getLevel().getEntitiesOfClass(net.minecraft.world.entity.projectile.AbstractArrow.class,
                                        new net.minecraft.world.phys.AABB(feet).inflate(12)).stream()
                                        .map(a -> a.position() + " fn=" + a.getPersistentData().getStringOr(WishThrowImpact.HIT_FN, "")
                                                + " weapon=" + WishShapeEvents.weaponOf(a))
                                        .toList());
            }
            MockPlayerNetwork.remove(p);
        });
    }

    /**
     * A TRIDENT, thrown: vanilla records no weapon for a trident (it IS the projectile), so the
     * wish rides on its own stack — measured in Gate D, where the lightning trident struck nothing.
     */
    @GameTest(template = "empty_test", batch = BATCH, timeoutTicks = 900)
    public static void aThrownTridentMakesTheWishWhereItLands(GameTestHelper helper) {
        if (!CbScriptCompiler.available()) {
            helper.succeed();
            return;
        }
        MockPlayerNetwork.clearStaleMocks(helper);
        ServerPlayer p = MockPlayerNetwork.inLevel(helper);
        ItemStack trident = made(helper, p, "Glow Trident " + helper.getLevel().getGameTime(), "arrow",
                "minecraft:trident");
        if (trident.isEmpty()) {
            MockPlayerNetwork.remove(p);
            return;
        }
        BlockPos feet = helper.absolutePos(new BlockPos(1, 2, 1));
        BlockPos wall = feet.offset(0, 1, 4);
        for (int dx = -1; dx <= 1; dx++) {
            for (int dy = -1; dy <= 1; dy++) {
                helper.getLevel().setBlockAndUpdate(wall.offset(dx, dy, 0), Blocks.STONE.defaultBlockState());
            }
        }
        p.getInventory().clearContent();
        p.setItemInHand(InteractionHand.MAIN_HAND, trident);
        p.snapTo(feet.getX() + 0.5, feet.getY(), feet.getZ() + 0.5, 0.0F, 0.0F);
        p.getMainHandItem().releaseUsing(helper.getLevel(), p, p.getMainHandItem().getUseDuration(p) - 30);
        helper.succeedWhen(() -> {
            if (!glowstoneNear(helper, wall.north(), 1)) {
                throw GameTestCompat.assertion("no glowstone where the trident hit the wall; tridents="
                                + helper.getLevel().getEntitiesOfClass(net.minecraft.world.entity.projectile.ThrownTrident.class,
                                        new net.minecraft.world.phys.AABB(feet).inflate(12)).stream()
                                        .map(a -> a.position() + " fn=" + a.getPersistentData().getStringOr(WishThrowImpact.HIT_FN, "")
                                                + " weapon=" + WishShapeEvents.weaponOf(a).get(
                                                        net.minecraft.core.component.DataComponents.CUSTOM_DATA))
                                        .toList());
            }
            MockPlayerNetwork.remove(p);
        });
    }

    /** He breaks stone with the pickaxe; the wish happens at the block AFTER it breaks. */
    @GameTest(template = "empty_test", batch = BATCH, timeoutTicks = 900)
    public static void aPickaxeMakesTheWishWhereHeMines(GameTestHelper helper) {
        if (!CbScriptCompiler.available()) {
            helper.succeed();
            return;
        }
        MockPlayerNetwork.clearStaleMocks(helper);
        ServerPlayer p = MockPlayerNetwork.inLevel(helper);
        ItemStack pick = made(helper, p, "Glow Pick " + helper.getLevel().getGameTime(), "on_mine",
                "minecraft:diamond_pickaxe");
        if (pick.isEmpty()) {
            MockPlayerNetwork.remove(p);
            return;
        }
        BlockPos rock = helper.absolutePos(new BlockPos(2, 2, 2));
        helper.getLevel().setBlockAndUpdate(rock, Blocks.STONE.defaultBlockState());
        p.getInventory().clearContent();
        p.setItemInHand(InteractionHand.MAIN_HAND, pick);
        p.snapTo(rock.getX() + 0.5, rock.getY() + 1, rock.getZ() - 1.5, 0.0F, 30.0F);
        boolean broke = p.gameMode.destroyBlock(rock);
        helper.assertTrue(broke, Component.literal("the pickaxe could not break the stone"));
        helper.succeedWhen(() -> {
            if (!helper.getLevel().getBlockState(rock).is(Blocks.GLOWSTONE)) {
                throw GameTestCompat.assertion("the mined block is " + helper.getLevel().getBlockState(rock) + ", not the wish; held "
                                + p.getMainHandItem().get(net.minecraft.core.component.DataComponents.CUSTOM_DATA)
                                + " fnExists=" + helper.getLevel().getServer().getFunctions().get(
                                        net.minecraft.resources.ResourceLocation.parse(WishItem.onMine(pick))).isPresent());
            }
            MockPlayerNetwork.remove(p);
        });
    }

    /** A thing that cannot shoot cannot be an arrow wish; it is refused in words. */
    @GameTest(template = "empty_test", batch = BATCH, timeoutTicks = 600)
    public static void anArrowOnAShovelIsRefused(GameTestHelper helper) {
        JsonObject o = new JsonObject();
        o.addProperty("name", "Bad Arrow");
        o.addProperty("icon", "wand");
        o.addProperty("colour", "#FFAA00");
        o.addProperty("shape", "arrow");
        o.addProperty("effect", "light");
        o.addProperty("count", 1);
        o.addProperty("base_item", "minecraft:iron_shovel");
        JsonObject params = new JsonObject();
        params.addProperty("GLOW_SECONDS", "0");
        o.add("params", params);
        ToolResult r = new MakeItemTool().call(o);
        helper.assertTrue(r.isError() && r.text().contains("bow"), Component.literal("an arrow on a shovel was accepted: " + r.text()));
        helper.succeed();
    }
}
