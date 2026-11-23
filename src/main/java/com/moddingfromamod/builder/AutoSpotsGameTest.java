package com.moddingfromamod.builder;

import net.minecraft.network.chat.Component;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import com.moddingfromamod.ModdingFromAModMod;
import com.moddingfromamod.agent.AgentContext;
import com.moddingfromamod.agent.ToolResult;
import com.moddingfromamod.agent.tools.DefineSpeciesTool;
import com.moddingfromamod.agent.tools.InspectBuildTool;
import com.moddingfromamod.marshal.Marshal;
import com.moddingfromamod.testing.MockPlayerNetwork;
import com.moddingfromamod.wish.Wish;
import com.moddingfromamod.wish.WishExecutor;
import net.minecraft.core.BlockPos;
import com.moddingfromamod.testing.gt.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.block.Blocks;
import com.moddingfromamod.testing.gt.GameTestHolder;

/**
 * <b>an earlier issue's automatic spots and the shared {@code at}, where they RUN</b>: a hall built with NO
 * spots marked still has a roof, an inside and a ring around it, and {@code wishspot}, {@code
 * inspect_build} and {@code define_creature} all reach them through the one finder — plus {@code me}
 * as the thing. The rules are Gate A ({@code BuildAutoSpotsTest}, {@code PlaceResolverTest}); this is
 * the wiring.
 *
 * <p>Each test is its own batch (they bind a wish run; see {@link BuildSpotsGameTest}). The hall goes
 * up high over a stone platform, clear of every other test's plot.
 */
@GameTestHolder(ModdingFromAModMod.MODID)
public class AutoSpotsGameTest {

    /** The hall with its throne spot taken away: nothing marked, everything automatic. */
    public static BuildPlan plainHall(String id) {
        return BuildPlan.fromJson(BuildSpotsGameTest.args(BuildSpotsGameTest.hallJson(id))).withSpots(List.of());
    }

    /** Put {@code plan} up at {@code anchor} over a 31x31 stone platform one below its floor. */
    public static void standOnPlatform(ServerLevel level, BlockPos anchor, BuildPlan plan) {
        for (int x = -10; x <= 20; x++) {
            for (int z = -10; z <= 20; z++) {
                level.setBlockAndUpdate(anchor.offset(x, -1, z), Blocks.STONE.defaultBlockState());
            }
        }
        BuildRegistry.put(level.getServer(), plan);
        Builder.submit(level, anchor, plan);
    }

    public static void clearPlatform(ServerLevel level, BlockPos anchor, BuildPlan plan) {
        Builder.clearBuild(level, anchor, plan);
        for (int x = -10; x <= 20; x++) {
            for (int z = -10; z <= 20; z++) {
                level.setBlockAndUpdate(anchor.offset(x, -1, z), Blocks.AIR.defaultBlockState());
            }
        }
    }

    private static Entity tagged(ServerLevel level, String tag) {
        for (Entity e : level.getEntities().getAll()) {
            if (e.getTags().contains(tag)) {
                return e;
            }
        }
        return null;
    }

    // ===================== wishspot on the automatic spots, and on him ===========================

    @GameTest(template = "empty_test", batch = "autospots_ws", timeoutTicks = 400)
    public static void aHallWithNoSpotsMarkedStillHasARoofAnInsideAndHimInFrontOfIt(GameTestHelper helper) {
        String hall = "gt_auto_ws_hall";
        ServerLevel level = helper.getLevel();
        ServerPlayer mock = MockPlayerNetwork.inLevel(helper);
        BuildPlan plan = plainHall(hall);
        BlockPos anchor = helper.absolutePos(new BlockPos(1, 1, 1)).offset(0, 70, 0);
        standOnPlatform(level, anchor, plan);
        // A tool call, so off the server thread (AgentBridge refuses the deadlock otherwise).
        AtomicReference<String> inspected = new AtomicReference<>();
        BuildSpotsGameTest.offThread(() -> inspected.set(
                new InspectBuildTool().call(BuildSpotsGameTest.args("{\"id\":\"" + hall + "\"}")).text()));
        List<String> said = new ArrayList<>();
        for (String[] c : new String[][]{{hall + ":roof", "gt_auto_top"}, {hall + ":inside", "gt_auto_in"},
                {"me:front", "gt_auto_me"}}) {
            WishExecutor.Result r = WishExecutor.runOne(level.getServer(), level, mock.position(), mock,
                    "wishspot " + c[0] + " run summon minecraft:armor_stand ~ ~ ~ {NoGravity:1b,Tags:[\"" + c[1] + "\"]}");
            said.add(String.join(" ", r.output()));
            helper.assertTrue(r.success(), Component.literal("wishspot " + c[0] + " failed: " + said));
        }
        BlockPos top = anchor.offset(BuildSpots.spot(plan, "top", 0, 0).dx(), BuildSpots.spot(plan, "top", 0, 0).dy(),
                BuildSpots.spot(plan, "top", 0, 0).dz());
        BuildSpot in = BuildSpots.spot(plan, "inside", 0, 0);
        BlockPos inside = anchor.offset(in.dx(), in.dy(), in.dz());
        helper.succeedWhen(() -> {
            helper.assertTrue(inspected.get() != null, Component.literal("waiting for inspect_build"));
            helper.assertTrue(inspected.get().contains("top at") && inspected.get().contains("inside at")
                            && inspected.get().contains("around at") && inspected.get().contains("(automatic)"),
                    Component.literal("inspect_build must list the automatic spots: " + inspected.get()));
            Entity a = tagged(level, "gt_auto_top"), b = tagged(level, "gt_auto_in"), c = tagged(level, "gt_auto_me");
            helper.assertTrue(a != null && b != null && c != null, Component.literal("waiting for the three stands: " + said));
            helper.assertTrue(a.blockPosition().equals(top), Component.literal("roof → top: the stand is at "
                    + a.blockPosition().toShortString() + ", the roof spot is " + top.toShortString()));
            helper.assertTrue(level.getBlockState(top.below()).is(Blocks.STONE_BRICKS),
                    Component.literal("it must stand ON the roof: below it is " + level.getBlockState(top.below())));
            helper.assertTrue(b.blockPosition().equals(inside), Component.literal("inside: the stand is at "
                    + b.blockPosition().toShortString() + ", the room's floor centre is " + inside.toShortString()));
            double r = Math.toRadians(mock.getYRot());
            double fx = mock.getX() - Math.sin(r) * (mock.getBbWidth() / 2 + 1.5);
            double fz = mock.getZ() + Math.cos(r) * (mock.getBbWidth() / 2 + 1.5);
            helper.assertTrue(Math.hypot(c.getX() - fx, c.getZ() - fz) < 0.01,
                    Component.literal("me:front: the stand is at " + c.position() + ", in front of him is " + fx + "," + fz));
            a.discard();
            b.discard();
            c.discard();
            clearPlatform(level, anchor, plan);
            MockPlayerNetwork.remove(mock);
        });
    }

