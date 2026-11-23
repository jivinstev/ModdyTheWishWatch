package com.moddingfromamod.item;

import net.minecraft.network.chat.Component;
import java.util.concurrent.atomic.AtomicReference;

import com.google.gson.JsonObject;
import com.moddingfromamod.ModdingFromAModMod;
import com.moddingfromamod.agent.ToolResult;
import com.moddingfromamod.agent.tools.MakeItemTool;
import com.moddingfromamod.agent.tools.RunCommandTool;
import com.moddingfromamod.builder.AutoSpotsGameTest;
import com.moddingfromamod.builder.BuildPlan;
import com.moddingfromamod.builder.BuildSpot;
import com.moddingfromamod.builder.BuildSpots;
import com.moddingfromamod.builder.Builder;
import com.moddingfromamod.datapack.CbScriptCompiler;
import com.moddingfromamod.testing.MockPlayerNetwork;
import net.minecraft.core.BlockPos;
import com.moddingfromamod.testing.gt.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import com.moddingfromamod.testing.gt.GameTestHolder;

/**
 * <b>: a made thing put SOMEWHERE by name, through the tools the agent calls</b> — a placed
 * thing put down inside a hall (its marker on the room's floor, as his right-click would leave it),
 * a keepsake into the chest standing there, and a real minecart summoned into the hall with
 * {@code run_command}'s {@code at} and NOT tidied away as a test prop.
 */
@GameTestHolder(ModdingFromAModMod.MODID)
public class PutAtGameTest {

    private static JsonObject thing(String name, String shape, String at) {
        JsonObject o = new JsonObject();
        o.addProperty("name", name);
        o.addProperty("icon", "wand");
        o.addProperty("colour", "#FFAA00");
        o.addProperty("shape", shape);
        if (!"plain".equals(shape)) {
            o.addProperty("effect", "light");
            JsonObject params = new JsonObject();
            params.addProperty("GLOW_SECONDS", "0");
            o.add("params", params);
        }
        o.addProperty("count", 1);
        o.addProperty("at", at);
        return o;
    }

    private static String featureId(String text) {
        int i = text.indexOf("feature id: ");
        return i < 0 ? "" : text.substring(i + "feature id: ".length()).split("[\\s(]")[0];
    }

    @GameTest(template = "empty_test", batch = "putat_make", timeoutTicks = 1200)
    public static void aPlacedThingIsPutDownInTheHallAndAKeepsakeGoesInItsChest(GameTestHelper helper) {
        if (!CbScriptCompiler.available()) {
            helper.succeed();
            return;
        }
        String hall = "gt_putat_hall";
        ServerLevel level = helper.getLevel();
        ServerPlayer mock = MockPlayerNetwork.inLevel(helper);
        BuildPlan plan = AutoSpotsGameTest.plainHall(hall);
        BlockPos anchor = helper.absolutePos(new BlockPos(1, 1, 1)).offset(0, 115, 0);   // its own height over its own plot
        AutoSpotsGameTest.standOnPlatform(level, anchor, plan);
        BuildSpot in = BuildSpots.spot(plan, "inside", 0, 0);
        BlockPos inside = anchor.offset(in.dx(), in.dy(), in.dz());
        long t = level.getGameTime();
        AtomicReference<ToolResult> placed = new AtomicReference<>(), kept = new AtomicReference<>();
        // A bound wish, as the agent's make_item always has: with nobody in the loop, a wishspot is a
        // REPLAY, and seats a replay on the building he is aiming at rather than the one named.
        var wish = com.moddingfromamod.builder.BuildSpotsGameTest.bind(level, mock, "a glowing totem in my hall",
                "gt-putat-make");
        helper.succeedWhen(() -> {
            helper.assertFalse(Builder.isBuilding(hall), Component.literal("waiting for the hall"));
            if (placed.get() == null) {
                // The chest the keepsake goes into, standing on the inside spot's floor beside it.
                level.setBlockAndUpdate(inside.east(), Blocks.CHEST.defaultBlockState());
                com.moddingfromamod.agent.AgentContext.with("gt-putat-make", () -> {
                    placed.set(new MakeItemTool().call(thing("Glow Totem " + t, "placed", hall + ":inside")));
                    kept.set(new MakeItemTool().call(thing("Lucky Pebble " + t, "plain", hall + ":inside")));
                    return null;
                });
            }
            ToolResult p = placed.get(), k = kept.get();
            helper.assertTrue(!p.isError() && p.text().contains("PUT THERE"), Component.literal("make_item at must put it there: " + p.text()));
            helper.assertFalse(p.text().contains("NOW PUT IT IN HIS HAND"),
                    Component.literal("a thing put somewhere is not handed over as well: " + p.text()));
            String fid = featureId(p.text());
            ArmorStand marker = null;
            for (Entity e : level.getEntities().getAll()) {
                if (e instanceof ArmorStand a && a.getTags().contains(fid + "_here")) {
                    marker = a;
                }
            }
            helper.assertTrue(marker != null, Component.literal("no marker tagged " + fid + "_here was put down: " + p.text()));
            helper.assertTrue(marker.blockPosition().equals(inside), Component.literal("the marker is at "
                    + marker.blockPosition().toShortString() + ", the hall's inside spot is " + inside.toShortString()));
            helper.assertTrue(!k.isError() && k.text().contains("PUT THERE"), Component.literal("the keepsake: " + k.text()));
            helper.assertTrue(level.getBlockEntity(inside.east()) instanceof ChestBlockEntity chest
                            && !chest.isEmpty()
                            && chest.getItem(0).getHoverName().getString().contains("Lucky Pebble"),
                    Component.literal("the keepsake must be IN the chest at the spot: " + k.text()));
            com.moddingfromamod.marshal.Marshal.global().finish(wish.id());
            marker.discard();
            level.setBlockAndUpdate(inside.east(), Blocks.AIR.defaultBlockState());
            AutoSpotsGameTest.clearPlatform(level, anchor, plan);
            MockPlayerNetwork.remove(mock);
        });
    }

