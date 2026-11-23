package com.moddingfromamod.builder;

import net.minecraft.network.chat.Component;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import com.google.gson.JsonObject;
import com.moddingfromamod.ModdingFromAModMod;
import com.moddingfromamod.agent.AgentContext;
import com.moddingfromamod.agent.ToolResult;
import com.moddingfromamod.agent.tools.BuildStructureTool;
import com.moddingfromamod.agent.tools.PaintSurfaceTool;
import com.moddingfromamod.marshal.Marshal;
import com.moddingfromamod.testing.MockPlayerNetwork;
import com.moddingfromamod.wish.Wish;
import net.minecraft.core.BlockPos;
import com.moddingfromamod.testing.gt.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.decoration.ItemFrame;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;
import com.moddingfromamod.testing.gt.GameTestHolder;

/**
 * <b>: a new building put BY one he has, and a picture on its wall</b>, through the real
 * {@code build_structure} and {@code paint_surface}: a hay-bale stable {@code beside} the hall stands
 * outside its west wall (the hall's door faces south, so beside is clockwise: west) with air between,
 * and a picture {@code at} the hall's north wall hangs its map art in front of that wall. The rules
 * are Gate A ({@code BuildBesideTest}, {@code BuildAutoSpotsTest}); this is the wiring.
 */
@GameTestHolder(ModdingFromAModMod.MODID)
public class BuildAtGameTest {

    @GameTest(template = "empty_test", batch = "build_at", timeoutTicks = 1600)
    public static void aStableBesideTheHallAndAPictureOnItsNorthWall(GameTestHelper helper) {
        String hall = "gt_build_at_hall", stable = "gt_build_at_stable", pic = "gt_build_at_flag";
        ServerLevel level = helper.getLevel();
        ServerPlayer mock = MockPlayerNetwork.inLevel(helper);
        BuildPlan plan = AutoSpotsGameTest.plainHall(hall);
        BlockPos anchor = helper.absolutePos(new BlockPos(1, 1, 1)).offset(0, 135, 0);   // its own height over its own plot
        AutoSpotsGameTest.standOnPlatform(level, anchor, plan);
        Wish wish = BuildSpotsGameTest.bind(level, mock, "a stable next to my hall and a flag on its wall",
                "gt-build-at");
        AtomicReference<String> built = new AtomicReference<>(), painted = new AtomicReference<>();
        AtomicReference<Boolean> started = new AtomicReference<>(false);
        helper.succeedWhen(() -> {
            helper.assertFalse(Builder.isBuilding(hall), Component.literal("waiting for the hall"));
            if (!started.get()) {
                started.set(true);
                BuildSpotsGameTest.offThread(() -> AgentContext.with("gt-build-at", () -> {
                    JsonObject s = BuildSpotsGameTest.args("{\"id\":\"" + stable + "\",\"name\":\"Stable\",\"note\":\"gate\","
                            + "\"at\":\"" + hall + ":beside\",\"shapes\":[{\"kind\":\"box\",\"dx\":0,\"dy\":0,\"dz\":0,"
                            + "\"width\":3,\"height\":3,\"depth\":5,\"block\":\"minecraft:hay_block\"}]}");
                    ToolResult b = new BuildStructureTool().call(s);
                    built.set((b.isError() ? "ERROR " : "") + b.text());
                    JsonObject p = new JsonObject();
                    p.addProperty("id", pic);
                    p.addProperty("note", "gate");
                    p.addProperty("wide", 2);
                    p.addProperty("tall", 2);
                    p.addProperty("at", hall + ":north");
                    // Coordinates he worked out himself ride along with the `at` (measured, the at-p1
                    // grant: they won and the flag went on the castle's back): the `at` must win.
                    p.addProperty("x", 0);
                    p.addProperty("y", 0);
                    p.addProperty("z", 0);
                    p.addProperty("facing", "south");
                    p.addProperty("picture", "{\"size\":64,\"rects\":[{\"x\":0,\"y\":0,\"w\":64,\"h\":64,\"colour\":\"red\"}]}");
                    ToolResult r = new PaintSurfaceTool().call(p);
                    painted.set((r.isError() ? "ERROR " : "") + r.text());
                    return null;
                }));
            }
            helper.assertTrue(built.get() != null && painted.get() != null, Component.literal("waiting for the tools"));
            helper.assertFalse(built.get().startsWith("ERROR"), Component.literal("build_structure at failed: " + built.get()));
            helper.assertFalse(Builder.isBuilding(stable), Component.literal("waiting for the stable"));
            Long sa = BuildRegistry.anchorOf(stable);
            helper.assertTrue(sa != null, Component.literal("the stable has no anchor: " + built.get()));
            BlockPos s = BlockPos.of(sa);
            // The hall's west wall is x = anchor.x; its west spot is two out; the stable's east edge sits on it.
            helper.assertTrue(s.getX() + 2 == anchor.getX() - BuildSpots.FRONT_GAP,
                    Component.literal("the stable's east edge (" + (s.getX() + 2) + ") must be on the west spot ("
                            + (anchor.getX() - BuildSpots.FRONT_GAP) + "): " + built.get()));
            helper.assertTrue(s.getY() == anchor.getY(), Component.literal("on the ground beside it: " + s + " vs " + anchor));
            helper.assertTrue(level.getBlockState(s).is(Blocks.HAY_BLOCK), Component.literal("the stable is not standing at " + s));
            helper.assertTrue(level.getBlockState(anchor.offset(-1, 1, 5)).isAir(), Component.literal("air between the walls"));
            helper.assertFalse(painted.get().startsWith("ERROR"), Component.literal("paint_surface at failed: " + painted.get()));
            List<ItemFrame> frames = level.getEntitiesOfClass(ItemFrame.class,
                    new AABB(anchor.offset(0, 0, -1)).inflate(12, 8, 1), f -> f.getItem().is(Items.FILLED_MAP));
            helper.assertTrue(frames.size() == 4, Component.literal("a 2x2 picture is four framed maps in front of the north wall, found "
                    + frames.size() + ": " + painted.get()));
            for (ItemFrame f : frames) {
                helper.assertTrue(f.blockPosition().getZ() == anchor.getZ() - 1
                                && level.getBlockState(f.blockPosition().south()).is(Blocks.STONE_BRICKS),
                        Component.literal("each frame hangs on the north wall's face: " + f.blockPosition()));
                f.discard();
            }
            Marshal.global().finish(wish.id());
            Builder.clearBuild(level, s, BuildRegistry.get(stable));
            AutoSpotsGameTest.clearPlatform(level, anchor, plan);
            MockPlayerNetwork.remove(mock);
        });
    }
}