    // ===================== a herd around it is spread round it, never on its foundations =========

    @GameTest(template = "empty_test", batch = "autospots_ring", timeoutTicks = 600)
    public static void aHerdAroundAHallStandsOnItsRingAndStaysThere(GameTestHelper helper) {
        String hall = "gt_auto_ring_hall", sheep = "gt_auto_ring_sheep";
        ServerLevel level = helper.getLevel();
        ServerPlayer mock = MockPlayerNetwork.inLevel(helper);
        BuildPlan plan = plainHall(hall);
        BlockPos anchor = helper.absolutePos(new BlockPos(1, 1, 1)).offset(0, 95, 0);   // its own height over its own plot: no other test's hall is ever here
        standOnPlatform(level, anchor, plan);
        Wish wish = BuildSpotsGameTest.bind(level, mock, "three sheep around my hall", "gt-auto-ring");
        AtomicReference<ToolResult> made = new AtomicReference<>();
        List<BlockPos> firstAt = new ArrayList<>();
        BuildSpotsGameTest.offThread(() -> AgentContext.with("gt-auto-ring", () -> {
            made.set(new DefineSpeciesTool().call(BuildSpotsGameTest.args("{\"id\":\"" + sheep + "\",\"name\":\"Fluff\","
                    + "\"spawn\":true,\"count\":3,\"powers\":[\"friendly\"],\"at\":\"" + hall + ":around\"}")));
            return null;
        }));
        BuildSpot around = BuildSpots.spot(plan, "around", 0, 0);
        double cx = anchor.getX() + around.dx() + 0.5, cz = anchor.getZ() + around.dz() + 0.5;
        double ring = BuildAutoSpots.aroundRadius(plan);
        helper.succeedWhen(() -> {
            helper.assertTrue(made.get() != null, Component.literal("waiting for define_creature"));
            helper.assertFalse(made.get().isError(), Component.literal("define_creature failed: " + made.get().text()));
            List<LivingEntity> on = BuildSpotsGameTest.carriers(level, sheep);
            if (firstAt.isEmpty() && on.size() == 3) {
                on.forEach(e -> firstAt.add(e.blockPosition()));
            }
            helper.assertTrue(on.size() == 3, Component.literal("three should stand there, found " + on.size() + ": " + made.get().text()));
            for (BlockPos p : firstAt) {
                double d = Math.hypot(p.getX() + 0.5 - cx, p.getZ() + 0.5 - cz);
                helper.assertTrue(Math.abs(d - ring) <= 1.5, Component.literal("each on the ring " + ring + " out, one is "
                        + d + " out at " + p.toShortString()));
                helper.assertTrue(p.getY() == anchor.getY(), Component.literal("on the ground beside it, not at "
                        + p.getY() + " (the floor is " + anchor.getY() + ")"));
            }
            helper.assertTrue(firstAt.get(0).distManhattan(firstAt.get(1)) > 4,
                    Component.literal("spread round it, not piled up: " + firstAt));
            for (LivingEntity e : on) {
                helper.assertFalse(e.isInWall(), Component.literal("one is inside a block"));
                helper.assertTrue(!(e instanceof net.minecraft.world.entity.Mob m) || m.hasHome(),
                        Component.literal("a place the agent NAMED keeps them there: each stays near its point on the ring"));
            }
            Marshal.global().finish(wish.id());
            BuildSpotsGameTest.tidy(level, sheep);
            clearPlatform(level, anchor, plan);
            MockPlayerNetwork.remove(mock);
        });
    }
}