    @GameTest(template = "empty_test", batch = "putat_cmd", timeoutTicks = 600)
    public static void aMinecartSummonedIntoTheHallStaysThere(GameTestHelper helper) {
        String hall = "gt_putat_cmd_hall";
        ServerLevel level = helper.getLevel();
        ServerPlayer mock = MockPlayerNetwork.inLevel(helper);
        BuildPlan plan = AutoSpotsGameTest.plainHall(hall);
        BlockPos anchor = helper.absolutePos(new BlockPos(1, 1, 1)).offset(0, 30, 0);  // within the prop bracket's 48 blocks of him, so the skip is what is tested
        AutoSpotsGameTest.standOnPlatform(level, anchor, plan);
        BuildSpot in = BuildSpots.spot(plan, "inside", 0, 0);
        BlockPos inside = anchor.offset(in.dx(), in.dy(), in.dz());
        AtomicReference<ToolResult> ran = new AtomicReference<>();
        // A bound wish, so the run's prop ledger is live: the cart must NOT be written on it.
        var wish = com.moddingfromamod.builder.BuildSpotsGameTest.bind(level, mock, "cars in my garage", "gt-putat-cmd");
        JsonObject o = new JsonObject();
        com.google.gson.JsonArray cmds = new com.google.gson.JsonArray();
        cmds.add("summon minecraft:minecart ~ ~ ~ {Tags:[\"gt_putat_cart\"]}");
        o.add("command", cmds);
        o.addProperty("at", hall + ":inside");
        // A tool call, so off the server thread; wishspot waits for the hall to finish.
        com.moddingfromamod.builder.BuildSpotsGameTest.offThread(() -> com.moddingfromamod.agent.AgentContext.with(
                "gt-putat-cmd", () -> {
                    ran.set(new RunCommandTool().call(o));
                    return null;
                }));
        helper.succeedWhen(() -> {
            helper.assertTrue(ran.get() != null, Component.literal("waiting for run_command"));
            helper.assertFalse(ran.get().isError(), Component.literal("run_command at failed: " + ran.get().text()));
            Entity cart = null;
            for (Entity e : level.getEntities().getAll()) {
                if (e.getTags().contains("gt_putat_cart")) {
                    cart = e;
                }
            }
            helper.assertTrue(cart != null, Component.literal("waiting for the cart: " + ran.get().text()));
            helper.assertTrue(cart.blockPosition().distManhattan(inside) <= 1, Component.literal("the cart is at "
                    + cart.blockPosition().toShortString() + ", the hall's inside is " + inside.toShortString()));
            var run = com.moddingfromamod.marshal.Marshal.global().run(wish.id());
            helper.assertTrue(run != null && run.commandSpawned().stream().noneMatch(pr -> pr.typeId().contains("minecart")),
                    Component.literal("a cart put AT a named place is what he asked for — it must not be on the prop ledger "
                            + "the end-of-wish tidy takes down (#1343): " + (run == null ? "no run" : run.commandSpawned())));
            com.moddingfromamod.marshal.Marshal.global().finish(wish.id());
            cart.discard();
            AutoSpotsGameTest.clearPlatform(level, anchor, plan);
            MockPlayerNetwork.remove(mock);
        });
    }
}
