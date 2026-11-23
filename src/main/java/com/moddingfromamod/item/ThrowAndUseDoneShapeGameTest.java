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
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;
import com.moddingfromamod.testing.gt.GameTestHolder;

/**
 * <b>: a thrown thing is thrown as itself and the wish happens where it LANDS
 * ({@code on_throw_land}); a horn is blown and the wish happens when he is DONE
 * ({@code on_use_done}).</b>
 *
 * <p>The rest of group A. A {@code thrown} wish on a real snowball threw a second, tagged snowball of
 * our own and the real one's throw was cancelled; a {@code held} wish on a pearl, a horn or a brush
 * took their use away. Here the real item does its own thing through the real {@code useItem} — the
 * snowball, the pearl and the wind charge are thrown by vanilla, ONCE — and the wish rides on the
 * landing, or on the end of the use.
 *
 * <p>Each test is its own batch: the mock-player helper clears every stale mock.
 */
@GameTestHolder(ModdingFromAModMod.MODID)
public final class ThrowAndUseDoneShapeGameTest {

    private static ItemStack made(GameTestHelper helper, ServerPlayer p, String name, String shape, String base,
                                  int count) {
        JsonObject o = new JsonObject();
        o.addProperty("name", name);
        o.addProperty("icon", "orb");
        o.addProperty("colour", "#40C0FF");
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
        String line = MakeItemTool.gives(shape, tool.lastMadeId(), ItemCatalogue.icon("orb"), count,
                "A hint.", java.util.List.of(new MakeItemTool.Variant(name, 0x40C0FF)),
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

    private static ServerPlayer player(GameTestHelper helper, BlockPos feet) {
        MockPlayerNetwork.clearStaleMocks(helper);
        ServerPlayer p = MockPlayerNetwork.inLevel(helper);
        p.setGameMode(GameType.SURVIVAL);
        return p;
    }

    /** A 3x3 wall `distance` blocks south; the wish should land on its near face. */
    private static BlockPos wall(GameTestHelper helper, BlockPos feet, int distance) {
        BlockPos wall = feet.offset(0, 1, distance);
        for (int dx = -1; dx <= 1; dx++) {
            for (int dy = -1; dy <= 1; dy++) {
                helper.getLevel().setBlockAndUpdate(wall.offset(dx, dy, 0), Blocks.STONE.defaultBlockState());
            }
        }
        return wall;
    }

    /** Thrown by vanilla ONCE, one fewer in his hand, and the wish where it landed. */
    private static void throwIt(GameTestHelper helper, String base, Class<? extends Projectile> kind, String what) {
        if (!CbScriptCompiler.available()) {
            helper.succeed();
            return;
        }
        BlockPos feet = helper.absolutePos(new BlockPos(1, 2, 1));
        ServerPlayer p = player(helper, feet);
        ItemStack stack = made(helper, p, what + " " + helper.getLevel().getGameTime(), "on_throw_land", base, 4);
        if (stack.isEmpty()) {
            MockPlayerNetwork.remove(p);
            return;
        }
        BlockPos wall = wall(helper, feet, 5);
        p.getInventory().clearContent();
        p.setItemInHand(InteractionHand.MAIN_HAND, stack);
        p.snapTo(feet.getX() + 0.5, feet.getY(), feet.getZ() + 0.5, 0.0F, 0.0F);
        AABB around = new AABB(feet).inflate(12);
        int before = helper.getLevel().getEntitiesOfClass(kind, around).size();
        p.gameMode.useItem(p, p.level(), p.getMainHandItem(), InteractionHand.MAIN_HAND);
        int thrown = helper.getLevel().getEntitiesOfClass(kind, around).size() - before;
        helper.assertTrue(thrown == 1, Component.literal("the wished " + base + " must be thrown ONCE, as itself — thrown " + thrown));
        helper.assertTrue(p.getMainHandItem().getCount() == 3,
                Component.literal("one fewer " + base + " in his hand, as vanilla does — left " + p.getMainHandItem().getCount()));
        helper.succeedWhen(() -> {
            if (!glowstoneNear(helper, wall.north(), 1)) {
                throw GameTestCompat.assertion("the " + base + " landed and the wish never came");
            }
            MockPlayerNetwork.remove(p);
        });
    }

    @GameTest(template = "empty_test", batch = "throwland_snowball", timeoutTicks = 900)
    public static void aWishedSnowballIsThrownAsItselfAndTheWishIsWhereItLands(GameTestHelper helper) {
        throwIt(helper, "minecraft:snowball", net.minecraft.world.entity.projectile.Snowball.class, "Glow Snowball");
    }

    @GameTest(template = "empty_test", batch = "throwland_pearl", timeoutTicks = 900)
    public static void aWishedPearlIsThrownAsItselfAndTheWishIsWhereItLands(GameTestHelper helper) {
        throwIt(helper, "minecraft:ender_pearl", net.minecraft.world.entity.projectile.ThrownEnderpearl.class,
                "Glow Pearl");
    }

    @GameTest(template = "empty_test", batch = "throwland_wind", timeoutTicks = 900)
    public static void aWishedWindChargeIsThrownAsItselfAndTheWishIsWhereItLands(GameTestHelper helper) {
        // A wind charge carries no item of its own once thrown, so the wish is matched to the
        // click that threw it (WishShapeEvents).
        throwIt(helper, "minecraft:wind_charge",
                net.minecraft.world.entity.projectile.windcharge.WindCharge.class, "Glow Charge");
    }

    /** A horn is blown the way it always is, and the wish happens when he lets go. */
    @GameTest(template = "empty_test", batch = "usedone_horn", timeoutTicks = 900)
    public static void aWishedHornIsBlownAndTheWishHappensWhenItIsDone(GameTestHelper helper) {
        if (!CbScriptCompiler.available()) {
            helper.succeed();
            return;
        }
        BlockPos feet = helper.absolutePos(new BlockPos(1, 2, 1));
        ServerPlayer p = player(helper, feet);
        ItemStack horn = made(helper, p, "Glow Horn " + helper.getLevel().getGameTime(), "on_use_done",
                "minecraft:goat_horn", 1);
        if (horn.isEmpty()) {
            MockPlayerNetwork.remove(p);
            return;
        }
        p.setItemInHand(InteractionHand.MAIN_HAND, horn);
        // A horn with no instrument plays nothing; the one in a child's hand always has one.
        helper.getLevel().getServer().getCommands().performPrefixedCommand(
                p.createCommandSourceStack().withPermission(2).withSuppressedOutput(),
                "item modify entity @s weapon.mainhand {function:\"minecraft:set_instrument\","
                        + "options:\"#minecraft:goat_horns\"}");
        p.snapTo(feet.getX() + 0.5, feet.getY(), feet.getZ() + 0.5, 0.0F, 0.0F);
        p.gameMode.useItem(p, p.level(), p.getMainHandItem(), InteractionHand.MAIN_HAND);
        helper.assertTrue(p.isUsingItem(), Component.literal("the wished horn cannot be BLOWN"));
        BlockPos ahead = feet.offset(0, 1, 5);
        helper.assertFalse(glowstoneNear(helper, ahead, 2), Component.literal("the wish happened on the click, not when he was done"));
        p.releaseUsingItem();
        helper.succeedWhen(() -> {
            if (!glowstoneNear(helper, ahead, 2)) {
                throw GameTestCompat.assertion("he blew the horn and let go, and the wish never came");
            }
            MockPlayerNetwork.remove(p);
        });
    }

    /** make_item moves a click-taking shape on a throwable onto the landing, and says so. */
    @GameTest(template = "empty_test", batch = "throwland_redirect", timeoutTicks = 900)
    public static void aThrownSnowballIsBuiltToLand(GameTestHelper helper) {
        if (!CbScriptCompiler.available()) {
            helper.succeed();
            return;
        }
        JsonObject o = new JsonObject();
        o.addProperty("name", "Ride Snowball " + helper.getLevel().getGameTime());
        o.addProperty("icon", "orb");
        o.addProperty("colour", "#40C0FF");
        o.addProperty("shape", "thrown");
        o.addProperty("effect", "light");
        o.addProperty("count", 4);
        o.addProperty("base_item", "minecraft:snowball");
        JsonObject params = new JsonObject();
        params.addProperty("GLOW_SECONDS", "0");
        o.add("params", params);
        ToolResult r = new MakeItemTool().call(o);
        helper.assertTrue(!r.isError() && r.text().contains("Built as `on_throw_land`, not `thrown`"),
                Component.literal("a thrown snowball must be built to LAND as itself, and said so: " + r.text()));
        helper.succeed();
    }
}
