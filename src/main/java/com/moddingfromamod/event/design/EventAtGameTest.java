package com.moddingfromamod.event.design;

import net.minecraft.network.chat.Component;
import java.util.concurrent.atomic.AtomicReference;

import com.google.gson.JsonParser;
import com.moddingfromamod.ModdingFromAModMod;
import com.moddingfromamod.agent.ToolResult;
import com.moddingfromamod.agent.tools.StageEventTool;
import com.moddingfromamod.builder.AutoSpotsGameTest;
import com.moddingfromamod.builder.BuildPlan;
import com.moddingfromamod.builder.BuildSpot;
import com.moddingfromamod.builder.BuildSpots;
import com.moddingfromamod.builder.Builder;
import com.moddingfromamod.event.EventDirector;
import com.moddingfromamod.event.StagedEvent;
import com.moddingfromamod.testing.MockPlayerNetwork;
import net.minecraft.core.BlockPos;
import com.moddingfromamod.testing.gt.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import com.moddingfromamod.testing.gt.GameTestHolder;

/**
 * <b>: an event aimed AT a building, through the real {@code stage_event}</b> — a crater on
 * the hall's roof (its default spot, {@code top}) and a flood {@code around} it rising from the
 * ground beside it, each with the hall inside the camera's planned reach. {@code EventAimTest} is
 * the rule; this is the wiring, in a lane of its own far from every other event test.
 */
@GameTestHolder(ModdingFromAModMod.MODID)
public final class EventAtGameTest {

    private static AtomicReference<String> call(String... jsons) {
        AtomicReference<String> out = new AtomicReference<>();
        Thread t = new Thread(() -> {
            StringBuilder all = new StringBuilder();
            for (String json : jsons) {
                ToolResult r = new StageEventTool().call(JsonParser.parseString(json).getAsJsonObject());
                all.append(r.isError() ? "ERROR " : "OK ").append(r.text()).append("\n@@@\n");
            }
            out.set(all.toString());
        }, "event-at-test");
        t.setDaemon(true);
        t.start();
        return out;
    }

    private static String idAfter(String replies, int n) {
        String reply = replies.split("\n@@@\n")[n];
        int a = reply.indexOf("happening now (");
        return a < 0 ? null : reply.substring(a + "happening now (".length(), reply.indexOf(')', a));
    }

    @GameTest(template = "empty_test", batch = "event_at", timeoutTicks = 900)
    public static void aCraterLandsOnTheHallsRoofAndAFloodRisesRoundIt(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        MinecraftServer server = level.getServer();
        MockPlayerNetwork.clearStaleMocks(helper);
        ServerPlayer child = MockPlayerNetwork.inLevel(helper);
        MockPlayerNetwork.actingChildFor(helper, child);   // put back on pass AND fail (the 18-test cluster)
        BlockPos lane = new BlockPos(90_900, 0, 108_400);
        for (int cx = (lane.getX() - 48) >> 4; cx <= (lane.getX() + 48) >> 4; cx++) {
            for (int cz = (lane.getZ() - 48) >> 4; cz <= (lane.getZ() + 48) >> 4; cz++) {
                level.setChunkForced(cx, cz, true);
            }
        }
        String hall = "gt_event_at_hall";
        BuildPlan plan = AutoSpotsGameTest.plainHall(hall);
        BlockPos anchor = EventBuild.ground(level, lane.getX(), lane.getZ()).above();
        AutoSpotsGameTest.standOnPlatform(level, anchor, plan);
        BuildSpot top = BuildSpots.spot(plan, "top", 0, 0);
        BlockPos roof = anchor.offset(top.dx(), top.dy() - 1, top.dz());
        BuildSpot around = BuildSpots.spot(plan, "around", 0, 0);
        long t = level.getGameTime();
        AtomicReference<AtomicReference<String>> staged = new AtomicReference<>();
        helper.succeedWhen(() -> {
            helper.assertFalse(Builder.isBuilding(hall), Component.literal("waiting for the hall"));
            if (staged.get() == null) {
                staged.set(call(
                        "{\"name\":\"gt_roof_crater_" + t + "\",\"motion\":\"impact_stamp\",\"size\":\"little\","
                                + "\"material\":\"minecraft:magma_block\",\"afterwards\":\"until_undo\",\"at\":\"" + hall + "\"}",
                        "{\"name\":\"gt_moat_flood_" + t + "\",\"motion\":\"layer\",\"size\":\"little\","
                                + "\"material\":\"minecraft:water\",\"afterwards\":\"until_undo\",\"at\":\"" + hall
                                + ":around\"}"));
            }
            String replies = staged.get().get();
            helper.assertTrue(replies != null, Component.literal("waiting for stage_event"));
            helper.assertFalse(replies.contains("ERROR"), Component.literal("stage_event refused: " + replies));
            helper.assertTrue(replies.contains("happening at " + hall + ":top"), Component.literal("it must say where: " + replies));
            StagedEvent crater = EventDirector.get(server, idAfter(replies, 0));
            StagedEvent flood = EventDirector.get(server, idAfter(replies, 1));
            helper.assertTrue(crater != null && flood != null, Component.literal("both must be running: " + replies));
            helper.assertTrue(roof.equals(crater.focus()), Component.literal("the crater's centre is " + crater.focus()
                    + ", the hall's roof is " + roof));
            var reach = crater.planned();
            helper.assertTrue(reach.x0() <= anchor.getX() && reach.z0() <= anchor.getZ()
                            && reach.x1() >= anchor.getX() + 10 && reach.y0() <= anchor.getY(),
                    Component.literal("the camera's reach must hold the whole hall, it is " + reach));
            BlockPos f = flood.focus();
            helper.assertTrue(f.getX() == anchor.getX() + around.dx() && f.getZ() == anchor.getZ() + around.dz()
                            && f.getY() == anchor.getY() - 1,
                    Component.literal("a flood around the hall rises from the ground at its middle (" + (anchor.getY() - 1)
                            + "), not its roof: " + f));
            // And the WATER, not only its centre: the focus was right while the level stood on the
            // roof and the flood hung in the air (the at-e3 grant). Beside the wall at the ground:
            BlockPos beside = new BlockPos(anchor.getX() - 2, anchor.getY(), f.getZ());
            helper.assertTrue(level.getFluidState(beside).is(net.minecraft.tags.FluidTags.WATER),
                    Component.literal("waiting for the flood to stand on the ground beside the hall at " + beside));
            helper.assertFalse(level.getFluidState(beside.atY(roof.getY() + 1)).is(net.minecraft.tags.FluidTags.WATER),
                    Component.literal("the flood must not stand as high as the hall's roof (" + roof.getY() + ")"));
            EventDirector.undo(server, crater.id());
            EventDirector.undo(server, flood.id());
            AutoSpotsGameTest.clearPlatform(level, anchor, plan);
            MockPlayerNetwork.remove(child);
        });
    }
}
