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
 * #114, the owner's decision, through the real press ({@code BuilderMake.make}, what a Builder tile runs): a farm plot
 * made from a sky island's edge. He stands on an island 120 blocks up; 50 below, out past its edge, is a small stone
 * pad (28 blocks out, so his look clears the island's own edge); nothing else is within reach. A MISS stays level with him (#46) and stands on a base of its own, so it does not
 * hang; a STEADY look at the pad is a confident hit and settles there, however far down; a ONE-TICK flick to the pad is
 * not, and is held level as a miss is. Test code: never in the jar.
 */
@GameTestHolder(ModdingFromAModMod.MODID)
public final class SkyIslandAimGameTest {

    private SkyIslandAimGameTest() {
    }

    static final int ISLAND = 120;
    static final int PAD = 70;

    private static final String FARM = "{\"builder\":1,\"kind\":\"building\",\"from\":\"preset:buildings/farm_plot\",\"size\":2}";

    /** The island, the pad and him on the island's east edge, facing east over the drop. */
    private static ServerPlayer setUp(GameTestHelper helper, String name) {
        MockPlayerNetwork.clearStaleMocks(helper);
        BlockPos o = helper.absolutePos(BlockPos.ZERO);
        var level = helper.getLevel();
        // ITS OWN SPACE (#129's lesson): everything here is above the 9x9x9 structure the framework clears, and batches
        // reuse positions, so an earlier batch's farm (built level with the island) stood in this one's line of sight.
        // The layers it uses are emptied before and after.
        clear(level, o);
        MockPlayerNetwork.afterTest(helper, () -> clear(level, o));
        for (int x = 0; x <= 4; x++) {
            for (int z = 0; z <= 4; z++) {
                level.setBlockAndUpdate(o.offset(x, ISLAND, z), Blocks.GRASS_BLOCK.defaultBlockState());
            }
        }
        for (int x = 31; x <= 33; x++) {
            for (int z = 1; z <= 3; z++) {
                level.setBlockAndUpdate(o.offset(x, PAD, z), Blocks.STONE.defaultBlockState());
            }
        }
        ServerPlayer p = MockPlayerNetwork.inLevel(helper, name);
        // at the island's east edge; the pad 28 out keeps his look shallow enough to clear the island's own edge by
        // ~0.5 of a block (at 20 out it cleared it by 0.12, and a mock a little off its spot hit the island instead)
        p.setPos(o.getX() + 4.8, o.getY() + ISLAND + 1, o.getZ() + 2.5);
        look(p, 0f);
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
        return p;
    }

    /** The island's and the pad's layers, and every farm a case builds on them, emptied. */
    static void clear(net.minecraft.server.level.ServerLevel level, BlockPos o) {
        for (int x = -4; x <= 48; x++) {
            for (int z = -10; z <= 14; z++) {
                for (int y : new int[]{ISLAND - 2, PAD - 2}) {
                    for (int dy = 0; dy <= 10; dy++) {
                        level.setBlock(o.offset(x, y + dy, z), Blocks.AIR.defaultBlockState(), 2);
                    }
                }
            }
        }
    }

    /** Face east, at this pitch. */
    private static void look(ServerPlayer p, float pitch) {
        p.setYRot(-90f);
        p.setYHeadRot(-90f);
        p.setXRot(pitch);
    }

    /** The pitch that looks at the middle of the pad's top. */
    private static float atThePad(GameTestHelper helper, ServerPlayer p) {
        BlockPos o = helper.absolutePos(BlockPos.ZERO);
        double dx = o.getX() + 32.5 - p.getX();
        double dy = p.getEyeY() - (o.getY() + PAD + 1);
        return (float) Math.toDegrees(Math.atan2(dy, dx));
    }

    /** Press the farm plot, then wait for it to stand; {@code check} reads its anchor and the base layer. */
    private static void pressAndCheck(GameTestHelper helper, ServerPlayer p, int wantY, boolean wantBase) {
        AtomicReference<BuilderMake.Result> made = new AtomicReference<>();
        BuilderMake.make(p, BuilderSpec.read(JsonParser.parseString(FARM).getAsJsonObject()), BuilderAllowlist.REVIEWED)
                .thenAcceptAsync(made::set);
        BlockPos o = helper.absolutePos(BlockPos.ZERO);
        com.moddingfromamod.test.RealTime.succeedWhen(helper, () -> {
            BuilderMake.Result r = made.get();
            helper.assertTrue(r != null && r.outcome() == BuilderMake.Outcome.MADE, Component.literal("the farm: " + r));
            Long packed = com.moddingfromamod.builder.BuildRegistry.anchorOf(r.madeId());
            helper.assertTrue(packed != null, Component.literal("the farm stands somewhere"));
            BlockPos at = BlockPos.of(packed);
            helper.assertTrue(at.getY() == o.getY() + wantY, Component.literal("the farm stands at y " + (at.getY() - o.getY())
                    + " above the test, wanted " + wantY + " (anchor " + at.toShortString() + ")"));
            if (wantBase) {
                int base = 0;
                for (int x = at.getX() - 8; x <= at.getX() + 8; x++) {
                    for (int z = at.getZ() - 8; z <= at.getZ() + 8; z++) {
                        if (x - o.getX() <= 4 && z - o.getZ() >= 0 && z - o.getZ() <= 4) {
                            continue;   // the island itself
                        }
                        if (!helper.getLevel().getBlockState(new BlockPos(x, at.getY() - 1, z)).isAir()) {
                            base++;
                        }
                    }
                }
                helper.assertTrue(base > 0, Component.literal("it stands on a base of its own, not hanging"));
            }
        });
    }

    @GameTest(template = "empty_test", batch = "sky_island_miss", timeoutTicks = 40000)
    public static void aMissFromTheEdgeStaysLevelAndStandsOnABase(GameTestHelper helper) {
        ServerPlayer p = setUp(helper, "gtskymiss");
        helper.runAfterDelay(10, () -> pressAndCheck(helper, p, ISLAND + 1, true));
    }

    @GameTest(template = "empty_test", batch = "sky_island_sure", timeoutTicks = 40000)
    public static void aSteadyLookFarBelowSettlesWhereHeLooked(GameTestHelper helper) {
        ServerPlayer p = setUp(helper, "gtskysure");
        float pitch = atThePad(helper, p);
        // held for more than AimConfidence.STEADY_TICKS: a gaze. Sent every tick, as a client sends his look (a mock's
        // rotation is not kept between ticks by anything else).
        java.util.concurrent.atomic.AtomicBoolean pressed = new java.util.concurrent.atomic.AtomicBoolean();
        helper.onEachTick(() -> {
            if (!pressed.get()) {
                look(p, pitch);
            }
        });
        helper.runAfterDelay(AimConfidence.STEADY_TICKS + 5, () -> {
            look(p, pitch);
            pressed.set(true);
            pressAndCheck(helper, p, PAD + 1, false);
        });
    }

    @GameTest(template = "empty_test", batch = "sky_island_flick", timeoutTicks = 40000)
    public static void aOneTickFlickToFarBelowIsHeldLevel(GameTestHelper helper) {
        ServerPlayer p = setUp(helper, "gtskyflick");
        helper.runAfterDelay(AimConfidence.STEADY_TICKS + 5, () -> {
            look(p, atThePad(helper, p));   // swung down on the very tick of the press
            pressAndCheck(helper, p, ISLAND + 1, true);
        });
    }
}
