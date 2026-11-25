package com.moddingfromamod.wish;

import java.util.concurrent.atomic.AtomicReference;

import com.google.gson.JsonParser;
import com.moddingfromamod.ModdingFromAModMod;
import com.moddingfromamod.testing.MockPlayerNetwork;
import com.moddingfromamod.testing.gt.GameTest;
import com.moddingfromamod.testing.gt.GameTestHolder;
import com.moddingfromamod.wishbuilder.BuilderAllowlist;
import com.moddingfromamod.wishbuilder.BuilderMake;
import com.moddingfromamod.wishbuilder.BuilderSettings;
import com.moddingfromamod.wishbuilder.BuilderSpec;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.Blocks;

/**
 * <b>A house aimed inside an arena's wall is not carried through it</b> (an arena playtest, 2026-11-25), through the
 * real press ({@code BuilderMake.make}, an Apprentice's cozy house). A floor 120 blocks up with a wall 5 high across
 * it and nothing past the wall; he stands 9 blocks from the wall and looks at the floor about 4 blocks short of it. A
 * building slides out to 12 from him: that used to take it through the wall and over the drop (where the arena's rule
 * refused it), and now stops at the last floor before the wall. Test code: never in the jar.
 */
@GameTestHolder(ModdingFromAModMod.MODID)
public final class ArenaWallSlideGameTest {

    private ArenaWallSlideGameTest() {
    }

    static final int FLOOR = 120;
    static final int WALL_Z = 10;

    private static final String HOUSE = "{\"builder\":1,\"kind\":\"building\",\"size\":2,"
            + "\"opts\":{\"type\":\"preset:buildings/cozy_house\",\"outside\":\"nothing\"}}";

    @GameTest(template = "empty_test", batch = "arena_wall_slide", timeoutTicks = 40000)
    public static void aHouseAimedInsideTheWallStaysInside(GameTestHelper helper) {
        MockPlayerNetwork.clearStaleMocks(helper);
        BlockPos o = helper.absolutePos(BlockPos.ZERO);
        var level = helper.getLevel();
        // its own space, above the 9x9x9 structure the framework clears: emptied before and after (#129's lesson)
        clear(level, o);
        MockPlayerNetwork.afterTest(helper, () -> clear(level, o));
        for (int x = -4; x <= 24; x++) {
            for (int z = -2; z < WALL_Z; z++) {
                level.setBlockAndUpdate(o.offset(x, FLOOR, z), Blocks.STONE.defaultBlockState());
            }
            for (int z = WALL_Z; z <= WALL_Z + 1; z++) {
                for (int y = FLOOR; y <= FLOOR + 5; y++) {
                    level.setBlockAndUpdate(o.offset(x, y, z), Blocks.STONE_BRICKS.defaultBlockState());
                }
            }
        }
        ServerPlayer p = MockPlayerNetwork.inLevel(helper, "gtarenawall");
        p.setPos(o.getX() + 10.5, o.getY() + FLOOR + 1, o.getZ() + 1.5);
        double dz = 4.5;   // he looks at the floor 4-5 blocks out: z ~6, 4 short of the wall
        float pitch = (float) Math.toDegrees(Math.atan2(p.getEyeY() - (o.getY() + FLOOR + 1), dz));
        java.util.concurrent.atomic.AtomicBoolean pressed = new java.util.concurrent.atomic.AtomicBoolean();
        helper.onEachTick(() -> {
            if (!pressed.get()) {
                look(p, pitch);
            }
        });
        String before = System.getProperty(BuilderSettings.ENABLED_PROPERTY);
        System.setProperty(BuilderSettings.ENABLED_PROPERTY, "true");
        BuilderMake.takeCard = false;
        long rise = BuilderMake.riseMillis;
        BuilderMake.riseMillis = 0;
        MockPlayerNetwork.afterTest(helper, () -> {
            BuilderMake.takeCard = true;
            BuilderMake.riseMillis = rise;
            if (before == null) {
                System.clearProperty(BuilderSettings.ENABLED_PROPERTY);
            } else {
                System.setProperty(BuilderSettings.ENABLED_PROPERTY, before);
            }
        });
        AtomicReference<BuilderMake.Result> made = new AtomicReference<>();
        helper.runAfterDelay(10, () -> {
            look(p, pitch);
            pressed.set(true);
            BuilderMake.make(p, BuilderSpec.read(JsonParser.parseString(HOUSE).getAsJsonObject()), BuilderAllowlist.REVIEWED)
                    .thenAcceptAsync(made::set);
        });
        com.moddingfromamod.test.RealTime.succeedWhen(helper, () -> {
            BuilderMake.Result r = made.get();
            helper.assertTrue(r != null && r.outcome() == BuilderMake.Outcome.MADE, Component.literal("the house: " + r));
            Long packed = com.moddingfromamod.builder.BuildRegistry.anchorOf(r.madeId());
            helper.assertTrue(packed != null, Component.literal("the house stands somewhere"));
            BlockPos at = BlockPos.of(packed);
            helper.assertTrue(at.getZ() < o.getZ() + WALL_Z,
                    Component.literal("the house stands inside the wall (z " + (at.getZ() - o.getZ()) + ", the wall at "
                            + WALL_Z + "), not carried through it"));
            helper.assertTrue(at.getY() == o.getY() + FLOOR + 1,
                    Component.literal("on the floor, at y " + (at.getY() - o.getY())));
        });
    }

    static void clear(net.minecraft.server.level.ServerLevel level, BlockPos o) {
        for (int x = -12; x <= 32; x++) {
            for (int z = -12; z <= 30; z++) {
                for (int y = FLOOR - 2; y <= FLOOR + 10; y++) {
                    level.setBlock(o.offset(x, y, z), Blocks.AIR.defaultBlockState(), 2);
                }
            }
        }
    }

    private static void look(ServerPlayer p, float pitch) {
        p.setYRot(0f);
        p.setYHeadRot(0f);
        p.setXRot(pitch);
    }
}
