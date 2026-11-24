package com.moddingfromamod.vanilla;

import net.minecraft.network.chat.Component;
import java.util.List;

import com.google.gson.JsonParser;
import com.moddingfromamod.ModdingFromAModMod;
import com.moddingfromamod.builder.BuildPlan;
import com.moddingfromamod.builder.Builder;
import com.moddingfromamod.compat.Teleports;
import com.moddingfromamod.testing.MockPlayerNetwork;
import com.moddingfromamod.world.WorldModuleHost;
import net.minecraft.core.BlockPos;
import com.moddingfromamod.testing.gt.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import com.moddingfromamod.testing.gt.GameTestHolder;

/**
 * <b>A player is never left inside blocks</b> — the three ways a live server buried a player:
 * a build going up where he stood, a build on his home spot, and (the backstop for every other way)
 * suffocation damage. Each asserts he ends up out of the blocks and was told why.
 */
@GameTestHolder(ModdingFromAModMod.MODID)
public final class PlayerRescueGameTest {

    private static final String BATCH = "playerrescue";

    private static int floor(GameTestHelper helper) {
        return helper.getBlockState(new BlockPos(4, 0, 4)).isAir() ? 1 : 0;
    }

    private static void box(GameTestHelper helper, int x0, int y0, int z0, int x1, int y1, int z1, Block b) {
        for (int x = x0; x <= x1; x++) {
            for (int y = y0; y <= y1; y++) {
                for (int z = z0; z <= z1; z++) {
                    helper.setBlock(new BlockPos(x, y, z), b);
                }
            }
        }
    }

    /** A survival mock standing centred on the block {@code rel} of the plot. */
    private static ServerPlayer standing(GameTestHelper helper, BlockPos rel) {
        ServerPlayer p = MockPlayerNetwork.asSurvival(MockPlayerNetwork.inLevel(helper));
        BlockPos at = helper.absolutePos(rel);
        Teleports.player(p, helper.getLevel(), at.getX() + 0.5, at.getY(), at.getZ() + 0.5, 0f, 0f);
        return p;
    }

    private static boolean told(ServerPlayer p, String words) {
        List<String> chat = MockPlayerNetwork.chat(p);
        return chat.stream().anyMatch(l -> l.contains(words));
    }

    /**
     * <b>A build going up where he stands moves him out</b> (the nearest safe spot) and says so. The volcano was
     * built around the child who wished it; only the creatures beside him were moved.
     */
    @GameTest(template = "empty_test", batch = BATCH, timeoutTicks = 100)
    public static void aBuildOverAPlayerMovesHimOutAndTellsHim(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        int f = floor(helper);
        ServerPlayer p = standing(helper, new BlockPos(4, f + 1, 4));
        double x0 = p.getX(), y0 = p.getY(), z0 = p.getZ();
        BlockPos anchor = helper.absolutePos(new BlockPos(0, f + 1, 0));
        BuildPlan plan = BuildPlan.fromJson(JsonParser.parseString("""
                {"id":"gt_rescue_mound","name":"Mound","shapes":[
                  {"kind":"box","dx":1,"dy":0,"dz":1,"width":7,"height":6,"depth":7,"block":"minecraft:stone"}]}
                """).getAsJsonObject());
        try {
            String r = Builder.submit(level, anchor, plan, false);
            helper.assertTrue(r.startsWith("Building"), Component.literal("the build was refused: " + r));
            Builder.drainNow(level);
            helper.assertTrue(!p.isInWall(), Component.literal("the player is inside the build at " + p.position()));
            helper.assertTrue(p.getX() != x0 || p.getY() != y0 || p.getZ() != z0, Component.literal("he was not moved"));
            helper.assertTrue(told(p, "your new building was going up right where you stood"),
                    Component.literal("he was not told why he moved: " + MockPlayerNetwork.chat(p)));
            helper.succeed();
        } finally {
            box(helper, 0, f + 1, 0, 8, f + 7, 8, Blocks.AIR);
            p.discard();
        }
    }

    /**
     * <b>A home spot with something built on it sends him to the nearest safe spot</b>, not into the
     * blocks: the volcano on the child's home suffocated him on every respawn.
     */
    @GameTest(template = "empty_test", batch = BATCH, timeoutTicks = 100)
    public static void aBuriedHomeSendsHimBesideItInsteadOfIntoIt(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        int f = floor(helper);
        ServerPlayer p = standing(helper, new BlockPos(0, f + 1, 0));
        box(helper, 2, f + 1, 2, 6, f + 5, 6, Blocks.STONE);
        BlockPos home = helper.absolutePos(new BlockPos(4, f + 1, 4));
        try {
            WorldModuleHost.goHome(p, level, home.getX() + 0.5, home.getY(), home.getZ() + 0.5);
            helper.assertTrue(!p.isInWall(), Component.literal("he was sent into his buried home at " + p.position()));
            helper.assertTrue(Math.abs(p.getX() - home.getX() - 0.5) > 1e-6 || p.getY() != home.getY()
                    || Math.abs(p.getZ() - home.getZ() - 0.5) > 1e-6, Component.literal("he is on the buried home spot"));
            helper.assertTrue(told(p, "built on your home spot"),
                    Component.literal("he was not told his home was built on: " + MockPlayerNetwork.chat(p)));

            // and an open home is exactly where he goes
            box(helper, 2, f + 1, 2, 6, f + 5, 6, Blocks.AIR);
            WorldModuleHost.goHome(p, level, home.getX() + 0.5, home.getY(), home.getZ() + 0.5);
            helper.assertTrue(Math.abs(p.getY() - home.getY()) < 1e-6 && Math.abs(p.getX() - home.getX() - 0.5) < 1e-6,
                    Component.literal("an open home must be used as it is: " + p.position()));
            helper.succeed();
        } finally {
            box(helper, 2, f + 1, 2, 6, f + 5, 6, Blocks.AIR);
            p.discard();
        }
    }

    /**
     * <b>Suffocation damage moves him out instead</b> — the backstop for every way a player ends up
     * in blocks that the two rules above cannot see (an unloaded home, a datapack, falling sand).
     */
    @GameTest(template = "empty_test", batch = BATCH, timeoutTicks = 100)
    public static void inWallDamageMovesHimOutAndCostsNothing(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        int f = floor(helper);
        box(helper, 0, f + 1, 0, 8, f + 4, 8, Blocks.STONE);   // wide enough that the way out is a real move
        ServerPlayer p = standing(helper, new BlockPos(4, f + 1, 4));
        p.connection.markClientLoaded();    // a player whose client has not loaded ignores all damage
        try {
            float hp = p.getHealth();
            boolean hurt = p.hurtServer(level, level.damageSources().inWall(), 1.0f);
            helper.assertTrue(!hurt && p.getHealth() == hp, Component.literal("in_wall damage was not cancelled (hp "
                    + hp + " -> " + p.getHealth() + ")"));
            helper.assertTrue(!p.isInWall(), Component.literal("he is still inside the blocks at " + p.position()));
            helper.assertTrue(told(p, "stuck inside blocks"), Component.literal("he was not told: " + MockPlayerNetwork.chat(p)));

            // any other damage is untouched
            box(helper, 0, f + 1, 0, 8, f + 4, 8, Blocks.AIR);
            float before = p.getHealth();
            p.hurtServer(level, level.damageSources().generic(), 1.0f);
            helper.assertTrue(p.getHealth() < before, Component.literal("ordinary damage must still hurt"));
            helper.succeed();
        } finally {
            box(helper, 0, f + 1, 0, 8, f + 4, 8, Blocks.AIR);
            p.discard();
        }
    }
}
